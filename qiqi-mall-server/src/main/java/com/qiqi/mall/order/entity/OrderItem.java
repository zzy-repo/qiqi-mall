package com.qiqi.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 商品ID（SPU快照） */
    private Long productId;

    /** SKU ID（快照） */
    private Long productSkuId;

    /** 下单时商品名（快照） */
    private String productName;

    /** 成交价（快照） */
    private BigDecimal productPrice;

    private Integer productQuantity;
}
