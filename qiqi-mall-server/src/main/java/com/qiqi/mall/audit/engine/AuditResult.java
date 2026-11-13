package com.qiqi.mall.audit.engine;

/**
 * 一轮判定的结论（与 {@code order_audit_decision.result} 的 ENUM 逐字一致）。
 *
 * <h3>六种枚举值，对外只有五类计数</h3>
 * 前五种（{@link #AUTO_PASS} / {@link #MANUAL} / {@link #BLOCKED} / {@link #FROZEN} /
 * {@link #NO_MATCH}）是 {@code audit_run} 的五类计数，<b>一轮里每条决策行恰好属于其中一类</b>：
 * {@code 五类之和 == 本轮决策行数}，而 {@code audit_run.total == 五类之和 + skipped}
 * （total 的口径见 {@code RunResultDto}）。
 *
 * <p>{@link #SKIPPED} 表达「这单进了本轮候选范围但没参与判定」（状态已不是 7，或已被冻结）。
 * 它<b>不写决策行</b>、也<b>不进五类计数</b> —— 只体现为 run 的 {@code skipped} 计数。
 * 为什么不留行：候选范围故意包含「有 active FROZEN 标」的单，冻结单每跑一轮就多一行
 * 「什么都没发生」的 SKIPPED，{@code round_no} 一直涨、证据表被无意义的行撑大，
 * 而「它为什么没被审」这件事本来就有 {@code order_audit_tag} 那行标在回答。
 *
 * <h3>{@link #MANUAL} 不是一种来路，是一类结论</h3>
 * 转人工有三支来路（被排除条件挡掉 / 库存不足降级 / 成本价缺失降级），它们在
 * {@code result} 上<b>都是 MANUAL</b>，靠 {@code downgrade_reason} 区分：
 * <ul>
 *   <li>被排除 → {@code downgrade_reason} 为 {@code NULL}（不是降级，是本来就得人看）；</li>
 *   <li>库存不足 → {@code STOCK_SHORT}；</li>
 *   <li>成本价缺失 → {@code COST_MISSING}。</li>
 * </ul>
 * 不给 STOCK_SHORT 单独立一个枚举值：它是「为什么留人工」的一个原因，不是一种新结论；
 * 单独立类会让「留人工」这类计数分散在两个值上，对外永远数不平。
 */
public enum AuditResult {
    /** 自动过审（执行层随后 7 → 8） */
    AUTO_PASS,
    /** 转人工：被排除 / 预检降级（库存不足、成本缺失） */
    MANUAL,
    /** 被 BLOCK 策略前置拦截 */
    BLOCKED,
    /** 被 EXCEPTION 策略命中并冻结 */
    FROZEN,
    /** 没有任何 AUTO 策略命中 */
    NO_MATCH,
    /** 未参与本轮（状态不是 7 / 已冻结）：不写决策行，只计入 run 的 skipped */
    SKIPPED
}
