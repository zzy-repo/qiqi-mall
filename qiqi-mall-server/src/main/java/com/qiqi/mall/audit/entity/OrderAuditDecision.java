package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 订单审核决策台账（{@code order_audit_decision}）：每单每轮一行，只追加。 */
@Data
@TableName("order_audit_decision")
public class OrderAuditDecision {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long runId;

    private Integer roundNo;

    /** AUTO_PASS / MANUAL / BLOCKED / FROZEN / NO_MATCH / SKIPPED */
    private String result;

    private Long matchedPolicyId;

    private String matchedPolicyName;

    private Integer matchedPolicyVersionNo;

    private String matchedConditionJson;

    private String excludedByJson;

    private String missReasonJson;

    private Long wouldBeAutoPolicyId;

    private String wouldBeAutoPolicyName;

    /** STOCK_SHORT / COST_MISSING */
    private String downgradeReason;

    private String evidenceJson;

    private Integer orderStatusSeq;

    private String operatorType;

    private Long operatorId;

    private String operatorName;

    /** {orderId}:{roundNo} */
    private String idemKey;

    private LocalDateTime createdAt;
}
