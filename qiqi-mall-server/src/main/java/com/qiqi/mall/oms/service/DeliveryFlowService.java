package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.wms.MockWmsService;
import com.qiqi.mall.order.entity.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发货单状态机 + 父订单聚合（方案 3b/3c，图23）。
 * 守卫与幂等都在这里集中实现：
 * 待下发(0) → 已下发(1) → 已拣货(2) → 已出库(3) → 运输中(4) → 已签收(5)；待下发可取消(9)
 * 父订单聚合：全部子单 ≥ 已出库 → 订单=已发货(2)；部分出库 → 订单=部分发货(9)；全部已签收 → 订单=已完成(3)
 */
@Service
public class DeliveryFlowService {

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    private final OrderService orderService;
    private final SkuService skuService;
    private final MockWmsService wms;

    public DeliveryFlowService(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                               DeliveryLogisticsService deliveryLogisticsService, OrderService orderService,
                               SkuService skuService, MockWmsService wms) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.orderService = orderService;
        this.skuService = skuService;
        this.wms = wms;
    }

    /** 下发：守卫 仅待下发 */
    public DeliveryOrder dispatch(Long deliveryId) {
        DeliveryOrder delivery = require(deliveryId);
        if (delivery.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发发货单可以下发");
        }
        wms.dispatch(delivery.getDeliverySn());
        delivery.setStatus(DeliveryOrder.DISPATCHED);
        deliveryOrderService.updateById(delivery);
        return delivery;
    }

    /**
     * 取消：守卫 仅待下发。
     * 联动：回补「总池」库存（下单时扣的是 sku.stock；inventory 是分仓归属记录，拆单并未扣它，故不回补 inventory）。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeliveryOrder cancel(Long deliveryId) {
        DeliveryOrder delivery = require(deliveryId);
        if (delivery.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发发货单可以取消");
        }
        delivery.setStatus(DeliveryOrder.CANCELLED);
        deliveryOrderService.updateById(delivery);

        deliveryItemService.list(new LambdaQueryWrapper<DeliveryItem>()
                        .eq(DeliveryItem::getDeliveryId, deliveryId))
                .forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getQuantity()));

        recomputeOrderStatus(delivery.getOrderId());
        return delivery;
    }

    /**
     * WMS 回传：pick / outbound / transit / signed
     * 幂等：同一事件重复推送时，若当前状态已 ≥ 目标状态则直接返回（只生效一次）。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeliveryOrder wmsCallback(Long deliveryId, String event) {
        DeliveryOrder delivery = require(deliveryId);
        int current = delivery.getStatus();
        if (current == DeliveryOrder.CANCELLED) throw new IllegalStateException("已取消的发货单不能回传");

        switch (event == null ? "" : event.toLowerCase()) {
            case "pick" -> {
                if (current >= DeliveryOrder.PICKED) return delivery;           // 幂等
                requireState(delivery, DeliveryOrder.DISPATCHED, "只有已下发发货单可以回传拣货");
                delivery.setStatus(DeliveryOrder.PICKED);
            }
            case "outbound" -> {
                if (current >= DeliveryOrder.OUTBOUND) return delivery;         // 幂等
                if (current != DeliveryOrder.DISPATCHED && current != DeliveryOrder.PICKED) {
                    throw new IllegalStateException("只有已下发/已拣货发货单可以回传出库");
                }
                delivery.setStatus(DeliveryOrder.OUTBOUND);
                saveLogisticsIfAbsent(delivery);                                // 出库写运单号
            }
            case "transit" -> {
                if (current >= DeliveryOrder.IN_TRANSIT) return delivery;
                requireState(delivery, DeliveryOrder.OUTBOUND, "只有已出库发货单可以回传揽收");
                delivery.setStatus(DeliveryOrder.IN_TRANSIT);
            }
            case "signed" -> {
                if (current >= DeliveryOrder.SIGNED) return delivery;
                if (current < DeliveryOrder.OUTBOUND) throw new IllegalStateException("只有已出库之后的发货单可以回传签收");
                delivery.setStatus(DeliveryOrder.SIGNED);
            }
            default -> throw new IllegalStateException("未知回传事件：" + event + "（支持 pick/outbound/transit/signed）");
        }
        deliveryOrderService.updateById(delivery);
        recomputeOrderStatus(delivery.getOrderId());
        return delivery;
    }

    /** 父订单状态聚合（图23 黄框） */
    public void recomputeOrderStatus(Long orderId) {
        Order order = orderService.getById(orderId);
        if (order == null) return;
        List<DeliveryOrder> children = deliveryOrderService.list(
                new LambdaQueryWrapper<DeliveryOrder>().eq(DeliveryOrder::getOrderId, orderId));
        if (children.isEmpty()) return;
        // 已取消的子单不参与聚合
        List<DeliveryOrder> active = children.stream()
                .filter(d -> d.getStatus() != DeliveryOrder.CANCELLED).toList();
        if (active.isEmpty()) return;

        boolean allOutbound = active.stream().allMatch(d -> d.getStatus() >= DeliveryOrder.OUTBOUND);
        boolean allSigned = active.stream().allMatch(d -> d.getStatus() >= DeliveryOrder.SIGNED);
        boolean anyOutbound = active.stream().anyMatch(d -> d.getStatus() >= DeliveryOrder.OUTBOUND);

        if (allSigned) {
            order.setStatus(3);            // 已完成
        } else if (allOutbound) {
            order.setStatus(2);            // 已发货（全部子单出库）
        } else if (anyOutbound) {
            order.setStatus(9);            // 部分发货（中间态）
        } else {
            return;                        // 仍停留在待发货
        }
        orderService.updateById(order);
    }

    private void saveLogisticsIfAbsent(DeliveryOrder delivery) {
        DeliveryLogistics exist = deliveryLogisticsService.getOne(
                new LambdaQueryWrapper<DeliveryLogistics>()
                        .eq(DeliveryLogistics::getDeliveryId, delivery.getId()), false);
        if (exist != null) return;
        DeliveryLogistics logistics = new DeliveryLogistics();
        logistics.setDeliveryId(delivery.getId());
        logistics.setCompany(wms.randomCompany());
        logistics.setTrackNo(wms.randomTrackNo());
        logistics.setCreatedAt(LocalDateTime.now());
        deliveryLogisticsService.save(logistics);
    }

    private DeliveryOrder require(Long deliveryId) {
        DeliveryOrder delivery = deliveryOrderService.getById(deliveryId);
        if (delivery == null) throw new IllegalStateException("发货单不存在");
        return delivery;
    }

    private void requireState(DeliveryOrder delivery, int expected, String message) {
        if (delivery.getStatus() != expected) throw new IllegalStateException(message);
    }
}
