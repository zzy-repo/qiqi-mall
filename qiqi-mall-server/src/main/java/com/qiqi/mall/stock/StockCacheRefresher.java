package com.qiqi.mall.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 展示缓存 {@code sku.stock} 的周期回写任务（默认 250ms 一轮，可配 {@code qiqi.stock.cache-refresh-ms}）。
 *
 * <h3>为什么要单独一个任务，而不是在扣减事务末尾写 sku.stock</h3>
 * 因为「写 sku.stock」就是原来那把热行锁。如果每笔订单都在自己的事务里顺手写一次，
 * 并发量一大，所有请求又会在 sku 那一行上排队 —— 分桶的收益当场归零。
 * 所以扣/补路径只做一件内存操作（{@link StockCacheDirtyMarker#mark}），
 * 真正写 sku.stock 的频率是**按时间**的（每秒最多几次/每 SKU），与下单 QPS 无关。
 *
 * <h3>为什么是 250ms</h3>
 * 展示口径本来就是「秒级延迟可接受」（C 端详情 / 后台列表都读 sku.stock），
 * 250ms 让界面上的库存看起来几乎是实时的，同时把写 sku 行的频率压到极低
 * （热点 SKU 最多 4 次/秒，而不是几千次/秒）。要求更实时就调小，但要接受
 * 「写 sku 行的频率也随之上升」，别调到 10ms —— 那就把热行锁请回来了。
 *
 * <h3>失败处理</h3>
 * 单个 SKU 回写抛异常不影响其他 SKU：把该 SKU 重新标脏，下一轮再试。
 * 缓存写不进去最坏也就是「界面上的数字旧一点」，不影响任何扣减裁决。
 */
@Component
public class StockCacheRefresher {

    private static final Logger log = LoggerFactory.getLogger(StockCacheRefresher.class);

    /** 展示缓存回写走 StockStore：legacy 模式下它是 no-op（那时 sku.stock 就是权威） */
    private final StockStore stockStore;
    private final StockCacheDirtyMarker dirtyMarker;
    private final StockProperties properties;

    public StockCacheRefresher(StockStore stockStore, StockCacheDirtyMarker dirtyMarker,
                               StockProperties properties) {
        this.stockStore = stockStore;
        this.dirtyMarker = dirtyMarker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${qiqi.stock.cache-refresh-ms:250}",
               initialDelayString = "${qiqi.stock.cache-refresh-ms:250}")
    public void flush() {
        int batchSize = properties.getCacheRefreshBatch();
        List<Long> batch = dirtyMarker.drain(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Long skuId : batch) {
            try {
                stockStore.refreshDisplayCache(skuId);
                ok++;
            } catch (RuntimeException e) {
                dirtyMarker.mark(skuId);   // 本轮失败，下一轮再来；缓存允许滞后
                log.warn("展示缓存回写失败，已重新排队：SKU {} — {}", skuId, e.getMessage());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("展示缓存回写：本轮 {} 个 SKU，成功 {} 个，剩余待刷 {} 个",
                    batch.size(), ok, dirtyMarker.pendingCount());
        }
    }
}
