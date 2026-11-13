package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 策略条件（{@code audit_policy_condition}）：一条条件一行（决策 J3：不用 JSON 列）。 */
@Data
@TableName("audit_policy_condition")
public class AuditPolicyCondition {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属策略；scope=ALL_AUTO（全局排除）时必须为 NULL */
    private Long policyId;

    private Integer seq;

    /** MATCH / EXCLUDE */
    private String kind;

    /** POLICY / ALL_AUTO */
    private String scope;

    private String fieldCode;

    private String opCode;

    /** NUM / TEXT / BOOL / JSON */
    private String valueType;

    private BigDecimal valueNum;

    private String valueText;

    private String valueJson;
}
