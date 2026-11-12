package com.qiqi.mall.stock;

import com.qiqi.mall.stock.service.SkuBucketService;

/**
 * <b>分桶实现</b>（{@code qiqi.stock.bucket-enabled=true}）：库存权威在 {@code sku_bucket}，
 * {@code sku.stock} 降级为展示缓存。
 *
 * <p>热点 SKU 的并发被摊到 N 把互不相干的行锁上（默认 8），{@code sku} 那行不再参与扣减 ——
 * 这是分桶收益的全部来源；代价是「读 sku.stock 做展示」会滞后一个回写周期（默认 250ms）。
 *
 * <p>本类只是对 {@link SkuBucketService} 的薄适配：真正的裁决、换桶、跨桶拼单都在那里。
 */
public class BucketStockStore implements StockStore {

    private final SkuBucketService bucketService;
    private final StockProperties properties;

    public BucketStockStore(SkuBucketService bucketService, StockProperties properties) {
        this.bucketService = bucketService;
        this.properties = properties;
    }

    @Override
    public String mode() {
        return "bucket(N=" + properties.getBucketCount() + ")";
    }

    @Override
    public boolean usesBuckets() {
        return true;
    }

    @Override
    public StockWrite deduct(Long skuId, int quantity) {
        int bucketNo = bucketService.deduct(skuId, quantity);
        return bucketNo == SkuBucketService.NO_BUCKET ? StockWrite.insufficient() : StockWrite.ok(bucketNo);
    }

    @Override
    public StockWrite restore(Long skuId, int quantity, Integer preferredBucketNo) {
        int bucketNo = bucketService.restore(skuId, quantity, preferredBucketNo);
        return bucketNo == SkuBucketService.NO_BUCKET ? StockWrite.insufficient() : StockWrite.ok(bucketNo);
    }

    @Override
    public int availableStock(Long skuId) {
        return bucketService.sumStock(skuId);
    }

    @Override
    public void resetStock(Long skuId, int total) {
        bucketService.resetTo(skuId, total);
    }

    @Override
    public int refreshDisplayCache(Long skuId) {
        return bucketService.refreshSkuStockCache(skuId);
    }
}
