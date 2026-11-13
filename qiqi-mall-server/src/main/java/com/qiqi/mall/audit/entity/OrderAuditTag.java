package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单审核标记（{@code order_audit_tag}）：冻结/挂起<b>用标表达，不动 order.status</b>（决策 J1）。
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

    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime releasedAt;

    private String releasedBy;
}
