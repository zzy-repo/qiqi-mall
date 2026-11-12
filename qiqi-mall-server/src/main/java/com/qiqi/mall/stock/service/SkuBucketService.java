package com.qiqi.mall.stock.service;

import com.qiqi.mall.stock.BucketRotation;
import com.qiqi.mall.stock.StockCacheDirtyMarker;
import com.qiqi.mall.stock.StockProperties;
import com.qiqi.mall.stock.mapper.SkuBucketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * SKU 库存分桶服务：<b>库存的唯一裁决处</b>。
 *
 * <h3>为什么要有它</h3>
 * 原来扣减只有 {@code UPDATE sku SET stock = stock - N WHERE id = ? AND stock >= N}。
 * 它原子、不超卖，但把「同一 SKU 的所有并发」压进一把行锁 —— 热点 SKU 的 TPS 上限
 * 就是单行的行锁吞吐。现在同一份库存物理拆成 N 行（{@code sku_bucket}，默认 8 桶），
 * 并发分散到 N 把互不相干的行锁上；{@code sku.stock} 降级为展示缓存。
 *
 * <h3>扣减（权威裁决）</h3>
 * <ol>
 *   <li><b>随机起点</b>轮询 N 个桶，对每个桶做
 *       {@code UPDATE ... SET stock = stock - N WHERE sku_id=? AND bucket_no=? AND stock>=N}；
 *       影响 1 行 = 扣成功（返回命中的桶号），并发下别的请求自然走别的桶；</li>
 *   <li>N 个桶都没命中 → ①若该 SKU 还没有任何桶（历史库没跑迁移 / 测试直接 INSERT 了 sku），
 *       先按 {@code sku.stock} 幂等回填一次再重试；②否则按桶号升序做<b>跨桶拼单</b>
 *       （平均拆桶后单个桶可能装不下一张订单的量，但总量够）；</li>
 *   <li>拼不出来 = 真·库存不足，把已经拿走的量原样补回去，返回 {@link #NO_BUCKET}。</li>
 * </ol>
 *
 * <h3>回补</h3>
 * 回到台账里记的那个桶（{@code stock_ledger.bucket_no}，见 {@code StockLedgerService}）；
 * 台账没有桶号（分桶之前的历史账）或那个桶已不存在时，按 sku 任选一个桶（桶号最小的那个，确定性）。
 * 桶的容量没有上限，所以回补量落在哪个桶都不影响总量守恒。
 *
 * <h3>与 sku.stock 的关系（只有一条）</h3>
 * 扣减/回补路径<b>不写 sku.stock</b>，只调用 {@link StockCacheDirtyMarker#mark} 标脏；
 * {@code sku.stock} 由 {@link com.qiqi.mall.stock.StockCacheRefresher} 周期回写，
 * 也可由 {@link #refreshSkuStockCache} 立即回写。任何「拿 sku.stock 当库存判断依据」的写法
 * 都是回归 —— 它是缓存。
 */
@Service
public class SkuBucketService {

    private static final Logger log = LoggerFactory.getLogger(SkuBucketService.class);

    /** 扣减/回补失败的返回值：没有可用的桶（库存不足 / 规格不存在） */
    public static final int NO_BUCKET = -1;

    private final SkuBucketMapper bucketMapper;
    private final StockProperties properties;
    private final StockCacheDirtyMarker dirtyMarker;

    public SkuBucketService(SkuBucketMapper bucketMapper, StockProperties properties,
                            StockCacheDirtyMarker dirtyMarker) {
        this.bucketMapper = bucketMapper;
        this.properties = properties;
        this.dirtyMarker = dirtyMarker;
    }

    // ================================================================== 扣减

    /**
     * 扣减库存（权威裁决）。
     *
     * <p><b>必须在调用方的事务里执行</b>：跨桶拼单会先动多个桶、再在某一步失败时补偿回去，
     * 事务回滚是最外层的兜底。
     *
     * @return 实际扣减的桶号（跨桶拼单时返回第一个被扣的桶号）；{@link #NO_BUCKET} = 库存不足，一件都没扣
     */
    public int deduct(Long skuId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("扣减件数必须为正数：" + quantity);
        }
        int bucketCount = properties.getBucketCount();

        // ① 快路径：随机起点，单桶原子扣减（并发量被摊到 N 把行锁上）
        int hit = deductFromSingleBucket(skuId, quantity, bucketCount,
                ThreadLocalRandom.current().nextInt(bucketCount));
        if (hit != NO_BUCKET) {
            dirtyMarker.mark(skuId);
            return hit;
        }

        // ② 兜底一：这个 SKU 一个桶都没有（老库没跑迁移 / 测试或脚本直接 INSERT 了 sku）
        //    按 sku.stock 幂等回填一次 —— 这是「把同一份库存物化成 N 行」，不是第二套裁决逻辑
        if (!hasBuckets(skuId) && backfillSku(skuId) > 0) {
            log.info("SKU {} 尚无分桶，已按 sku.stock 物化 {} 个桶后重试扣减", skuId, bucketCount);
            hit = deductFromSingleBucket(skuId, quantity, bucketCount,
                    ThreadLocalRandom.current().nextInt(bucketCount));
            if (hit != NO_BUCKET) {
                dirtyMarker.mark(skuId);
                return hit;
            }
        }

        // ③ 兜底二：单个桶装不下这一单，但总量可能够（平均拆桶后单桶只有总量/N）
        hit = deductAcrossBuckets(skuId, quantity, bucketCount);
        if (hit != NO_BUCKET) {
            dirtyMarker.mark(skuId);
            return hit;
        }

        log.warn("库存不足：SKU {} 需要 {} 件，{} 个桶都扣不动（当前总量 {}）",
                skuId, quantity, bucketCount, safeSum(skuId));
        return NO_BUCKET;
    }

    /** 随机起点轮询：每个桶号恰好试一次（同一次扣减不会重复打同一个桶） */
    private int deductFromSingleBucket(Long skuId, int quantity, int bucketCount, int start) {
        for (int bucketNo : BucketRotation.sequence(bucketCount, start)) {
            if (bucketMapper.deductOne(skuId, bucketNo, quantity) > 0) {
                return bucketNo;
            }
        }
        return NO_BUCKET;
    }

    /**
     * 跨桶拼单：按<b>桶号升序</b>逐桶取货，凑够为止；凑不够就把已经取的补回去。
     *
     * <p>为什么升序而不是随机：这条路径一次要锁多个桶，所有事务都按同一顺序加锁，
     * 才不会互相形成环（死锁）。它不是热路径 —— 只有「单桶装不下这一单」时才会走到。
     */
    private int deductAcrossBuckets(Long skuId, int quantity, int bucketCount) {
        int remaining = quantity;
        int firstBucket = NO_BUCKET;
        List<int[]> taken = new ArrayList<>(bucketCount);

        for (int bucketNo = 0; bucketNo < bucketCount && remaining > 0; bucketNo++) {
            Integer current = bucketMapper.stockOf(skuId, bucketNo);
            if (current == null || current <= 0) {
                continue;
            }
            int take = Math.min(remaining, current);
            // 仍然用带 stock >= take 的原子 UPDATE：读到的值可能刚被别人拿走
            if (bucketMapper.deductOne(skuId, bucketNo, take) > 0) {
                taken.add(new int[]{bucketNo, take});
                remaining -= take;
                if (firstBucket == NO_BUCKET) {
                    firstBucket = bucketNo;
                }
            }
        }

        if (remaining > 0) {
            // 凑不够：原样补回去（即使调用方事务会回滚，也不留下半扣状态）
            for (int[] t : taken) {
                bucketMapper.restoreOne(skuId, t[0], t[1]);
            }
            log.warn("跨桶拼单凑不够：SKU {} 需要 {} 件，只凑到 {} 件，已回滚这 {} 个桶的扣减",
                    skuId, quantity, quantity - remaining, taken.size());
            return NO_BUCKET;
        }
        log.info("跨桶拼单扣减：SKU {} 需要 {} 件，动用 {} 个桶（首个桶号 {}）", skuId, quantity, taken.size(), firstBucket);
        return firstBucket;
    }

    // ================================================================== 回补

    /**
     * 回补库存。
     *
     * @param preferredBucketNo 台账里记录的扣减桶号（可空：历史台账没有这一列）
     * @return 实际回补到的桶号；{@link #NO_BUCKET} = 补不进去（规格不存在 / 无法建桶），调用方须 fail-closed
     */
    public int restore(Long skuId, int quantity, Integer preferredBucketNo) {
        if (quantity <= 0) {
            return NO_BUCKET;
        }

        // ① 回到原桶：同一件货怎么出去的、就怎么回来（桶内明细可对账，也避免所有回补都砸在一个桶上）
        if (preferredBucketNo != null && bucketMapper.restoreOne(skuId, preferredBucketNo, quantity) > 0) {
            dirtyMarker.mark(skuId);
            return preferredBucketNo;
        }

        // ② 还没有桶（老数据）：按 sku.stock 物化一次，再试原桶
        if (!hasBuckets(skuId) && backfillSku(skuId) > 0) {
            if (preferredBucketNo != null && bucketMapper.restoreOne(skuId, preferredBucketNo, quantity) > 0) {
                dirtyMarker.mark(skuId);
                return preferredBucketNo;
            }
        }

        // ③ 台账没记桶号 / 那个桶已不存在：按 sku 任选一个桶（取桶号最小的，确定性便于排查）
        int fallback = bucketMapper.minBucketNo(skuId);
        if (fallback == NO_BUCKET) {
            log.warn("回补失败：SKU {} 既没有可用的桶、也无法按 sku.stock 建桶（规格可能已被删除）", skuId);
            return NO_BUCKET;
        }
        if (bucketMapper.restoreOne(skuId, fallback, quantity) == 0) {
            log.warn("回补失败：SKU {} 的 {} 号桶更新了 0 行", skuId, fallback);
            return NO_BUCKET;
        }
        log.info("回补未找到台账桶号（preferred={}），已回补到 SKU {} 的 {} 号桶（+{} 件）",
                preferredBucketNo, skuId, fallback, quantity);
        dirtyMarker.mark(skuId);
        return fallback;
    }

    // ================================================================== 汇总 / 回填 / 缓存

    /** 权威库存总量 = SUM(桶)。展示层要「实时库存」时用它；列表页照旧读 sku.stock（缓存） */
    public int sumStock(Long skuId) {
        return bucketMapper.sumStock(skuId);
    }

    /** 该 SKU 现在有几个桶 */
    public boolean hasBuckets(Long skuId) {
        return bucketMapper.countBuckets(skuId) > 0;
    }

    /**
     * 幂等回填：把 {@code sku.stock} 平均拆到 N 个桶，只补「一个桶都没有」的 SKU。
     *
     * @return 实际插入的行数（重跑时 0）
     */
    public int backfill() {
        List<Integer> numbers = bucketNumbers();
        int rows = bucketMapper.backfill(numbers.size(), numbers, null);
        if (rows > 0) {
            log.info("库存分桶回填：插入 {} 行（{} 个桶/规格，来源 sku.stock）", rows, numbers.size());
        }
        return rows;
    }

    /** 同上，只补一个 SKU（老数据懒初始化用） */
    public int backfillSku(Long skuId) {
        List<Integer> numbers = bucketNumbers();
        return bucketMapper.backfill(numbers.size(), numbers, skuId);
    }

    /**
     * 按给定总量重置该 SKU 的分桶（管理端把库存改成 X = 把 X 重新平均铺到 N 个桶）。
     *
     * <p>为什么必须重置而不是「只改 sku.stock」：库存权威在桶里，只改缓存等于没改库存，
     * 界面显示 100、实际还扣得动老数字 —— 那才是最坏的不一致。
     *
     * @return 写入的桶行数
     */
    public int resetTo(Long skuId, int total) {
        int safeTotal = Math.max(0, total);
        bucketMapper.deleteBySku(skuId);
        List<Integer> numbers = bucketNumbers();
        int rows = bucketMapper.insertSplit(skuId, safeTotal, numbers.size(), numbers);
        dirtyMarker.mark(skuId);
        log.info("库存重置为分桶：SKU {} 总量 {}（{} 个桶，实际写入 {} 行）", skuId, safeTotal, numbers.size(), rows);
        return rows;
    }

    /**
     * 立即用 SUM(桶) 回写展示缓存 {@code sku.stock}。
     *
     * <p>平时由 {@link com.qiqi.mall.stock.StockCacheRefresher} 周期调用（默认 250ms 一轮）；
     * 也可以在「必须立刻看到准数」的地方（运维接口 / 测试断言 / 排障）手动调一次。
     *
     * @return 影响行数（0 = 该 SKU 没有桶，缓存无从算起，不动它）
     */
    public int refreshSkuStockCache(Long skuId) {
        int rows = bucketMapper.refreshSkuCache(skuId);
        if (rows > 0 && log.isDebugEnabled()) {
            log.debug("展示缓存已回写：SKU {} sku.stock = SUM(bucket) = {}", skuId, safeSum(skuId));
        }
        return rows;
    }

    /**
     * 启动自检 / 体检用：有多少 SKU 的「桶总量」与展示缓存不一致。
     *
     * <p>分桶模式下这个数应当只有个位数（正在被扣减的 SKU 会在一个回写周期内追平）；
     * 如果很大，通常意味着「刚用 legacy 镜像跑过一轮，只写了 sku.stock」——
     * 那时必须用 {@code sql/reset-sku-bucket.sql} 把桶重置成目标值。
     */
    public int countCacheDrift() {
        return bucketMapper.countCacheDrift();
    }

    /** 便捷：先把缓存刷成真值再读，给「必须看到准数」的调用方（后台详情 / 排障）用 */
    public int refreshAndRead(Long skuId) {
        refreshSkuStockCache(skuId);
        return safeSum(skuId);
    }

    // ================================================================== 内部工具

    private List<Integer> bucketNumbers() {
        return IntStream.range(0, properties.getBucketCount()).boxed().toList();
    }

    private int safeSum(Long skuId) {
        try {
            return bucketMapper.sumStock(skuId);
        } catch (RuntimeException e) {
            return -1;   // 只用于日志
        }
    }
}
