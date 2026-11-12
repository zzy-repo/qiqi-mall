package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.shared.common.OrderSnGenerator;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 下单事务本体：每次调用都是**全新事务**（REQUIRES_NEW），
 * 这样外层重试时上一轮（被死锁牺牲/回滚）不会留下任何残留。
 */
@Service
public class OrderTransactionalCreator {

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final ProductService productService;
    private final SkuService skuService;

    public OrderTransactionalCreator(OrderService orderService, OrderItemService orderItemService,
                                     ProductService productService, SkuService skuService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.productService = productService;
        this.skuService = skuService;
    }

    public record ItemInput(Long skuId, Integer quantity) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Order create(Long memberId, String receiverName, String receiverPhone, String receiverAddress,
                        List<ItemInput> inputs, boolean fromCart) {

        Order order = new Order();
        order.setOrderSn(OrderSnGenerator.next());
        order.setMemberId(memberId);
        order.setStatus(0);
        order.setPayType(0);
        order.setReceiverName(receiverName);
        order.setReceiverPhone(receiverPhone);
        order.setReceiverAddress(receiverAddress);
        order.setTotalAmount(BigDecimal.ZERO);
        orderService.save(order);

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        for (ItemInput input : inputs) {
            Sku sku = skuService.getById(input.skuId());
            if (sku == null) continue;
            Product product = productService.getById(sku.getProductId());
            int qty = input.quantity() == null || input.quantity() < 1 ? 1 : input.quantity();

            // 原子扣减：把「判断+减法」压进一条 SQL，避免 check-then-act 超卖
            if (!skuService.deductStock(sku.getId(), qty)) {
                throw new IllegalStateException("库存不足：" + (product == null ? "" : product.getName()));
            }

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
        if (items.isEmpty()) throw new IllegalStateException("商品信息无效，无法下单");

        orderItemService.saveBatch(items);
        order.setTotalAmount(total);
        // 总额写回：0 行 = 这张刚建的订单在事务内被别人删了（并发取消/清理）→ 抛异常回滚，
        // 不能留一张「总额 0 元、却有明细」的订单；事务是 REQUIRES_NEW，回滚即整单不成立。
        int rows = orderService.getBaseMapper().updateById(order);
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_RECORD_DELETED);

        // 清购物车已上移到 portal 侧（下单事务后执行）：
        // 购物车是「意图清单」而非账务数据，允许与订单存在轻微不一致，以此解除 oms→member 的模块依赖。
        return order;
    }
}
