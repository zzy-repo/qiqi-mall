package com.qiqi.mall.shared.ledger;

import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.shared.ledger.mapper.DeliveryStatusLedgerMapper;
import com.qiqi.mall.shared.ledger.mapper.OrderStatusLedgerMapper;
import com.qiqi.mall.shared.ledger.mapper.ReturnStatusLedgerMapper;
import com.qiqi.mall.shared.security.ActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.LongFunction;

/**
 * 三张状态台账（order / delivery / return_status_ledger）的<b>唯一写入口</b>。
 *
 * <h3>它必须与业务在同一条 UPDATE 之后、同一个事务里</h3>
 * 台账证明的是「状态是怎么变成现在这样的」。它要是和状态写入分成两个事务，就会出现两种都不能接受的结果：
 * <ul>
 *   <li>状态改了、台账没写（中间崩溃/回滚）→ 证据缺失，事后无法回答「谁改的、为什么改」；</li>
 *   <li>台账写了、状态没改 → 台账在说谎，比没有台账更坏。</li>
 * </ul>
 * 所以调用点全部在 {@code OrderStatusService} / {@code DeliveryStatusService} /
 * {@code ReturnStatusService} 的那句 UPDATE **成功之后**（{@code rows > 0}），
 * 由同一个 {@code @Transactional} 包住 —— 要么两个都落，要么两个都不落。
 *
 * <h3>幂等键 = {业务ID}:{statusSeq}（seq 用本次 UPDATE 之后的新值）</h3>
 * <ul>
 *   <li>为什么带 statusSeq：状态可以合法地回到同一个值（7→8 审核通过 → 包裹全取消退回 7 → 再审核 8），
 *       只用 (业务ID, from, to) 当唯一键会误杀第二次合法流转；seq 是单调的，永远不会误杀；</li>
 *   <li>为什么用「本次 UPDATE 之后的新值」：{@code status_seq = status_seq + 1} 与 {@code status} 在
 *       <b>同一条</b> UPDATE 里自增（见三个状态服务），所以「更新成功」与「seq 变成 N」是同一件事，
 *       不存在中间态；重放（同一笔业务被提交两次）算出同一个 seq → 同一个键 → 静默跳过，不重复记账。</li>
 * </ul>
 *
 * <h3>seq 从哪来（不回查业务表）</h3>
 * 三个实体（{@code Order} / {@code DeliveryOrder} / {@code ReturnApply}）都带上了 {@code statusSeq} 只读字段
 * （{@code @TableField(insertStrategy=NEVER, updateStrategy=NEVER)}：只读不写，状态服务那句 UPDATE 才是
 * 唯一写者）。更新成功后 {@code seq} 就是「实体上的值 + 1」，这里算完顺手回写实体 ——
 * 同一事务里若发生第二次流转，第二次算出来的 seq 仍然正确。
 * 只有实体上缺这个值（理论上只出现在手工构造的实体上）才回查一次，回查也拿不到就<b>抛异常</b>：
 * 没有可信的 seq 就没有可信的幂等键，宁可不写（让业务失败），也不写一条可能撞键被静默跳过的台账。
 *
 * <h3>开关</h3>
 * {@code qiqi.ledger.enabled=false}（或 {@code qiqi.ledger.status-enabled=false}）时本类全部方法直接 return：
 * 一行不写、一句 SQL 都不发 —— 行为精确回到 P2 第一步。
 */
