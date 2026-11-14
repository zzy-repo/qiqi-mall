package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.oms.dto.DeliveryItemQty;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
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

    private static final Logger log = LoggerFactory.getLogger(DeliveryEditService.class);

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final OrderItemService orderItemService;
    private final OrderService orderService;
    private final WarehouseService warehouseService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    /** 包裹状态写入的唯一入口（出生：人工拆出来的包裹 = 待下发(0)） */
    private final DeliveryStatusService deliveryStatusService;
    /** 后台操作审计（改仓/改量/拆包/合包/删包都是敏感动作） */
    private final AdminAuditWriter auditWriter;

    public DeliveryEditService(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                               OrderItemService orderItemService, OrderService orderService,
                               WarehouseService warehouseService,
                               DeliveryLogisticsService deliveryLogisticsService,
                               DeliveryStatusService deliveryStatusService,
                               AdminAuditWriter auditWriter) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.orderItemService = orderItemService;
        this.orderService = orderService;
        this.warehouseService = warehouseService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.deliveryStatusService = deliveryStatusService;
        this.auditWriter = auditWriter;
    }

    // ------------------------------------------------------------------
    // 1. 改仓 / 改量
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long deliveryId, Long warehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder delivery = requirePending(deliveryId, "编辑");
        Long warehouseBefore = delivery.getWarehouseId();          // 改之前的发货仓：审计的 before 要用旧值
        List<String> itemsBefore = itemLines(deliveryId);
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
                updateItem(item, "改量");
            }
            assertQuantityNotExceeded(delivery.getOrderId());
        }
        markManual(delivery);
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_DELIVERY_UPDATE, ReasonCodes.TARGET_PACKAGE, deliveryId, delivery.getDeliverySn(),
                "编辑待下发包裹 " + delivery.getDeliverySn() + "（改发货仓 / 改明细数量）",
                null, delivery.getOrderId(),
                AdminAuditWriter.snapshot("warehouseId", warehouseBefore, "items", itemsBefore),
                AdminAuditWriter.snapshot("warehouseId", delivery.getWarehouseId(), "items", itemLines(deliveryId))));
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
        // 出生：新包裹初始状态「待下发(0)」走唯一入口（save 之前定初值，不产生 UPDATE）
        deliveryStatusService.init(created, DeliveryOrder.PENDING);
        created.setSource(DeliveryOrder.SOURCE_MANUAL);       // 拆出来的包是人拆的
        created.setReceiverName(source.getReceiverName());
        created.setReceiverPhone(source.getReceiverPhone());
        created.setReceiverAddress(source.getReceiverAddress());
        created.setCreatedAt(LocalDateTime.now());
        deliveryOrderService.save(created);
        // 出生台账（from_status = NULL + delivery_sn 快照）：人工拆分出来的新包同样要有出生证据
        deliveryStatusService.recordBirth(created, DeliveryStatusService.REASON_CREATE, "人工拆分");

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
                updateItem(item, "拆分");
            } else {
                deliveryItemService.removeById(item.getId());      // 整条拆走
            }
        }

        assertQuantityNotExceeded(source.getOrderId());
        markManual(source);                                        // 源包 source=1
        if (itemsOf(source.getId()).isEmpty()) {                   // 全拆走 → 源包已空，删掉
            deliveryOrderService.removeById(source.getId());
        }
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_DELIVERY_SPLIT, ReasonCodes.TARGET_PACKAGE, source.getId(), source.getDeliverySn(),
                "拆分包裹 " + source.getDeliverySn() + " → 新包 " + created.getDeliverySn()
                        + "（" + requestLines(items).size() + " 条明细）",
                null, source.getOrderId(),
                AdminAuditWriter.snapshot("deliverySn", source.getDeliverySn()),
                AdminAuditWriter.snapshot("newDeliverySn", created.getDeliverySn(),
                        "newDeliveryId", created.getId(), "warehouseId", created.getWarehouseId())));
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
                updateItem(exist, "合并");
                deliveryItemService.removeById(item.getId());
            } else {                                               // 直接搬过去
                item.setDeliveryId(target.getId());
                updateItem(item, "合并");
            }
        }
        deliveryOrderService.removeById(source.getId());
        markManual(target);
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_DELIVERY_MERGE, ReasonCodes.TARGET_PACKAGE, source.getId(), source.getDeliverySn(),
                "合并包裹 " + source.getDeliverySn() + " 并入 " + target.getDeliverySn() + "（源包消失）",
                null, target.getOrderId(),
                AdminAuditWriter.snapshot("deliverySn", source.getDeliverySn(), "targetSn", target.getDeliverySn()),
                AdminAuditWriter.snapshot("targetSn", target.getDeliverySn(), "targetId", target.getId())));
        return summary(target.getOrderId());
    }

    // ------------------------------------------------------------------
    // 4. 删除：还有明细时必须指定并入目标（等同合并到别处）
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delete(Long deliveryId, Long targetDeliveryId) {
        // 依赖检查排在状态守卫之前：有物流记录时真正的原因不是「状态不对」，而是
        // 「运单已经产生、删了就对不上账」—— 必须说准，别让人以为改状态就能删。
        DeliveryOrder current = deliveryOrderService.getById(deliveryId);
        if (current == null) throw new IllegalStateException("发货单不存在：" + deliveryId);
        String blockReason = blockReasonOfLogistics(current);
        if (blockReason != null) throw new IllegalStateException(blockReason);
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
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_DELIVERY_DELETE, ReasonCodes.TARGET_PACKAGE, deliveryId, delivery.getDeliverySn(),
                "删除空包裹 " + delivery.getDeliverySn() + "（没有明细，删后不可恢复）",
                null, delivery.getOrderId(),
                AdminAuditWriter.snapshot("deliverySn", delivery.getDeliverySn(),
                        "status", delivery.getStatus()),
                AdminAuditWriter.snapshot("deleted", Boolean.TRUE)));
        return summary(delivery.getOrderId());
    }

    // ------------------------------------------------------------------
    // 预演（dryRun）：只说「将要发生什么」
    // ------------------------------------------------------------------

    /*
     * previewXxx() 返回给人看的中文描述（带真实单号/仓名/件数），**跑业务守卫** ——
     * 已下发的包裹、不存在的目标包，预演阶段就说清原因，不给出「将要发生什么」。
     * 预演不落库、不签发凭证；「期间被别人改过」由 version 乐观锁在 controller 层拦（409）。
     */

    /** 预演「改仓/改量」：守卫同 {@link #update} */
    public String previewUpdate(Long deliveryId, Long warehouseId, List<DeliveryItemQty> items) {
        DeliveryOrder delivery = requirePending(deliveryId, "编辑");
        if (warehouseId != null && warehouseService.getById(warehouseId) == null) {
            throw new IllegalStateException("仓库不存在：" + warehouseId);
        }
        List<String> changes = requestLines(items);
        Long finalWarehouseId = warehouseId == null ? delivery.getWarehouseId() : warehouseId;
        int lines = itemsOf(deliveryId).size();

        return "将把包裹 " + delivery.getDeliverySn() + "（" + warehouseName(delivery.getWarehouseId()) + "，"
                + lines + " 条明细）"
                + (warehouseId == null ? "的发货仓保持不变" : "的发货仓改为 " + warehouseName(finalWarehouseId))
                + (changes.isEmpty() ? "，本次不改明细数量"
                        : "，并把 " + changes.size() + " 条明细改为（" + String.join("、", changes) + "）")
                + "；该包裹会被标记为人工调整，订单拆单标记重算。";
    }

    /** 预演「拆分」：守卫同 {@link #split} */
    public String previewSplit(Long deliveryId, Long newWarehouseId, List<DeliveryItemQty> items) {
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
        return "将把包裹 " + source.getDeliverySn() + "（" + warehouseName(source.getWarehouseId())
                + "）拆成 2 个包裹：新包发货仓 " + warehouseName(targetWarehouseId) + "，承担 "
                + String.join("、", moves) + "；原包保留 " + remaining.size() + " 条明细"
                + (remaining.isEmpty() ? "（原包会变空并被自动删除，实际只剩 1 个包裹）" : "")
                + "。两个包都会被标记为人工调整。";
    }

    /** 预演「合并」：守卫同 {@link #merge} */
    public String previewMerge(Long deliveryId, Long targetDeliveryId) {
        DeliveryOrder source = requirePending(deliveryId, "合并");
        if (targetDeliveryId == null) throw new IllegalStateException("请指定要并入的目标包裹");
        if (targetDeliveryId.equals(deliveryId)) throw new IllegalStateException("不能与自己合并");
        DeliveryOrder target = requirePending(targetDeliveryId, "合并");
        if (!source.getOrderId().equals(target.getOrderId())) {
            throw new IllegalStateException("只能与同一订单下的包裹合并");
        }
        List<String> sourceLines = itemLines(deliveryId);
        List<String> targetLines = itemLines(targetDeliveryId);
        return "将把包裹 " + source.getDeliverySn() + "（" + warehouseName(source.getWarehouseId()) + "，"
                + sourceLines.size() + " 条明细）并入 " + target.getDeliverySn() + "（"
                + warehouseName(target.getWarehouseId()) + "，" + targetLines.size()
                + " 条明细）：源包消失，目标包接收这些明细并标记为人工调整。";
    }

    /** 预演「删除」（有明细时必须指定并入目标，等同合并） */
    public String previewDelete(Long deliveryId, Long targetDeliveryId) {
        DeliveryOrder current = deliveryOrderService.getById(deliveryId);
        if (current == null) throw new IllegalStateException("发货单不存在：" + deliveryId);
        String blockReason = blockReasonOfLogistics(current);
        if (blockReason != null) throw new IllegalStateException(blockReason);
        DeliveryOrder delivery = requirePending(deliveryId, "删除");
        List<String> items = itemLines(deliveryId);
        if (items.isEmpty()) {
            return "将删除空包裹 " + delivery.getDeliverySn() + "（" + warehouseName(delivery.getWarehouseId())
                    + "，没有明细）；订单的拆单标记会重新计算。";
        }
        if (targetDeliveryId == null) {
            throw new IllegalStateException("该包裹还有 " + items.size()
                    + " 条明细，删除前请指定并入的目标包裹 targetId（等同于合并到别处）");
        }
        DeliveryOrder target = requirePending(targetDeliveryId, "删除");
        if (!delivery.getOrderId().equals(target.getOrderId())) {
            throw new IllegalStateException("只能与同一订单下的包裹合并");
        }
        return "包裹 " + delivery.getDeliverySn() + " 还有 " + items.size() + " 条明细，删除等同于并入 "
                + target.getDeliverySn() + "（" + warehouseName(target.getWarehouseId())
                + "）：源包消失，这些明细转到目标包，源包标记为人工调整。";
    }

    /** 请求里的改动摘要：{@code ["deliveryItemId:qty", ...]} 排序 —— 纯请求内容，与 DB 现状无关 */
    private static List<String> requestLines(List<DeliveryItemQty> items) {
        List<String> lines = new ArrayList<>();
        if (items != null) {
            for (DeliveryItemQty row : items) {
                lines.add(row.getDeliveryItemId() + ":" + row.getQuantity());
            }
        }
        lines.sort(Comparator.naturalOrder());
        return lines;
    }

    /** 明细摘要：{@code ["orderItemId:skuId:qty", ...]} 排序 —— 包裹「现在到底装了什么」 */
    private List<String> itemLines(Long deliveryId) {
        List<String> lines = new ArrayList<>();
        for (DeliveryItem item : itemsOf(deliveryId)) {
            lines.add(item.getOrderItemId() + ":" + item.getProductSkuId() + ":" + item.getQuantity());
        }
        lines.sort(Comparator.naturalOrder());
        return lines;
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
        lines.sort(Comparator.naturalOrder());
        return lines;
    }

    private String warehouseName(Long warehouseId) {
        Warehouse wh = warehouseId == null ? null : warehouseService.getById(warehouseId);
        return wh == null ? ("仓 " + warehouseId) : wh.getName();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 发货单上的物流记录检查：有运单就不能删（{@code delivery_logistics.delivery_id} 对
     * {@code delivery_order} 是 NO ACTION，DB 层只会回一句「数据处理失败」）。
     *
     * <p>返回 {@code null} 表示没有物流记录、可以继续走状态守卫；否则返回中文原因（含运单号），
     * 并给出「先作废物流 / 改为取消」的出路 —— 要么删干净，要么说清为什么不能删。
     */
    private String blockReasonOfLogistics(DeliveryOrder delivery) {
        List<DeliveryLogistics> logistics = deliveryLogisticsService.list(
                new LambdaQueryWrapper<DeliveryLogistics>()
                        .eq(DeliveryLogistics::getDeliveryId, delivery.getId())
                        .orderByAsc(DeliveryLogistics::getId));
        if (logistics.isEmpty()) return null;
        List<String> tracks = new ArrayList<>();
        for (DeliveryLogistics row : logistics) {
            tracks.add(row.getTrackNo() == null ? ("物流#" + row.getId()) : row.getTrackNo());
        }
        return "该发货单 " + delivery.getDeliverySn() + "（id=" + delivery.getId() + "）有 "
                + logistics.size() + " 条物流记录（运单号 " + String.join("、", tracks)
                + "），无法删除；请先在履约页作废物流，或改为取消发货单。";
    }

    /** 统一守卫：只有待下发可以改 */
    private DeliveryOrder requirePending(Long deliveryId, String action) {
        DeliveryOrder delivery = deliveryOrderService.getById(deliveryId);
        if (delivery == null) throw new IllegalStateException("发货单不存在：" + deliveryId);
        if (delivery.getStatus() == null || delivery.getStatus() != DeliveryOrder.PENDING) {
            // 提示必须与入口一致：已下发状态下页面上编辑/拆分/合并/删除/取消五个按钮都不渲染
            // （发货单调整.spec.js 第 ⑨ 步逐个数 count=0），只剩「模拟 WMS 回传」；
            // 取消也只对 status=0 放行（DeliveryStatusService 的 0 → 9）。旧文案「已下发的只能取消」
            // 是在教人去点一个不存在的按钮，已按现状改掉。
            throw new IllegalStateException("只有待下发包裹可以" + action
                    + "（当前状态 " + delivery.getStatus() + "；已下发的包裹不能" + action
                    + "，取消入口只在待下发时可用，已下发后的状态只能由 WMS 回传推进）");
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
        int rows = orderService.getBaseMapper().updateById(order);
        OptimisticLockGuard.requireAffected(rows);
    }

    /**
     * 写回一条发货明细：**先看影响行数再决定要不要继续**。
     *
     * <p>{@code delivery_item} 是子表、**没有 version 列**（5 张带 version 的热表是
     * order / delivery_order / product / sku / member），所以这里的 UPDATE 不带
     * {@code AND version = ?}：影响行数 0 不可能是「版本冲突」，**只可能是这条明细被别人删了**
     * （另一个管理员同时在拆/合同一个包裹）。继续往下跑等于往一条不存在的明细上记账，
     * 所以同样 fail-closed，只是文案说准原因：「{@value OptimisticLockGuard#MSG_DELIVERY_ITEM_DELETED}」。
     *
     * @param item   要落库的明细（已改好 quantity / deliveryId）
     * @param action 当前动作（写进日志便于排查是哪一步撞上的）
     */
    private void updateItem(DeliveryItem item, String action) {
        int rows = deliveryItemService.getBaseMapper().updateById(item);
        if (rows == 0) {
            log.warn("发货明细 {} 在{}时已不存在（被并发删除），拒绝继续", item.getId(), action);
        }
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_DELIVERY_ITEM_DELETED);
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
