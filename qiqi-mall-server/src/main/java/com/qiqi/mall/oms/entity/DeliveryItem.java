package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 发货单明细（order_item_id 用于溯源拆分来源） */
@Data
@TableName("delivery_item")
public class DeliveryItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deliveryId;

    private Long orderItemId;

    private Long productSkuId;

    /** 本发货单承担的数量 */
    private Integer quantity;
}
