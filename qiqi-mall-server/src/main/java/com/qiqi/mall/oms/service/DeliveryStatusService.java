package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.ledger.StatusLedgerWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 包裹（发货单）状态写入的<b>唯一入口</b>（P2 第一步：收敛写点，行为不变）。
 *
 * <p>与 {@link OrderStatusService} 同一套口径，只是主体是 {@code delivery_order}：
 * <pre>
 * 0 待下发 → 1 已下发 → 2 已拣货 → 3 已出库 → 4 运输中 → 5 已签收；0 待下发 → 9 已取消
 * </pre>
 * 收敛前这 7 个写点散在 {@code DeliveryFlowService}（下发/取消/WMS 回传四个事件/确认收货推签收）
 * 与两处「出生」（自动拆单、人工拆分），每处都自己 {@code setStatus} + {@code updateById}。
 *
 * <h3>行为不变的三个要点</h3>
 * <ol>
 *   <li>一条 UPDATE 写 {@code status} + {@code status_seq+1} + {@code version+1}，
 *       WHERE 条件与 {@code @Version} 生成的完全一致；</li>
 *   <li>返回值是影响行数，调用方照旧 {@code requireAffected(rows, 文案)} ——
 *       确认收货那条用的是买家视角文案 {@code MSG_ORDER_STATE_CHANGED}，文案留在调用方不改；</li>
 *   <li>幂等判断（「已 ≥ 目标状态就直接 return」「已签收跳过」）仍然在调用方 ——
 *       那是业务语义，不是状态写入。</li>
 * </ol>
 */
