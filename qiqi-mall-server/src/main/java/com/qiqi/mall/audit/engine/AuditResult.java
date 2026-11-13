package com.qiqi.mall.audit.engine;

/**
 * 一轮判定的结论（与 {@code order_audit_decision.result} 的 ENUM 逐字一致）。
 *
 * <p>六种而不是五种：{@link #SKIPPED} 表达「这单进了本轮范围但没参与判定」
 * （状态已不是 7，或已被冻结）。它<b>不</b>计入 {@code audit_run} 的五类计数，
 * 但仍要留一行决策 —— 「这单为什么没被审」本身就是要回答的问题。
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
    /** 未参与本轮（状态不是 7 / 已冻结） */
    SKIPPED
}
