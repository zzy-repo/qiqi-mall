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
 * wouldBeConditions          → evidence_json.would_be_conditions（列没有专门的，放证据里）
 * downgradeReason            → downgrade_reason
 * reasonText                 → evidence_json.reason（人话原因，含具体数值差）
 * evidence                   → evidence_json
 * </pre>
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

    public List<Map<String, Object>> matchedConditionMaps() {
        return matchedConditions.stream().map(ConditionTrace::toMap).toList();
    }

    public List<Map<String, Object>> excludedByMaps() {
        return excludedBy.stream().map(ConditionTrace::toMap).toList();
    }

    public List<Map<String, Object>> missReasonMaps() {
        return missReasons.stream().map(PolicyMiss::toMap).toList();
    }

    /** 证据副本（引擎产出后不再改） */
    public Map<String, Object> evidenceCopy() {
        return new LinkedHashMap<>(evidence);
    }
}
