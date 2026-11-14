package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.shared.common.OrderSnGenerator;
import com.qiqi.mall.stock.StockLedgerContext;
import com.qiqi.mall.stock.entity.StockLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 下单事务本体：每次调用都是**全新事务**（REQUIRES_NEW），
 * 这样外层重试时上一轮（被死锁牺牲/回滚）不会留下任何残留。
 *
 * <h3>本轮的 SQL 合并（只改这一处）</h3>
 * 原来建单要写两条 DML：{@code INSERT order(total_amount=0)} → 扣完库存 → {@code UPDATE order SET
 * total_amount=?}。现在把「读」（SKU/商品/价格）全部前置到第一遍，总额算出来之后
 * **一条 INSERT 就写对**，那条 UPDATE 消失。
 *
 * <p><b>为什么等价</b>：
 * <ol>
 *   <li>落库终态逐列相同 —— INSERT 写成 0 再 UPDATE 回填，与直接 INSERT 写总值，
 *       提交后 {@code order} 的行内容一字不差（UPDATE 那 8 个列的值全部来自同一份内存实体）；</li>
 *   <li>失败语义相同 —— 任何一步抛异常都是整个 REQUIRES_NEW 事务回滚，库里什么都不留；
 *       合并后总额算得更早，但「算得早」不产生任何可观测副作用（纯读 + 内存算术）；</li>
 *   <li>读的快照相同 —— 第一遍的多次 {@code getById} 与原来的交错读都在同一个事务快照里
 *       （REPEATABLE READ 下快照由事务内第一次一致性读建立），价格/商品名取值不变；</li>
 *   <li>明细为空时抛「商品信息无效」的位置从「建单之后」挪到「建单之前」——原来也是回滚，
 *       提交状态同样是什么都没有。</li>
 * </ol>
 *
 * <p><b>代价（如实记）</b>：原来那句 {@code updateById} 后面跟着
 * {@code OptimisticLockGuard.requireAffected(rows, MSG_RECORD_DELETED)}（「0 行 = 这张刚建的订单
 * 在事务内被别人删了」）。它在<b>本事务里不可达</b>：同一事务内没有任何 DELETE order 的语句，
 * 而刚 INSERT 未提交的行对其他事务不可见、更不可能被删。所以它不是可达行为，随 UPDATE 一起消失
 * 不改变任何可观测结果 —— 但这句话必须写下来，因为它确实是一条被删掉的防御性检查。
 */
@Service
public class OrderTransactionalCreator {

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final ProductService productService;
    private final SkuService skuService;
    /** 订单状态写入的唯一入口（出生：新建订单 = 待付款(0)） */
    private final OrderStatusService orderStatusService;

    public OrderTransactionalCreator(OrderService orderService, OrderItemService orderItemService,
                                     ProductService productService, SkuService skuService,
                                     OrderStatusService orderStatusService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.productService = productService;
        this.skuService = skuService;
        this.orderStatusService = orderStatusService;
    }

    public record ItemInput(Long skuId, Integer quantity) {}

    /**
     * 建单结果：订单本体 + **在同一个事务里回读到的明细**。
     *
     * <p><b>为什么明细由这里带出来（本轮唯一改的那处事务边界）</b>：原来是 controller 在建单事务
     * 提交之后又发一条 {@code SELECT ... FROM order_item WHERE order_id=?} 去拼响应 —— 那条 SELECT
     * 是自动提交的，于是**一单变成两个事务事件**（性能实测：1 个建单事务 + 1 个只读事务）。
     * 那条只读事务没有任何正确性理由：它读的就是本事务刚写进去的行，既不跨请求、也不需要
     * 看见别人后来的提交。把它挪进建单事务里，事务数从 2 降到 1，响应内容一字不变
     * （同一事务快照看到的就是刚写的那些行，列值/顺序 {@code orderByAsc(id)} 完全相同）。
     */
    public record CreatedOrder(Order order, List<OrderItem> items) {}

