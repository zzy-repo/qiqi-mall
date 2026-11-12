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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「Java 算出来的打散前缀」与「SQL 回填用的公式」是否**逐字节一致** —— 这条不测就等于没测。
 *
 * <h3>为什么必须有这一条</h3>
 * 迁移脚本回填用的是 SQL：{@code CONCAT(LPAD(order_id % N, 宽度, '0'), ':', idem_key)}；
 * 应用算键用的是 Java：{@code String.format("%0Nd:", floorMod(orderId, N))}。
 * 只要两边有一点点不同（位宽、补零、floorMod vs 取模、整数转字符串的方式），
 * 回填过的旧行就永远匹配不上新算的键 → 幂等键失效 → **重复扣减/重复回补**。
 *
 * <p>所以本类做的是「拿真库跑一条扣减，再把那一行的 idem_key 用 SQL 现场拼一遍，比对是否相同」。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=IdemKeyShardSqlConsistencyTest
 */
@SpringBootTest(properties = {
        "qiqi.stock.bucket-enabled=false",
        "qiqi.stock.ledger-insert-ignore=true",
        "qiqi.stock.idem-key-shards=64",     // ← 被测配置
        "qiqi.stock.cache-refresh-ms=3600000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdemKeyShardSqlConsistencyTest {

    private static final int SHARDS = 64;
    private static final int WIDTH = 2;       // N=64 → "00".."63"

    @Autowired
    private SkuService skuService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private Long memberId;
    private Long categoryId;
    private Long typeId;
    private Long skuId;
    private Long productId;
    private final Set<Long> orderIds = ConcurrentHashMap.newKeySet();

    @BeforeAll
    void prepare() {
        assertThat(properties.getIdemKeyShards()).as("本类必须跑在 shards=64").isEqualTo(SHARDS);

        categoryId = jdbc.queryForObject("SELECT id FROM category ORDER BY id LIMIT 1", Long.class);
        typeId = jdbc.queryForObject("SELECT id FROM product_type ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO member (username, password, nickname, phone, status) VALUES (?, '', ?, NULL, 1)",
                "idem-shard-" + System.currentTimeMillis(), "幂等键打散测试会员");
        memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("INSERT INTO product (name, description, category_id, type_id, publish_status) "
                + "VALUES (?, '幂等键打散一致性测试（跑完即删）', ?, ?, 0)",
                "打散测试商品-" + System.nanoTime(), categoryId, typeId);
        productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // 初值给足：本类三个用例合计要扣 2+3+100=105 件（库存不足会伪装成「打散没生效」的假故障）
        jdbc.update("INSERT INTO sku (product_id, price, stock, version) VALUES (?, ?, 1000, 0)",
                productId, new BigDecimal("9.90"));
        skuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        System.out.printf("[准备] shards=%d（前缀宽度 %d 位）, SKU=%d%n",
                stockLedgerService.idemKeyShards(), WIDTH, skuId);
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : orderIds) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);
        }
        jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId);
        jdbc.update("DELETE FROM sku WHERE id = ?", skuId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
    }

    @Test
    @DisplayName("Java 的键 == SQL 回填公式拼出来的键（逐字节）；并且插入后在库里能按这个键查到")
    void javaKeyMatchesSqlBackfillFormula() {
        long orderId = 930_000_001L;
        orderIds.add(orderId);

        assertThat(deduct(orderId, 2)).isTrue();

        String fromDb = jdbc.queryForObject(
                "SELECT idem_key FROM stock_ledger WHERE order_id = ? AND sku_id = ? AND delta < 0",
                String.class, orderId, skuId);
        String fromSql = jdbc.queryForObject(
                "SELECT CONCAT(LPAD(? % ?, ?, '0'), ':', ?, ':', ?, ':', ?, ':', ?)",
                String.class, orderId, SHARDS, WIDTH, orderId, skuId, StockLedger.REASON_ORDER_CREATE, 0);

        System.out.printf("① 库里 idem_key = %s%n   SQL 公式     = %s%n   Java 前缀     = %s%n",
                fromDb, fromSql, StockLedgerContext.Attribution.shardPrefix(orderId, SHARDS));

        assertThat(fromDb).as("**Java 与 SQL 必须算出逐字节相同的键**").isEqualTo(fromSql);
        assertThat(fromDb).as("前缀 = orderId %% N 零填充两位").startsWith("01:");
    }

    @Test
    @DisplayName("打散后幂等仍然有效：同一触发重放只记一行、库存不再变")
    void idempotencyStillHoldsWithPrefix() {
        long orderId = 930_000_002L;
        orderIds.add(orderId);

        assertThat(deduct(orderId, 3)).isTrue();
        int afterFirst = stock();
        assertThat(deduct(orderId, 3)).as("重放按「已扣过」返回 true").isTrue();

        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE order_id = ?",
                Integer.class, orderId);
        System.out.printf("① 重放后：台账 %d 行，库存 %d（第一次之后也是这个数）%n", rows, stock());

        assertThat(rows).as("**重放不许记第二行**").isEqualTo(1);
        assertThat(stock()).as("重放不许二次扣库存").isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("100 个连续订单的前缀铺满 0..63（不是全挤在一个 gap 上）")
    void consecutiveOrdersSpreadAcrossAllShards() {
        for (int i = 0; i < 100; i++) {
            long orderId = 930_001_000L + i;
            orderIds.add(orderId);
            assertThat(deduct(orderId, 1)).isTrue();
        }
        List<String> prefixes = jdbc.queryForList(
                "SELECT DISTINCT SUBSTRING_INDEX(idem_key, ':', 1) FROM stock_ledger WHERE order_id BETWEEN ? AND ?",
                String.class, 930_001_000L, 930_001_099L);

        System.out.printf("① 100 单落在 %d 个不同前缀上（上限 %d）%n", prefixes.size(), SHARDS);

        assertThat(prefixes).as("**必须真的铺开到 64 个 gap：否则等于没打散**").hasSize(SHARDS);
    }

    // ------------------------------------------------------------------ 辅助

    private boolean deduct(long orderId, int qty) {
        StockLedgerContext.Attribution outer = StockLedgerContext.open(
                orderId, StockLedger.REASON_ORDER_CREATE, null, null);
        try {
            return skuService.deductStock(skuId, qty);
        } finally {
            StockLedgerContext.close(outer);
        }
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }
}
