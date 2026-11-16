package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 策略条件（{@code audit_policy_condition}）：<b>一条条件一行</b>，不用 JSON 列。
 *
 * <p>这样存的三个原因，都是从下面这段代码里读得出来的：
 * <ol>
 *   <li>判定要有确定顺序：{@code AuditPolicyConditionMapper} 按 {@code seq ASC, id ASC} 取条件，
 *       「先比哪一条」由行的顺序直接决定；折进一个 JSON 列就得在 Java 里自己排一遍；</li>
 *   <li>全局排除行（{@code scope = ALL_AUTO}）不属于任何一条策略，{@code policy_id} 为 NULL ——
 *       JSON 列挂在某条策略上，那条策略被删时它就会被一起带走；</li>
 *   <li>值按类型分列存（{@code value_num} / {@code value_text} / {@code value_json} + {@code value_type}），
 *       一条条件只占其中一列，比较时不用先解析字符串。</li>
 * </ol>
 * 代价是改策略要整套重写（先按 scope 删、再逐行插）—— 条件本来就是整体语义，没有「只改其中一条」的说法。
 */
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