    /** 第一遍解析出来的「一行明细要用到的全部东西」：只读产物，不产生任何写 */
    private record ResolvedItem(Sku sku, Product product, int qty) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CreatedOrder create(Long memberId, String receiverName, String receiverPhone, String receiverAddress,
                               List<ItemInput> inputs, boolean fromCart) {

        // ---- 第一遍：只读解析（把「读」全部前置，才有下一步「一条 INSERT 写对总额」的前提）
        List<ResolvedItem> resolved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ItemInput input : inputs) {
            Sku sku = skuService.getById(input.skuId());
            if (sku == null) continue;
            Product product = productService.getById(sku.getProductId());
            int qty = input.quantity() == null || input.quantity() < 1 ? 1 : input.quantity();
            resolved.add(new ResolvedItem(sku, product, qty));
            total = total.add(sku.getPrice().multiply(BigDecimal.valueOf(qty)));
        }
        if (resolved.isEmpty()) throw new IllegalStateException("商品信息无效，无法下单");

        Order order = new Order();
        order.setOrderSn(OrderSnGenerator.next());
        order.setMemberId(memberId);
        // 出生：新订单初始状态「待付款(0)」走唯一入口（save 之前定初值，不产生 UPDATE）
        orderStatusService.init(order, 0);
        order.setPayType(0);
        order.setReceiverName(receiverName);
        order.setReceiverPhone(receiverPhone);
        order.setReceiverAddress(receiverAddress);
        // 总额在 INSERT 时一次写对：原来要先写 0、扣完库存再 UPDATE 回填（两条 DML），
        // 现在总额已在前一遍算出，一条 INSERT 落地 —— 这就是本轮唯一改的那处 SQL 合并。
        order.setTotalAmount(total);
        orderService.save(order);
        // 出生台账：save 之后（拿到自增 id）才写，与建单同一个 REQUIRES_NEW 事务 ——
        // 台账写不进去就整单不成立，绝不出现「订单建了、出生事件没有」
        orderStatusService.recordBirth(order, OrderStatusService.REASON_CREATE, null);

        List<OrderItem> items = new ArrayList<>();
        // 每次扣减库存都要记台账（缺陷：扣/补两边本来都没有台账）。先声明「这次扣减属于哪张订单、
        // 什么原因、由哪张单据触发」，库存接口在里面按幂等键记账；没有声明会 fail-closed 抛异常 ——
        // 不允许出现「改了库存但账上没有」。下单这一侧有幂等令牌兜住重复提交，台账仍要记，账才完整。
        StockLedgerContext.Attribution outerLedgerContext = StockLedgerContext.open(
                order.getId(), StockLedger.REASON_ORDER_CREATE, null, null);
        try {
            // 第二遍：只做写（扣库存 + 组装明细），不再读库 —— 读已经在第一遍取全了
            for (ResolvedItem r : resolved) {
                Sku sku = r.sku();
                // 原子扣减：把「判断+减法」压进一条 SQL，避免 check-then-act 超卖
                if (!skuService.deductStock(sku.getId(), r.qty())) {
                    throw new IllegalStateException("库存不足："
                            + (r.product() == null ? "" : r.product().getName()));
                }

                OrderItem item = new OrderItem();
                item.setOrderId(order.getId());
                item.setProductId(sku.getProductId());
                item.setProductSkuId(sku.getId());
                item.setProductName(r.product() == null ? "" : r.product().getName());
                item.setProductPrice(sku.getPrice());
                item.setProductQuantity(r.qty());
                items.add(item);
            }
        } finally {
            StockLedgerContext.close(outerLedgerContext);
        }

        orderItemService.saveBatch(items);

        // 明细回读放进**本事务**（原来在 controller 里、提交之后另开一个自动提交事务）：
        // 同一份 SQL、同一份结果，少一个事务事件与一次往返。见 {@link CreatedOrder} 的说明。
        List<OrderItem> persisted = orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()).orderByAsc(OrderItem::getId));

        // 清购物车已上移到 portal 侧（下单事务后执行）：
        // 购物车是「意图清单」而非账务数据，允许与订单存在轻微不一致，以此解除 oms→member 的模块依赖。
        return new CreatedOrder(order, persisted);
    }
}
