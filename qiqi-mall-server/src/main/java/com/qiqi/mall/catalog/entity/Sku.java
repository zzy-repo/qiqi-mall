package com.qiqi.mall.catalog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("sku")
public class Sku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private BigDecimal price;

    private Integer stock;

    /** SKU图URL（指向 MinIO） */
    private String pic;

    /** 成本价（当前值）；0/null = 未维护成本 —— 自动审核会因此标 COST_MISSING 转人工 */
    private BigDecimal costPrice;

    /**
     * 乐观锁版本号（MyBatis-Plus @Version）。
     * updateById 时会生成 {@code SET version = version + 1 WHERE id = ? AND version = ?}；
     * 影响行数 0 = 期间被别人改过 → 接口回 code=409，不静默覆盖。
     */
    @Version
    @TableField("version")
    private Integer version;
}
