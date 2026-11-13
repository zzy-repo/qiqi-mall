package com.qiqi.mall.stock.dto;

import lombok.Data;

/**
 * 全库偏差列表的一行（{@code GET /admin/inventory/reconcile/drift}）。
 *
 * <p>字段是前端契约：{@code skuId, skuCode, bucketSum, skuStock, drift}。
 *
 * <p>为什么这里<b>不</b>复用 {@link InventoryReconcileDTO}：这张表的每一行都是一个 SKU 的三数快照，
 * 不需要名称/流水/桶数；把它们带上等于每一行多查三次，而列表只看「谁偏了、偏多少」。
 * 名称由前端点击该行后再走单 SKU 对账接口拿（页面就是这么做的）。
 */
@Data
public class InventoryDriftRow {

    /** SKU 主键 */
    private Long skuId;

    /** 合成编码 {@code SKU-{id}}（同 {@link InventoryReconcileDTO#getSkuCode()}） */
    private String skuCode;

    /** 权威值 {@code SUM(sku_bucket.stock)}（无桶时为 0） */
    private Integer bucketSum;

    /** 展示缓存 {@code sku.stock} */
    private Integer skuStock;

    /** 偏差 = {@code bucketSum - skuStock}（排序键：偏差大的在前） */
    private Integer drift;
}
