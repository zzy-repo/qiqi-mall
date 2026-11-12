package com.qiqi.mall.stock;

import com.qiqi.mall.catalog.mapper.SkuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>legacy 实现</b>（{@code qiqi.stock.bucket-enabled=false}，默认）：
 * 库存就是 {@code sku.stock}，扣减 = {@code UPDATE sku SET stock = stock - N WHERE id=? AND stock>=N}。
 *
 * <p>这就是分桶改造**之前**的 baseline：同一条 SQL、同一把行锁、同样的死锁重试语义
 * （{@code GlobalExceptionHandler} 那层的重试逻辑一行没动）。它的存在意义是**对照基准**：
 * 连接池优化的收益（C 阶段）必须在一个「没有分桶变量」的镜像上量。
 *
 * <p>台账、幂等、数量守恒仍然全部生效 —— 它们长在 {@code StockLedgerService} 里，与本类无关。
 * 本类只负责「最后一脚踩在 sku 那行上」。
 */
public class LegacyStockStore implements StockStore {

    private static final Logger log = LoggerFactory.getLogger(LegacyStockStore.class);

    private final SkuMapper skuMapper;

    public LegacyStockStore(SkuMapper skuMapper) {
        this.skuMapper = skuMapper;
    }

    @Override
    public String mode() {
        return "legacy";
    }

    @Override
    public StockWrite deduct(Long skuId, int quantity) {
        // 与 baseline 完全同一条 SQL（判断压在 WHERE stock >= N 里，不做应用层先查后写）
        int rows = skuMapper.deductStock(skuId, quantity);
        return rows > 0 ? StockWrite.ok(null) : StockWrite.insufficient();
    }

    @Override
    public StockWrite restore(Long skuId, int quantity, Integer preferredBucketNo) {
        // legacy 没有桶的概念：台账里的 bucket_no 在这条路径下恒为 null，直接忽略
        int rows = skuMapper.restoreStock(skuId, quantity);
        if (rows == 0) {
            log.warn("legacy 回补更新 0 行：SKU {} 不存在或已被删除", skuId);
            return StockWrite.insufficient();
        }
        return StockWrite.ok(null);
    }

    @Override
    public int availableStock(Long skuId) {
        var sku = skuMapper.selectById(skuId);
        return sku == null || sku.getStock() == null ? 0 : sku.getStock();
    }

    @Override
    public void resetStock(Long skuId, int total) {
        // legacy 语义：sku.stock 就是库存本身，直接写它
        skuMapper.overwriteStock(skuId, Math.max(0, total));
    }

    @Override
    public int refreshDisplayCache(Long skuId) {
        // legacy 没有「缓存」这回事（sku.stock 就是权威），无需回写
        return 0;
    }
}
