package com.qiqi.mall.stock;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「展示缓存待回写」名单（进程内、无锁、allow-lost）。
 *
 * <p>扣减/回补成功时只在这里记一笔（内存操作，微秒级），真正的 {@code sku.stock} 回写由
 * {@link StockCacheRefresher} 在固定周期里做。这样「下单事务」里**一次都不碰 sku 那行**，
 * 热行锁才真正离开主路径 —— 这正是分桶的收益来源。
 *
 * <h3>为什么敢「允许丢」</h3>
 * 缓存值随时可以从 {@code SUM(sku_bucket.stock)} 重算，丢一条只是「这次没刷新」，
 * 下一次这单 SKU 再被扣/补就会重新标脏。名字用 marker 而不是 cache 也是为了说明：
 * 它不承载任何正确性，只影响展示新鲜度。
 *
 * <h3>为什么不放 Redis</h3>
 * 这是「本实例刚写过谁」的本地事实，跨实例共享没有意义：每个实例各自刷新自己写过的 SKU，
 * 写进去的都是同一个 {@code SUM} 的真值（幂等），最多互相覆盖成同一个数。
 * 多实例下唯一代价是同一个 SKU 被多个实例各写一次，与正确性无关。
 */
@Component
public class StockCacheDirtyMarker {

    /** 名单上限：极端情况下（几十万 SKU 同时被扣）宁可少刷一些，也不把堆撑爆 —— 缓存本就允许滞后 */
    private static final int MAX_PENDING = 100_000;

    private final Map<Long, Long> dirty = new ConcurrentHashMap<>();

    /** 标脏（同一个 SKU 多次标脏只留一条，天然去重） */
    public void mark(Long skuId) {
        if (skuId == null) {
            return;
        }
        if (dirty.size() >= MAX_PENDING && !dirty.containsKey(skuId)) {
            return;   // 名单满了就不再收新面孔，等下一轮刷掉一批
        }
        dirty.put(skuId, System.nanoTime());
    }

    /** 取走最多 {@code max} 个待回写的 SKU（取走即从名单移除；刷新失败时由调用方重新 mark） */
    public List<Long> drain(int max) {
        if (dirty.isEmpty() || max <= 0) {
            return List.of();
        }
        List<Long> batch = new ArrayList<>(Math.min(max, dirty.size()));
        for (Long skuId : dirty.keySet()) {
            if (batch.size() >= max) {
                break;
            }
            if (dirty.remove(skuId) != null) {
                batch.add(skuId);
            }
        }
        return batch;
    }

    /** 当前待回写数量（测试与排障用） */
    public int pendingCount() {
        return dirty.size();
    }
}
