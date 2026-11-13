package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审核策略主体（{@code audit_policy}）。type 用字符串存取，枚举化在服务层做（DDL 是 ENUM 列）。 */
@Data
@TableName("audit_policy")
public class AuditPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** AUTO / BLOCK / EXCEPTION */
    private String type;

    /** 同类型内升序执行 */
    private Integer priority;

    private String name;

    /** 1 启用 0 停用 */
    private Integer enabled;

    /** 每次改条件 +1，决策行快照它 */
    private Integer versionNo;

    /** EXCEPTION 的冻结动作 {"tag":"FROZEN","reason":"..."}；AUTO/BLOCK 必须 NULL（DDL CHECK） */
    private String actionJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
