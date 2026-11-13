package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次自动审核 run（{@code audit_run}）。计数在建行时写 0、跑完回写一次。 */
@Data
@TableName("audit_run")
public class AuditRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** MANUAL / SCHEDULE */
    private String triggerType;

    /** 仅 SCHEDULE：{trigger}:{yyyyMMddHHmm}；MANUAL 一律 NULL */
    private String idemKey;

    private Integer total;

    private Integer autoPass;

    private Integer manual;

    private Integer blocked;

    private Integer frozen;

    private Integer noMatch;

    private LocalDateTime createdAt;
}
