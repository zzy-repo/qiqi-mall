package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("tenant_plan")
public class TenantPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long planId;

    /** 到期时间 */
    private LocalDate expireAt;
}
