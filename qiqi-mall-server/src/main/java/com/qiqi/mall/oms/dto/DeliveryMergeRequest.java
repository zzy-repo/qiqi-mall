package com.qiqi.mall.oms.dto;

import lombok.Data;

/** 待下发包裹合并请求（POST /admin/deliveries/{id}/merge）：把当前包裹并入 targetId */
@Data
public class DeliveryMergeRequest {

    /** 目标包裹（必须同订单、同为待下发） */
    private Long targetId;
}