@Service
public class DeliveryStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusService.class);

    // ---- 事件原因（reason 列口径）----
    // 常量唯一来源是 shared/ledger/ReasonCodes；这里保留别名，P2 第一步的调用点不用改。
    // 台账写入时原样落到 delivery_status_ledger.reason（VARCHAR(32)）。
    public static final String REASON_CREATE = ReasonCodes.DELIVERY_CREATE;
    public static final String REASON_DISPATCH = ReasonCodes.DELIVERY_DISPATCH;
    public static final String REASON_CANCEL = ReasonCodes.DELIVERY_CANCEL;
    public static final String REASON_PICK = ReasonCodes.WMS_PICK;
    public static final String REASON_OUTBOUND = ReasonCodes.WMS_OUTBOUND;
    public static final String REASON_TRANSIT = ReasonCodes.WMS_TRANSIT;
    public static final String REASON_SIGNED = ReasonCodes.WMS_SIGNED;
    public static final String REASON_CONFIRM_SIGN = ReasonCodes.ORDER_CONFIRM_SIGN;

    /** 出生：新建包裹只允许「待下发」（拆单出来的包裹还没下发，其它状态只能由 WMS 回传产生） */
    private static final Set<Integer> BIRTH = Set.of(DeliveryOrder.PENDING);

    /**
     * 流转表（来源 → 允许去向）：从现有守卫抄出来，不是新规则。
     *
     * <p>对应代码：下发（仅 0）、取消（仅 0）、拣货（仅 1）、出库（1/2）、揽收（仅 3）、签收（≥3 → 5）。
     */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            0, Set.of(DeliveryOrder.DISPATCHED, DeliveryOrder.CANCELLED),
            1, Set.of(DeliveryOrder.PICKED, DeliveryOrder.OUTBOUND),
            2, Set.of(DeliveryOrder.OUTBOUND),
            3, Set.of(DeliveryOrder.IN_TRANSIT, DeliveryOrder.SIGNED),
            4, Set.of(DeliveryOrder.SIGNED),
            5, Set.of(),
            9, Set.of());

    private static final String STATUS_SEQ_INCREMENT = "status_seq = status_seq + 1";
    private static final String VERSION_INCREMENT = "version = version + 1";

    private final DeliveryOrderService deliveryOrderService;
    /** 状态台账写入口（与状态 UPDATE 同一事务；开关关闭时它自己 no-op） */
    private final StatusLedgerWriter ledger;

    public DeliveryStatusService(DeliveryOrderService deliveryOrderService, StatusLedgerWriter ledger) {
        this.deliveryOrderService = deliveryOrderService;
        this.ledger = ledger;
    }

    /** 出生：给**尚未落库**的新包裹定初始状态（{@code save} 之前调用，不产生 UPDATE） */
    public void init(DeliveryOrder delivery, int to) {
        if (!BIRTH.contains(to)) {
            throw new IllegalStateException("新建包裹只能是「待下发」状态，收到 " + to);
        }
        delivery.setStatus(to);
    }

    /**
     * 出生台账（{@code from_status = NULL}，并写下两个已登记冗余列 —— {@code delivery_sn} 快照
     * 与 {@code order_id}）：拆单/人工拆分新建出来的包裹也要有「出生」这条证据。
     *
     * <p>必须在 {@code save()} 之后调用（幂等键 {@code {deliveryId}:0} 要真实 id），且同一事务。
     * 单号快照尤其重要：{@code OrderPurgeService} 会物理删包裹行，单号只留在台账里；
     * {@code order_id} 同理 —— 包裹行一删，{@code delivery_order} 这座「台账 → 订单」的桥就断了，
     * 不冗余订单 id 就再也回答不了「这张订单的几个包裹是怎么走的」（与售后台账的 order_id 对称）。
     * 订单 id 由 {@link StatusLedgerWriter} 从包裹实体取（实体缺失才回查一次，取不到直接抛）。
     */
    public void recordBirth(DeliveryOrder delivery, String reason, String reasonDetail) {
        ledger.deliveryBirth(delivery, reason, reasonDetail);
    }

    /** 流转（不带附带列） */
    public int change(DeliveryOrder delivery, int to, String reason, String reasonDetail) {
        return change(delivery, to, reason, reasonDetail, null);
    }

    /**
     * 流转：一条 UPDATE 写 {@code status} + {@code status_seq+1} + {@code version+1}；
     * 台账那一行同时带上 {@code delivery_sn} 与 {@code order_id} 两个已登记冗余列。
     *
     * @param extraSets 需要与状态同一条 UPDATE 一起写的其它列（当前没有调用方用，留作第二步/后续）
     * @return 影响行数；0 = version 不匹配（期间被别人改过）→ 调用方抛 409
     */
    public int change(DeliveryOrder delivery, int to, String reason, String reasonDetail,
                      Consumer<LambdaUpdateWrapper<DeliveryOrder>> extraSets) {
        return change(delivery, to, reason, reasonDetail, extraSets, null, null);
    }

    /** 流转（带触发单据）：refType/refId 记这次流转被哪张单据触发 */
    public int change(DeliveryOrder delivery, int to, String reason, String reasonDetail,
                      Consumer<LambdaUpdateWrapper<DeliveryOrder>> extraSets, String refType, Long refId) {
        Integer from = delivery.getStatus();
        requireAllowed(delivery.getId(), from, to, reason);
        Integer version = delivery.getVersion();

        LambdaUpdateWrapper<DeliveryOrder> update = new LambdaUpdateWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getId, delivery.getId())
                .set(DeliveryOrder::getStatus, to)
                .setSql(STATUS_SEQ_INCREMENT)
                .setSql(VERSION_INCREMENT);
        if (version != null) update.eq(DeliveryOrder::getVersion, version);
        if (extraSets != null) extraSets.accept(update);

        int rows = deliveryOrderService.getBaseMapper().update(null, update);
        if (rows > 0) {
            delivery.setStatus(to);
            if (version != null) delivery.setVersion(version + 1);
            // 台账：只在 UPDATE 成功之后写（0 行 = 有人抢先改过 → 调用方 409 回滚，状态没变就不许记）
            ledger.deliveryChanged(delivery, from, to, reason, reasonDetail, refType, refId);
        }
        log.debug("包裹 {} 状态 {} → {}（reason={}，影响 {} 行）", delivery.getId(), from, to, reason, rows);
        return rows;
    }

    /** 合法性判定：只拦「现网不存在的箭头」（走到这里说明某个入口少了一道守卫，属于代码 bug） */
    private void requireAllowed(Long deliveryId, Integer from, int to, String reason) {
        if (from == null) {
            throw new IllegalStateException("包裹 " + deliveryId + " 没有当前状态，无法流转到 " + to);
        }
        Set<Integer> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException(
                    "非法的包裹状态流转：" + from + " → " + to + "（reason=" + reason + "，包裹 " + deliveryId + "）");
        }
    }
}
