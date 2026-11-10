package com.qiqi.mall.catalog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sku_spec")
public class SkuSpec {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属SKU */
    private Long skuId;

    /** 指向属性定义 */
    private Long attributeId;

    /** 属性值，如 黑色 */
    private String value;
}
