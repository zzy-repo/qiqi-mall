package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 策略版本快照（{@code audit_policy_version}，append-only）。 */
@Data
@TableName("audit_policy_version")
public class AuditPolicyVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long policyId;

    private Integer versionNo;

    private String snapshotJson;

    private LocalDateTime createdAt;
}
