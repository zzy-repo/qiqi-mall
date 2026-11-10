package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Order;
import com.qiqi.mall.entity.OrderItem;
import com.qiqi.mall.entity.Product;
import com.qiqi.mall.entity.Sku;
import com.qiqi.mall.security.CurrentUser;
import com.qiqi.mall.service.*;
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
    private final ProductService productService;
    private final SkuService skuService;
    private final CartService cartService;

    public PortalOrderController(OrderService orderService, OrderItemService orderItemService,
                                 ProductService productService, SkuService skuService, CartService cartService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.productService = productService;
        this.skuService = skuService;
        this.cartService = cartService;
    }

    /** 我的订单列表 */
    @GetMapping
    public Result<List<Order>> list() {
        return Result.ok(orderService.list(new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, CurrentUser.id()).orderByDesc(Order::getId)));
    }

    /** 订单详情（含明细） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
        return Result.ok(detailBody(order));
    }

    /** 提交订单：{receiverName, receiverPhone, receiverAddress, items:[{productSkuId, quantity}], fromCart} */
    @PostMapping
    @Transactional
    public Result<Map<String, Object>> create(@RequestBody CreateRequest request) {
        Long memberId = CurrentUser.id();
        if (request.getItems() == null || request.getItems().isEmpty()) return Result.fail("请选择要结算的商品");
        if (request.getReceiverName() == null || request.getReceiverName().isBlank()) return Result.fail("请填写收货人");
        if (request.getReceiverAddress() == null || request.getReceiverAddress().isBlank()) return Result.fail("请填写收货地址");

        Order order = new Order();
        order.setOrderSn("QQ" + System.currentTimeMillis());
        order.setMemberId(memberId);
        order.setStatus(0);
        order.setPayType(0);
        order.setReceiverName(request.getReceiverName());
        order.setReceiverPhone(request.getReceiverPhone());
        order.setReceiverAddress(request.getReceiverAddress());
        order.setTotalAmount(BigDecimal.ZERO);
        orderService.save(order);

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        for (ItemInput input : request.getItems()) {
            Sku sku = skuService.getById(input.getProductSkuId());
            if (sku == null) continue;
            Product product = productService.getById(sku.getProductId());
            int qty = input.getQuantity() == null || input.getQuantity() < 1 ? 1 : input.getQuantity();

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(sku.getProductId());
            item.setProductSkuId(sku.getId());
            item.setProductName(product == null ? "" : product.getName());
            item.setProductPrice(sku.getPrice());
            item.setProductQuantity(qty);
            items.add(item);
            total = total.add(sku.getPrice().multiply(BigDecimal.valueOf(qty)));
        }
        if (items.isEmpty()) return Result.fail("商品信息无效，无法下单");
        orderItemService.saveBatch(items);
        order.setTotalAmount(total);
        orderService.updateById(order);

        // 从购物车下单则移除对应条目
        if (Boolean.TRUE.equals(request.getFromCart())) {
            for (ItemInput input : request.getItems()) {
                cartService.remove(new LambdaQueryWrapper<com.qiqi.mall.entity.Cart>()
                        .eq(com.qiqi.mall.entity.Cart::getMemberId, memberId)
                        .eq(com.qiqi.mall.entity.Cart::getProductSkuId, input.getProductSkuId()));
            }
        }
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
    }
}
