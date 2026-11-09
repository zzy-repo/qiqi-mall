package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cart")
public class Cart {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** 商品ID（SPU） */
    private Long productId;

    /** 具体规格SKU */
    private Long productSkuId;

    private Integer quantity;

    private LocalDateTime createTime;
}
