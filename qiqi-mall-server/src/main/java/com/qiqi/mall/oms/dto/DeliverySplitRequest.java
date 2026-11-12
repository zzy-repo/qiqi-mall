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

    /**
     * 乐观锁版本号：来自 {@code GET /admin/deliveries?orderId=} 返回的 version，**必填**。
     *
     * <p>为什么拆单也要版本号：状态守卫（status=0）只拦「已下发的包裹」，拦不住
     * **两个管理员同时拆同一个待下发包裹** —— 两人拿的是同一份明细，各自「拆出一件」，
     * 合起来就拆出了两份不存在的货。version 才是把这种并发压成「后来的人 409 请刷新」的那道闸门。
     *
     * <p>缺 → HTTP 400 + code=400；与库里当前版本不一致 → HTTP 409 + code=409。
     */
    private Integer version;
}
