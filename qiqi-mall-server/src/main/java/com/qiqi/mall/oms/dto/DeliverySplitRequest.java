package com.qiqi.mall.oms.dto;

import lombok.Data;

import java.util.List;

/**
 * 待下发包裹拆分请求（POST /admin/deliveries/{id}/split）
 * 从原包裹里按数量拆出一部分，生成一个新的待下发包裹。
 */
@Data
public class DeliverySplitRequest {

    /** 新包裹的发货仓；null 表示沿用原包裹的仓 */
    private Long newWarehouseId;

    /** 要拆出的明细与数量（不填则报错，避免拆出空包） */
    private List<DeliveryItemQty> items;

    /** 一次性确认码（先 ?dryRun=true 预演拿到）；也可用查询参数 ?confirmCode= 传 */
    private String confirmCode;
}
