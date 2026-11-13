package com.qiqi.mall.audit.dto;

/** 一轮 run 里某张单的结论摘要（run 接口的返回值用）。 */
public record DecisionRowDto(
        Long orderId,
        String orderSn,
        Integer roundNo,
        String result,
        String matchedPolicyName,
        String wouldBeAutoPolicyName,
        String downgradeReason,
        String reason) {
}
