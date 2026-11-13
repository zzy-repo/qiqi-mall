package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.Inventory;
import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.shared.common.DeliverySnGenerator;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拆单（docs/flows/22-拆单时序图 / ER-21）：
 * 把「待发货」订单按 SKU 的分仓归属拆成若干发货单。
 *
 * 口径（FIX-oms-split 已确认）：
 * ① 拆单只做「归属标记」，不扣 inventory —— 下单时已扣总池 sku.stock，
 *    拆单只是确认从哪个仓发货；inventory 只在入库/取消回补/盘点时变动。
 * ② 单仓订单也生成 1 个发货单，统一履约链路。
 * ③ 某个 SKU 在多个仓都有库存时，取「库存最多的仓」作为其发货仓（不做智能分仓，方案明确不做）。
 * ④ <b>该 SKU 一条分仓记录都没有时不再报错</b>：归到<b>默认仓</b>（启用仓里 id 最小的那个），
 *    并在日志与拆单方案（{@code plan[].note} / {@code willDo}）里写明「该规格未配置分仓库存，已按默认仓 XX 处理」。
 *    这条兜底修的是「分仓库存只配了少数规格 → 其余商品一下单就报『无法确定发货仓』」——
 *    拆不出包裹是比「发错仓」更糟的结果：订单直接卡死在待审核。
 */
@Service
public class SplitOrderService {

    private static final Logger log = LoggerFactory.getLogger(SplitOrderService.class);

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final InventoryService inventoryService;
    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final WarehouseService warehouseService;
    /** 包裹状态写入的唯一入口（出生：拆出来的包裹 = 待下发(0)） */
    private final DeliveryStatusService deliveryStatusService;
    /** 后台操作审计（拆单决定货从哪个仓出，是敏感动作） */
    private final AdminAuditWriter auditWriter;

