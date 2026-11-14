package com.qiqi.mall.oms.service;

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
     * 建单结果：订单本体 + **刚写入的明细实体（内存里就是库里的终态，不做任何回读）**。
     *
     * <p><b>明细的来路改过两次，别按旧版本理解</b>：
     * <ol>
     *   <li>最早：controller 在**建单事务提交之后**另发一条 {@code SELECT ... FROM order_item
     *       WHERE order_id=? ORDER BY id ASC} 拼响应 —— 那条 SELECT 是自动提交的，于是
     *       **一单变成两个事务事件**（1 个建单事务 + 1 个只读事务）；</li>
     *   <li>第一次改（事务数 2.19 → 1.19）：把它**挪进建单事务**。响应内容一字不变，但代价是一次
     *       **失败语义变更**——这条 SELECT 原先失败只影响拼响应，订单早已提交、客户端拿 500
     *       （「已提交 + 500」）；挪进事务后它一失败就是**整单回滚**。当时如实记下的取舍是：
     *       回滚比「订单成了却告诉用户失败」更自洽；</li>
     *   <li>现在：那条 SELECT **整条删掉**。{@code saveBatch} 已把自增 id 回填进内存的 {@code items}，
     *       回显所需的数据在内存里就完整了，于是「事务内回读」这一步本身也不存在，
     *       连带「回读失败要不要回滚」这个问题一起消失（失败语义只剩：事务内任何一步异常 = 整单回滚）。
     *       等价性不靠肉眼：{@code OrderItemInMemoryReturnEquivalenceTest} 逐列 + JSON 逐字节守着。</li>
     * </ol>
     * 结论：明细**没有**独立的读事务/读语句，它就是本事务刚写进去的那批实体；
     * 顺序 = 插入顺序 = 自增 id 分配顺序（与旧 SQL 的 {@code ORDER BY id ASC} 是同一序列）。
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

        // 明细**不再回读**：{@code saveBatch} 走 MyBatis-Plus 的批量 Executor，主键生成策略是
        // {@code IdType.AUTO}，所以每条 INSERT 的自增 id 会在执行时回填进上面这个 {@code items} 列表
        // （MyBatis 的 Jdbc3KeyGenerator 在 BATCH 执行器里按语句顺序回填）。既然如此，
        // 「回显刚写的明细」这件事在内存里就已经完整了 —— 原来的那条
        // {@code SELECT ... FROM order_item WHERE order_id=? ORDER BY id ASC} 是**纯多余的一次往返**
        // （上一轮已把它从"提交后另开事务"挪进本事务，这一轮直接删掉）。
        //
        // 等价性不是靠肉眼担保的：{@code OrderItemInMemoryReturnEquivalenceTest} 用
        // ①反射遍历 DB 的**每一个列**逐字段对比 ②同一份 Jackson ObjectMapper 序列化
        // 「内存 items」与「DB 回查 items」，要求 JSON 逐字节相等。id 回填不全（漏字段 / 只有 DB 默认值）
        // 会在那里直接失败 —— 失败就说明这条改动不能做。
        //
        // 顺序也等价：{@code items} 的插入顺序就是自增 id 的分配顺序，原来 {@code orderByAsc(id)} 的
        // 结果与它就是同一个序列（同一批 INSERT 在同一事务里按列表顺序执行）。

        // 清购物车已上移到 portal 侧（下单事务后执行）：
        // 购物车是「意图清单」而非账务数据，允许与订单存在轻微不一致，以此解除 oms→member 的模块依赖。
        return new CreatedOrder(order, items);
    }
}
