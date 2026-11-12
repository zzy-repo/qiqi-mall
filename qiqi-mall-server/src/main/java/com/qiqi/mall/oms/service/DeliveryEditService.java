package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.oms.dto.DeliveryItemQty;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.shared.common.DeliverySnGenerator;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.security.ConfirmCodeService;
import com.qiqi.mall.shared.security.ConfirmPreview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待下发包裹的人工干预（docs/flows/06-拆单履约：发货单状态图 / 拆单时序图 / ER-拆单合单）。
 *
 * 统一守卫：**只有 delivery_order.status = 0 待下发 的包裹可以被改**；已下发及之后的状态一律拒绝，
 * 并返回明确错误（不静默降级）。
 *
 * 统一记账：任何一次人工改动，把被改的发货单（含拆分出来的新包、被合并到的目标包）的
 * source 置 1（人工调整），并重算父订单的 split_status。
 *
 * 四个动作：
 *   update 改仓 / 改量（部分拆量）
 *   split  拆成两个待下发包裹
 *   merge  与同一订单的另一个待下发包裹合并
 *   delete 删除待下发包裹（还有明细时必须指定并入目标，等同合并到别处）
 */
@Service
public class DeliveryEditService {

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final OrderItemService orderItemService;
    private final OrderService orderService;
    private final WarehouseService warehouseService;

