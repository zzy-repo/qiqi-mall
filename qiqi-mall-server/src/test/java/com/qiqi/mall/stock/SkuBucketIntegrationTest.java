package com.qiqi.mall.stock;

import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.stock.entity.StockLedger;
import com.qiqi.mall.stock.service.SkuBucketService;
import com.qiqi.mall.stock.service.StockLedgerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存分桶的集成测试（真连库、真并发；不走 HTTP —— 要打的就是库存这条路径本身）。
 *
 * <h3>要固化的三件事</h3>
 * <ol>
 *   <li><b>不超卖</b>：N 个线程抢同一个 SKU，「成功件数之和」必须恰好等于初值，
 *       {@code SUM(sku_bucket.stock)} 落到 0 且永不为负（表上还有 {@code CHECK (stock>=0)} 兜底）；</li>
 *   <li><b>权威在桶里、sku.stock 只是缓存</b>：扣/补成功后 {@code SUM(桶)} 立刻变，
 *       {@code sku.stock} 可以还是旧值（允许偏差），但一刷新就必须等于 SUM；
 *       <b>任何时刻都不允许 SUM &lt; 0 或超卖</b>；</li>
 *   <li><b>换桶 / 回原桶</b>：某个桶空了要自动换下一个桶；回补要回到台账里记的那一个桶。</li>
 * </ol>
 *
 * <p><b>为什么把 {@code cache-refresh-ms} 调成 1 小时</b>：后台回写任务默认 250ms 一轮，
 * 会在用例中途把 sku.stock 刷成新值，让「缓存允许滞后」这类断言变成碰运气。
 * 本类不依赖那个任务 —— 需要准数时显式调 {@code refreshSkuStockCache}。
 *
 * <p>数据自建自清：每个用例自己造商品/规格（不碰种子 SKU），跑完按 id 全删
 * （台账 → 订单 → 规格(桶随外键级联) → 商品 → 会员）。
 * 注意本类连的是 dev 库 {@code qiqi_mall}（与 {@code mvn test} 其它用例同一个库），
 * 所以**只用自己造的数据做断言**，绝不动别人的行。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=SkuBucketIntegrationTest
 */
