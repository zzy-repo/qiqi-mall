package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.shared.security.CurrentUser;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.service.DeliveryFlowService;
import com.qiqi.mall.oms.service.OrderCreateService;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.oms.service.OrderTransactionalCreator;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.member.entity.Cart;
import com.qiqi.mall.member.service.CartService;
import com.qiqi.mall.shared.security.IdempotentTokenStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** C 端下单、我的订单、模拟支付 */
@Tag(name = "买家端-下单与我的订单", description = "下单、我的订单、确认收货与模拟支付")
@RestController
@RequestMapping("/portal/orders")
public class PortalOrderController {

    private final OrderService orderService;
    /** 订单状态写入的唯一入口（P2 第一步收敛：支付/确认收货的状态都不再就地 setStatus） */
    private final OrderStatusService orderStatusService;
    private final OrderItemService orderItemService;
    private final PaymentRecordService paymentRecordService;
    private final OrderCreateService orderCreateService;
    private final IdempotentTokenStore idempotentTokenStore;
    private final CartService cartService;
    private final DeliveryFlowService deliveryFlowService;

    public PortalOrderController(OrderService orderService, OrderStatusService orderStatusService,
                                 OrderItemService orderItemService,
                                 PaymentRecordService paymentRecordService,
                                 OrderCreateService orderCreateService,
                                 IdempotentTokenStore idempotentTokenStore,
                                 CartService cartService,
                                 DeliveryFlowService deliveryFlowService) {
        this.orderService = orderService;
        this.orderStatusService = orderStatusService;
        this.orderItemService = orderItemService;
        this.paymentRecordService = paymentRecordService;
        this.orderCreateService = orderCreateService;
        this.idempotentTokenStore = idempotentTokenStore;
        this.cartService = cartService;
        this.deliveryFlowService = deliveryFlowService;
    }

