package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tenant_user")
public class TenantUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属租户 */
    private Long tenantId;

    private String username;

    private String password;
}
