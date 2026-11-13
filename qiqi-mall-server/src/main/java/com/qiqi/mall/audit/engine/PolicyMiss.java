package com.qiqi.mall.audit.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条策略「差在哪」：未命中（或命中排除）时的逐条说明。
 *
 * @param policyId   策略 ID
 * @param policyName 策略名
 * @param reasons    逐条人话（每条都是 expected vs actual）
 */
public record PolicyMiss(Long policyId, String policyName, List<String> reasons) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy_id", policyId);
        m.put("policy_name", policyName);
        m.put("reasons", reasons);
        return m;
    }
}
