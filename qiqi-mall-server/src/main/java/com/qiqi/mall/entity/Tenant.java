package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tenant")
public class Tenant {

    /** tenant_id 全局根源 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公司名 */
    private String name;

    /** 1启用 0禁用 */
    private Integer status;
}
