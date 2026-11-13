package com.qiqi.mall.audit.engine;

/**
 * EXCEPTION 策略命中的冻结动作（{@code audit_policy.action_json} 解析结果）。
 *
 * <p>冻结<b>不是新状态码</b>（决策 J1）：订单还是 7，只是多了一条 active 的
 * {@code order_audit_tag}。所以动作只需要两件事：打什么标、为什么。
 *
 * @param tagCode 标码（FROZEN / FROZEN_RETURN_APPLY …；判定链第 2 步按 {@code FROZEN} 前缀识别）
 * @param reason  冻结原因（写进 tag.reason 与 admin_audit_log.summary）
 */
public record ActionSpec(String tagCode, String reason) {

    public static final String DEFAULT_TAG = "FROZEN";

    public static ActionSpec freeze(String reason) {
        return new ActionSpec(DEFAULT_TAG, reason);
    }
}
