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

    /**
     * 乐观锁版本号：来自 {@code GET /admin/deliveries?orderId=} 返回的 version，**必填**。
     * 缺 → code=400；与库里当前版本不一致 → code=409（期间被别人改过，请刷新后重试）。
     */
    private Integer version;
}
