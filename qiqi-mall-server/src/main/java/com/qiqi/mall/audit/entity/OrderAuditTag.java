package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单审核标记（{@code order_audit_tag}）：冻结/挂起<b>用标表达，不动 order.status</b> ——
 * 冻结不是一种订单状态：订单仍停在待审核(7)，只是多了一条 active 的审核标记。
 * 做成状态码会让状态机多出一个「既能继续审核、又像已结束」的中间态，
 * 所以它落在标记表里，与状态机正交。
 *
 * <p>{@code active_tag} 是数据库的**虚拟生成列**（active=1 时 = {@code orderId:tagCode}，否则 NULL），
 * 唯一键 {@code uk_active} 建在它上面 ⇒ 「同一单同一标只允许一条 active」由 DB 兜底，
 * 而历史（active=0）行可以随便多。本实体只读它、永不写它（insert/update 策略 NEVER）。
 */
@Data
@TableName("order_audit_tag")
public class OrderAuditTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** FROZEN / STOCK_SHORT / COST_MISSING … */
    private String tagCode;

    /** 生成列：只读（写它会报错，见 @TableField 的 NEVER 策略） */
    @TableField(value = "active_tag", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeTag;

    /** 1 生效 0 已解除 */
    private Integer active;

    /**
     * 冻结原因字典 ID（{@code audit_freeze_reason.id}，外键）。
     *
     * <p>只有 {@code FROZEN} 类标才有值：{@code STOCK_SHORT} / {@code COST_MISSING} 的 reason 是
     * 「留人工的原因」长句，不是冻结原因，且已有 {@code downgrade_reason} 这个结构化字段表达。
     */
    private Long reasonId;

    /**
     * 原因码快照（= {@code audit_freeze_reason.code}）。
     *
     * <p>为什么有外键还要存一份码：{@code reason_id} 是自增代理键，**跨库不稳定**；
     * 而「这个月为什么冻了 50 单」要的是一条不用 join、不含字符串比较、改名也不动的聚合键。
     * 码是稳定键（永不修改），所以统计 / P1 豁免 / 导出回放都以它为准。
     */
    private String reasonCode;

    /**
     * 冻结那一刻的人话快照（**不是字典名的副本**，见 {@link #reasonId}）：
     * 人工冻结 = 运营填的备注，没填则写字典中文名；自动冻结 = 策略里配的原话，没配则写字典中文名。
     * 历史行保留迁移前的原文（{@code 111} / {@code 1231} …），一个字符都不改。
     */
    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime releasedAt;

    private String releasedBy;
}
