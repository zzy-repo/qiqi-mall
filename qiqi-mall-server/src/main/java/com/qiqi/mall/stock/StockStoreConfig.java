package com.qiqi.mall.stock;

import com.qiqi.mall.catalog.mapper.SkuMapper;
import com.qiqi.mall.stock.service.SkuBucketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置把「库存落点」装配成 legacy 或 bucket —— <b>一个进程只有一种</b>。
 *
 * <p>两个开关都在启动时读一次（{@code qiqi.stock.bucket-enabled} / {@code qiqi.stock.ledger-insert-ignore}），
 * 之后整条链路上没有任何 if：热路径不额外分支，也不会出现「两种裁决同时生效」的窗口。
 *
 * <p>两个开关都默认 {@code false} = 与 baseline 完全一致（同一条扣减 SQL、同一条台账插入 SQL、
 * 同一套死锁重试）。压测据此分三次量：C（连接池）/ L（台账插入写法）/ E（合并 + 分桶）。
 */
@Configuration
public class StockStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StockStoreConfig.class);

    @Bean
    public StockStore stockStore(StockProperties properties, SkuBucketService bucketService, SkuMapper skuMapper) {
        if (!properties.isBucketEnabled()) {
            log.info("库存落点=legacy（sku.stock 就是库存，单行原子 UPDATE —— 与 baseline 同一条 SQL）");
            return new LegacyStockStore(skuMapper);
        }
        log.info("库存落点=bucket（sku_bucket {} 个桶，sku.stock 降级为展示缓存）", properties.getBucketCount());
        BucketStockStore store = new BucketStockStore(bucketService, properties);
        auditCacheDrift(bucketService);
        return store;
    }

    /**
     * 启动自检：分桶模式下「桶总量」必须与展示缓存一致，否则说明这次切模式之前的数据是
     * 另一条路径写出来的（例如刚用 legacy 镜像跑过压测，只 UPDATE 了 sku.stock）。
     *
     * <p>只告警不阻断 —— 服务该起来还是要起来；但它就是「切模式前记得重置桶」那件事的提醒。
     */
    private void auditCacheDrift(SkuBucketService bucketService) {
        try {
            int drifted = bucketService.countCacheDrift();
            if (drifted > 0) {
                log.warn("分桶自检：有 {} 个 SKU 的 SUM(sku_bucket.stock) 与 sku.stock 不一致 "
                                + "（缓存滞后属正常；若刚切到分桶模式，请用 sql/reset-sku-bucket.sql 重置桶）",
                        drifted);
            } else {
                log.info("分桶自检：所有 SKU 的桶总量与展示缓存一致");
            }
        } catch (RuntimeException e) {
            log.error("分桶自检失败：sku_bucket 不可用（迁移没跑？）—— 分桶模式下这会直接导致扣减失败：{}",
                    e.getMessage());
        }
    }
}
