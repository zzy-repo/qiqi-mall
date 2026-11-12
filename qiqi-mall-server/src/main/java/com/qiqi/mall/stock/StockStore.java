package com.qiqi.mall.stock;

/**
 * 「动库存」这一步的策略接口 —— <b>唯一</b>的库存落点抽象。
 *
 * <h3>为什么要有这层</h3>
 * 要干净归因两个优化（连接池 / 分桶）各自的收益，就必须能一键在
 * 「原来的单行 UPDATE」和「分桶」之间切换，且**两条路径共用同一套台账**：
 * 幂等键、数量守恒、先记账后动库存这些闸门都长在 {@code StockLedgerService} 里，
 * 只有「最后一脚踩哪儿」是可换的 —— 所以换的是这一层，而不是在业务代码里写两套账。
 *
 * <h3>为什么用「启动时选一个实现」而不是每次调用 if</h3>
 * <ol>
 *   <li>热路径上没有分支：扣减方法里除了这条 SQL 之外不该多任何判断；</li>
 *   <li>物理上不可能「两条路径同时生效」—— 一个进程只注入一个 {@link StockStore}，
 *       杜绝「两个地方都能裁决库存」；</li>
 *   <li>台账逻辑只写一遍：{@code StockLedgerService} 只认这个接口，不认实现。</li>
 * </ol>
 *
 * <h3>两种实现</h3>
 * <ul>
 *   <li>{@link LegacyStockStore}（{@code qiqi.stock.bucket-enabled=false}，默认）：
 *       {@code UPDATE sku SET stock = stock -/+ N}，与分桶改造前的 baseline **同一条 SQL**、
 *       同样的死锁重试语义、同样的行锁行为 —— 用于 D/C 阶段的对照基准；</li>
 *   <li>{@link BucketStockStore}（{@code true}）：{@code sku_bucket} 的某个桶做原子扣减，
 *       {@code sku.stock} 降级为展示缓存。</li>
 * </ul>
 */
public interface StockStore {

    /** 模式标识，启动日志与健康排查用：{@code legacy} / {@code bucket(N=8)} */
    String mode();

    /** 这条实现是否以「桶」为权威（决定台账要不要记 bucket_no、要不要回原桶） */
    default boolean usesBuckets() {
        return false;
    }

    /** 扣减结果：{@code ok=false} = 库存不足（一件都没扣）；{@code bucketNo} 仅分桶模式有意义 */
    record StockWrite(boolean ok, Integer bucketNo) {

        public static StockWrite ok(Integer bucketNo) {
            return new StockWrite(true, bucketNo);
        }

        public static StockWrite insufficient() {
            return new StockWrite(false, null);
        }
    }

    /**
     * 扣减（原子裁决）。
     *
     * @return ok=true 表示扣成功；ok=false 表示库存不足，库存一件未动
     */
    StockWrite deduct(Long skuId, int quantity);

    /**
     * 回补。
     *
     * @param preferredBucketNo 台账里记录的扣减桶号（legacy 模式恒为 null，忽略）
     * @return ok=false = 补不进去（规格不存在等），调用方须 fail-closed
     */
    StockWrite restore(Long skuId, int quantity, Integer preferredBucketNo);

    /**
     * 权威可用库存（用于「只剩 N 件」这类必须说准的地方）。
     * legacy = {@code sku.stock}（它就是权威）；bucket = {@code SUM(sku_bucket.stock)}。
     */
    int availableStock(Long skuId);

    /**
     * 管理端「把库存设成总量 total」。
     * legacy = 直接写 {@code sku.stock}；bucket = 用 total 重铺 N 个桶（权威在桶里）。
     */
    void resetStock(Long skuId, int total);

    /**
     * 立即把展示缓存刷成权威值。
     * legacy = 无需回写（sku.stock 就是权威）→ 返回 0；bucket = 用 SUM(桶) 回写 → 返回影响行数。
     */
    int refreshDisplayCache(Long skuId);
}
