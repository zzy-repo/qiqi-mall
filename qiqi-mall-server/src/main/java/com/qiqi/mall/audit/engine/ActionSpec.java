package com.qiqi.mall.audit.engine;

/**
 * EXCEPTION 策略命中的冻结动作（{@code audit_policy.action_json} 解析结果）。
 *
 * <p>冻结<b>不是新状态码</b>：订单还停在 7 待审核，只是多了一条 active 的 {@code order_audit_tag}
 * （完整理由见 {@link com.qiqi.mall.audit.service.OrderAuditTagService} 的类注释）。
 * 既然不改状态，动作里就只剩两件事：打什么标、为什么。
 *
 * <h3>{@code reasonCode} 是后加的，{@code reason} 没有被取代</h3>
 * <ul>
 *   <li>{@code reasonCode}：冻结原因字典的稳定键（{@code audit_freeze_reason.code}）——
 *       统计与 P1 的「不再以此原因冻结该单」都以它为键，所以策略里**应当**配它；</li>
 *   <li>{@code reason}：人话（会写进 {@code order_audit_tag.reason} 作为"冻结那一刻的快照"，
 *       也进审计摘要）。老策略只配了它、没配 code，所以它必须留着 ——
 *       这种策略在执行时按人话关键词反查出 code（见 {@code AuditExecutor}），不会因为没升级配置就丢原因。</li>
 * </ul>
 *
 * @param tagCode    标码（FROZEN / FROZEN_RETURN_APPLY …；判定链第 2 步按 {@code FROZEN} 前缀识别）
 * @param reason     冻结原因的人话（写进 tag.reason 与 admin_audit_log.summary），可为 null
 * @param reasonCode 冻结原因的稳定键（{@code audit_freeze_reason.code}），可为 null（老配置）
 */
public record ActionSpec(String tagCode, String reason, String reasonCode) {

    public static final String DEFAULT_TAG = "FROZEN";

    /** 只有人话（老配置 / 测试造数）：code 留空，执行时按人话反查 */
    public static ActionSpec freeze(String reason) {
        return new ActionSpec(DEFAULT_TAG, reason, null);
    }

    /** 人话 + 稳定键（新配置：策略页下拉选的就是 code） */
    public static ActionSpec freeze(String reason, String reasonCode) {
        return new ActionSpec(DEFAULT_TAG, reason, reasonCode);
    }
}
