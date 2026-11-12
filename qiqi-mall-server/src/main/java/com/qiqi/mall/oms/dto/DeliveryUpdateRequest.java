package com.qiqi.mall.oms.dto;

import lombok.Data;

import java.util.List;

/**
 * 待下发包裹编辑请求（PUT /admin/deliveries/{id}）
 * 两个字段都可选：只改仓、只改量、或两者一起改。
 */
@Data
public class DeliveryUpdateRequest {

    /** 新的发货仓；null 表示不改 */
    private Long warehouseId;

    /** 要改量的明细；null/空表示不改量 */
    private List<DeliveryItemQty> items;

    /** 一次性确认码（先 ?dryRun=true 预演拿到）；也可用查询参数 ?confirmCode= 传 */
    private String confirmCode;
}
