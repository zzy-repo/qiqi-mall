package com.qiqi.mall.audit.engine;

import java.util.List;

/**
 * 一条策略（引擎视角，只读）：{@code audit_policy} + 它的条件行。
 *
 * @param id         策略 ID
 * @param type       AUTO / BLOCK / EXCEPTION
 * @param priority   同类型内升序
 * @param name       策略名（决策行会快照）
 * @param versionNo  版本号（决策行会快照：改过条件后仍能回答「当时按哪版判的」）
 * @param enabled    是否启用
 * @param action     EXCEPTION 的冻结动作（其余类型为 null）
 * @param conditions 条件行（含 ALL_AUTO 全局排除行；引擎按 scope 分别处理）
 */
public record PolicyView(
        Long id,
        PolicyType type,
        int priority,
        String name,
        int versionNo,
        boolean enabled,
        ActionSpec action,
        List<ConditionView> conditions) {

    public static PolicyView of(Long id, PolicyType type, int priority, String name, int versionNo,
                                List<ConditionView> conditions) {
        return new PolicyView(id, type, priority, name, versionNo, true, null, conditions);
    }

    /** 本策略自己的 MATCH 条件（按 seq 升序） */
    public List<ConditionView> matchConditions() {
        return conditions.stream()
                .filter(c -> c.kind() == ConditionKind.MATCH && c.scope() == ConditionScope.POLICY)
                .sorted(java.util.Comparator.comparingInt(c -> c.seq() == null ? 0 : c.seq()))
                .toList();
    }

    /** 本策略的 POLICY 级排除条件 */
    public List<ConditionView> policyExclusions() {
        return conditions.stream()
                .filter(c -> c.kind() == ConditionKind.EXCLUDE && c.scope() == ConditionScope.POLICY)
                .sorted(java.util.Comparator.comparingInt(c -> c.seq() == null ? 0 : c.seq()))
                .toList();
    }
}