    public SplitOrderService(OrderService orderService, OrderItemService orderItemService,
                             InventoryService inventoryService, DeliveryOrderService deliveryOrderService,
                             DeliveryItemService deliveryItemService, WarehouseService warehouseService,
                             DeliveryStatusService deliveryStatusService, AdminAuditWriter auditWriter) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.inventoryService = inventoryService;
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.warehouseService = warehouseService;
        this.deliveryStatusService = deliveryStatusService;
        this.auditWriter = auditWriter;
    }

    /**
     * 拆单方案预览（dry-run）：只算不落库。
     * 守卫：仅 status=7 待审核 可预览（此刻还没拆出任何发货单）。
     * 返回：[{warehouseId, warehouseName, items:[{orderItemId, productSkuId, productName, quantity}]}]
     * 不写任何表、不改状态、不校验库存——调用前后 delivery_order 行数必须一致。
     */
    public List<Map<String, Object>> preview(Long orderId) {
        Order order = orderService.getById(orderId);
        if (order == null) throw new IllegalStateException("订单不存在");
        if (order.getStatus() == null || order.getStatus() != 7) {
            throw new IllegalStateException("只有待审核订单可以预览拆单方案");
        }
        return buildPlan(orderId);
    }

    /**
     * 重算当前方案（**不校验 status**）：只给「确认码指纹比对」用。
     *
     * <p>为什么不直接复用 {@link #preview(Long)}：audit-pass 的校验顺序是
     * ④ 比对指纹 → ⑤ 再看 status==7。若这里也守 status，状态不对的订单会先抛
     * 「只有待审核订单可以预览拆单方案」，把「码对、但单子已被别人审过」误报成别的原因。
     *
     * @throws IllegalStateException 订单不存在 / 没有明细 / SKU 无法确定发货仓（= 方案已不可复现）
     */
    public List<Map<String, Object>> recomputePlan(Long orderId) {
        if (orderService.getById(orderId) == null) throw new IllegalStateException("订单不存在");
        return buildPlan(orderId);
    }

    /** preview 与 recomputePlan 的共同实现：同一套归属口径 => 「预览即所得」 */
    private List<Map<String, Object>> buildPlan(Long orderId) {
        List<OrderItem> items = orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).orderByAsc(OrderItem::getId));
        if (items.isEmpty()) throw new IllegalStateException("订单没有明细，无法预览拆单方案");

        Map<Long, Assignment> assignments = assignmentsOf(items);
        Map<Long, List<OrderItem>> byWarehouse = groupByWarehouse(items, assignments);
        List<Map<String, Object>> plan = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> entry : byWarehouse.entrySet()) {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("warehouseId", entry.getKey());
            Warehouse wh = warehouseService.getById(entry.getKey());
            pkg.put("warehouseName", wh == null ? "" : wh.getName());

            // 兜底说明：这包里有哪些规格是「没有分仓记录 → 落到默认仓」的。
            // 结构化字段给前端，note 给人和 willDo 文案用 —— 预演阶段就说清，别等审完才发现。
            List<Long> fallbackSkuIds = new ArrayList<>();
            for (OrderItem item : entry.getValue()) {
                if (assignments.get(item.getProductSkuId()).fallbackToDefault()) {
                    fallbackSkuIds.add(item.getProductSkuId());
                }
            }
            pkg.put("fallbackToDefaultWarehouse", !fallbackSkuIds.isEmpty());
            if (!fallbackSkuIds.isEmpty()) {
                pkg.put("note", "该规格未配置分仓库存，已按默认仓「" + pkg.get("warehouseName")
                        + "」（id=" + entry.getKey() + "）处理（SKU " + joinIds(fallbackSkuIds) + "）");
            }

            List<Map<String, Object>> lines = new ArrayList<>();
            for (OrderItem item : entry.getValue()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("orderItemId", item.getId());
                line.put("productSkuId", item.getProductSkuId());
                line.put("productName", item.getProductName());
                line.put("quantity", item.getProductQuantity());
                line.put("fallbackToDefaultWarehouse", assignments.get(item.getProductSkuId()).fallbackToDefault());
                lines.add(line);
            }
            pkg.put("items", lines);
            plan.add(pkg);
        }
        return plan;
    }

    private static String joinIds(List<Long> ids) {
        List<String> parts = new ArrayList<>();
        for (Long id : ids) parts.add(String.valueOf(id));
        return String.join("、", parts);
    }

    /**
     * 拆单。守卫：订单必须是「待发货」，且未被拆过，且有待拆明细。
     * @return 生成/已存在的发货单（幂等：已拆过直接返回现有子单）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<DeliveryOrder> split(Long orderId) {
        Order order = orderService.getById(orderId);
        if (order == null) throw new IllegalStateException("订单不存在");
        if (order.getSplitStatus() != null && order.getSplitStatus() == 1) {
            // 幂等：重复调用直接返回已有发货单
            return deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                    .eq(DeliveryOrder::getOrderId, orderId).orderByAsc(DeliveryOrder::getId));
        }
        if (order.getStatus() == null || order.getStatus() != 8) {
            throw new IllegalStateException("只有待发货订单可以拆单");
        }

        List<OrderItem> items = orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).orderByAsc(OrderItem::getId));
        if (items.isEmpty()) throw new IllegalStateException("订单没有明细，无法拆单");

        // 按「发货仓」分组；同 SKU 多仓时取库存最多的仓（归属标记口径）；
        // 该 SKU 没有任何分仓记录 → 落默认仓（启用仓里 id 最小的那个），不报错
        Map<Long, List<OrderItem>> byWarehouse = groupByWarehouse(items, assignmentsOf(items));

        List<DeliveryOrder> created = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> entry : byWarehouse.entrySet()) {
            DeliveryOrder delivery = new DeliveryOrder();
            delivery.setDeliverySn(DeliverySnGenerator.next());
            delivery.setOrderId(order.getId());
            delivery.setWarehouseId(entry.getKey());
            // 出生：新包裹初始状态「待下发(0)」走唯一入口（save 之前定初值，不产生 UPDATE）
            deliveryStatusService.init(delivery, DeliveryOrder.PENDING);
            delivery.setSource(DeliveryOrder.SOURCE_AUTO);   // 自动拆单
            // 收货三件套快照：多包各自贴单
            delivery.setReceiverName(order.getReceiverName());
            delivery.setReceiverPhone(order.getReceiverPhone() == null ? "" : order.getReceiverPhone());
            delivery.setReceiverAddress(order.getReceiverAddress() == null ? "" : order.getReceiverAddress());
            delivery.setCreatedAt(LocalDateTime.now());
            deliveryOrderService.save(delivery);
            // 出生台账（from_status = NULL + delivery_sn 快照）：拆单生成的包裹也有「出生」证据
            deliveryStatusService.recordBirth(delivery, DeliveryStatusService.REASON_CREATE, "自动拆单");

            for (OrderItem item : entry.getValue()) {
                DeliveryItem di = new DeliveryItem();
                di.setDeliveryId(delivery.getId());
                di.setOrderItemId(item.getId());
                di.setProductSkuId(item.getProductSkuId());
                di.setQuantity(item.getProductQuantity());
                deliveryItemService.save(di);
            }
            created.add(delivery);
        }

        order.setSplitStatus(1);
        // 0 行 = 本事务读出来之后订单被别人改过（审核/取消/另一笔拆单）→ 抛 409 让**整笔拆单回滚**。
        // 忽略返回值的后果：「发货单生成了、订单却没标记已拆」，履约链路从此对不上账。
        int rows = orderService.getBaseMapper().updateById(order);
        OptimisticLockGuard.requireAffected(rows);
        // 审计（敏感动作：拆单决定货从哪个仓出；0 行上面已经抛 409，不会记一条没发生的拆单）
        List<String> sns = new ArrayList<>();
        for (DeliveryOrder d : created) sns.add(d.getDeliverySn());
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_ORDER_SPLIT, ReasonCodes.TARGET_ORDER, orderId, order.getOrderSn(),
                "拆单：订单 " + order.getOrderSn() + " 拆成 " + created.size() + " 个包裹（"
                        + String.join("、", sns) + "）",
                null, orderId,
                AdminAuditWriter.snapshot("splitStatus", order.getSplitStatus() == null ? null : 0),
                AdminAuditWriter.snapshot("splitStatus", 1, "packages", sns)));
        return created;
    }

    /** 按发货仓分组（split 与 preview 共用同一套归属口径，保证「预览即所得」） */
    private Map<Long, List<OrderItem>> groupByWarehouse(List<OrderItem> items,
                                                       Map<Long, Assignment> assignments) {
        Map<Long, List<OrderItem>> byWarehouse = new LinkedHashMap<>();
        for (OrderItem item : items) {
            Long warehouseId = assignments.get(item.getProductSkuId()).warehouseId();
            byWarehouse.computeIfAbsent(warehouseId, k -> new ArrayList<>()).add(item);
        }
        return byWarehouse;
    }

    /** 明细各规格的归属（同一 SKU 只解析一次；split 与 preview 共用） */
    private Map<Long, Assignment> assignmentsOf(List<OrderItem> items) {
        Map<Long, Assignment> assignments = new HashMap<>();
        for (OrderItem item : items) {
            assignments.computeIfAbsent(item.getProductSkuId(), this::resolveAssignment);
        }
        return assignments;
    }

    /**
     * 一个规格的归属结果。
     *
     * @param warehouseId       发货仓
     * @param fallbackToDefault true = 该 SKU 没有任何分仓记录，走的是「默认仓」兜底
     */
    private record Assignment(Long warehouseId, boolean fallbackToDefault) {
    }

    /**
     * SKU 的发货仓：取库存最多的仓（归属标记口径）。
     *
     * <p><b>没有分仓记录时不再抛错</b>，改为落<b>默认仓</b>（启用状态里 id 最小的那个仓）并记 warn：
     * 分仓库存只配了少数规格时，其余商品一下单/拆单就报「无法确定发货仓」，订单直接卡死；
     * 正确做法是「没配就是没细分，按默认仓发」，同时把这件事说清楚（日志 + 预演 note）。
     * 补数据请跑 {@code scripts/backfill-inventory.sh}（幂等，按默认仓补齐）。
     */
    private Assignment resolveAssignment(Long skuId) {
        List<Inventory> rows = inventoryService.list(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (rows.isEmpty()) {
            Long defaultWarehouseId = defaultWarehouseId();
            Warehouse wh = warehouseService.getById(defaultWarehouseId);
            String name = wh == null ? ("仓 " + defaultWarehouseId) : wh.getName();
            log.warn("规格 {} 未配置分仓库存记录，已按默认仓 {}（id={}）处理", skuId, name, defaultWarehouseId);
            return new Assignment(defaultWarehouseId, true);
        }
        Long warehouseId = rows.stream()
                .max(Comparator.comparingInt(r -> r.getStock() == null ? 0 : r.getStock()))
                .map(Inventory::getWarehouseId)
                .orElseThrow(() -> new IllegalStateException("SKU " + skuId + " 发货仓解析失败"));
        return new Assignment(warehouseId, false);
    }

    /** 默认仓 = 启用状态（status=1）里 id 最小的仓（与约定一致，稳定不随数据量变化） */
    private Long defaultWarehouseId() {
        Warehouse wh = warehouseService.getOne(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getStatus, 1)
                .orderByAsc(Warehouse::getId)
                .last("LIMIT 1"), false);
        if (wh == null) {
            throw new IllegalStateException("没有启用的仓库（warehouse.status=1），无法确定默认发货仓");
        }
        return wh.getId();
    }
}
