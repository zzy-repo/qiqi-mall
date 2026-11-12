package com.qiqi.mall.oms.dto;

import lombok.Data;

/** 待下发包裹合并请求（POST /admin/deliveries/{id}/merge）：把当前包裹并入 targetId */
@Data
public class DeliveryMergeRequest {

    /** 目标包裹（必须同订单、同为待下发） */
    private Long targetId;

    /**
     * 乐观锁版本号：**源包裹**（{@code {id}}）的 version，来自 {@code GET /admin/deliveries?orderId=}，必填。
     *
     * <p>校验的是 {@code {id}} 这一行的版本（本次请求真正改动的那一行），targetId 那一行由
     * service 的「同订单 + 待下发」守卫兜住 —— 目标是只被「接收明细/数量相加」的另一方，
     * 它的并发改动由 {@code markManual()} 的影响行数检查发现。
     *
     * <p>缺 → HTTP 400 + code=400；与库里当前版本不一致 → HTTP 409 + code=409。
     */
    private Integer version;
}