@SpringBootTest(properties = {
        // 本类测的是「最终形态」（镜像3 = 分桶 + 台账 INSERT IGNORE）：
        //   · bucket-enabled：默认 false 是 legacy（与压测 baseline 一致），这里显式打开；
        //   · ledger-insert-ignore：naive 的 INSERT...SELECT NOT EXISTS 会取 uk_idem 的间隙锁，
        //     并发下单条语句都能撞死锁（压测实测 45,790 次）—— 那是台账写法的问题，不是分桶的，
        //     所以这里用修复后的写法，避免用例被已知缺陷干扰。
        "qiqi.stock.bucket-enabled=true",
        "qiqi.stock.ledger-insert-ignore=true",
        "qiqi.stock.bucket-count=8",
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.stock.cache-refresh-batch=500"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkuBucketIntegrationTest {

    private static final int BUCKETS = 8;

    @Autowired
    private SkuService skuService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private SkuBucketService bucketService;

    @Autowired
    private StockCacheDirtyMarker dirtyMarker;

    @Autowired
    private StockProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private Long memberId;
    private Long categoryId;
    private Long typeId;

    private final List<Long> createdProducts = new CopyOnWriteArrayList<>();
    private final List<Long> createdSkus = new CopyOnWriteArrayList<>();
    private final List<Long> createdOrders = new CopyOnWriteArrayList<>();
    /** 本类用过的所有订单号（含并发用例的合成号），清理时按它删台账 */
    private final Set<Long> ledgerOrderIds = ConcurrentHashMap.newKeySet();
    /** 合成订单号发号器：每个请求一个独立幂等键，绝不重复（重复 = 被当成重放跳过） */
    private final AtomicLong orderIdSeq = new AtomicLong(900_000_000L);
    /** 死锁重试次数（打印出来看「这条路径到底还撞不撞锁」） */
    private final AtomicInteger deadlockRetries = new AtomicInteger();

    // ------------------------------------------------------------------ 准备 / 清理

    @BeforeAll
    void prepare() {
        categoryId = jdbc.queryForObject("SELECT id FROM category ORDER BY id LIMIT 1", Long.class);
        typeId = jdbc.queryForObject("SELECT id FROM product_type ORDER BY id LIMIT 1", Long.class);

        jdbc.update("INSERT INTO member (username, password, nickname, phone, status) VALUES (?, '', ?, NULL, 1)",
                "bucket-" + System.currentTimeMillis(), "分桶测试会员");
        memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        System.out.printf("[准备] 会员=%d 分类=%d 类型=%d 桶数=%d 缓存回写周期=%dms%n",
                memberId, categoryId, typeId, properties.getBucketCount(), properties.getCacheRefreshMs());
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : createdOrders) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);   // 明细随外键级联
        }
        for (Long orderId : ledgerOrderIds) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
        }
        for (Long skuId : createdSkus) {
            jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId);
            jdbc.update("DELETE FROM sku WHERE id = ?", skuId);
        }
        for (Long productId : createdProducts) {
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        System.out.printf("[清理] 商品 %d 个、规格 %d 个、订单 %d 张、会员 1 个已删除%n",
                createdProducts.size(), createdSkus.size(), createdOrders.size());
    }

    // ================================================================== 并发不超卖

    @Test
    @DisplayName("80 线程抢 50 件：恰好成功 50 次，SUM 落到 0，全程不为负，且扣减路径一次都没写 sku.stock")
    void concurrentDeduct_neverOversells_andNeverTouchesSkuStockCache() throws Exception {
        int initial = 50;
        int threads = 80;
        Long skuId = createSku(initial);

        assertThat(bucketService.sumStock(skuId)).as("铺桶后 SUM 必须等于初值").isEqualTo(initial);
        int cacheBefore = skuStockOf(skuId);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicBoolean sawNegative = new AtomicBoolean(false);

        runConcurrently(threads, i -> {
            boolean ok = deductAs(syntheticOrderId(), skuId, 1);
            (ok ? success : failed).incrementAndGet();
            if (bucketService.sumStock(skuId) < 0) {
                sawNegative.set(true);
            }
        });

        int sum = bucketService.sumStock(skuId);
        System.out.printf("① 抢购：初值 %d，%d 线程各买 1 件 → 成功 %d、失败 %d；SUM %d，最小桶 %d，"
                        + "sku.stock %d（未被扣减路径改写=%b），待回写名单 %d 个 SKU%n",
                initial, threads, success.get(), failed.get(), sum, minBucketStock(skuId),
                skuStockOf(skuId), skuStockOf(skuId) == cacheBefore, dirtyMarker.pendingCount());

        assertThat(success.get()).as("**不超卖：成功件数必须恰好等于初值**").isEqualTo(initial);
        assertThat(failed.get()).as("其余请求必须明确失败（库存不足），不能静默成功").isEqualTo(threads - initial);
        assertThat(sum).as("**SUM 必须恰好扣光，不能多扣**").isZero();
        assertThat(sawNegative.get()).as("**任何时刻 SUM 都不许为负**").isFalse();
        assertThat(minBucketStock(skuId)).as("单桶也不许为负（表上还有 CHECK 兜底）").isGreaterThanOrEqualTo(0);

        // 核心收益的证据：扣减路径一次都没写 sku.stock（写它就是要离开的那把热行锁）
        assertThat(skuStockOf(skuId)).as("**扣减/回补不许写 sku.stock —— 这正是 TPS 收益的来源**")
                .isEqualTo(cacheBefore);
        assertThat(dirtyMarker.pendingCount()).as("扣减后必须把 SKU 标脏，交给周期任务回写缓存")
                .isGreaterThan(0);

        // 缓存允许滞后，但刷新后必须等于真值
        bucketService.refreshSkuStockCache(skuId);
        assertThat(skuStockOf(skuId)).as("刷新缓存后 sku.stock 必须等于 SUM").isEqualTo(sum);

        // 卖光之后再买：必须一件都扣不动（判断看桶，不看缓存）
        assertThat(deductAs(syntheticOrderId(), skuId, 1)).as("库存 0 后扣减必须失败").isFalse();
        assertThat(bucketService.sumStock(skuId)).as("失败的扣减不许把库存扣成负数").isZero();
    }

    @Test
    @DisplayName("并发扣减被摊到多个桶上（不是所有请求都挤在 0 号桶）")
    void concurrentDeduct_spreadsAcrossBuckets() throws Exception {
        int initial = 80;      // 每桶 10
        int threads = 40;
        Long skuId = createSku(initial);
        List<Long> orderIds = new CopyOnWriteArrayList<>();
        AtomicInteger success = new AtomicInteger();

        runConcurrently(threads, i -> {
            long orderId = syntheticOrderId();
            orderIds.add(orderId);
            if (deductAs(orderId, skuId, 1)) {
                success.incrementAndGet();
            }
        });

        int distinctBuckets = distinctLedgerBuckets(orderIds, skuId);
        System.out.printf("① %d 线程各扣 1 件：成功 %d，台账落在 %d 个不同桶上（共 %d 桶）%n",
                threads, success.get(), distinctBuckets, BUCKETS);

        assertThat(success.get()).as("库存充足，40 次都必须成功").isEqualTo(threads);
        assertThat(bucketService.sumStock(skuId)).as("SUM = 80 - 40").isEqualTo(initial - threads);
        assertThat(distinctBuckets).as("**并发必须真的分散到多个桶，否则等于没拆桶**").isGreaterThan(1);
    }

    // ================================================================== 换桶

    @Test
    @DisplayName("0 号桶空了自动换下一个桶：扣减成功，台账记的是真正扣的那个桶")
    void deduct_skipsToNextBucketWhenBucketIsEmpty() {
        Long skuId = createSku(3);                                     // 8 桶：0 号 3 件，其余 0
        jdbc.update("UPDATE sku_bucket SET stock = 0 WHERE sku_id = ?", skuId);
        jdbc.update("UPDATE sku_bucket SET stock = 3 WHERE sku_id = ? AND bucket_no = 5", skuId);

        long orderId = syntheticOrderId();
        boolean ok = deductAs(orderId, skuId, 3);

        System.out.printf("① 只有 5 号桶有 3 件，扣 3 件 → %s；台账桶号 %s；SUM %d%n",
                ok, ledgerBucketNo(orderId, skuId), bucketService.sumStock(skuId));

        assertThat(ok).as("空桶要跳过，不能因为 0 号桶是空的就判库存不足").isTrue();
        assertThat(ledgerBucketNo(orderId, skuId)).as("**台账要记真正扣的那个桶**").isEqualTo(5);
        assertThat(bucketService.sumStock(skuId)).isZero();
    }

    @Test
    @DisplayName("单个桶装不下一单、但总量够 → 跨桶拼单成功（平均拆桶不能让大额订单假性缺货）")
    void deduct_spillsAcrossBucketsWhenSingleBucketTooSmall() {
        Long skuId = createSku(10);           // 8 桶：0 号 3 件，1..7 号各 1 件
        int maxBucket = maxBucketStock(skuId);
        assertThat(maxBucket).as("前提：没有任何一个桶装得下 5 件").isLessThan(5);

        boolean ok = deductAs(syntheticOrderId(), skuId, 5);

        System.out.printf("① 总量 10 件、最大单桶 %d 件，扣 5 件 → %s；SUM %d%n",
                maxBucket, ok, bucketService.sumStock(skuId));

        assertThat(ok).as("**总量够就必须扣得动**").isTrue();
        assertThat(bucketService.sumStock(skuId)).as("10 - 5").isEqualTo(5);
    }

    // ================================================================== 回补

    @Test
    @DisplayName("回补回到台账记的那个桶（怎么出去的、就怎么回来）")
    void restore_returnsToTheBucketRecordedInLedger() {
        Long skuId = createSku(40);
        Long orderId = createRealOrder(skuId, 3);        // 这单买了 3 件（数量守恒的基准）
        int initial = bucketService.sumStock(skuId);
        int cacheBefore = skuStockOf(skuId);

        assertThat(deductAs(orderId, skuId, 2)).isTrue();
        Integer deductBucket = ledgerBucketNo(orderId, skuId);
        assertThat(deductBucket).as("扣减必须把桶号记进台账").isNotNull();
        int bucketAfterDeduct = bucketStock(skuId, deductBucket);
        assertThat(bucketService.sumStock(skuId)).isEqualTo(initial - 2);

        int restored = restoreAs(orderId, StockLedger.REASON_AUDIT_REJECT, skuId, 2);

        System.out.printf("① 扣 2 件落在 %d 号桶（该桶 %d → %d）；回补 %d 件后 SUM %d（初值 %d），"
                        + "sku.stock 未被回补路径改写=%b%n",
                deductBucket, bucketAfterDeduct, bucketStock(skuId, deductBucket), restored,
                bucketService.sumStock(skuId), initial, skuStockOf(skuId) == cacheBefore);

        assertThat(restored).as("回补 2 件").isEqualTo(2);
        assertThat(bucketStock(skuId, deductBucket)).as("**必须回到原桶**：该桶 +2")
                .isEqualTo(bucketAfterDeduct + 2);
        assertThat(bucketService.sumStock(skuId)).as("总量回到初值").isEqualTo(initial);
        assertThat(skuStockOf(skuId)).as("回补同样不许写 sku.stock（缓存由周期任务回写）")
                .isEqualTo(cacheBefore);

        bucketService.refreshSkuStockCache(skuId);
        assertThat(skuStockOf(skuId)).as("刷新后缓存 = SUM = 初值").isEqualTo(initial);
    }

    @Test
    @DisplayName("同一触发重放不会补两次（幂等键），SUM 不变")
    void restore_isIdempotentPerTrigger() {
        Long skuId = createSku(30);
        Long orderId = createRealOrder(skuId, 3);
        deductAs(orderId, skuId, 2);
        int afterDeduct = bucketService.sumStock(skuId);

        int first = restoreAs(orderId, StockLedger.REASON_AUDIT_REJECT, skuId, 2);
        int afterFirst = bucketService.sumStock(skuId);
        int second = restoreAs(orderId, StockLedger.REASON_AUDIT_REJECT, skuId, 2);
        int afterSecond = bucketService.sumStock(skuId);

        System.out.printf("① 回补第 1 次 = %d（SUM %d → %d）；第 2 次 = %d（SUM → %d）%n",
                first, afterDeduct, afterFirst, second, afterSecond);

        assertThat(first).as("第一次回补 2 件").isEqualTo(2);
        assertThat(second).as("**同一触发重放必须 0 件（不能补两次）**").isZero();
        assertThat(afterSecond).as("SUM 不许因为重放而变化").isEqualTo(afterFirst);
    }

    // ================================================================== 回填 / 重置 / 懒初始化

    @Test
    @DisplayName("回填幂等：重跑不增桶、不增库存（重复拆分会凭空多出库存）")
    void backfill_isIdempotent() {
        Long skuId = createSku(24);
        int sumBefore = bucketService.sumStock(skuId);
        assertThat(bucketCount(skuId)).as("新建 SKU 已经铺好 8 个桶").isEqualTo(BUCKETS);

        jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId);   // 造出「还没回填」的状态
        int firstRun = bucketService.backfillSku(skuId);
        int secondRun = bucketService.backfillSku(skuId);

        System.out.printf("① 回填：第一次插入 %d 行、第二次 %d 行；桶数 %d、SUM %d（初值 %d）%n",
                firstRun, secondRun, bucketCount(skuId), bucketService.sumStock(skuId), sumBefore);

        assertThat(firstRun).as("第一次按 sku.stock 拆成 8 行").isEqualTo(BUCKETS);
        assertThat(secondRun).as("**重跑必须 0 行（NOT EXISTS 守卫）**").isZero();
        assertThat(bucketCount(skuId)).as("桶数不变").isEqualTo(BUCKETS);
        assertThat(bucketService.sumStock(skuId)).as("**重跑不许让库存虚增**").isEqualTo(sumBefore);
    }

    @Test
    @DisplayName("没有任何桶的老规格：第一次扣减先按 sku.stock 懒物化，然后照常扣")
    void deduct_lazilyBackfillsSkuWithoutBuckets() {
        Long skuId = createSku(9);
        jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId);   // 模拟「迁移没跑到的老数据」
        assertThat(bucketCount(skuId)).isZero();

        boolean ok = deductAs(syntheticOrderId(), skuId, 9);

        System.out.printf("① 无桶规格扣 9 件（sku.stock=9）→ %s；物化后桶数 %d、SUM %d%n",
                ok, bucketCount(skuId), bucketService.sumStock(skuId));

        assertThat(ok).as("**不能因为「还没建桶」就报库存不足**").isTrue();
        assertThat(bucketCount(skuId)).as("扣减时按 sku.stock 物化成 8 个桶").isEqualTo(BUCKETS);
        assertThat(bucketService.sumStock(skuId)).isZero();
    }

    @Test
    @DisplayName("管理端把库存改成 X = 用 X 重新铺桶（只改缓存等于没改库存，那是最坏的不一致）")
    void resetTo_rebuildsBucketsFromTotal() {
        Long skuId = createSku(16);
        int rows = bucketService.resetTo(skuId, 33);
        bucketService.refreshSkuStockCache(skuId);

        System.out.printf("① 重置为 33 → 写入 %d 行；桶数 %d、SUM %d、sku.stock %d、最大桶 %d%n",
                rows, bucketCount(skuId), bucketService.sumStock(skuId),
                skuStockOf(skuId), maxBucketStock(skuId));

        assertThat(rows).as("N 个桶各一行").isEqualTo(BUCKETS);
        assertThat(bucketService.sumStock(skuId)).as("总量 = 33").isEqualTo(33);
        assertThat(skuStockOf(skuId)).as("缓存回写后 = 33").isEqualTo(33);
        assertThat(maxBucketStock(skuId)).as("余数进 0 号桶（33 = 4×8+1 ⇒ 0 号桶 5）").isEqualTo(5);
    }

    // ================================================================== 关键不变量

    @Test
    @DisplayName("关键不变量：并发扣减 + 并发刷新缓存 + 并发读 之下，SUM 永不为负、永不超卖")
    void invariant_sumNeverNegative_andNoOversell_underMixedLoad() throws Exception {
        int initial = 60;
        int writerThreads = 16;
        int attemptsPerWriter = 8;      // 共 128 次尝试，库存只有 60 → 必然有一半失败
        Long skuId = createSku(initial);

        AtomicInteger success = new AtomicInteger();
        List<String> violations = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + 8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        List<Future<?>> observers = new ArrayList<>();

        for (int t = 0; t < writerThreads; t++) {
            writers.add(pool.submit(() -> {
                await(go);
                for (int i = 0; i < attemptsPerWriter; i++) {
                    if (deductAs(syntheticOrderId(), skuId, 1)) {
                        success.incrementAndGet();
                    }
                }
            }));
        }
        // 读线程：一直采样 SUM，验证「任何时刻都不为负」
        for (int t = 0; t < 4; t++) {
            observers.add(pool.submit(() -> {
                await(go);
                while (!stop.get()) {
                    int sum = bucketService.sumStock(skuId);
                    if (sum < 0) {
                        violations.add("SUM=" + sum);
                    }
                }
            }));
        }
        // 刷新线程：一直把缓存刷成 SUM（模拟后台回写任务并发跑），缓存竞态不许影响裁决
        for (int t = 0; t < 4; t++) {
            observers.add(pool.submit(() -> {
                await(go);
                while (!stop.get()) {
                    bucketService.refreshSkuStockCache(skuId);
                }
            }));
        }

        go.countDown();
        for (Future<?> f : writers) {
            f.get(90, TimeUnit.SECONDS);      // 线程里的异常会在这里抛出来
        }
        stop.set(true);
        for (Future<?> f : observers) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        int sum = bucketService.sumStock(skuId);
        System.out.printf("① 混合负载：初值 %d，成功 %d 次 × 1 件；SUM %d；负值采样 %d 次；最小桶 %d%n",
                initial, success.get(), sum, violations.size(), minBucketStock(skuId));

        assertThat(violations).as("**SUM 永不为负**").isEmpty();
        assertThat(sum).as("**不超卖：初值 = 成功件数 + 剩余**").isEqualTo(initial - success.get());
        assertThat(sum).isGreaterThanOrEqualTo(0);
        assertThat(success.get()).as("128 次尝试、60 件库存：成功数不许超过库存")
                .isLessThanOrEqualTo(initial);
        assertThat(minBucketStock(skuId)).isGreaterThanOrEqualTo(0);

        bucketService.refreshSkuStockCache(skuId);
        assertThat(skuStockOf(skuId)).as("刷新后 sku.stock = SUM").isEqualTo(sum);
        assertThat(skuStockOf(skuId)).as("**缓存也不许是负数**（界面不能显示负库存）")
                .isGreaterThanOrEqualTo(0);
    }

    // ================================================================== 数据构造

    /** 造一个商品 + 规格（初始库存 total），返回 skuId；同时按 total 把桶铺好 */
    private Long createSku(int total) {
        jdbc.update("INSERT INTO product (name, description, category_id, type_id, publish_status) "
                        + "VALUES (?, '库存分桶集成测试专用（跑完即删）', ?, ?, 0)",
                "分桶测试商品-" + System.nanoTime(), categoryId, typeId);
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdProducts.add(productId);

        jdbc.update("INSERT INTO sku (product_id, price, stock, version) VALUES (?, ?, ?, 0)",
                productId, new BigDecimal("9.90"), total);
        Long skuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdSkus.add(skuId);

        bucketService.resetTo(skuId, total);   // 与「管理端新建规格」同一口径
        return skuId;
    }

    /** 造一张真订单 + 一条明细（回补用例需要它当数量守恒的基准） */
    private Long createRealOrder(Long skuId, int quantity) {
        Long productId = productIdOf(skuId);
        jdbc.update("INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version) "
                        + "VALUES (?, ?, ?, 0, 0, '分桶测试收货人', '13700000000', '自动化测试地址（跑完即删）', 0, 0)",
                "BKT" + System.nanoTime(), memberId, BigDecimal.TEN);
        Long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdOrders.add(orderId);

        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, product_price, "
                        + "product_quantity) VALUES (?, ?, ?, '分桶测试明细', ?, ?)",
                orderId, productId, skuId, new BigDecimal("9.90"), quantity);
        return orderId;
    }

    /**
     * 一笔扣减（每次都用新订单号，绕开台账幂等键，模拟不同买家）。
     *
     * <p>遇到死锁/锁等待失败时按生产口径重试（{@code OrderCreateService#createWithRetry}：
     * 死锁牺牲方整笔事务已回滚，重试是安全的；重试次数在这里放宽到 5 次并抖动退避）。
     * 生产里这层重试在 {@code PortalOrderController} 外层，服务级测试必须自己带上，
     * 否则会量到「框架没兜底」，而不是「分桶能不能扛并发」。
     */
    private boolean deductAs(Long orderId, Long skuId, int qty) {
        for (int attempt = 0; ; attempt++) {
            StockLedgerContext.Attribution outer = StockLedgerContext.open(
                    orderId, StockLedger.REASON_ORDER_CREATE, null, null);
            try {
                return skuService.deductStock(skuId, qty);
            } catch (org.springframework.dao.ConcurrencyFailureException e) {
                deadlockRetries.incrementAndGet();
                if (attempt >= 5) {
                    throw e;
                }
                try {
                    Thread.sleep(30L * (1L << attempt) + ThreadLocalRandom.current().nextInt(30));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("重试等待被中断", ie);
                }
            } finally {
                StockLedgerContext.close(outer);
            }
        }
    }

    /** 一次回补；返回实际回补件数（0 = 触发重放被幂等键跳过） */
    private int restoreAs(Long orderId, String reason, Long skuId, int qty) {
        StockLedgerContext.Attribution outer = StockLedgerContext.open(orderId, reason, null, null);
        try {
            return stockLedgerService.restore(skuId, qty);
        } finally {
            StockLedgerContext.close(outer);
        }
    }

    // ------------------------------------------------------------------ 查询辅助

    private Long productIdOf(Long skuId) {
        return jdbc.queryForObject("SELECT product_id FROM sku WHERE id = ?", Long.class, skuId);
    }

    private int skuStockOf(Long skuId) {
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }

    private int bucketCount(Long skuId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sku_bucket WHERE sku_id = ?", Integer.class, skuId);
    }

    private int bucketStock(Long skuId, int bucketNo) {
        return jdbc.queryForObject("SELECT stock FROM sku_bucket WHERE sku_id = ? AND bucket_no = ?",
                Integer.class, skuId, bucketNo);
    }

    private int minBucketStock(Long skuId) {
        return jdbc.queryForObject("SELECT COALESCE(MIN(stock),0) FROM sku_bucket WHERE sku_id = ?",
                Integer.class, skuId);
    }

    private int maxBucketStock(Long skuId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(stock),0) FROM sku_bucket WHERE sku_id = ?",
                Integer.class, skuId);
    }

    private Integer ledgerBucketNo(Long orderId, Long skuId) {
        List<Integer> rows = jdbc.queryForList("SELECT bucket_no FROM stock_ledger "
                        + "WHERE order_id = ? AND sku_id = ? AND delta < 0 AND bucket_no IS NOT NULL",
                Integer.class, orderId, skuId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int distinctLedgerBuckets(List<Long> orderIds, Long skuId) {
        if (orderIds.isEmpty()) {
            return 0;
        }
        String in = String.join(",", orderIds.stream().map(String::valueOf).toList());
        Integer n = jdbc.queryForObject("SELECT COUNT(DISTINCT bucket_no) FROM stock_ledger "
                + "WHERE sku_id = ? AND delta < 0 AND order_id IN (" + in + ")", Integer.class, skuId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 并发辅助

    /** 合成订单号：台账没有外键，只为让每个请求拿到独立幂等键；发号器保证绝不重复 */
    private long syntheticOrderId() {
        long id = orderIdSeq.incrementAndGet();
        ledgerOrderIds.add(id);
        return id;
    }

    /** 并发跑 threads 个任务，同一个起跑线放行（避免「其实没有并发」） */
    private void runConcurrently(int threads, IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                await(go);
                task.accept(index);
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("并发任务必须在 120s 内跑完").isTrue();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待起跑失败", e);
        }
    }
}
