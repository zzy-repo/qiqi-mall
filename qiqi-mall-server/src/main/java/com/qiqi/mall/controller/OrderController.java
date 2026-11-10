package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.common.OrderSnGenerator;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.dto.OrderDetailDTO;
import com.qiqi.mall.entity.Order;
import com.qiqi.mall.entity.OrderItem;
import com.qiqi.mall.entity.ReturnApply;
import com.qiqi.mall.service.OrderItemService;
import com.qiqi.mall.service.OrderService;
import com.qiqi.mall.service.ReturnApplyService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final ReturnApplyService returnApplyService;

    public OrderController(OrderService orderService, OrderItemService orderItemService,
                           ReturnApplyService returnApplyService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.returnApplyService = returnApplyService;
    }

    /**
     * 订单列表（分页 + 按状态/订单号筛选）。
     * 订单量大时必须分页：size 夹在 1~50，按主键倒序。
     */
    @GetMapping
    public Result<Page<Order>> list(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(required = false) String orderSn) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 50);
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<>();
        query.eq(status != null, Order::getStatus, status)
                .like(orderSn != null && !orderSn.isBlank(), Order::getOrderSn, orderSn)
                .orderByDesc(Order::getId);
        return Result.ok(orderService.page(new Page<>(safePage, safeSize), query));
    }

    /** 订单详情（含明细与退货申请，聚合展示） */
    @GetMapping("/{id}")
    public Result<OrderDetailDTO> detail(@PathVariable Long id) {
        OrderDetailDTO dto = new OrderDetailDTO();
        dto.setOrder(orderService.getById(id));
        dto.setItems(orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, id).orderByAsc(OrderItem::getId)));
        dto.setReturnApplies(returnApplyService.list(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, id).orderByAsc(ReturnApply::getId)));
        return Result.ok(dto);
    }

    /** 创建订单（含明细，快照由前端传入） */
    @PostMapping
    @Transactional
    public Result<Order> create(@RequestBody OrderCreateRequest request) {
        Order order = request.getOrder();
        order.setId(null);
        if (order.getOrderSn() == null || order.getOrderSn().isBlank()) {
            order.setOrderSn(OrderSnGenerator.next());
        }
        List<OrderItem> items = request.getItems();
        // 总金额以明细为准（快照价 × 数量）
        if (items != null && !items.isEmpty()) {
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (OrderItem it : items) {
                java.math.BigDecimal price = it.getProductPrice() == null ? java.math.BigDecimal.ZERO : it.getProductPrice();
                int qty = it.getProductQuantity() == null ? 1 : it.getProductQuantity();
                total = total.add(price.multiply(java.math.BigDecimal.valueOf(qty)));
            }
            order.setTotalAmount(total);
        }
        orderService.save(order);
        if (items != null) {
            items.forEach(item -> {
                item.setId(null);
                item.setOrderId(order.getId());
            });
            orderItemService.saveBatch(items);
        }
        return Result.ok(order);
    }

    /**
     * 更新订单基础信息（收货人/地址等）。
     * 守卫：status 不通过本接口变更——统一置空，状态只能经专用接口 + 前置校验流转。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Order body) {
        body.setId(id);
        body.setStatus(null);
        orderService.updateById(body);
        return Result.ok();
    }

    /** 发货：已付款(1) → 已发货(2)，守卫：仅 status=1 */
    @PostMapping("/{id}/ship")
    public Result<Order> ship(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        if (order.getStatus() != 1) return Result.fail("只有已付款订单可以发货");
        order.setStatus(2);
        orderService.updateById(order);
        return Result.ok(order);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Result<Void> delete(@PathVariable Long id) {
        orderService.removeById(id);
        return Result.ok();
    }

    /** 创建/更新订单时的请求体：订单 + 明细 */
    public static class OrderCreateRequest {
        private Order order;
        private List<OrderItem> items;

        public Order getOrder() {
            return order;
        }

        public void setOrder(Order order) {
            this.order = order;
        }

        public List<OrderItem> getItems() {
            return items;
        }

        public void setItems(List<OrderItem> items) {
            this.items = items;
        }
    }
}
