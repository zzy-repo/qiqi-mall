package com.qiqi.mall.oms.dto;

import lombok.Data;

/** 待下发包裹「改量」的一行：把某条发货单明细的数量改成 quantity */
@Data
public class DeliveryItemQty {

    /** delivery_item.id（不是 order_item.id） */
    private Long deliveryItemId;

    /** 目标数量（1..原数量） */
    private Integer quantity;
}
