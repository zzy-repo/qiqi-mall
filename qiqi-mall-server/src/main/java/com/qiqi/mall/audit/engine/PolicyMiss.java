package com.qiqi.mall.audit.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条策略「差在哪」：未命中（或命中排除）时的逐条说明。
 *
 * <h3>为什么不是「一串人话」而是「逐条件四元组 + 人话」</h3>
 * 只给 {@code reasons: ["毛利 -12.00 ≤ 0"]} 时，前端「差在哪」抽屉拿不到
 * {@code {expected, actual, satisfied, evaluated}}，只能靠 AND 短路规则**自己推导**哪几条没求值 ——
 * 那是把引擎的判定规则复制到前端，两边一旦不一致，页面就会给出与事实相反的解释。
 * 所以口径留在这里：{@link #conditions} 是引擎**本轮的求值留痕**（不重算、不猜），
 * {@link #reasons} 保留原样只作摘要/兼容。
 *
 * <p>落 {@code miss_reason_json} 的形状（与 {@code matched_condition_json} / {@code excluded_by_json}
 * 同一形状：都是「策略组 → 条件数组」）：
 * <pre>
 * [{"policyId":2,"policyName":"淘宝-标准单","reasons":["商品种类数 6 ≤ 5"],
 *   "conditions":[{"seq":1,"fieldCode":"CHANNEL",...,"satisfied":true,"evaluated":true,"reason":"..."},
 *                 {"seq":2,...,"satisfied":true,"evaluated":true},
 *                 {"seq":3,"fieldCode":"ITEM_KIND_COUNT",...,"satisfied":false,"evaluated":true}]}]
 * </pre>
 *
 * @param policyId   策略 ID
 * @param policyName 策略名
 * @param reasons    逐条人话（每条都是 expected vs actual；摘要用，保留旧口径）
 * @param conditions 逐条件求值留痕（含 satisfied / evaluated 三态依据，见 {@link ConditionTrace}）
 */
public record PolicyMiss(Long policyId, String policyName, List<String> reasons, List<ConditionTrace> conditions) {

    public PolicyMiss {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policyId", policyId);
        m.put("policyName", policyName);
        m.put("reasons", reasons);
        m.put("conditions", conditions.stream().map(ConditionTrace::toMap).toList());
        // 兼容键：旧消费方（历史 SQL 断言 / 旧版前端）按 snake_case 读
        m.put("policy_id", policyId);
        m.put("policy_name", policyName);
        return m;
    }
}
