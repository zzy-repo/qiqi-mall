package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("attribute")
public class Attribute {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属商品类型ID */
    private Long typeId;

    private String name;

    /** 是否可筛选：0 否 1 是 */
    private Integer isFilter;
}
