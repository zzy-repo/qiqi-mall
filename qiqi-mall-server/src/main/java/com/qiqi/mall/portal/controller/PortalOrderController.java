package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.shared.security.CurrentUser;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.service.OrderCreateService;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderTransactionalCreator;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.shared.security.IdempotentTokenStore;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** C 端下单、我的订单、模拟支付 */
@RestController
@RequestMapping("/portal/orders")
public class PortalOrderController {

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final PaymentRecordService paymentRecordService;
    private final OrderCreateService orderCreateService;
    private final IdempotentTokenStore idempotentTokenStore;

    public PortalOrderController(OrderService orderService, OrderItemService orderItemService,
                                 PaymentRecordService paymentRecordService,
                                 OrderCreateService orderCreateService,
                                 IdempotentTokenStore idempotentTokenStore) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.paymentRecordService = paymentRecordService;
        this.orderCreateService = orderCreateService;
        this.idempotentTokenStore = idempotentTokenStore;
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

        return Result.ok(detailBody(order));
    }

    /** 模拟支付：0待付款 → 1已付款 */
    @PostMapping("/{id}/pay")
    public Result<Map<String, Object>> pay(@PathVariable Long id, @RequestParam(defaultValue = "1") Integer payType) {
        Order order = orderService.getById(id);
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
        if (order.getStatus() != 0) return Result.fail("当前订单状态不可支付");
        order.setStatus(1);
        order.setPayType(payType);
        order.setPaymentTime(LocalDateTime.now());
        orderService.updateById(order);
        // 支付流水（对账键 = 订单号）
        paymentRecordService.record(order.getOrderSn(), order.getTotalAmount(),
                PaymentRecord.TYPE_PAY, payType);
        return Result.ok(detailBody(order));
    }

    /** 确认收货：已发货(2) → 已完成(3)，守卫：仅本人订单且 status=2 */
    @PostMapping("/{id}/confirm")
    public Result<Map<String, Object>> confirm(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
        if (order.getStatus() != 2) return Result.fail("只有已发货订单可以确认收货");
        order.setStatus(3);
        orderService.updateById(order);
        return Result.ok(detailBody(order));
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
