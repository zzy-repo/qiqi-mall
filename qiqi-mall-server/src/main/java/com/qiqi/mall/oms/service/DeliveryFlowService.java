package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.oms.wms.MockWmsService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.ledger.WmsExchangeLedgerWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DeliveryFlowService.class);

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    private final OrderService orderService;
    private final SkuService skuService;
    private final MockWmsService wms;
    private final WarehouseService warehouseService;
    /** 包裹状态写入的唯一入口（P2 第一步收敛）；父订单状态写入走 {@link OrderStatusService} */
    private final DeliveryStatusService deliveryStatusService;
    private final OrderStatusService orderStatusService;
    /** WMS 下发/回传台账（write-ahead 两阶段：REQ → 外呼 → ACK） */
    private final WmsExchangeLedgerWriter wmsLedger;
    /** 后台操作审计（下发是敏感动作：外呼不可撤回） */
    private final AdminAuditWriter auditWriter;

    public DeliveryFlowService(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                               DeliveryLogisticsService deliveryLogisticsService, OrderService orderService,
                               SkuService skuService, MockWmsService wms, WarehouseService warehouseService,
                               DeliveryStatusService deliveryStatusService, OrderStatusService orderStatusService,
                               WmsExchangeLedgerWriter wmsLedger, AdminAuditWriter auditWriter) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.orderService = orderService;
        this.skuService = skuService;
        this.wms = wms;
        this.warehouseService = warehouseService;
        this.deliveryStatusService = deliveryStatusService;
        this.orderStatusService = orderStatusService;
        this.wmsLedger = wmsLedger;
        this.auditWriter = auditWriter;
    }

    /**
     * 下发：守卫 仅待下发。
     * 事务：状态落库 + WMS 调用必须在同一个事务里 —— 否则状态改了、外呼失败就会留下「已下发但 WMS 不知道」的包裹。
     *
     * <p>**顺序：先落库（验影响行数）再外呼**。0 行 = 读到「待下发」之后别人改过这张包裹
     * （另一个实例已经下发 / 有人把它取消了）→ 抛 409 回滚，而且**这次一次外呼都没发出去**。
     * 反过来的顺序（先外呼再落库）在冲突回滚时 WMS 已经收到货单，那才是真的不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeliveryOrder dispatch(Long deliveryId) {
        DeliveryOrder delivery = require(deliveryId);
        if (delivery.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发发货单可以下发");
        }
        // 状态写入统一走 DeliveryStatusService（同一条 UPDATE 里带 status_seq 自增与 version 自增）
        int rows = deliveryStatusService.change(delivery, DeliveryOrder.DISPATCHED,
                DeliveryStatusService.REASON_DISPATCH, null);
        OptimisticLockGuard.requireAffected(rows);

        // === WMS 台账（write-ahead 两阶段）===
        // 顺序不可换：① 先把「我准备下发什么」落成 REQ 行（此时还没有回执）
        //           ② 再外呼（副作用在库外，回滚救不回来）
        //           ③ 拿到回执后补 ACK 行（result / latency / payload）
        // 反过来（先外呼、成功才记账）在失败路径上会留下「WMS 收到了、我们库里没有任何记录」的黑洞。
        java.util.Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("deliverySn", delivery.getDeliverySn());
        req.put("orderId", delivery.getOrderId());
        req.put("warehouseId", delivery.getWarehouseId());
        int attemptNo = wmsLedger.requestSent(delivery, req);
        long startedAt = System.nanoTime();
        String ack = wms.dispatch(delivery.getDeliverySn());
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        wmsLedger.requestAcked(delivery, attemptNo, WmsExchangeLedgerWriter.RESULT_SUCCESS, latencyMs,
                java.util.Map.of("ack", ack));

        // 审计（敏感动作：下发后不可撤回，只能等 WMS 回传）
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_DELIVERY_DISPATCH, ReasonCodes.TARGET_PACKAGE, delivery.getId(),
                delivery.getDeliverySn(),
                "包裹 " + delivery.getDeliverySn() + " 下发给 WMS（订单 id=" + delivery.getOrderId()
                        + "，仓 id=" + delivery.getWarehouseId() + "，回执 " + ack + "）",
                null, delivery.getOrderId(),
                AdminAuditWriter.snapshot("status", DeliveryOrder.PENDING),
                AdminAuditWriter.snapshot("status", DeliveryOrder.DISPATCHED)));
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
        // fail-closed：0 行 = 读到「待下发」之后别人改过 → 409 回滚，库存一次都不会被回补
        int rows = deliveryStatusService.change(delivery, DeliveryOrder.CANCELLED,
                DeliveryStatusService.REASON_CANCEL, null);
        OptimisticLockGuard.requireAffected(rows);

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

        // 幂等判断与守卫仍在各自 case 里（那是业务语义）；这里只算出「目标状态 + 事件原因」，
        // 落库统一走 switch 之后的那一条 UPDATE（与旧结构一致：一次回传只写一次状态）
        final int target;
        final String reason;
        switch (event == null ? "" : event.toLowerCase()) {
            case "pick" -> {
                if (current >= DeliveryOrder.PICKED) return delivery;           // 幂等
                requireState(delivery, DeliveryOrder.DISPATCHED, "只有已下发发货单可以回传拣货");
                target = DeliveryOrder.PICKED;
                reason = DeliveryStatusService.REASON_PICK;
            }
            case "outbound" -> {
                if (current >= DeliveryOrder.OUTBOUND) return delivery;         // 幂等
                if (current != DeliveryOrder.DISPATCHED && current != DeliveryOrder.PICKED) {
                    throw new IllegalStateException("只有已下发/已拣货发货单可以回传出库");
                }
                target = DeliveryOrder.OUTBOUND;
                reason = DeliveryStatusService.REASON_OUTBOUND;
                saveLogisticsIfAbsent(delivery);                                // 出库写运单号
            }
            case "transit" -> {
                if (current >= DeliveryOrder.IN_TRANSIT) return delivery;
                requireState(delivery, DeliveryOrder.OUTBOUND, "只有已出库发货单可以回传揽收");
                target = DeliveryOrder.IN_TRANSIT;
                reason = DeliveryStatusService.REASON_TRANSIT;
            }
            case "signed" -> {
                if (current >= DeliveryOrder.SIGNED) return delivery;
                if (current < DeliveryOrder.OUTBOUND) throw new IllegalStateException("只有已出库之后的发货单可以回传签收");
                target = DeliveryOrder.SIGNED;
                reason = DeliveryStatusService.REASON_SIGNED;
            }
            default -> throw new IllegalStateException("未知回传事件：" + event + "（支持 pick/outbound/transit/signed）");
        }
        // fail-closed：0 行 = 读到当前状态之后别人改过（同一事件重复回传的另一路并发）→ 409 回滚，
        // 状态机不会因为「后写的把先写的盖掉」而回退
        int rows = deliveryStatusService.change(delivery, target, reason, null);
        OptimisticLockGuard.requireAffected(rows);
        // WMS 台账：仓库回传也是一次「交换」，记一行 ACK（方向=ACK，尝试号按 ACK 方向自增，
        // 因此不会与「下发回执」撞幂等键）。业务幂等判断在上面已经挡掉了重复事件 —— 这里只记真实发生的那次。
        wmsLedger.callbackReceived(delivery, event, current, target);
        recomputeOrderStatus(delivery.getOrderId());
        return delivery;
    }

    // ------------------------------------------------------------------ 预演（dryRun）：只说「将要发生什么」

    /*
     * 每个写操作给一个 previewXxx()：返回给用户看的中文描述（带真实单号/仓名/件数）。
     * **跑业务守卫** —— 没资格执行的操作，预演阶段就说清原因，不给出「将要发生什么」再让人白点一次确认。
     * 预演不落库、不签发任何凭证；并发控制不在这里，靠 version 乐观锁与状态守卫（见各 controller）。
     */

    /** 预演「下发」：守卫同 {@link #dispatch} */
    public String previewDispatch(Long deliveryId) {
        DeliveryOrder d = require(deliveryId);
        if (d.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发发货单可以下发");
        }
        List<DeliveryItem> items = itemsOf(deliveryId);
        return "将把包裹 " + d.getDeliverySn() + "（" + warehouseName(d.getWarehouseId()) + "，"
                + items.size() + " 条明细合计 " + totalQty(items) + " 件）下发给 WMS：状态 待下发→已下发。"
                + "下发后不可撤回，只能等 WMS 回传。";
    }

    /** 预演「取消」：守卫同 {@link #cancel}；文案要点明回补哪些 SKU 多少件 */
    public String previewCancel(Long deliveryId) {
        DeliveryOrder d = require(deliveryId);
        if (d.getStatus() != DeliveryOrder.PENDING) {
            throw new IllegalStateException("只有待下发发货单可以取消");
        }
        List<DeliveryItem> items = itemsOf(deliveryId);
        List<String> restore = skuQtyLines(items);
        return "将取消包裹 " + d.getDeliverySn() + "（" + warehouseName(d.getWarehouseId())
                + "）：状态 待下发→已取消，并把 " + items.size() + " 条明细的 SKU 总池库存回补（"
                + describeSkuQty(restore) + "，合计 " + totalQty(items) + " 件）；父订单状态会重新聚合。";
    }

    /** 预演「WMS 回传」：守卫同 {@link #wmsCallback}（含幂等判断） */
    public String previewWmsCallback(Long deliveryId, String event) {
        DeliveryOrder d = require(deliveryId);
        int current = d.getStatus();
        if (current == DeliveryOrder.CANCELLED) throw new IllegalStateException("已取消的发货单不能回传");
        String name = event == null ? "" : event.toLowerCase();
        int target = targetStatusOf(name);
        if (current >= target) {
            return "包裹 " + d.getDeliverySn() + " 当前已是「" + statusText(current) + "」，回传 " + name
                    + " 不会改动任何数据（接口幂等），只会原样返回。";
        }
        // 与执行路径同一套状态守卫
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
        return "将记录 WMS 回传 " + name + "：包裹 " + d.getDeliverySn() + "（" + warehouseName(d.getWarehouseId())
                + "）状态 " + statusText(current) + "→" + statusText(target) + "；父订单会按所有子单重新聚合。";
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

    /** 包裹明细（按 id 升序，明细摘要的顺序才稳定） */
    private List<DeliveryItem> itemsOf(Long deliveryId) {
        return deliveryItemService.list(new LambdaQueryWrapper<DeliveryItem>()
                .eq(DeliveryItem::getDeliveryId, deliveryId).orderByAsc(DeliveryItem::getId));
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

    // ------------------------------------------------------------------ 买家确认收货：口径②（子单是事实源）

    /**
     * 该订单下**未取消**的包裹张数。
     *
     * <p>0 = 直发路径（单仓直发，订单由管理员「发货」直接从 1 已付款 置到 2 已发货，压根没有包裹行），
     * 此时「子单是事实源」这条口径没有事实可依：父单状态只能由买家确认收货这一步自己推进
     * （此时那一次写入是唯一写者，不存在两个写者抢同一个字段的问题）。
     */
    public int activeDeliveryCount(Long orderId) {
        return (int) deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                        .eq(DeliveryOrder::getOrderId, orderId)).stream()
                .filter(d -> d.getStatus() != DeliveryOrder.CANCELLED)
                .count();
    }

    /**
     * 口径②（用户拍板：**子单是事实源**）：买家确认收货时，把该订单下所有未取消、**已经在途**
     * （≥ 已出库）的包裹推到「已签收」；父单状态随后由 {@link #recomputeOrderStatus} 从子单推导出来（不再硬写 3）。
     *
     * <p>**只推已经出过库的包裹**：待下发 / 已下发 / 已拣货的包裹货还在商家手里，买家不可能收到 ——
     * 把它们推到「已签收」等于让包裹凭空跳过整条 WMS 流程（下发 → 拣货 → 出库 → 运输），
     * 还会凭空补一条现实里不存在的运单号。所以这类情况**直接拒绝确认收货**并说明原因
     * （口径② 管的是「父单状态由子单推导」，不是「买家一按就把包裹推到终点」）。
     *
     * <p>为什么必须推子单、而不是只把父单写成 3：父单状态是**推导值**，唯一事实源是包裹。
     * 只在父单上写 3，父单就和子单事实分叉了 —— 之后任何一个包裹的状态变化都会按子单重新聚合，
     * 把「已完成」写回「已发货」（真实的倒退序列见 OptimisticLockIntegrationTest ㉒：
     * ① 买家确认收货 → 订单 3；② 此时另一个包裹还在运输中；③ WMS 晚到的回传让 allSigned 变 false、
     * allOutbound 变 true；④ 订单被写回 2）。
     *
     * <p>落库路径与 WMS 回传 signed 一致：`updateById`（带 @Version）+ 影响行数校验 + 补运单 ——
     * 不绕过状态机直接 UPDATE 状态字段，是为了让「包裹已签收」在库里与回传签收长得一样，
     * 也避免漏掉「已签收的包裹必须有运单」这个副作用（没走过出库回传的包裹本来没有运单）。
     *
     * <p>0 行 = 这张包裹刚被别人改过（并发的另一个「确认收货」/ 晚到的回传）→ 抛 409 回滚整笔
     * （父单、其它包裹一起回滚），文案用买家视角的「订单状态已变化，请刷新后重试」。
     *
     * @return 本次真正被推进的包裹数（幂等：已签收的跳过，重复调用不会重复推进）
     */
    @Transactional(rollbackFor = Exception.class)
    public int signRemainingDeliveries(Long orderId) {
        List<DeliveryOrder> active = deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                        .eq(DeliveryOrder::getOrderId, orderId)
                        .orderByAsc(DeliveryOrder::getId)).stream()
                .filter(d -> d.getStatus() != DeliveryOrder.CANCELLED)
                .toList();

        // ① 还有包裹没出库（待下发 / 已下发 / 已拣货）→ **拒绝确认收货**。
        // 货还在商家手里，买家不可能收到；如果照样把它推到「已签收」，等于让这个包裹凭空跳过
        // 下发 → 拣货 → 出库 → 运输整条 WMS 流程，还要再凭空补一条现实里不存在的运单号。
        // 口径② 说的是「父单状态由子单推导」，**不是**「买家一按就把包裹推到终点」。
        List<DeliveryOrder> notOutbound = active.stream()
                .filter(d -> d.getStatus() < DeliveryOrder.OUTBOUND).toList();
        if (!notOutbound.isEmpty()) {
            String current = statusText(notOutbound.get(0).getStatus());
            log.warn("拒绝确认收货：订单 {} 还有 {} 个包裹未出库（当前：{}），不允许把包裹直接推到已签收",
                    orderId, notOutbound.size(), current);
            throw new IllegalStateException("该订单还有 " + notOutbound.size() + " 个包裹尚未出库（当前："
                    + current + "），暂不能确认收货；请等包裹发出后再确认");
        }

        // ② 只推**已经在途**的包裹（≥ 已出库）：出库/运输中 → 已签收。
        // 这些包裹真出过库，`saveLogisticsIfAbsent` 在这里只是「出库回传漏写运单」的兜底，
        // 不是给没出库的包裹伪造运单（上面那道守卫已经保证走到这里的一定出过库）。
        int signed = 0;
        for (DeliveryOrder delivery : active) {
            if (delivery.getStatus() >= DeliveryOrder.SIGNED) continue;       // 已签收：幂等跳过
            // 状态写入统一走 DeliveryStatusService（买家视角的 409 文案留在调用方，一个字不改）
            int rows = deliveryStatusService.change(delivery, DeliveryOrder.SIGNED,
                    DeliveryStatusService.REASON_CONFIRM_SIGN, null);
            OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_ORDER_STATE_CHANGED);
            saveLogisticsIfAbsent(delivery);                                  // 已出库的包裹该有运单（缺了才补）
            signed++;
        }
        return signed;
    }

    /**
     * 父订单状态聚合（图23 黄框）：**父单状态是推导值，事实源是子单**。
     * 全部子单 ≥ 已出库 → 2 已发货；部分出库 → 9 部分发货；全部已签收 → 3 已完成；其余不动。
     *
     * <p><b>为什么这里不需要「终态不许降级」的守卫</b>（口径②，用户拍板「子单是事实源」）：
     * 曾经出现过「已完成(3) 被写回 已发货(2)」，根因**不是**聚合算错了，而是当时
     * {@code PortalOrderController#confirm} 绕开子单**直接硬写** order.status=3，
     * 于是父单与子单事实分叉（父单已完成、还有包裹在运输中），下一次聚合当然只能按子单算出 2。
     * 口径②把写入口收敛成一处：确认收货改为「先把在途包裹推到已签收 → 再由这里推导父单」
     * （见 {@link #signRemainingDeliveries}）。子单已是事实，聚合就永远算不出更早的状态，
     * 再加一道「禁止降级」只会把真正的分叉藏起来（掩盖二次写入），而不是消除它。
     * 回归用例：OptimisticLockIntegrationTest ㉒（确认收货后所有在途包裹 = 已签收、订单 = 已完成，
     * 之后任意晚到的回传/重复聚合都不许把订单改回 2）。
     *
     * <p><b>不许被聚合改写的状态：已关闭(4) / 已退款(6) / 退款中(5)</b>。
     * 4、6 不是包裹推导出来的，而是外部业务动作（审核不通过 / 退货审核通过退款）的结论；
     * 一旦进入就代表「这单已经结束了」，晚到的包裹回传不该把它写成 2 已发货 / 9 部分发货。
     * <b>5 退款中虽然不是终态，但同样不让包裹回传改写</b>：它是「退款正在审批中」这个
     * <b>进行中的业务动作</b>，下一步只能由退货审核写成 6 已退款（或审核拒绝时由
     * {@link #recomputeOrderStatusAfterReturnReject} 交还给包裹聚合）—— 如果这期间一张在途包裹的
     * 回传就能把订单改回 2 已发货 / 9 部分发货，退款流程的状态就丢了：管理员在「退款中」列表里
     * 再也找不到这张单，买家却还在等退款。所以它的语义和 4/6 一样：**只有业务动作能改它**。
     * 已完成(3) **不加**守卫：口径②下它本来就是子单推导出来的结论（全部子单已签收），
     * 再加一道「3 不许动」就成了两套逻辑（一套推导、一套终态锁），反而会把真正的分叉藏起来。
     * 回归用例：OptimisticLockIntegrationTest ㉓（已退款 / 已关闭订单 + 在途包裹 + 晚到回传 → 状态不变）、
     * ㉖（退款中 + 在途包裹 + 晚到回传 → 状态不变，但 5→6 的退货审核通过仍照常生效）。
     *
     * <p><b>全部包裹被取消时退回待审核(7)</b>（另一个死锁）：拆单只在「审核通过」内部触发，
     * 没有独立入口。若一张订单的包裹全被取消，订单会停在 8 待发货 —— 既没有包裹可发（发货/回传/签收
     * 全都没对象），也没法重新拆单（审核通过只接受 7 待审核）→ 这张订单永远出不去。
     * 所以这里把「有包裹行、但没有一张未取消的」识别成「拆单结果被撤销」，退回 7 待审核，
     * 让管理员重新审核拆单。**同时把 split_status 归零**：否则重新审核时 {@code split()} 会命中
     * 「已拆过」的幂等分支，把几张已取消的包裹原样返回，一张新包裹都不生成（状态退回了、单还是拆不了）。
     * 注意 4/6/5 在上面就 return 了，所以这条只在「在途阶段」生效。
     */
    public void recomputeOrderStatus(Long orderId) {
        recompute(orderId, false, false);
    }

    /**
     * 退货审核**被拒绝**后用：把订单从「5 退款中」交还给包裹聚合（口径②：子单是事实源）。
     *
     * <p>为什么需要这个专用入口：{@link #recomputeOrderStatus} 现在会跳过 5 退款中（不许包裹回传改写
     * 进行中的退款），而「审核拒绝」恰恰是**唯一有权把订单从 5 拿走**的业务动作 —— 它必须能穿过那道守卫，
     * 否则拒绝之后订单会永远卡在「退款中」。这里只是放开「5 这一条」的跳过判断，
     * 4/6 的终态守卫、以及下面整套聚合规则完全不变。
     */
    public void recomputeOrderStatusAfterReturnReject(Long orderId) {
        recompute(orderId, true, false);
    }

    /**
     * 删除**待处理**退货申请后用：把订单从「5 退款中」交还给包裹聚合（口径②：子单是事实源）。
     *
     * <p>为什么必须有这个入口（缺陷：删掉申请后订单永远卡在「退款中」，真死路）：
     * 订单进 5 的唯一写者是「登记退货申请」，出 5 的写者只有两个 ——
     * （a）退货审核通过 → 6 已退款；（b）退货审核拒绝 → {@link #recomputeOrderStatusAfterReturnReject}。
     * 而后台还有第三条出口：{@code DELETE /admin/return-applies/{id}} 直接删掉那条待处理申请。
     * 旧实现只删申请、订单一动不动：5 被聚合跳过（不许包裹回传改写进行中的退款），
     * 审核通过又要求「必须还有一条待处理申请」（申请已经没了）→ 这张订单没有任何写者能把它带出来。
     *
     * <p>所以删除待处理申请 = 退款流程被撤销，订单必须交回聚合推导（**不硬写某个固定状态**，
     * 保持「子单是事实源」这唯一口径）：
     * <ul>
     *   <li>有在途包裹：包裹都出库 → 2 已发货；部分出库 → 9 部分发货；都签收 → 3 已完成；
     *       一张都没出库 → 8 待发货；</li>
     *   <li>没有任何有效包裹（一张包裹行都没有的直发单 / 包裹全被取消）：没有子单事实可依，
     *       回到 7 待审核（拆单标记一并归零），让人重新走审核流程。</li>
     * </ul>
     * 4/6 终态守卫不变（已关闭/已退款不会被这条路径改写）。
     */
    public void recomputeOrderStatusAfterReturnApplyDeleted(Long orderId) {
        recompute(orderId, true, true);
    }

    /**
     * @param leaveReturnInProgress true 只应来自「退货审核拒绝 / 删除待处理退货申请」：
     *                              允许把订单从 5 退款中交回聚合
     * @param noParcelToPendingAudit true 只应来自「删除待处理退货申请」：连一张包裹行都没有的直发单
     *                              也要退回 7 待审核（否则订单没有任何事实可依，只能卡在 5）
     */
    private void recompute(Long orderId, boolean leaveReturnInProgress, boolean noParcelToPendingAudit) {
        Order order = orderService.getById(orderId);
        if (order == null) return;
        Integer current = order.getStatus();
        // 终态不出让：已关闭(4) / 已退款(6) 是业务终局，不允许被包裹回传聚合改写
        // （已完成(3) 不在此列 —— 口径②下它由子单推导得出，不需要额外守卫，避免又变成两套逻辑）
        if (current != null && (current == 4 || current == 6)) {
            log.info("订单 {} 已是「{}」，跳过包裹聚合（终态不被回传改写）",
                    orderId, current == 4 ? "已关闭" : "已退款");
            return;
        }
        // 5 退款中：不是终态，但是「退款进行中」这个业务动作的结论，同样不许被包裹回传改写
        // （只有退货审核拒绝能把它交回来，见 recomputeOrderStatusAfterReturnReject）
        if (current != null && current == 5 && !leaveReturnInProgress) {
            log.info("订单 {} 正处于「退款中」（退货申请审批中），跳过包裹聚合（退款流程状态不被回传改写）", orderId);
            return;
        }
        List<DeliveryOrder> children = deliveryOrderService.list(
                new LambdaQueryWrapper<DeliveryOrder>().eq(DeliveryOrder::getOrderId, orderId));
        if (children.isEmpty()) {
            // 一张包裹行都没有（直发单）：聚合没有事实可依，正常调用一律不动状态。
            // 唯一例外是「删掉了待处理退货申请」—— 那一步把订单从 5 交回来，必须给它一个可继续的状态，
            // 否则它会永远停在「退款中」（审核通过要求有申请、申请已被删）。
            if (!noParcelToPendingAudit) return;
            if (current != null && current == 7 && (order.getSplitStatus() == null || order.getSplitStatus() == 0)) {
                return;                                   // 已经是待审核且未拆：不必再写一次
            }
            log.info("订单 {} 的待处理退货申请已删除，且该订单没有任何包裹（直发单）→ 回「待审核(7)」并清空拆单标记",
                    orderId);
            // 状态与 split_status 必须在**同一条** UPDATE 里（拆两条会多涨一次 version）
            int rows = orderStatusService.change(order, 7, OrderStatusService.REASON_AGGREGATE,
                    "删除待处理退货申请：无包裹的直发单回待审核并清空拆单标记",
                    update -> update.set(Order::getSplitStatus, 0));
            OptimisticLockGuard.requireAffected(rows);
            return;
        }
        // 已取消的子单不参与聚合
        List<DeliveryOrder> active = children.stream()
                .filter(d -> d.getStatus() != DeliveryOrder.CANCELLED).toList();
        if (active.isEmpty()) {
            // 有包裹行、但没有一张在途：全部包裹都被取消了 —— 拆单结果已被撤销。
            // 停在 8 待发货是死锁（没包裹可发、又不能重新拆单），退回 7 待审核让人重新审核拆单。
            boolean needStatus = current == null || current != 7;
            boolean needSplitFlag = order.getSplitStatus() == null || order.getSplitStatus() != 0;
            if (!needStatus && !needSplitFlag) return;            // 已经是待审核且未拆：不必再写一次
            // split_status 必须一起归零：它还是 1 的话，重新审核时 split() 会走「已拆过」的幂等分支
            // 直接返回那几张**已取消**的包裹，一张新包裹都不会生成 —— 状态退回 7 了却还是拆不了单。
            // 状态与 split_status 必须在**同一条** UPDATE 里（拆两条会多涨一次 version）
            log.info("订单 {} 的 {} 张包裹已全部取消（无在途包裹），退回「待审核(7)」并清空拆单标记，以便重新审核拆单",
                    orderId, children.size());
            int rows = orderStatusService.change(order, 7, OrderStatusService.REASON_AGGREGATE,
                    "包裹全部取消：退回待审核并清空拆单标记",
                    update -> update.set(Order::getSplitStatus, 0));
            OptimisticLockGuard.requireAffected(rows);
            return;
        }

        boolean allOutbound = active.stream().allMatch(d -> d.getStatus() >= DeliveryOrder.OUTBOUND);
        boolean allSigned = active.stream().allMatch(d -> d.getStatus() >= DeliveryOrder.SIGNED);
        boolean anyOutbound = active.stream().anyMatch(d -> d.getStatus() >= DeliveryOrder.OUTBOUND);

        final int target;
        if (allSigned) {
            target = 3;            // 已完成
        } else if (allOutbound) {
            target = 2;            // 已发货（全部子单出库）
        } else if (anyOutbound) {
            target = 9;            // 部分发货（中间态）
        } else {
            // 一张都没出库：包裹侧推不出「更早」的结论，正常在途订单本来就停在 8 待发货 —— 不写。
            // 唯一例外是退货审核拒绝把订单从 5 交回来时：必须落回 8 待发货，否则会永远卡在「退款中」。
            if (!leaveReturnInProgress) return;
            target = 8;
            log.info("订单 {} 的退货申请被拒，且包裹都还没出库 → 由子单推导回「待发货(8)」", orderId);
        }
        // 父订单聚合也是写（统一走 OrderStatusService）：0 行 = 读完之后别人改过这张订单 →
        // 409 回滚整笔（子单状态一起回滚），不允许「子单已出库、父单状态没跟上」这种半截状态悄悄提交
        int rows = orderStatusService.change(order, target, OrderStatusService.REASON_AGGREGATE, null);
        OptimisticLockGuard.requireAffected(rows);
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
