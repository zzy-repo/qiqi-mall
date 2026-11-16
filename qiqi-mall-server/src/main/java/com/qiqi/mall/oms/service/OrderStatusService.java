package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.audit.service.OrderAuditTagService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.ledger.StatusLedgerWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 订单状态写入的<b>唯一入口</b>：所有改 {@code order.status} 的路径都走这里。
 *
 * <h3>为什么要有它</h3>
 * 收敛前 {@code order.status} 有 15 个写点散在 5 个类里（审核通过/审核不通过/发货/支付/确认收货/
 * 退货申请/退货审核/聚合…），每处都自己 {@code setStatus(x)} + {@code updateById}。于是：
 * <ul>
 *   <li>「哪些流转是合法的」只存在于每个 {@code if} 里，谁也说不全整张状态图；</li>
 *   <li>没有一处能顺手在<b>同一条 UPDATE</b> 里维护 {@code status_seq}（状态台账的幂等键要它）；</li>
 *   <li>加一行台账 INSERT 要改 15 处，漏一处就少一条证据。</li>
 * </ul>
 * 现在所有写点都走 {@link #change}：合法性在一处判定，{@code status} / {@code status_seq} /
 * {@code version} 在同一句 UPDATE 里改完，台账写入也只挂在这一处。
 *
 * <h3>台账跟着状态一起写</h3>
 * {@link #change} 在 UPDATE 成功（{@code rows > 0}）之后才调 {@code StatusLedgerWriter#orderChanged}，
 * 与状态写入在同一个事务里；{@code rows == 0} 说明有人抢先改过、状态其实没变，这时一行台账都不写
 * （写了就是「记一笔没发生的事」）。{@code reason}/{@code reasonDetail} 就是
 * {@code order_status_ledger} 的那两列，所以每个调用点必须把「这次是什么事件」说清楚。
 *
 * <h3>结论标跟着状态一起结算（离开待审核就清空）</h3>
 * 「结论标」（{@code order_audit_tag} 里 {@code category='结论'} 或 {@code FROZEN%} 的那些：AUDIT_PENDING /
 * BLOCKED / NO_POLICY_MATCH / STOCK_SHORT / FROZEN…）描述的是<b>这一轮判定得出的当前结论</b>。
 * 单子一旦离开「7 待审核」，那个结论就不再成立，必须收尾 —— 就在 {@link #change} 成功之后、
 * 同一事务内，由 {@link #settleConclusionTagsIfLeavingAuditPending} 统一做。
 * 期 1.6 曾在两个入口（审核通过 / 审核不通过）手工调用过结算，本期删掉了那两处：
 * 同一件事有两个写点，迟早早漏（7→5 申请退货就是这么漏掉的）。
 *
 * <h3>与旧写法的等价性（逐条对齐，行为不变是硬门槛）</h3>
 * <ol>
 *   <li><b>一条 UPDATE</b>：{@code SET status=?, status_seq = status_seq + 1, version = version + 1
 *       WHERE id = ? AND version = ?} —— 旧写法 {@code updateById} 由 MyBatis-Plus 的
 *       {@code @Version} 生成的条件与自增完全一致，这里只是多带了 {@code status_seq}；</li>
 *   <li><b>条件按 version，不按 status</b>：旧写法的并发保护就是 version（{@code WHERE version = ?}），
 *       这里不额外加 {@code AND status = ?} —— 加了会把「status 没变但 version 变了」的其它场景
 *       从「照常写入」变成「409」，那是<b>新的业务规则</b>，本轮不引入；</li>
 *   <li><b>返回值</b>：返回影响行数，调用方照旧用 {@code OptimisticLockGuard.requireAffected(rows, 文案)}
 *       决定 409 与文案 —— 各种入口的文案不一样（买家视角 {@code MSG_ORDER_STATE_CHANGED} /
 *       后台视角 {@code MSG_VERSION_CONFLICT}），所以守卫留在调用方，一个字的文案都不改；</li>
 *   <li><b>实体回写</b>：成功后把 {@code status} 与 {@code version+1} 写回内存实体，和
 *       {@code updateById} 之后实体手里的值一致（有的入口紧接着就用实体里的状态组装响应）；</li>
 *   <li><b>附带列</b>：旧代码常常一次 {@code updateById} 同时写状态和别的列（支付写
 *       {@code pay_type}/{@code payment_time}、聚合写 {@code split_status}）。这些列通过
 *       {@code extraSets} 加进<b>同一条</b> UPDATE —— 拆成两条会多涨一次 version，行为就变了。</li>
 * </ol>
 */
@Service
public class OrderStatusService {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusService.class);

    // ---- 事件原因（reason 列口径）----
    // 常量本身住在 shared/ledger/ReasonCodes（唯一来源）；这里保留同名别名，是为了让已经写好的一批
    // 调用点（OrderStatusService.REASON_PAY 之类）一个字都不用改。
    // 台账写入时 reason 会原样落到 order_status_ledger.reason（VARCHAR(32)）。
    public static final String REASON_CREATE = ReasonCodes.ORDER_CREATE;
    public static final String REASON_PAY = ReasonCodes.ORDER_PAY;
    public static final String REASON_AUDIT_PASS = ReasonCodes.AUDIT_PASS;
    public static final String REASON_AUDIT_REJECT = ReasonCodes.AUDIT_REJECT;
    public static final String REASON_SHIP = ReasonCodes.ORDER_SHIP;
    public static final String REASON_CONFIRM = ReasonCodes.ORDER_CONFIRM;
    public static final String REASON_RETURN_APPLY = ReasonCodes.RETURN_APPLY;
    public static final String REASON_RETURN_APPROVE = ReasonCodes.RETURN_APPROVE;
    public static final String REASON_RETURN_REJECT = ReasonCodes.RETURN_REJECT;
    public static final String REASON_AGGREGATE = ReasonCodes.DELIVERY_AGGREGATE;

    /** 出生：新建订单允许的初始状态（其余必须由业务流程流转产生，与建单守卫同一口径） */
    private static final Set<Integer> BIRTH = Set.of(0, 1, 7);

    /**
     * 明确流转表（来源 → 允许去向）：**从现有代码里抄出来的**，不是新定的规则。
     *
     * <p>抄的口径：每一条箭头都能指出原来哪个 {@code setStatus} 写的。凡是本表没写的箭头，
     * 现网也不存在（写出来就会被这里拦下，属于代码 bug，不是业务结论）。
     * 逐条来源见 {@code docs} 里的状态图与各入口的守卫文案。
     */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            // 0 待付款：支付 → 7 待审核（PortalOrderController#pay；口径「支付即送审」）
            0, Set.of(7),
            // 1 已付款：发货 → 2（OrderController#ship，历史直发单）；申请退货 → 5（PortalReturnController#apply）
            1, Set.of(2, 5),
            // 2 已发货：确认收货 → 3（PortalOrderController#confirm）；申请退货 → 5
            2, Set.of(3, 5),
            // 3 已完成：只能被聚合重复写成 3（幂等重算，见 DERIVED）
            3, Set.of(),
            // 4 已关闭：终局，任何入口都不再改写
            4, Set.of(),
            // 5 退款中：审核通过 → 6；审核拒绝（直发单，无包裹）→ 1；拒绝/删申请后交回聚合 → 2/3/8/9（见 DERIVED）
            5, Set.of(6, 1),
            // 6 已退款：终局
            6, Set.of(),
            // 7 待审核：审核通过 → 8；审核不通过 → 4；申请退货 → 5
            7, Set.of(8, 4, 5),
            // 8 待发货：发货 → 2（直发）；申请退货 → 5
            8, Set.of(2, 5),
            // 9 部分发货：申请退货 → 5
            9, Set.of(5));

    /**
     * 聚合推导值：父单状态是<b>子单的推导值</b>（口径：子单是事实源）。
     *
     * <p>为什么它们不走 {@link #TRANSITIONS}：{@code recomputeOrderStatus} 每收到一次包裹事件就重算一遍，
     * 算出来的可能就是当前值（例如订单已是 9 部分发货，又有一个包裹出库但还没全出库 → 再写一次 9）。
     * 这种「重复写同一个推导值」是幂等重算，不是流转，所以允许 <b>X → X</b>，也允许从任何
     * 非终局状态被推导到 2/3/7/8/9（4 已关闭 / 6 已退款是业务终局，聚合在原地就 return 了）。
     *
     * <p>注意这不是放宽业务规则：能推导出什么，仍然由包裹事实唯一决定（见
     * {@code DeliveryFlowService#recompute}），这里只承认「聚合有权写这些值」。
     */
    private static final Set<Integer> DERIVED = Set.of(2, 3, 7, 8, 9);

    /** 业务终局：聚合与任何流转都不得改写（4 已关闭 / 6 已退款） */
    private static final Set<Integer> TERMINAL = Set.of(4, 6);

    // ============================================================ 离开待审核 ⇒ 结算结论标

    /**
     * 待审核状态码。<b>它是唯一「身上会挂结论标」的状态</b>，所以只有「离开它」时要做收尾。
     *
     * <p>为什么收尾必须挂在这里、而不是挂在每个入口：期 1.6 只在两个入口
     * （{@code OrderController#auditPass} 7→8、{@code #auditReject} 7→4）手工结算了结论标，
     * 而离开 7 的出口<b>不止两个</b> —— 申请退货 7→5（{@code ReturnApplyController#create} /
     * {@code PortalReturnController#apply}）、包裹聚合把 7 推导成 2/3/9 也都是出口。
     * 逐个入口补的后果是「迟早还会漏一个」，而且漏掉的那个方向最危险：单子带着
     * {@code AUDIT_PENDING}（{@code blocking=1}）离开 7，期 2 若在下发前按 blocking 拦一道，
     * 这些单会被<b>误拦</b>，且队列里它们已不是待审核 —— 没有任何动作能解掉那条残留标，
     * 保险当场变成第二道死锁。所以结算挂到状态写入的唯一入口上：出口不会漏，因为出口只有一个。
     */
    private static final int STATUS_AUDIT_PENDING = 7;

    /**
     * 7 待审核 → 5 退款中（登记退货申请）结算结论标时的解除人。
     *
     * <p>7→8 / 7→4 两条沿用 {@link OrderAuditTagService} 里既有的
     * {@code RELEASED_BY_AUDIT_PASS} / {@code RELEASED_BY_AUDIT_REJECT}（口径不变，历史数据与
     * 期 1.6 的用例继续成立）；7→5 这一条是本期新增的出口，给它一个同样「一眼看出原因」的值。
     */
    public static final String RELEASED_BY_RETURN_APPLY = "SYSTEM:RETURN_APPLY";

    /** 与 {@code status} 同一条 UPDATE 自增：状态台账的幂等键 [shard:]{bizId}:{statusSeq} 靠它 */
    private static final String STATUS_SEQ_INCREMENT = "status_seq = status_seq + 1";

    /** 与 {@code status} 同一条 UPDATE 自增：保持 {@code @Version} 乐观锁语义 */
    private static final String VERSION_INCREMENT = "version = version + 1";

    private final OrderService orderService;
    /** 状态台账写入口（与状态 UPDATE 同一事务；开关关闭时它自己 no-op） */
    private final StatusLedgerWriter ledger;
    /**
     * 审核标写入口：只用于<b>离开待审核</b>时结算结论标（见 {@link #settleConclusionTagsIfLeavingAuditPending}）。
     * 这里是 oms → audit 的唯一一条依赖，方向与 {@code AuditExecutor}（audit → oms）相反，
     * 但两者是<b>不同 bean 的不同字段</b>，Spring 不会成环；语义上也不是环：
     * 「状态变了 ⇒ 结论标这一栏清空」是状态写入的收尾，判定链反过来只是状态的一个调用方。
     */
    private final OrderAuditTagService conclusionTags;

    public OrderStatusService(OrderService orderService, StatusLedgerWriter ledger,
                              OrderAuditTagService conclusionTags) {
        this.orderService = orderService;
        this.ledger = ledger;
        this.conclusionTags = conclusionTags;
    }

    // ------------------------------------------------------------------ 出生

    /**
     * 出生：给**尚未落库**的新订单定初始状态（{@code save} 之前调用，不产生 UPDATE、不动 status_seq）。
     *
     * <p>为什么出生也走这里：让「status 这一列谁写过」这件事只有一个答案。建单守卫
     * （只允许 0 待付款 / 1 已付款 / 7 待审核）仍在调用方 —— 那里要按状态说清中文原因，
     * 挪进来就会丢掉「收到的是哪个状态、允许哪三个」这句人话。
     */
    public void init(Order order, int to) {
        if (!BIRTH.contains(to)) {
            throw new IllegalStateException("建单只允许「待付款」「已付款」「待审核」作为初始状态，收到 " + to);
        }
        order.setStatus(to);
    }

    /**
     * 出生台账（{@code from_status = NULL}）：建单这一次也要留一条证据，否则「这张单是怎么来的」
     * 在台账里是空白。
     *
     * <p><b>为什么出生要拆成两步（{@code init()} 在 save 前、本方法在 save 后）</b>：
     * {@code init()} 的作用是「在落库前定初值」（{@code save} 时一次 INSERT 写进状态），
     * 那时自增主键还不存在，而台账的幂等键 {@code {orderId}:0} 必须带真实 id。
     * 所以 save 之后、同一事务内补这一条 —— 失败一起回滚，不会出现「订单建了、出生台账没有」。
     *
     * @param reason 出生原因码（{@link ReasonCodes#ORDER_CREATE}）
     */
    public void recordBirth(Order order, String reason, String reasonDetail) {
        ledger.orderBirth(order, reason, reasonDetail);
    }

    // ------------------------------------------------------------------ 流转

    /** 流转（不带附带列）：返回影响行数，调用方据此 {@code requireAffected} */
    public int change(Order order, int to, String reason, String reasonDetail) {
        return change(order, to, reason, reasonDetail, null);
    }

    /**
     * 流转：在<b>一条</b> UPDATE 里写 {@code status} + {@code status_seq+1} + {@code version+1}。
     *
     * @param order        已从库里读出来的订单（{@code status} 与 {@code version} 必须是读到的真实值）
     * @param to           目标状态
     * @param reason       事件原因（第二步写台账用；本轮只带进来不落库）
     * @param reasonDetail 事件补充说明（同上，可为 null）
     * @param extraSets    需要与状态<b>同一条</b> UPDATE 一起写的其它列（支付方式/支付时间/拆单标记…），可为 null
     * @return 影响行数；0 = 期间被别人改过（version 不匹配）→ 调用方抛 409，与旧写法一致
     */
    public int change(Order order, int to, String reason, String reasonDetail,
                      Consumer<LambdaUpdateWrapper<Order>> extraSets) {
        return change(order, to, reason, reasonDetail, extraSets, null, null);
    }

    /**
     * 流转（带触发单据）：{@code refType/refId} 记「这次流转是被哪张单据触发的」
     * （退货申请审核 → RETURN_APPLY#12、支付 → PAYMENT#…）——
     * 审核时间线（{@code AuditQueryService#timeline}）就是靠它把一单的三段证据串起来的。
     */
    public int change(Order order, int to, String reason, String reasonDetail,
                      Consumer<LambdaUpdateWrapper<Order>> extraSets, String refType, Long refId) {
        Integer from = order.getStatus();
        requireAllowed(order.getId(), from, to, reason);
        Integer version = order.getVersion();

        LambdaUpdateWrapper<Order> update = new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, order.getId())
                .set(Order::getStatus, to)
                .setSql(STATUS_SEQ_INCREMENT)
                .setSql(VERSION_INCREMENT);
        // 乐观锁：与 @Version 生成的 WHERE version = ? 完全一致（version 为 null 时不做条件 ——
        // 旧写法 updateById 在 version 为 null 时同样退化成无条件更新，这里保持同一语义）
        if (version != null) update.eq(Order::getVersion, version);
        if (extraSets != null) extraSets.accept(update);

        int rows = orderService.getBaseMapper().update(null, update);
        if (rows > 0) {
            // 与 updateById 之后实体手里的值保持一致（有的入口紧接着就用实体组装响应）
            order.setStatus(to);
            if (version != null) order.setVersion(version + 1);
            // 台账：**只在 UPDATE 成功（rows > 0）之后**写，且与状态在同一个事务里。
            // rows == 0 说明有人抢先改过（调用方会抛 409 回滚），这时状态根本没变，
            // 写台账就是「记了一笔没发生的事」。
            ledger.orderChanged(order, from, to, reason, reasonDetail, refType, refId);
            // 结论标结算：同样只在 UPDATE 成功之后、同一事务内 —— rows == 0 时状态没变，
            // 「单子还停在待审核」，结算就是「把还成立着的结论标清掉」（写没了证据也丢了依据）。
            settleConclusionTagsIfLeavingAuditPending(order, from, to, reason);
        }
        log.debug("订单 {} 状态 {} → {}（reason={}，影响 {} 行）", order.getId(), from, to, reason, rows);
        return rows;
    }

    /**
     * 离开「待审核(7)」时结算结论标：把这单身上所有 active 的「结论」标置为已解除（行留着当历史）。
     *
     * <h3>为什么挂在这里（而不是各入口）</h3>
     * 见 {@link #STATUS_AUDIT_PENDING}：出口不止两个，逐个入口手工调用迟早早漏，而漏掉的后果是
     * 带着 {@code blocking=1} 的残留结论标离开待审核 —— 期 2 的「下发前第二道保险」会把这类单误拦，
     * 而队列里它已经不是待审核，没有任何入口能解掉那条标。
     *
     * <h3>口径（固定的映射表，改它就要同时改这张表）</h3>
     * <pre>
     * 离开 7 的流转                       reason（台账原因码）    released_by（解除人）
     * ──────────────────────────────────────────────────────────────────────────────
     * 7 → 8 待发货（人工放行）            AUDIT_PASS              SYSTEM:AUDIT_PASS      ← 沿用期 1.6 常量
     * 7 → 8 待发货（自动审核引擎过审）    AUDIT_AUTO_PASS         SYSTEM:SUPERSEDED      ← 引擎落新结论 ⇒ 旧结论「被取代」
     * 7 → 4 已关闭（审核不通过）          AUDIT_REJECT            SYSTEM:AUDIT_REJECT    ← 沿用期 1.6 常量
     * 7 → 5 退款中（登记退货申请）        RETURN_APPLY            SYSTEM:RETURN_APPLY    ← 本期补上的那条出口
     * 7 → 2/3/8/9（包裹聚合推导）         其它                     SYSTEM:STATUS_&lt;to&gt;     ← 兜底：不认识的出口也要结算
     * ──────────────────────────────────────────────────────────────────────────────
     * 7 → 7（聚合幂等重写同一个值）       ——                      不结算：状态没离开待审核，结论仍然成立
     * </pre>
     *
     * <p>为什么 7→8 自动过审仍写 {@code SYSTEM:SUPERSEDED}：自动审核是一轮<b>新判定</b>，
     * 上一轮的结论标是被这一轮的结论取代的（{@code AuditExecutor} 原来就是这么写的），口径不能变。
     * 换句话说 released_by 回答「它为什么不成立了」，两条 7→8 的答案本来就不同：
     * 人工放行是「人放行了」，自动过审是「引擎给出了新结论」。
     *
     * <p>为什么兜底不省：聚合推导（{@code DeliveryFlowService#recompute}）也能把 7 推成 2/3/9
     * （包裹事实变了），那同样是「离开待审核」。今天它少见，但正因为它少见，
     * 将来谁再往 7 的出口上加一条时，「忘了结算」不该是一次静默的回归 —— 兜底值
     * {@code SYSTEM:STATUS_<to>} 至少让库里的解除痕迹说得出「是因为流转到了哪个状态」。
     *
     * <p>进入 7（0→7 支付送审、5→7 删申请交回聚合、8/9→7 包裹全取消退回）<b>不需要任何处理</b>：
     * 进 7 之前那次「离开 7」已经结算干净，而 7 的出生（建单/渠道导单）本来就没有结论标；
     * 交回重算后的结论标由下一次判定重新落（{@code AuditExecutor#settleConclusionTag}）。
     */
    private void settleConclusionTagsIfLeavingAuditPending(Order order, Integer from, int to, String reason) {
        if (from == null || from != STATUS_AUDIT_PENDING || to == STATUS_AUDIT_PENDING) return;
        String releasedBy = conclusionReleasedBy(to, reason);
        int released = conclusionTags.releaseAllConclusionTags(order.getId(), releasedBy);
        log.info("订单 {} 离开待审核（7→{}，reason={}），结论标结算 {} 条（released_by={}）",
                order.getId(), to, reason, released, releasedBy);
    }

    /** 离开待审核时 {@code released_by} 的取值（映射表见 {@link #settleConclusionTagsIfLeavingAuditPending}） */
    private static String conclusionReleasedBy(int to, String reason) {
        if (to == 8) {
            if (ReasonCodes.AUDIT_PASS.equals(reason)) return OrderAuditTagService.RELEASED_BY_AUDIT_PASS;
            if (ReasonCodes.AUDIT_AUTO_PASS.equals(reason)) return OrderAuditTagService.RELEASED_BY_SUPERSEDED;
        }
        if (to == 4 && ReasonCodes.AUDIT_REJECT.equals(reason)) return OrderAuditTagService.RELEASED_BY_AUDIT_REJECT;
        if (to == 5 && ReasonCodes.RETURN_APPLY.equals(reason)) return RELEASED_BY_RETURN_APPLY;
        return "SYSTEM:STATUS_" + to;
    }

    /**
     * 合法性判定：只拦「现网不存在的箭头」，不改任何已有行为。
     *
     * <p>抛 {@link IllegalStateException}：走到这里说明<b>代码</b>写错了（某个入口少了一道守卫），
     * 不是用户操作错误 —— 用户能触发的非法流转，早已在各入口的 {@code if} 里被中文文案挡下。
     */
    private void requireAllowed(Long orderId, Integer from, int to, String reason) {
        if (from == null) {
            throw new IllegalStateException("订单 " + orderId + " 没有当前状态，无法流转到 " + to);
        }
        // 聚合推导：父单状态是子单的推导值，任何非终局状态都可以被推导成 2/3/7/8/9（含重复写成同一个值）
        if (DERIVED.contains(to) && !TERMINAL.contains(from)) return;
        Set<Integer> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException(
                    "非法的订单状态流转：" + from + " → " + to + "（reason=" + reason + "，订单 " + orderId + "）");
        }
    }
}
