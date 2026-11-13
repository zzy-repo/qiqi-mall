package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.order.entity.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 订单状态写入的<b>唯一入口</b>（P2 第一步：收敛写点，行为不变）。
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
 * {@code version} 在同一句 UPDATE 里改完，第二步加台账 INSERT 只改这一个类。
 *
 * <h3>这一步**不写台账**</h3>
 * 本轮只收敛入口、不动台账（台账 INSERT 是第二步）。所以这里 {@code reason}/{@code reasonDetail}
 * 只被带进来、不落库 —— 它们就是第二步写 {@code order_status_ledger} 时那两个列的值，
 * 先让每个调用点把「这次是什么事件」说清楚，第二步就不用再回头猜。
 *
 * <h3>与旧写法的等价性（逐条对齐，行为不变是硬门槛）</h3>
 * <ol>
 *   <li><b>一条 UPDATE</b>：{@code SET status=?, status_seq = status_seq + 1, version = version + 1
 *       WHERE id = ? AND version = ?} —— 旧写法 {@code updateById} 由 MyBatis-Plus 的
 *       {@code @Version} 生成的条件与自增完全一致，这里只是多带了 {@code status_seq}；</li>
 *   <li><b>条件按 version，不按 status</b>：旧写法的并发闸门就是 version（{@code WHERE version = ?}），
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

    // ---- 事件原因（reason 列口径）：第二步写台账时原样落到 order_status_ledger.reason ----
    public static final String REASON_PAY = "ORDER_PAY";
    public static final String REASON_AUDIT_PASS = "AUDIT_PASS";
    public static final String REASON_AUDIT_REJECT = "AUDIT_REJECT";
    public static final String REASON_SHIP = "ORDER_SHIP";
    public static final String REASON_CONFIRM = "ORDER_CONFIRM";
    public static final String REASON_RETURN_APPLY = "RETURN_APPLY";
    public static final String REASON_RETURN_APPROVE = "RETURN_APPROVE";
    public static final String REASON_RETURN_REJECT = "RETURN_REJECT";
    public static final String REASON_AGGREGATE = "DELIVERY_AGGREGATE";

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
            // 0 待付款：支付 → 7 待审核（PortalOrderController#pay；口径②「支付即送审」）
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
     * 聚合推导值：父单状态是<b>子单的推导值</b>（口径②：子单是事实源）。
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

    /** 与 {@code status} 同一条 UPDATE 自增：状态台账的幂等键 [shard:]{bizId}:{statusSeq} 靠它 */
    private static final String STATUS_SEQ_INCREMENT = "status_seq = status_seq + 1";

    /** 与 {@code status} 同一条 UPDATE 自增：保持 {@code @Version} 乐观锁语义 */
    private static final String VERSION_INCREMENT = "version = version + 1";

    private final OrderService orderService;

    public OrderStatusService(OrderService orderService) {
        this.orderService = orderService;
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
        }
        log.debug("订单 {} 状态 {} → {}（reason={}，影响 {} 行）", order.getId(), from, to, reason, rows);
        return rows;
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