    public DeliveryEditService(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                               OrderItemService orderItemService, OrderService orderService,
                               WarehouseService warehouseService) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.orderItemService = orderItemService;
        this.orderService = orderService;
        this.warehouseService = warehouseService;
    }

    // ------------------------------------------------------------------
    // 1. 改仓 / 改量
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long deliveryId, Long warehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder delivery = requirePending(deliveryId, "编辑");
        if (warehouseId != null) {
            if (warehouseService.getById(warehouseId) == null) {
                throw new IllegalStateException("仓库不存在：" + warehouseId);
            }
            delivery.setWarehouseId(warehouseId);
        }
        if (items != null && !items.isEmpty()) {
            for (DeliveryItemQty row : items) {
                DeliveryItem item = requireItem(row.getDeliveryItemId(), deliveryId);
                int qty = requirePositive(row.getQuantity());
                item.setQuantity(qty);
                deliveryItemService.updateById(item);
            }
            assertQuantityNotExceeded(delivery.getOrderId());
        }
        markManual(delivery);
        return summary(delivery.getOrderId());
    }

    // ------------------------------------------------------------------
    // 2. 拆分：从原包裹按数量拆出一部分，生成新的待下发包裹
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> split(Long deliveryId, Long newWarehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder source = requirePending(deliveryId, "拆分");
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("请指定要拆出的明细与数量");
        }
        Long targetWarehouseId = newWarehouseId == null ? source.getWarehouseId() : newWarehouseId;
        if (warehouseService.getById(targetWarehouseId) == null) {
            throw new IllegalStateException("仓库不存在：" + targetWarehouseId);
        }

        DeliveryOrder created = new DeliveryOrder();
        created.setDeliverySn(DeliverySnGenerator.next());
        created.setOrderId(source.getOrderId());
        created.setWarehouseId(targetWarehouseId);
        created.setStatus(DeliveryOrder.PENDING);
        created.setSource(DeliveryOrder.SOURCE_MANUAL);       // 拆出来的包是人拆的
        created.setReceiverName(source.getReceiverName());
        created.setReceiverPhone(source.getReceiverPhone());
        created.setReceiverAddress(source.getReceiverAddress());
        created.setCreatedAt(LocalDateTime.now());
        deliveryOrderService.save(created);

        for (DeliveryItemQty row : items) {
            DeliveryItem item = requireItem(row.getDeliveryItemId(), deliveryId);
            int moveQty = requirePositive(row.getQuantity());
            if (moveQty > item.getQuantity()) {
                throw new IllegalStateException("拆出数量 " + moveQty + " 超过该明细现有数量 " + item.getQuantity());
            }
            DeliveryItem newItem = new DeliveryItem();
            newItem.setDeliveryId(created.getId());
            newItem.setOrderItemId(item.getOrderItemId());
            newItem.setProductSkuId(item.getProductSkuId());
            newItem.setQuantity(moveQty);
            deliveryItemService.save(newItem);

            int left = item.getQuantity() - moveQty;
            if (left > 0) {
                item.setQuantity(left);
                deliveryItemService.updateById(item);
            } else {
                deliveryItemService.removeById(item.getId());      // 整条拆走
            }
        }

        assertQuantityNotExceeded(source.getOrderId());
        markManual(source);                                        // 源包 source=1
        if (itemsOf(source.getId()).isEmpty()) {                   // 全拆走 → 源包已空，删掉
            deliveryOrderService.removeById(source.getId());
        }
        return summary(source.getOrderId());
    }

    // ------------------------------------------------------------------
    // 3. 合并：把当前包裹并入同订单的另一个待下发包裹
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> merge(Long deliveryId, Long targetDeliveryId) {
        DeliveryOrder source = requirePending(deliveryId, "合并");
        if (targetDeliveryId == null) {
            throw new IllegalStateException("请指定要并入的目标包裹");
        }
        if (targetDeliveryId.equals(deliveryId)) {
            throw new IllegalStateException("不能与自己合并");
        }
        DeliveryOrder target = requirePending(targetDeliveryId, "合并");
        if (!source.getOrderId().equals(target.getOrderId())) {
            throw new IllegalStateException("只能与同一订单下的包裹合并");
        }

        for (DeliveryItem item : itemsOf(source.getId())) {
            DeliveryItem exist = findItem(target.getId(), item.getOrderItemId());
            if (exist != null) {                                   // 同一订单明细 → 数量相加
                exist.setQuantity(exist.getQuantity() + item.getQuantity());
                deliveryItemService.updateById(exist);
                deliveryItemService.removeById(item.getId());
            } else {                                               // 直接搬过去
                item.setDeliveryId(target.getId());
                deliveryItemService.updateById(item);
            }
        }
        deliveryOrderService.removeById(source.getId());
        markManual(target);
        return summary(target.getOrderId());
    }

    // ------------------------------------------------------------------
    // 4. 删除：还有明细时必须指定并入目标（等同合并到别处）
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delete(Long deliveryId, Long targetDeliveryId) {
        DeliveryOrder delivery = requirePending(deliveryId, "删除");
        List<DeliveryItem> items = itemsOf(deliveryId);
        if (!items.isEmpty()) {
            if (targetDeliveryId == null) {
                throw new IllegalStateException("该包裹还有 " + items.size()
                        + " 条明细，删除前请指定并入的目标包裹 targetId（等同于合并到别处）");
            }
            return merge(deliveryId, targetDeliveryId);
        }
        deliveryOrderService.removeById(deliveryId);
        recomputeSplitStatus(delivery.getOrderId());
        return summary(delivery.getOrderId());
    }

    // ------------------------------------------------------------------
    // 确认码：预演 + 指纹
    // ------------------------------------------------------------------

    /*
     * previewXxx() 给人看的 willDo + 指纹组成，跑业务守卫（签出来的码一定能执行）；
     * extrasOfXxx() 执行阶段当场重算指纹，不跑守卫（守卫排在确认码校验之后，见 ConfirmCodeService#verify）。
     *
     * 指纹口径：包裹 id 由 ConfirmCodeService 的 targetId 承担，extras 只放「会被别人改动、且决定执行结果」的量。
     */

    /** 预演「改仓/改量」：指纹 = 改后的仓库 id + 每条明细改后的件数 */
    public ConfirmPreview previewUpdate(Long deliveryId, Long warehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder delivery = requirePending(deliveryId, "编辑");
        if (warehouseId != null && warehouseService.getById(warehouseId) == null) {
            throw new IllegalStateException("仓库不存在：" + warehouseId);
        }
        List<String> changes = requestLines(items);
        Long finalWarehouseId = warehouseId == null ? delivery.getWarehouseId() : warehouseId;
        int lines = itemsOf(deliveryId).size();

        String willDo = "将把包裹 " + delivery.getDeliverySn() + "（" + warehouseName(delivery.getWarehouseId()) + "，"
                + lines + " 条明细）"
                + (warehouseId == null ? "的发货仓保持不变" : "的发货仓改为 " + warehouseName(finalWarehouseId))
                + (changes.isEmpty() ? "，本次不改明细数量"
                        : "，并把 " + changes.size() + " 条明细改为（" + String.join("、", changes) + "）")
                + "；该包裹会被标记为人工调整，订单拆单标记重算。";
        return new ConfirmPreview(willDo, updateExtras(delivery, warehouseId, changes));
    }

    /** 改仓/改量指纹（执行阶段重算；不跑守卫） */
    public Object[] extrasOfUpdate(Long deliveryId, Long warehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder delivery = deliveryOrderService.getById(deliveryId);
        return updateExtras(delivery, warehouseId, requestLines(items));
    }

    private Object[] updateExtras(DeliveryOrder delivery, Long warehouseId, List<String> changes) {
        Long finalWarehouseId = warehouseId != null ? warehouseId
                : (delivery == null ? null : delivery.getWarehouseId());
        return new Object[]{finalWarehouseId, changes};
    }

    /** 预演「拆分」：指纹 = 新包仓库 id + 拆出的明细 + 源包剩下的明细 */
    public ConfirmPreview previewSplit(Long deliveryId, Long newWarehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder source = requirePending(deliveryId, "拆分");
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("请指定要拆出的明细与数量");
        }
        Long targetWarehouseId = newWarehouseId == null ? source.getWarehouseId() : newWarehouseId;
        if (warehouseService.getById(targetWarehouseId) == null) {
            throw new IllegalStateException("仓库不存在：" + targetWarehouseId);
        }
        List<String> moves = requestLines(items);
        for (DeliveryItemQty row : items) {
            DeliveryItem item = requireItem(row.getDeliveryItemId(), deliveryId);
            int moveQty = requirePositive(row.getQuantity());
            if (moveQty > item.getQuantity()) {
                throw new IllegalStateException("拆出数量 " + moveQty + " 超过该明细现有数量 " + item.getQuantity());
            }
        }
        List<String> remaining = remainingLines(itemsOf(deliveryId), items);
        String willDo = "将把包裹 " + source.getDeliverySn() + "（" + warehouseName(source.getWarehouseId())
                + "）拆成 2 个包裹：新包发货仓 " + warehouseName(targetWarehouseId) + "，承担 "
                + String.join("、", moves) + "；原包保留 " + remaining.size() + " 条明细"
                + (remaining.isEmpty() ? "（原包会变空并被自动删除，实际只剩 1 个包裹）" : "")
                + "。两个包都会被标记为人工调整。";
        return new ConfirmPreview(willDo, splitExtras(source, targetWarehouseId, moves, remaining));
    }

    /** 拆分指纹（执行阶段重算；不跑守卫） */
    public Object[] extrasOfSplit(Long deliveryId, Long newWarehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder source = deliveryOrderService.getById(deliveryId);
        List<String> moves = requestLines(items);
        if (source == null) return new Object[]{newWarehouseId, moves, List.of()};
        Long targetWarehouseId = newWarehouseId == null ? source.getWarehouseId() : newWarehouseId;
        return splitExtras(source, targetWarehouseId, moves, remainingLines(itemsOf(deliveryId), items));
    }

    private Object[] splitExtras(DeliveryOrder source, Long targetWarehouseId, List<String> moves, List<String> remaining) {
        return new Object[]{targetWarehouseId, moves, remaining};
    }

    /** 预演「合并」：指纹 = 并入目标 id + 源包明细 + 目标包明细 */
    public ConfirmPreview previewMerge(Long deliveryId, Long targetDeliveryId) {
        DeliveryOrder source = requirePending(deliveryId, "合并");
        if (targetDeliveryId == null) throw new IllegalStateException("请指定要并入的目标包裹");
        if (targetDeliveryId.equals(deliveryId)) throw new IllegalStateException("不能与自己合并");
        DeliveryOrder target = requirePending(targetDeliveryId, "合并");
        if (!source.getOrderId().equals(target.getOrderId())) {
            throw new IllegalStateException("只能与同一订单下的包裹合并");
        }
        List<String> sourceLines = itemLines(deliveryId);
        List<String> targetLines = itemLines(targetDeliveryId);
        String willDo = "将把包裹 " + source.getDeliverySn() + "（" + warehouseName(source.getWarehouseId()) + "，"
                + sourceLines.size() + " 条明细）并入 " + target.getDeliverySn() + "（"
                + warehouseName(target.getWarehouseId()) + "，" + targetLines.size()
                + " 条明细）：源包消失，目标包接收这些明细并标记为人工调整。";
        return new ConfirmPreview(willDo, mergeExtras(targetDeliveryId, sourceLines, targetLines));
    }

    /** 合并指纹（执行阶段重算；不跑守卫） */
    public Object[] extrasOfMerge(Long deliveryId, Long targetDeliveryId) {
        return mergeExtras(targetDeliveryId, itemLines(deliveryId), itemLines(targetDeliveryId));
    }

    private Object[] mergeExtras(Long targetDeliveryId, List<String> sourceLines, List<String> targetLines) {
        return new Object[]{targetDeliveryId, sourceLines, targetLines};
    }

    /** 预演「删除」（有明细时必须指定并入目标，等同合并） */
    public ConfirmPreview previewDelete(Long deliveryId, Long targetDeliveryId) {
        DeliveryOrder delivery = requirePending(deliveryId, "删除");
        List<String> items = itemLines(deliveryId);
        if (items.isEmpty()) {
            String willDo = "将删除空包裹 " + delivery.getDeliverySn() + "（" + warehouseName(delivery.getWarehouseId())
                    + "，没有明细）；订单的拆单标记会重新计算。";
            return new ConfirmPreview(willDo, deleteExtras(null, items));
        }
        if (targetDeliveryId == null) {
            throw new IllegalStateException("该包裹还有 " + items.size()
                    + " 条明细，删除前请指定并入的目标包裹 targetId（等同于合并到别处）");
        }
        DeliveryOrder target = requirePending(targetDeliveryId, "删除");
        if (!delivery.getOrderId().equals(target.getOrderId())) {
            throw new IllegalStateException("只能与同一订单下的包裹合并");
        }
        String willDo = "包裹 " + delivery.getDeliverySn() + " 还有 " + items.size() + " 条明细，删除等同于并入 "
                + target.getDeliverySn() + "（" + warehouseName(target.getWarehouseId())
                + "）：源包消失，这些明细转到目标包，源包标记为人工调整。";
        return new ConfirmPreview(willDo, deleteExtras(targetDeliveryId, items));
    }

    /** 删除指纹（执行阶段重算；不跑守卫） */
    public Object[] extrasOfDelete(Long deliveryId, Long targetDeliveryId) {
        return deleteExtras(targetDeliveryId, itemLines(deliveryId));
    }

    private Object[] deleteExtras(Long targetDeliveryId, List<String> itemLines) {
        return new Object[]{targetDeliveryId, itemLines};
    }

    /** 请求里的改动摘要：{@code ["deliveryItemId:qty", ...]} 排序 —— 与 DB 现状无关，纯请求指纹 */
    private static List<String> requestLines(List<DeliveryItemQty> items) {
        List<String> lines = new ArrayList<>();
        if (items != null) {
            for (DeliveryItemQty row : items) {
                lines.add(row.getDeliveryItemId() + ":" + row.getQuantity());
            }
        }
        return ConfirmCodeService.sortedLines(lines);
    }

    /** 明细摘要：{@code ["orderItemId:skuId:qty", ...]} 排序 —— 包裹「现在到底装了什么」 */
    private List<String> itemLines(Long deliveryId) {
        List<String> lines = new ArrayList<>();
        for (DeliveryItem item : itemsOf(deliveryId)) {
            lines.add(item.getOrderItemId() + ":" + item.getProductSkuId() + ":" + item.getQuantity());
        }
        return ConfirmCodeService.sortedLines(lines);
    }

    /** 拆分后源包剩下的明细（按请求扣减后的结果，不落库） */
    private static List<String> remainingLines(List<DeliveryItem> current, List<DeliveryItemQty> moved) {
        Map<Long, Integer> movedById = new HashMap<>();
        if (moved != null) {
            for (DeliveryItemQty row : moved) {
                int qty = row.getQuantity() == null ? 0 : row.getQuantity();
                movedById.merge(row.getDeliveryItemId(), qty, Integer::sum);
            }
        }
        List<String> lines = new ArrayList<>();
        for (DeliveryItem item : current) {
            int left = (item.getQuantity() == null ? 0 : item.getQuantity())
                    - movedById.getOrDefault(item.getId(), 0);
            if (left > 0) lines.add(item.getOrderItemId() + ":" + item.getProductSkuId() + ":" + left);
        }
        return ConfirmCodeService.sortedLines(lines);
    }

    private String warehouseName(Long warehouseId) {
        Warehouse wh = warehouseId == null ? null : warehouseService.getById(warehouseId);
        return wh == null ? ("仓 " + warehouseId) : wh.getName();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 统一守卫：只有待下发可以改 */
    private DeliveryOrder requirePending(Long deliveryId, String action) {
        DeliveryOrder delivery = deliveryOrderService.getById(deliveryId);
        if (delivery == null) throw new IllegalStateException("发货单不存在：" + deliveryId);
        if (delivery.getStatus() == null || delivery.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发包裹可以" + action
                    + "（当前状态 " + delivery.getStatus() + "，已下发的只能取消）");
        }
        return delivery;
    }

    private DeliveryItem requireItem(Long deliveryItemId, Long deliveryId) {
        DeliveryItem item = deliveryItemId == null ? null : deliveryItemService.getById(deliveryItemId);
        if (item == null) throw new IllegalStateException("发货单明细不存在：" + deliveryItemId);
        if (!deliveryId.equals(item.getDeliveryId())) {
            throw new IllegalStateException("明细 " + deliveryItemId + " 不属于发货单 " + deliveryId);
        }
        return item;
    }

    private int requirePositive(Integer quantity) {
        if (quantity == null || quantity <= 0) throw new IllegalStateException("数量必须大于 0");
        return quantity;
    }

    /**
     * 人工改动记账：source=1 + 重算 split_status。
     *
     * <p>这里落库的是 {@code UPDATE delivery_order SET ... , version = version + 1
     * WHERE id = ? AND version = ?}（实体带 @Version）。影响行数 0 说明「本事务读出来之后
     * 又有别人改了这张包裹」—— 直接抛乐观锁冲突（code=409），而不是把别人的改动盖掉：
     * 事务回滚，明细改动一并撤销，前端刷新后重试即可。
     */
    private void markManual(DeliveryOrder delivery) {
        delivery.setSource(DeliveryOrder.SOURCE_MANUAL);
        int rows = deliveryOrderService.getBaseMapper().updateById(delivery);
        OptimisticLockGuard.requireAffected(rows);
        recomputeSplitStatus(delivery.getOrderId());
    }

    /** 订单拆单标记：还有未取消的子单 → 已拆(1)，否则未拆(0) */
    private void recomputeSplitStatus(Long orderId) {
        Order order = orderService.getById(orderId);
        if (order == null) return;
        long active = deliveryOrderService.count(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId)
                .ne(DeliveryOrder::getStatus, DeliveryOrder.CANCELLED));
        order.setSplitStatus(active > 0 ? 1 : 0);
        orderService.updateById(order);
    }

    /** 拆量不能超过下单数量（按 order_item 汇总所有未取消包裹） */
    private void assertQuantityNotExceeded(Long orderId) {
        Map<Long, Integer> used = new HashMap<>();
        List<DeliveryOrder> deliveries = deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId)
                .ne(DeliveryOrder::getStatus, DeliveryOrder.CANCELLED));
        for (DeliveryOrder d : deliveries) {
            for (DeliveryItem item : itemsOf(d.getId())) {
                used.merge(item.getOrderItemId(), item.getQuantity(), Integer::sum);
            }
        }
        for (OrderItem orderItem : orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId))) {
            int sum = used.getOrDefault(orderItem.getId(), 0);
            if (sum > orderItem.getProductQuantity()) {
                throw new IllegalStateException("订单明细 " + orderItem.getId() + " 的拆量合计 " + sum
                        + " 超过下单数量 " + orderItem.getProductQuantity());
            }
        }
    }

    private List<DeliveryItem> itemsOf(Long deliveryId) {
        return deliveryItemService.list(new LambdaQueryWrapper<DeliveryItem>()
                .eq(DeliveryItem::getDeliveryId, deliveryId).orderByAsc(DeliveryItem::getId));
    }

    private DeliveryItem findItem(Long deliveryId, Long orderItemId) {
        return deliveryItemService.getOne(new LambdaQueryWrapper<DeliveryItem>()
                .eq(DeliveryItem::getDeliveryId, deliveryId)
                .eq(DeliveryItem::getOrderItemId, orderItemId).last("limit 1"), false);
    }

    /** 编辑后的包裹结构（前端拿到即可整体刷新抽屉） */
    private Map<String, Object> summary(Long orderId) {
        List<DeliveryOrder> deliveries = deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId).orderByAsc(DeliveryOrder::getId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeliveryOrder d : deliveries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("deliverySn", d.getDeliverySn());
            row.put("warehouseId", d.getWarehouseId());
            Warehouse wh = warehouseService.getById(d.getWarehouseId());
            row.put("warehouseName", wh == null ? "" : wh.getName());
            row.put("status", d.getStatus());
            row.put("source", d.getSource());
            row.put("version", d.getVersion());      // 编辑后的最新版本号，前端下次编辑要用
            row.put("items", itemsOf(d.getId()));
            list.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        Order order = orderService.getById(orderId);
        result.put("splitStatus", order == null ? null : order.getSplitStatus());
        result.put("deliveries", list);
        return result;
    }
}