@Component
public class StatusLedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(StatusLedgerWriter.class);

    private final OrderStatusLedgerMapper orderLedger;
    private final DeliveryStatusLedgerMapper deliveryLedger;
    private final ReturnStatusLedgerMapper returnLedger;
    private final LedgerProperties props;

    public StatusLedgerWriter(OrderStatusLedgerMapper orderLedger,
                              DeliveryStatusLedgerMapper deliveryLedger,
                              ReturnStatusLedgerMapper returnLedger,
                              LedgerProperties props) {
        this.orderLedger = orderLedger;
        this.deliveryLedger = deliveryLedger;
        this.returnLedger = returnLedger;
        this.props = props;
    }

    // ================================================================ 订单

    /**
     * 订单出生事件（建单）：{@code from_status = NULL}、{@code to_status = 初值}、
     * 幂等键 {@code {orderId}:0}（{@code status_seq} 的列默认值就是 0）。
     *
     * <p><b>必须在 {@code save()} 之后调用</b>（否则拿不到自增 id），且与 save 同一事务。
     * {@code init()} 仍留在 save 之前（它只定初值、不落库），出生台账由
     * {@code OrderStatusService#recordBirth} 在 save 之后补上 —— 见该方法注释。
     */
    public void orderBirth(Order order, String reason, String reasonDetail) {
        if (!props.statusOn()) return;
        long orderId = requireId(order.getId(), "订单");
        ActorResolver.Actor actor = requireActor();
        int seq = order.getStatusSeq() == null ? 0 : order.getStatusSeq();
        order.setStatusSeq(seq);
        int to = order.getStatus() == null ? 0 : order.getStatus();
        orderLedger.insert(orderId, null, to, requireReason(reason), detail(reasonDetail),
                actor.type(), actor.id(), actor.name(), null, null, null, idemKey(orderId, seq));
        log.debug("订单台账（出生）：订单 {} status_seq={} to_status={} reason={}", orderId, seq, to, reason);
    }

    /** 订单状态流转事件（{@code from_status} = 变更前状态） */
    public void orderChanged(Order order, Integer from, int to, String reason, String reasonDetail,
                             String refType, Long refId) {
        if (!props.statusOn()) return;
        long orderId = requireId(order.getId(), "订单");
        ActorResolver.Actor actor = requireActor();
        int seq = nextSeq(orderId, order.getStatusSeq(), orderLedger::selectStatusSeq);
        order.setStatusSeq(seq);                      // 回写：同一事务内再流转一次也正确
        orderLedger.insert(orderId, from, to, requireReason(reason), detail(reasonDetail),
                actor.type(), actor.id(), actor.name(), refType, refId, null, idemKey(orderId, seq));
        log.debug("订单台账：订单 {} {} → {} status_seq={} reason={}", orderId, from, to, seq, reason);
    }

    // ================================================================ 包裹

    /**
     * 包裹出生事件（拆单/人工拆分新建的包裹）：{@code from_status = NULL}，
     * 额外写两个**已登记冗余列** —— {@code delivery_sn} 快照 + {@code order_id}。
     *
     * <p>为什么 order_id 也要冗余：{@code OrderPurgeService} 会物理删除包裹行，删完
     * {@code delivery_order} 这座桥就断了，只留单号的话「按订单反查包裹流水」彻底失效
     * （与 {@code return_status_ledger.order_id} 同一类缺口，两张表必须对称）。
     */
    public void deliveryBirth(DeliveryOrder delivery, String reason, String reasonDetail) {
        if (!props.statusOn()) return;
        long deliveryId = requireId(delivery.getId(), "包裹");
        ActorResolver.Actor actor = requireActor();
        int seq = delivery.getStatusSeq() == null ? 0 : delivery.getStatusSeq();
        delivery.setStatusSeq(seq);
        int to = delivery.getStatus() == null ? 0 : delivery.getStatus();
        deliveryLedger.insert(deliveryId, requireSn(delivery.getDeliverySn()), orderIdOf(delivery), null, to,
                requireReason(reason), detail(reasonDetail), actor.type(), actor.id(), actor.name(),
                null, null, null, idemKey(deliveryId, seq));
        log.debug("包裹台账（出生）：包裹 {} {} orderId={} status_seq={} reason={}",
                deliveryId, delivery.getDeliverySn(), delivery.getOrderId(), seq, reason);
    }

    /** 包裹状态流转事件（同样带上冗余的 {@code order_id}，理由见 {@link #deliveryBirth}） */
    public void deliveryChanged(DeliveryOrder delivery, Integer from, int to, String reason, String reasonDetail,
                                String refType, Long refId) {
        if (!props.statusOn()) return;
        long deliveryId = requireId(delivery.getId(), "包裹");
        ActorResolver.Actor actor = requireActor();
        int seq = nextSeq(deliveryId, delivery.getStatusSeq(), deliveryLedger::selectStatusSeq);
        delivery.setStatusSeq(seq);
        deliveryLedger.insert(deliveryId, requireSn(delivery.getDeliverySn()), orderIdOf(delivery), from, to,
                requireReason(reason), detail(reasonDetail), actor.type(), actor.id(), actor.name(),
                refType, refId, null, idemKey(deliveryId, seq));
        log.debug("包裹台账：包裹 {} {} → {} orderId={} status_seq={} reason={}",
                deliveryId, from, to, delivery.getOrderId(), seq, reason);
    }

    // ================================================================ 售后

    /** 售后出生事件（提交申请 / 历史结论直接写入）：{@code from_status = NULL}，额外写 {@code order_id} 冗余 */
    public void returnBirth(ReturnApply apply, String reason, String reasonDetail) {
        if (!props.statusOn()) return;
        long applyId = requireId(apply.getId(), "退货申请");
        ActorResolver.Actor actor = requireActor();
        int seq = apply.getStatusSeq() == null ? 0 : apply.getStatusSeq();
        apply.setStatusSeq(seq);
        int to = apply.getStatus() == null ? 0 : apply.getStatus();
        returnLedger.insert(applyId, apply.getOrderId(), null, to, requireReason(reason), detail(reasonDetail),
                actor.type(), actor.id(), actor.name(), null, null, null, idemKey(applyId, seq));
        log.debug("售后台账（出生）：申请 {} orderId={} status_seq={} to_status={} reason={}",
                applyId, apply.getOrderId(), seq, to, reason);
    }

    /** 售后状态流转事件（0 待处理 → 1 通过 / 2 拒绝） */
    public void returnChanged(ReturnApply apply, Integer from, int to, String reason, String reasonDetail,
                              String refType, Long refId) {
        if (!props.statusOn()) return;
        long applyId = requireId(apply.getId(), "退货申请");
        ActorResolver.Actor actor = requireActor();
        int seq = nextSeq(applyId, apply.getStatusSeq(), returnLedger::selectStatusSeq);
        apply.setStatusSeq(seq);
        returnLedger.insert(applyId, apply.getOrderId(), from, to, requireReason(reason), detail(reasonDetail),
                actor.type(), actor.id(), actor.name(), refType, refId, null, idemKey(applyId, seq));
        log.debug("售后台账：申请 {} {} → {} status_seq={} reason={}", applyId, from, to, seq, reason);
    }

    // ================================================================ 内部

    /**
     * 幂等键：{@code {业务ID}:{statusSeq}}（与 {@code sql/migrate-ledger.sql} 的注释逐字一致）。
     *
     * <p>为什么不加 shard 前缀：{@code stock_ledger} 的 {@code idem_key} 以单调递增的 orderId 开头，
     * 所有插入都落在 uk_idem 最右端同一个 gap，成了全局串行点（压测实证）。同样形状的键理论上
     * 有同一个风险，评估见 {@code docs} 与 P2 第二步报告：
     * <ul>
     *   <li>写入量差一个数量级：库存台账是「每条明细一次」，状态台账是「每次状态流转一次」；</li>
     *   <li>{@code {bizId}:{seq}} 是字符串键，字典序按 bizId 的**首字符**分簇（10 簇），
     *       并发插入落在不同簇的不同位置，不像纯递增键那样全挤在同一个 gap；</li>
     *   <li>每簇内部仍可能相邻 —— 若后续压测证明这条成了热点，现成的解法与 stock_ledger 相同：
     *       幂等键加 {@code LPAD(bizId % N, 位宽, '0') + ":"} 前缀（默认 N=1 = 不加前缀）。
     *       本轮**不加**：加前缀会改幂等键格式，需要同时回填历史行 + 切开关，属于另一个变量。</li>
     * </ul>
     */
    private static String idemKey(long bizId, int statusSeq) {
        return bizId + ":" + statusSeq;
    }

    /** 新 seq = 当前值 + 1；当前值缺失才回查一次，回查也没有就抛（不写不可信的键） */
    private static int nextSeq(long bizId, Integer onEntity, LongFunction<Integer> readBack) {
        Integer current = onEntity;
        if (current == null) current = readBack.apply(bizId);
        if (current == null) {
            throw new IllegalStateException("台账写入缺少 status_seq（业务ID=" + bizId + "）："
                    + "无法生成幂等键 {bizId}:{statusSeq} —— 拒绝写一条可能撞键被静默跳过的台账");
        }
        return current + 1;
    }

    private static long requireId(Long id, String what) {
        if (id == null) {
            throw new IllegalStateException(what + "台账写入时还没有业务ID（必须先 save() 拿到自增主键，再写台账）");
        }
        return id;
    }

    /**
     * 包裹的订单 id（台账的已登记冗余列）：优先取实体上的值，缺失才回查一次
     * {@code delivery_order.order_id}（该列 NOT NULL ⇒ 回查不到 = 包裹行已不存在，直接抛）。
     *
     * <p>为什么不许写 NULL 蒙过去：这条冗余列存在的**全部意义**就是「包裹行被物理删除后仍能按订单反查」，
     * 写一条 order_id 为 NULL 的台账等于把缺口原样保留下来，而且它看起来「写成功了」，事后没人会再发现。
     */
    private Long orderIdOf(DeliveryOrder delivery) {
        Long orderId = delivery.getOrderId();
        if (orderId == null) orderId = deliveryLedger.selectOrderId(delivery.getId());
        if (orderId == null) {
            throw new IllegalStateException("包裹台账写入缺少 order_id（包裹ID=" + delivery.getId()
                    + "）：delivery_order.order_id 是 NOT NULL，回查不到说明包裹行已不存在 —— "
                    + "拒绝写一条丢掉了订单归属的包裹台账");
        }
        return orderId;
    }

    private static String requireSn(String deliverySn) {
        if (deliverySn == null || deliverySn.isBlank()) {
            throw new IllegalStateException("包裹台账必须写 delivery_sn 快照（包裹行可能被物理删除，单号是唯一的追踪线索）");
        }
        return deliverySn;
    }

    /**
     * {@code reason_detail} 是**自由文本**（审核意见 / 退货说明），列宽 VARCHAR(255)。
     *
     * <p>业务表允许更长的原文（{@code return_apply.description VARCHAR(500)}），所以这里必须裁 ——
     * 不裁就是「用户写 300 字退货说明 → 审核接口 500」。只裁自由文本，原因码一律不裁（见 {@link LedgerText}）。
     */
    private static String detail(String reasonDetail) {
        return LedgerText.clip(reasonDetail, 255);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("台账必须带 reason（原因码）：没有原因的台账回答不了「为什么变成这样」");
        }
        return reason;
    }

    /** 操作者：{@code ActorResolver} 保证 type 在 CHECK 闭集内、name 非空（见该类的注释） */
    private static ActorResolver.Actor requireActor() {
        ActorResolver.Actor actor = ActorResolver.resolve();
        if (!actor.isValid()) {
            throw new IllegalStateException("操作者解析非法（type=" + actor.type() + ", name=" + actor.name()
                    + "）：operator_type 必须落在 ADMIN/MEMBER/APIKEY/SYSTEM 闭集内");
        }
        return actor;
    }
}
