package com.qiqi.mall.stock.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单个 SKU 的库存对账结果（前端「库存对账」页 {@code admin-web/src/views/inventory-reconcile/index.vue} 的卡片区）。
 *
 * <h3>字段是逐字契约（前端已定稿）</h3>
 * <pre>
 * skuId, skuCode, skuName, bucketSum, skuStock, drift, lastRefreshAt,
 * bucketCount, ledgerNet, ledgerRows[]
 * </pre>
 * 后面还有两个**追加字段**（前端不读，但交付要求 ③ 要「桶数 vs 配置桶数」的一致性）：
 * {@code expectedBucketCount}（{@code qiqi.stock.bucket-count} 的生效值）与
 * {@code bucketCountMatch}（{@code bucketCount == expectedBucketCount}）。
 * 追加而不改契约：前端读不到新字段不会坏，契约里要求的字段一个不少。
 *
 * <h3>三个数不是一回事（口径红线，接口只如实返回，绝不在后端「修正」或校验它们相等）</h3>
 * <ul>
 *   <li>{@code bucketSum = SUM(sku_bucket.stock)} —— <b>权威值</b>，库存的唯一事实源；</li>
 *   <li>{@code skuStock = sku.stock} —— <b>展示缓存</b>，由 {@code StockCacheRefresher} 周期回写；
 *       {@code drift = bucketSum - skuStock}，这才是「应当相等的一对」；</li>
 *   <li>{@code ledgerNet = SUM(stock_ledger.delta)} —— <b>全生命周期进出和，不是余额</b>。
 *       它把该 SKU 历史所有扣/补累加（下单扣掉的、退货退回的、审核回补的都算），
 *       所以<b>与 bucketSum 不要求相等</b>，也不允许后端拿它去「对齐」库存。</li>
 * </ul>
 */
@Data
public class InventoryReconcileDTO {

    /** SKU 主键（入参原样带回） */
    private Long skuId;

    /**
     * SKU 编码：{@code sku} 表**没有** code 列，这里用稳定的合成编码 {@code SKU-{id}}。
     * 不用规格值拼：规格值是可变的（改名/改值会让编码漂移），而编码要能长期指向同一个规格。
     * 规格值放在 {@link #skuName} 里（人看得懂的那一份）。
     */
    private String skuCode;

    /** SKU 名称：{@code product.name}（+ 规格文本，如 {@code iPhone 15 · 颜色:黑,内存:256G}） */
    private String skuName;

    /** 权威值：{@code SUM(sku_bucket.stock)}（该 SKU 一个桶都没有时是 0，同时 bucketCount = 0） */
    private Integer bucketSum;

    /** 展示缓存：{@code sku.stock} */
    private Integer skuStock;

    /** 偏差 = {@code bucketSum - skuStock}（正 = 缓存少记，负 = 缓存多记） */
    private Integer drift;

    /**
     * 缓存最后一次回写时间。
     *
     * <p><b>如实说明</b>：{@code sku} 表没有 {@code updated_at} 之类的列，回写动作本身没有留时间戳，
     * 所以这里给的是**最接近的可观测时间** {@code MAX(sku_bucket.updated_at)}（该 SKU 桶最近一次变动），
     * 一个桶都没有时为 {@code null}（前端显示「无记录」）。
     * 它不等于「缓存回写时刻」，两者在缓存滞后的窗口内会差一点 —— 见交付报告里的说明。
     */
    private LocalDateTime lastRefreshAt;

    /** 该 SKU 现在的桶数（{@code COUNT(sku_bucket)}；0 = 还没回填过，分桶模式下会假性缺货） */
    private Integer bucketCount;

    /** 【追加字段】配置里的桶数 {@code qiqi.stock.bucket-count} 的生效值（已按 [1,64] 钳过） */
    private Integer expectedBucketCount;

    /** 【追加字段】{@code bucketCount == expectedBucketCount}（check-data-integrity 的 ④ 桶缺失同口径） */
    private Boolean bucketCountMatch;

    /**
     * 台账净额 {@code SUM(stock_ledger.delta)}（全生命周期进出和，**不是余额**，
     * 不与 {@link #bucketSum} 对平；用途只有一个：这单到底补过库存没有、补了多少）。
     */
    private Integer ledgerNet;

    /** 最近 20 条库存流水（按 id 倒序；没有流水时是空数组，不是 null） */
    private java.util.List<InventoryLedgerRow> ledgerRows;
}