    /**
     * 我的订单列表（分页）。
     * size 限制 1~50；排序键用 payment_time 倒序，配合复合索引 idx_member_time(member_id, payment_time)，
     * 段内天然有序、免 filesort（未支付订单 payment_time 为 NULL 会排在最后，语义上仍是时间近似序）。
     * 返回 Page：{records, total, current, size, pages}
     */
    @GetMapping
    public Result<Page<Order>> list(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 50);
        Page<Order> result = orderService.page(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getMemberId, CurrentUser.id())
                        .orderByDesc(Order::getPaymentTime)
                        .orderByDesc(Order::getId));
        return Result.ok(result);
    }

    /** 订单详情（含明细） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
        return Result.ok(detailBody(order));
    }

    /** 进结算页领取幂等令牌（TTL 30 分钟），提交订单时携带，防重复提交 */
    @GetMapping("/idempotent-token")
    public Result<Map<String, Object>> idempotentToken() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokenId", idempotentTokenStore.issue());
        return Result.ok(data);
    }

    /** 提交订单：{receiverName, receiverPhone, receiverAddress, items:[{productSkuId, quantity}], fromCart, idempotentToken} */
    @Operation(summary = "买家下单")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CreateRequest request) {
        Long memberId = CurrentUser.id();
        if (request.getItems() == null || request.getItems().isEmpty()) return Result.fail("请选择要结算的商品");
        if (request.getReceiverName() == null || request.getReceiverName().isBlank()) return Result.fail("请填写收货人");
        if (request.getReceiverAddress() == null || request.getReceiverAddress().isBlank()) return Result.fail("请填写收货地址");

        // 幂等令牌：原子消费，重复提交直接拒绝（防双击/网络重放的重复下单）
        if (!idempotentTokenStore.consume(request.getIdempotentToken())) {
            return Result.fail("请勿重复提交订单（或页面已过期），请刷新结算页重试");
        }

        List<OrderTransactionalCreator.ItemInput> inputs = new ArrayList<>();
        for (ItemInput input : request.getItems()) {
            inputs.add(new OrderTransactionalCreator.ItemInput(input.getProductSkuId(), input.getQuantity()));
        }

        // 事务在 OrderTransactionalCreator 内（每次全新事务），此处负责死锁重试
        Order order = orderCreateService.createWithRetry(memberId, request.getReceiverName(),
                request.getReceiverPhone(), request.getReceiverAddress(), inputs,
                Boolean.TRUE.equals(request.getFromCart()));

        // 清购物车：从 oms 事务内上移到 portal 侧（解除 oms→member 依赖）
        if (Boolean.TRUE.equals(request.getFromCart())) {
            for (ItemInput input : request.getItems()) {
                cartService.remove(new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getMemberId, memberId)
                        .eq(Cart::getProductSkuId, input.getProductSkuId()));
            }
        }

        return Result.ok(detailBody(order));
    }

    /** 模拟支付：0待付款 → 1已付款 */
    @PostMapping("/{id}/pay")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> pay(@PathVariable Long id, @RequestParam(defaultValue = "1") Integer payType) {
        Order order = orderService.getById(id);
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
        if (order.getStatus() != 0) return Result.fail("当前订单状态不可支付");
        // 口径②：支付成功后自动进入「待审核」，无需管理员手动提交
        // 状态流转：0待付款 →（支付）1已付款 →（OMS 自动交付）7待审核
        // 状态统一走 OrderStatusService：payType / paymentTime 与 status 在**同一条** UPDATE 里
        // （拆成两条会多涨一次 version，买家的乐观锁语义就变了）
        java.time.LocalDateTime paidAt = LocalDateTime.now();
        order.setPayType(payType);
        order.setPaymentTime(paidAt);
        // **并发支付的唯一闸门**（order 表有 @Version）：0 行 = 读出来之后订单已经被改过
        // （最常见就是「同一张单被并发付了两次」）。这时绝不能当成功返回：
        // 用户会以为付了两笔、实际只记一笔，或者状态根本没变还说支付成功。
        // 抛 409 → 事务回滚（支付流水也不会落库），前端提示买家刷新。
        // 文案用买家视角的说法（不用「数据被他人修改」）：见 OptimisticLockGuard.MSG_ORDER_STATE_CHANGED
        int rows = orderStatusService.change(order, 7, OrderStatusService.REASON_PAY, null,
                update -> update.set(Order::getPayType, payType).set(Order::getPaymentTime, paidAt));
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_ORDER_STATE_CHANGED);
        // 支付流水（对账键 = 订单号）：与上面的状态推进在同一个事务里，要么都成、要么都不记
        paymentRecordService.record(order.getOrderSn(), order.getTotalAmount(),
                PaymentRecord.TYPE_PAY, payType);
        return Result.ok(detailBody(order));
    }

    /**
     * 确认收货：已发货(2) → 已完成(3)；守卫：仅本人订单且 status=2。
     *
     * <p><b>口径②（用户拍板）：子单是事实源 —— 父单状态是推导值，这里不再硬写 order.status=3。</b>
     * 顺序是「先把事实推到位，再让聚合算父单」：
     * <pre>
     * ① {@link com.qiqi.mall.oms.service.DeliveryFlowService#signRemainingDeliveries} 把该订单所有
     *    未取消、未签收的包裹推到「已签收」（与 WMS 回传 signed 同一条落库路径 + 补运单）
     * ② {@link com.qiqi.mall.oms.service.DeliveryFlowService#recomputeOrderStatus} 由子单推导父单 → 3 已完成
     * </pre>
     * 这样父单与子单不会分叉：老实现只写父单、不碰子单，于是「父单已完成 + 另一个包裹还在运输中」
     * 这个组合是真实存在的，之后晚到的 WMS 回传按子单重新聚合就把已完成为写回已发货（状态倒退）。
     *
     * <p><b>唯一的例外：直发路径</b>（订单下没有任何未取消的包裹 —— 单仓直发时订单由管理员「发货」
     * 直接从 1 置到 2，根本没有包裹行；或包裹都被取消了）。此时不存在子单事实，父单只能由这一步推进，
     * 这一笔写入是唯一写者，不存在两个写者抢同一个状态字段。
     *
     * <p>整笔一个事务：推子单 / 聚合写父单 / 补运单任一步失败 → 全部回滚（不会留下「包裹已签收、订单没完成」）。
     */
    @PostMapping("/{id}/confirm")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> confirm(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
        if (order.getStatus() != 2) return Result.fail("只有已发货订单可以确认收货");

        if (deliveryFlowService.activeDeliveryCount(id) > 0) {
            // ① 先写事实：所有在途包裹 → 已签收（0 行 = 被别人改过 → 409 回滚整笔）
            deliveryFlowService.signRemainingDeliveries(id);
            // ② 再推导父单：全部子单已签收 → 3 已完成（父单状态由子单算出，不是硬写）
            deliveryFlowService.recomputeOrderStatus(id);
        } else {
            // 直发路径：没有子单事实可依，只能由买家这一步推进（唯一写者）
            // 状态写入统一走 OrderStatusService（同一条 UPDATE 里带 status_seq 自增与 version 自增）
            // 0 行 = 读出来之后订单被别人改过（例如另一边已经在确认收货）→ 409，买家视角文案
            int rows = orderStatusService.change(order, 3, OrderStatusService.REASON_CONFIRM, null);
            OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_ORDER_STATE_CHANGED);
        }
        // 回读：返回的状态必须是库里真实的那个（聚合算出来的 3，而不是我们以为的 3）
        return Result.ok(detailBody(orderService.getById(id)));
    }

    private Map<String, Object> detailBody(Order order) {
        List<OrderItem> items = orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()).orderByAsc(OrderItem::getId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("items", items);
        return data;
    }

    public static class ItemInput {
        private Long productSkuId;
        private Integer quantity;

        public Long getProductSkuId() { return productSkuId; }
        public void setProductSkuId(Long productSkuId) { this.productSkuId = productSkuId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }

    public static class CreateRequest {
        private String receiverName;
        private String receiverPhone;
        private String receiverAddress;
        private List<ItemInput> items;
        private Boolean fromCart;
        private String idempotentToken;

        public String getReceiverName() { return receiverName; }
        public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
        public String getReceiverPhone() { return receiverPhone; }
        public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
        public String getReceiverAddress() { return receiverAddress; }
        public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }
        public List<ItemInput> getItems() { return items; }
        public void setItems(List<ItemInput> items) { this.items = items; }
        public Boolean getFromCart() { return fromCart; }
        public void setFromCart(Boolean fromCart) { this.fromCart = fromCart; }
        public String getIdempotentToken() { return idempotentToken; }
        public void setIdempotentToken(String idempotentToken) { this.idempotentToken = idempotentToken; }
    }
}
