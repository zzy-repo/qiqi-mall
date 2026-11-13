package com.qiqi.mall.audit.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎输出（<b>纯数据</b>，不含任何库操作）：执行层照它落决策、改状态、打标、写审计。
 *
 * <p>字段与 {@code order_audit_decision} 的列一一对应（列名 → 记录字段）：
 * <pre>
 * result                     → result
 * matchedPolicy.id/name/versionNo → matched_policy_id / matched_policy_name / matched_policy_version_no
 * matchedConditions          → matched_condition_json
 * excludedBy                 → excluded_by_json
 * missReasons                → miss_reason_json
 * wouldBePolicy              → would_be_auto_policy_id / would_be_auto_policy_name
 * wouldBeConditions          → evidence_json.would_be.conditions（列没有专门的，放证据里）
 * downgradeReason            → downgrade_reason
 * reasonText                 → evidence_json.reason（人话原因，含具体数值差）
 * evidence                   → evidence_json
 * </pre>
 *
 * <h3>三列 JSON 同一形状</h3>
 * {@code matched_condition_json} / {@code excluded_by_json} / {@code miss_reason_json} 都是
 * <b>「策略组 → 条件数组」</b>：{@code [{policyId, policyName, reasons:[...], conditions:[{seq, fieldCode,
 * fieldLabel, opCode, expected, actual, satisfied, evaluated, reason}]}]}。
 * 三者差异只在「装的是哪一类条件」（命中 / 被排除 / 没命中），形状一致前端才能用一套渲染 ——
 * 一块 JSON 一个形状，页面就得为每种结论写一套解析，最后必然有一套跟不上引擎。
 *
 * @param result            结论
 * @param matchedPolicy     命中/最相关的策略（SKIPPED/NO_MATCH 为 null）
 * @param matchedConditions 命中的 MATCH 条件留痕
 * @param excludedBy        命中的排除条件留痕（POLICY ∪ ALL_AUTO）
 * @param missReasons       逐条策略「差在哪」
 * @param wouldBePolicy     只读回填：若没被拦截/冻结/排除，会命中哪条 AUTO
 * @param wouldBeConditions 只读回填的条件留痕
 * @param downgradeReason   预检降级原因：STOCK_SHORT / COST_MISSING
 * @param reasonText        人话原因（含具体数值差）
 * @param evidence          证据（库存/成本/冻结动作/回填细节）
 */
public record AuditVerdict(
        AuditResult result,
        PolicyView matchedPolicy,
        List<ConditionTrace> matchedConditions,
        List<ConditionTrace> excludedBy,
        List<PolicyMiss> missReasons,
        PolicyView wouldBePolicy,
        List<ConditionTrace> wouldBeConditions,
        String downgradeReason,
        String reasonText,
        Map<String, Object> evidence) {

    /** 降级原因码（写进 {@code downgrade_reason VARCHAR(32)}） */
    public static final String DOWNGRADE_STOCK_SHORT = "STOCK_SHORT";
    public static final String DOWNGRADE_COST_MISSING = "COST_MISSING";

    /** 该结论是否会推进状态（只有 AUTO_PASS 会） */
    public boolean advancesStatus() {
        return result == AuditResult.AUTO_PASS;
    }

    /** 是否需要人工介入（人工队列三种：MANUAL / BLOCKED / FROZEN；NO_MATCH 见方法说明） */
    public boolean needsHuman() {
        return result == AuditResult.MANUAL || result == AuditResult.BLOCKED || result == AuditResult.FROZEN;
    }

    /** {@code matched_condition_json}：命中策略的逐条件留痕（策略组形状，与 miss 一致） */
    public List<Map<String, Object>> matchedConditionMaps() {
        return group(matchedPolicy, matchedConditions, List.of());
    }

    /** {@code excluded_by_json}：把单成立的排除条件挂到「被哪条策略排除」的组里 */
    public List<Map<String, Object>> excludedByMaps() {
        List<String> reasons = excludedBy.stream().map(t -> "被排除：" + t.text()).toList();
        return group(matchedPolicy, excludedBy, reasons);
    }

    public List<Map<String, Object>> missReasonMaps() {
        return missReasons.stream().map(PolicyMiss::toMap).toList();
    }

    /** 证据副本（引擎产出后不再改） */
    public Map<String, Object> evidenceCopy() {
        return new LinkedHashMap<>(evidence);
    }

    /**
     * 单个策略组（三列 JSON 的公共形状）。{@code policy} 为 null（如 NO_MATCH 的全局排除）时
     * id/name 留 null，前端按「不属于任何策略」展示 —— 与 DB 里 {@code policy_id IS NULL} 的
     * 全局排除行同口径。
     */
    private static List<Map<String, Object>> group(PolicyView policy, List<ConditionTrace> conditions,
                                                   List<String> reasons) {
        if (conditions == null || conditions.isEmpty()) return List.of();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("policyId", policy == null ? null : policy.id());
        g.put("policyName", policy == null ? null : policy.name());
        g.put("reasons", reasons);
        g.put("conditions", conditions.stream().map(ConditionTrace::toMap).toList());
        return List.of(g);
    }
}
