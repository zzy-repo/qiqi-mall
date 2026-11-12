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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 */
@Service
public class SplitOrderService {

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final InventoryService inventoryService;
    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final WarehouseService warehouseService;

    public SplitOrderService(OrderService orderService, OrderItemService orderItemService,
                             InventoryService inventoryService, DeliveryOrderService deliveryOrderService,
                             DeliveryItemService deliveryItemService, WarehouseService warehouseService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.inventoryService = inventoryService;
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.warehouseService = warehouseService;
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

        Map<Long, List<OrderItem>> byWarehouse = groupByWarehouse(items);
        List<Map<String, Object>> plan = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> entry : byWarehouse.entrySet()) {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("warehouseId", entry.getKey());
            Warehouse wh = warehouseService.getById(entry.getKey());
            pkg.put("warehouseName", wh == null ? "" : wh.getName());
            List<Map<String, Object>> lines = new ArrayList<>();
            for (OrderItem item : entry.getValue()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("orderItemId", item.getId());
                line.put("productSkuId", item.getProductSkuId());
                line.put("productName", item.getProductName());
                line.put("quantity", item.getProductQuantity());
                lines.add(line);
            }
            pkg.put("items", lines);
            plan.add(pkg);
        }
        return plan;
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

        // 按「发货仓」分组；同 SKU 多仓时取库存最多的仓（归属标记口径）
        Map<Long, List<OrderItem>> byWarehouse = groupByWarehouse(items);

        List<DeliveryOrder> created = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> entry : byWarehouse.entrySet()) {
            DeliveryOrder delivery = new DeliveryOrder();
            delivery.setDeliverySn(DeliverySnGenerator.next());
            delivery.setOrderId(order.getId());
            delivery.setWarehouseId(entry.getKey());
            delivery.setStatus(DeliveryOrder.PENDING);
            delivery.setSource(DeliveryOrder.SOURCE_AUTO);   // 自动拆单
            // 收货三件套快照：多包各自贴单
            delivery.setReceiverName(order.getReceiverName());
            delivery.setReceiverPhone(order.getReceiverPhone() == null ? "" : order.getReceiverPhone());
            delivery.setReceiverAddress(order.getReceiverAddress() == null ? "" : order.getReceiverAddress());
            delivery.setCreatedAt(LocalDateTime.now());
            deliveryOrderService.save(delivery);

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
        return created;
    }

    /** 按发货仓分组（split 与 preview 共用同一套归属口径，保证「预览即所得」） */
    private Map<Long, List<OrderItem>> groupByWarehouse(List<OrderItem> items) {
        Map<Long, List<OrderItem>> byWarehouse = new LinkedHashMap<>();
        for (OrderItem item : items) {
            Long warehouseId = resolveWarehouse(item.getProductSkuId());
            byWarehouse.computeIfAbsent(warehouseId, k -> new ArrayList<>()).add(item);
        }
        return byWarehouse;
    }

    /** SKU 的发货仓：取库存最多的仓；该 SKU 无分仓记录时退回库存最多/任一个仓并保持稳定排序 */
    private Long resolveWarehouse(Long skuId) {
        List<Inventory> rows = inventoryService.list(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (rows.isEmpty()) throw new IllegalStateException("SKU " + skuId + " 没有分仓库存记录，无法确定发货仓");
        return rows.stream()
                .max(Comparator.comparingInt(r -> r.getStock() == null ? 0 : r.getStock()))
                .map(Inventory::getWarehouseId)
                .orElseThrow(() -> new IllegalStateException("SKU " + skuId + " 发货仓解析失败"));
    }
}
