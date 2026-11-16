package com.qiqi.mall.audit.dto;

import java.util.List;

/**
 * 策略入参。
 *
 * @param type             AUTO / BLOCK / EXCEPTION
 * @param priority         同类型内升序（默认 100）
 * @param name             策略名（同类型内唯一）
 * @param enabled          默认 true
 * @param actionTag        EXCEPTION 的冻结标码（默认 FROZEN；AUTO/BLOCK 传了会被忽略）
 * @param actionReason     EXCEPTION 的冻结原因（人话；默认用策略名，写进 tag.reason 与审计摘要）
 * @param actionReasonCode EXCEPTION 的冻结原因**码**（{@code audit_freeze_reason.code}，可为 null）。
 *                         这是稳定键：统计与 P1 的「不再以此原因冻结该单」都以它为准。
 *                         老调用方只传 actionReason 也能工作（执行时按人话关键词反查）。
 * @param conditions       本策略的条件（kind MATCH/EXCLUDE，scope 一律 POLICY）
 * @param globalExclusions 全局排除（scope=ALL_AUTO）。**null = 不动全局排除**；非 null = 整套替换。
 *                         为什么用 null 区分：全局排除没有归属策略，若每次保存策略都重写它，
 *                         改策略 B 会顺手把在策略 A 里配的全局排除抹掉。
 */
public record PolicyRequest(
        String type,
        Integer priority,
        String name,
        Boolean enabled,
        String actionTag,
        String actionReason,
        String actionReasonCode,
        List<ConditionRequest> conditions,
        List<ConditionRequest> globalExclusions) {

    /**
     * 老签名（没有原因码）—— 只为不让既有调用方改一行。
     *
     * <p>{@link #actionReasonCode()} 是 P2 才加的第三个冻结动作字段。record 加一个组件就换了构造器
     * 元数，而 {@code new PolicyRequest(...)} 在集成测试里有十几个点位（造种子策略）。
     * 那些地方配的本来就只有人话，语义上等价于"没有原因码"，所以给个默认 null 的构造器就够了 ——
     * 比让十几处测试跟着改更有价值：**测试写的应该是业务事实，不是构造器元数**。
     */
    public PolicyRequest(String type, Integer priority, String name, Boolean enabled,
                         String actionTag, String actionReason,
                         List<ConditionRequest> conditions, List<ConditionRequest> globalExclusions) {
        this(type, priority, name, enabled, actionTag, actionReason, null, conditions, globalExclusions);
    }
}
