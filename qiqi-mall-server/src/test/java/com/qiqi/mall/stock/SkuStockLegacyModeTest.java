package com.qiqi.mall.stock;

import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.stock.entity.StockLedger;
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
 * <b>legacy 扣减路径</b>（{@code qiqi.stock.bucket-enabled=false}，默认）的回归测试。
 *
 * <h3>为什么必须单独测一遍</h3>
 * 压测的 C / L 阶段跑的就是这条路径。分桶改造把扣减实现搬走了，如果「开关关掉时」
 * 行为与 baseline 有任何偏差（多写一行、少扣一件、死锁语义变了），那两档数据就作废。
 * 本类要证明的就是一句话：<b>关掉开关 = 完全回到了分桶之前</b>。
 *
 * <p>三条断言口径：
 * <ol>
 *   <li>{@code sku.stock} 立刻变（它就是库存本身，没有缓存滞后）、而且<b>一张桶都不建</b>；</li>
 *   <li>并发下不超卖（同一条 {@code AND stock >= N} 的原子 SQL）；</li>
 *   <li>台账照样记：{@code bucket_no} 恒为 NULL（legacy 没有桶的概念），幂等键仍是唯一判重依据。</li>
 * </ol>
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=SkuStockLegacyModeTest
 */
@SpringBootTest(properties = {
        "qiqi.stock.bucket-enabled=false",       // ← 被测的就是这条路径（默认值，显式写出来防手滑）
        "qiqi.stock.ledger-insert-ignore=false", // 台账也用 baseline 写法
        "qiqi.stock.cache-refresh-ms=3600000"     // 别让后台任务插手本类的断言
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkuStockLegacyModeTest {

    @Autowired
    private SkuService skuService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockStore stockStore;

    @Autowired
    private StockProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private Long memberId;
    private Long categoryId;
    private Long typeId;

    private final List<Long> createdProducts = new CopyOnWriteArrayList<>();
    private final List<Long> createdSkus = new CopyOnWriteArrayList<>();
    private final Set<Long> ledgerOrderIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong orderIdSeq = new AtomicLong(910_000_000L);
    /** 死锁重试次数：legacy+naive 下非 0 是预期的（这正是 L 阶段要修的东西），打印出来做证据 */
    private final AtomicInteger deadlockRetries = new AtomicInteger();

    @BeforeAll
    void prepare() {
        assertThat(properties.isBucketEnabled()).as("本类必须跑在 legacy 模式下").isFalse();
        assertThat(stockStore.mode()).as("落点实现必须是 legacy").isEqualTo("legacy");
        assertThat(stockLedgerService.ledgerMode()).as("台账插入写法必须是 baseline 的 naive").isEqualTo("naive");

        categoryId = jdbc.queryForObject("SELECT id FROM category ORDER BY id LIMIT 1", Long.class);
        typeId = jdbc.queryForObject("SELECT id FROM product_type ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO member (username, password, nickname, phone, status) VALUES (?, '', ?, NULL, 1)",
                "legacy-" + System.currentTimeMillis(), "legacy 模式测试会员");
        memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        System.out.printf("[准备] legacy 模式：stock.mode=%s ledger=%s%n",
                stockStore.mode(), stockLedgerService.ledgerMode());
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : ledgerOrderIds) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            // 回补用例插过真订单；不删它，order_item 的外键会挡住下面删 sku
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);   // 明细随外键级联
        }
        for (Long skuId : createdSkus) {
            jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId);   // 本类正常不会建桶，兜一下
            jdbc.update("DELETE FROM sku WHERE id = ?", skuId);
        }
        for (Long productId : createdProducts) {
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        System.out.printf("[清理] 商品 %d 个、规格 %d 个已删除%n", createdProducts.size(), createdSkus.size());
    }

    @Test
    @DisplayName("legacy 语义：扣减立刻反映在 sku.stock 上，且一张桶都不建（与 baseline 完全一致）")
    void deductWritesSkuStockDirectly_andCreatesNoBuckets() {
        Long skuId = createSku(10);
        int cacheBefore = skuStockOf(skuId);
        assertThat(bucketCount(skuId)).as("前置：legacy 模式不该有任何桶").isZero();

        long orderId = nextOrderId();
        assertThat(deductAs(orderId, skuId, 3)).isTrue();

        System.out.printf("① legacy 扣 3 件：sku.stock %d → %d；桶数 %d；台账 bucket_no=%s%n",
                cacheBefore, skuStockOf(skuId), bucketCount(skuId), ledgerBucketNo(orderId, skuId));

        assertThat(skuStockOf(skuId)).as("**legacy 下 sku.stock 就是库存，必须立刻变（没有缓存滞后）**")
                .isEqualTo(cacheBefore - 3);
        assertThat(bucketCount(skuId)).as("**legacy 路径不许建桶**（否则等于悄悄切模式）").isZero();
        assertThat(ledgerBucketNo(orderId, skuId)).as("legacy 台账没有桶号").isNull();
        assertThat(stockStore.availableStock(skuId)).as("权威余量 = sku.stock").isEqualTo(cacheBefore - 3);
    }

    @Test
    @DisplayName("legacy 并发不超卖：同一条 AND stock >= N 的原子 SQL，一件都不多卖")
    void concurrentDeduct_neverOversells() throws Exception {
        int initial = 25;
        int threads = 40;
        Long skuId = createSku(initial);
        AtomicInteger success = new AtomicInteger();
        AtomicBoolean sawNegative = new AtomicBoolean(false);

        runConcurrently(threads, i -> {
            if (deductAs(nextOrderId(), skuId, 1)) {
                success.incrementAndGet();
            }
            if (skuStockOf(skuId) < 0) {
                sawNegative.set(true);
            }
        });

        System.out.printf("① legacy 抢购：初值 %d，%d 线程各买 1 → 成功 %d，剩余 sku.stock %d，死锁重试 %d 次%n",
                initial, threads, success.get(), skuStockOf(skuId), deadlockRetries.get());

        assertThat(success.get()).as("**不超卖：成功件数必须恰好等于初值**").isEqualTo(initial);
        assertThat(skuStockOf(skuId)).as("库存被恰好扣光").isZero();
        assertThat(sawNegative.get()).as("任何时刻都不许出现负库存").isFalse();
        assertThat(bucketCount(skuId)).as("全程没有产生桶").isZero();
    }

    @Test
    @DisplayName("legacy 回补：直接加回 sku.stock，台账净额相抵")
    void restoreAddsBackToSkuStock() {
        Long skuId = createSku(20);
        Long orderId = createRealOrder(skuId, 3);
        int cacheBefore = skuStockOf(skuId);

        assertThat(deductAs(orderId, skuId, 2)).isTrue();
        int afterDeduct = skuStockOf(skuId);
        int restored = restoreAs(orderId, StockLedger.REASON_AUDIT_REJECT, skuId, 2);

        System.out.printf("① legacy 扣 2 → %d；回补 %d 件 → %d（初值 %d）%n",
                afterDeduct, restored, skuStockOf(skuId), cacheBefore);

        assertThat(restored).as("回补 2 件").isEqualTo(2);
        assertThat(skuStockOf(skuId)).as("**legacy 回补直接加回 sku.stock（没有桶可回）**").isEqualTo(cacheBefore);
        assertThat(ledgerNetOf(orderId, skuId)).as("台账净额 = -2 + 2 = 0").isZero();
    }

    // ------------------------------------------------------------------ 数据构造

    private Long createSku(int stock) {
        jdbc.update("INSERT INTO product (name, description, category_id, type_id, publish_status) "
                        + "VALUES (?, 'legacy 模式测试专用（跑完即删）', ?, ?, 0)",
                "legacy 测试商品-" + System.nanoTime(), categoryId, typeId);
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdProducts.add(productId);

        jdbc.update("INSERT INTO sku (product_id, price, stock, version) VALUES (?, ?, ?, 0)",
                productId, new BigDecimal("9.90"), stock);
        Long skuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdSkus.add(skuId);
        return skuId;
    }

    private Long createRealOrder(Long skuId, int quantity) {
        Long productId = jdbc.queryForObject("SELECT product_id FROM sku WHERE id = ?", Long.class, skuId);
        jdbc.update("INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version) "
                        + "VALUES (?, ?, ?, 0, 0, 'legacy 收货人', '13700000000', '自动化测试地址（跑完即删）', 0, 0)",
                "LGC" + System.nanoTime(), memberId, BigDecimal.TEN);
        Long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        ledgerOrderIds.add(orderId);
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, product_price, "
                        + "product_quantity) VALUES (?, ?, ?, 'legacy 明细', ?, ?)",
                orderId, productId, skuId, new BigDecimal("9.90"), quantity);
        return orderId;
    }

    /**
     * 一笔扣减。<b>必须带死锁重试</b> —— 这就是 baseline 的真实样子：
     * naive 台账插入（{@code INSERT ... SELECT ... WHERE NOT EXISTS}）在并发下会撞间隙锁死锁，
     * 生产里由 {@code OrderCreateService#createWithRetry} 指数退避重试（最多几次，耗尽就是
     * 「系统繁忙，请重试」= 压测里的失败请求）。服务级测试复刻这层重试，
     * 才能验证「重试之后不超卖」这个真正的不变量，而不是量到框架没兜底。
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

    private int restoreAs(Long orderId, String reason, Long skuId, int qty) {
        StockLedgerContext.Attribution outer = StockLedgerContext.open(orderId, reason, null, null);
        try {
            return stockLedgerService.restore(skuId, qty);
        } finally {
            StockLedgerContext.close(outer);
        }
    }

    private long nextOrderId() {
        long id = orderIdSeq.incrementAndGet();
        ledgerOrderIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------ 查询辅助

    private int skuStockOf(Long skuId) {
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }

    private int bucketCount(Long skuId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sku_bucket WHERE sku_id = ?", Integer.class, skuId);
    }

    private Integer ledgerBucketNo(Long orderId, Long skuId) {
        List<Integer> rows = jdbc.queryForList("SELECT bucket_no FROM stock_ledger "
                        + "WHERE order_id = ? AND sku_id = ? AND delta < 0",
                Integer.class, orderId, skuId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int ledgerNetOf(Long orderId, Long skuId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM stock_ledger "
                + "WHERE order_id = ? AND sku_id = ?", Integer.class, orderId, skuId);
    }

    // ------------------------------------------------------------------ 并发辅助

    private void runConcurrently(int threads, IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    go.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.accept(0);
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("并发任务必须在 120s 内跑完").isTrue();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
    }
}
