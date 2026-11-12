package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.oms.wms.MockWmsService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.shared.security.ConfirmCodeService;
import com.qiqi.mall.shared.security.ConfirmPreview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

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
    private final WarehouseService warehouseService;

    public DeliveryFlowService(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                               DeliveryLogisticsService deliveryLogisticsService, OrderService orderService,
                               SkuService skuService, MockWmsService wms, WarehouseService warehouseService) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.orderService = orderService;
        this.skuService = skuService;
        this.wms = wms;
        this.warehouseService = warehouseService;
    }

    /**
     * 下发：守卫 仅待下发。
     * 事务：WMS 调用 + 状态落库必须在同一个事务里 —— 否则状态改了、外呼失败就会留下「已下发但 WMS 不知道」的包裹。
     */
    @Transactional(rollbackFor = Exception.class)
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

    // ------------------------------------------------------------------ 确认码：预演 + 指纹

    /*
     * 每个写操作给两样东西：
     *   previewXxx()  给人看的 willDo（带真实单号/仓名/件数）+ 指纹组成；**跑业务守卫**，签出来的码一定能执行；
     *   extrasOfXxx() 执行阶段当场重算的指纹组成；**不跑业务守卫** —— 因为校验顺序是
     *                 「缺码 → 无效/已用/过期 → 目标不符 → 指纹变化 → 原业务守卫」，
     *                 守卫若在这里抢跑，「码是对的、只是单子被别人改过」会被误报成别的原因。
     */

    /** 预演「下发」：守卫同 {@link #dispatch}；指纹 = 订单 id + 仓库 id + 包裹明细摘要 */
    public ConfirmPreview previewDispatch(Long deliveryId) {
        DeliveryOrder d = require(deliveryId);
        if (d.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发发货单可以下发");
        }
        List<DeliveryItem> items = itemsOf(deliveryId);
        String willDo = "将把包裹 " + d.getDeliverySn() + "（" + warehouseName(d.getWarehouseId()) + "，"
                + items.size() + " 条明细合计 " + totalQty(items) + " 件）下发给 WMS：状态 待下发→已下发。"
                + "下发后不可撤回，只能等 WMS 回传。";
        return new ConfirmPreview(willDo, dispatchExtras(d));
    }

    /** 下发指纹（执行阶段重算；不跑守卫） */
    public Object[] extrasOfDispatch(Long deliveryId) {
        DeliveryOrder d = deliveryOrderService.getById(deliveryId);
        return d == null ? new Object[]{null, null, List.of()} : dispatchExtras(d);
    }

    private Object[] dispatchExtras(DeliveryOrder d) {
        return new Object[]{d.getOrderId(), d.getWarehouseId(), itemLines(d.getId())};
    }

    /** 预演「取消」：守卫同 {@link #cancel}；指纹含「回补哪些 SKU 多少件」，明细被改过就废 */
    public ConfirmPreview previewCancel(Long deliveryId) {
        DeliveryOrder d = require(deliveryId);
        if (d.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发发货单可以取消");
        }
        List<DeliveryItem> items = itemsOf(deliveryId);
        List<String> restore = skuQtyLines(items);
        String willDo = "将取消包裹 " + d.getDeliverySn() + "（" + warehouseName(d.getWarehouseId())
                + "）：状态 待下发→已取消，并把 " + items.size() + " 条明细的 SKU 总池库存回补（"
                + describeSkuQty(restore) + "，合计 " + totalQty(items) + " 件）；父订单状态会重新聚合。";
        return new ConfirmPreview(willDo, cancelExtras(d, items));
    }

    /** 取消指纹（执行阶段重算；不跑守卫） */
    public Object[] extrasOfCancel(Long deliveryId) {
        DeliveryOrder d = deliveryOrderService.getById(deliveryId);
        if (d == null) return new Object[]{null, List.of(), 0};
        List<DeliveryItem> items = itemsOf(deliveryId);
        return cancelExtras(d, items);
    }

    private Object[] cancelExtras(DeliveryOrder d, List<DeliveryItem> items) {
        return new Object[]{d.getOrderId(), skuQtyLines(items), totalQty(items)};
    }

    /** 预演「WMS 回传」：守卫同 {@link #wmsCallback}（含幂等判断）；指纹 = 事件名 + 当前状态 */
    public ConfirmPreview previewWmsCallback(Long deliveryId, String event) {
        DeliveryOrder d = require(deliveryId);
        int current = d.getStatus();
        if (current == DeliveryOrder.CANCELLED) throw new IllegalStateException("已取消的发货单不能回传");
        String name = event == null ? "" : event.toLowerCase();
        int target = targetStatusOf(name);
        String willDo;
        if (current >= target) {
            willDo = "包裹 " + d.getDeliverySn() + " 当前已是「" + statusText(current) + "」，回传 " + name
                    + " 不会改动任何数据（接口幂等），只会原样返回。";
        } else {
            // 与执行路径同一套状态守卫，避免签出一个注定失败的码
            switch (name) {
                case "pick" -> requireState(d, DeliveryOrder.DISPATCHED, "只有已下发发货单可以回传拣货");
                case "outbound" -> {
                    if (current != DeliveryOrder.DISPATCHED && current != DeliveryOrder.PICKED) {
                        throw new IllegalStateException("只有已下发/已拣货发货单可以回传出库");
                    }
                }
                case "transit" -> requireState(d, DeliveryOrder.OUTBOUND, "只有已出库发货单可以回传揽收");
                case "signed" -> {
                    if (current < DeliveryOrder.OUTBOUND) {
                        throw new IllegalStateException("只有已出库之后的发货单可以回传签收");
                    }
                }
                default -> { }
            }
            willDo = "将记录 WMS 回传 " + name + "：包裹 " + d.getDeliverySn() + "（" + warehouseName(d.getWarehouseId())
                    + "）状态 " + statusText(current) + "→" + statusText(target) + "；父订单会按所有子单重新聚合。";
        }
        return new ConfirmPreview(willDo, wmsCallbackExtras(d, name));
    }

    /** 回传指纹（执行阶段重算；不跑守卫：未知事件/状态不符交给原守卫报错） */
    public Object[] extrasOfWmsCallback(Long deliveryId, String event) {
        DeliveryOrder d = deliveryOrderService.getById(deliveryId);
        if (d == null) return new Object[]{null, null};
        return wmsCallbackExtras(d, event == null ? "" : event.toLowerCase());
    }

    private Object[] wmsCallbackExtras(DeliveryOrder d, String name) {
        return new Object[]{name, d.getStatus()};
    }

    private static int targetStatusOf(String name) {
        return switch (name) {
            case "pick" -> DeliveryOrder.PICKED;
            case "outbound" -> DeliveryOrder.OUTBOUND;
            case "transit" -> DeliveryOrder.IN_TRANSIT;
            case "signed" -> DeliveryOrder.SIGNED;
            default -> throw new IllegalStateException("未知回传事件：" + name + "（支持 pick/outbound/transit/signed）");
        };
    }

    /** 包裹明细（按 id 升序，保证指纹稳定） */
    private List<DeliveryItem> itemsOf(Long deliveryId) {
        return deliveryItemService.list(new LambdaQueryWrapper<DeliveryItem>()
                .eq(DeliveryItem::getDeliveryId, deliveryId).orderByAsc(DeliveryItem::getId));
    }

    /** 明细摘要：{@code ["orderItemId:skuId:qty", ...]} 排序 —— 「包裹里到底是什么」的指纹来源 */
    private List<String> itemLines(Long deliveryId) {
        List<String> lines = new ArrayList<>();
        for (DeliveryItem item : itemsOf(deliveryId)) {
            lines.add(item.getOrderItemId() + ":" + item.getProductSkuId() + ":" + item.getQuantity());
        }
        return ConfirmCodeService.sortedLines(lines);
    }

    /** 回补摘要：按 skuId 汇总件数（库存是按 sku 回补的，同 sku 多条明细必须合并后才对得上） */
    private static List<String> skuQtyLines(List<DeliveryItem> items) {
        TreeMap<Long, Integer> bySku = new TreeMap<>();
        for (DeliveryItem item : items) {
            bySku.merge(item.getProductSkuId(), item.getQuantity() == null ? 0 : item.getQuantity(), Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        bySku.forEach((sku, qty) -> lines.add(sku + ":" + qty));
        return lines;
    }

    private static String describeSkuQty(List<String> skuQtyLines) {
        if (skuQtyLines.isEmpty()) return "无可回补明细";
        List<String> parts = new ArrayList<>();
        for (String line : skuQtyLines) {
            String[] kv = line.split(":");
            parts.add("sku " + kv[0] + " 回补 " + kv[1] + " 件");
        }
        return String.join("、", parts);
    }

    private static int totalQty(List<DeliveryItem> items) {
        return items.stream().mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
    }

    private String warehouseName(Long warehouseId) {
        Warehouse wh = warehouseId == null ? null : warehouseService.getById(warehouseId);
        return wh == null ? ("仓 " + warehouseId) : wh.getName();
    }

    /** 发货单状态中文名（预演文案用） */
    public static String statusText(int status) {
        return switch (status) {
            case 0 -> "待下发";
            case 1 -> "已下发";
            case 2 -> "已拣货";
            case 3 -> "已出库";
            case 4 -> "运输中";
            case 5 -> "已签收";
            case 9 -> "已取消";
            default -> "状态" + status;
        };
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
