package com.qiqi.mall.stock;

import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.stock.entity.StockLedger;
import com.qiqi.mall.stock.mapper.StockLedgerMapper;
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
 * <b>台账插入改写</b>（{@code qiqi.stock.ledger-insert-ignore=true}）的回归测试。
 *
 * <h3>改了什么、为什么</h3>
 * baseline 的 {@code INSERT ... SELECT ... WHERE NOT EXISTS} 会对 uk_idem 做范围扫描，
 * 在 REPEATABLE READ 下取间隙 S 锁，与并发插入的 intention X 锁成环 —— 压测抓到 45,790 次死锁。
 * 改成单语句 {@code INSERT IGNORE ... VALUES}（唯一索引点插入、不扫表、不取间隙锁）后：
 * <b>判据必须一字不变</b> —— 影响 0 行 = 幂等键已存在 = 重放。
 *
 * <p>本类钉的就是这个等价性：重放仍然 0 行、仍然只记一次、库存不被二次扣减。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=StockLedgerInsertIgnoreTest
 */
@SpringBootTest(properties = {
        "qiqi.stock.bucket-enabled=false",
        "qiqi.stock.ledger-insert-ignore=true",   // ← 被测的就是这条写法
        "qiqi.stock.cache-refresh-ms=3600000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockLedgerInsertIgnoreTest {

    @Autowired
    private SkuService skuService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockLedgerMapper ledgerMapper;

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
        assertThat(properties.isLedgerInsertIgnore()).as("本类必须跑在 INSERT IGNORE 模式下").isTrue();
        assertThat(stockLedgerService.ledgerMode()).as("台账模式标识").isEqualTo("ignore");

        categoryId = jdbc.queryForObject("SELECT id FROM category ORDER BY id LIMIT 1", Long.class);
        typeId = jdbc.queryForObject("SELECT id FROM product_type ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO member (username, password, nickname, phone, status) VALUES (?, '', ?, NULL, 1)",
                "ledger-ignore-" + System.currentTimeMillis(), "台账写法测试会员");
        memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("INSERT INTO product (name, description, category_id, type_id, publish_status) "
                + "VALUES (?, '台账 INSERT IGNORE 测试专用（跑完即删）', ?, ?, 0)",
                "台账写法测试商品-" + System.nanoTime(), categoryId, typeId);
        productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO sku (product_id, price, stock, version) VALUES (?, ?, 100, 0)",
                productId, new BigDecimal("9.90"));
        skuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        System.out.printf("[准备] 台账模式=%s（INSERT IGNORE），SKU=%d%n", stockLedgerService.ledgerMode(), skuId);
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : orderIds) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);   // 明细随外键级联
        }
        jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId);
        jdbc.update("DELETE FROM sku WHERE id = ?", skuId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
    }

    @Test
    @DisplayName("同一笔下单重放：仍只记一行台账、只扣一次库存（0 行 = 已存在 = 重放，判据不变）")
    void replayStillDeduped() {
        long orderId = newOrderId();

        int before = stock();
        assertThat(deduct(orderId, 3)).as("第一次扣减成功").isTrue();
        int afterFirst = stock();
        int rowsAfterFirst = ledgerRows(orderId);

        assertThat(deduct(orderId, 3)).as("重放按「已扣过」返回 true（与 baseline 一致）").isTrue();
        int afterSecond = stock();
        int rowsAfterSecond = ledgerRows(orderId);

        System.out.printf("① 重放：库存 %d → %d → %d；台账行数 %d → %d%n",
                before, afterFirst, afterSecond, rowsAfterFirst, rowsAfterSecond);

        assertThat(rowsAfterFirst).as("第一次记一行台账").isEqualTo(1);
        assertThat(rowsAfterSecond).as("**重放不许记第二行**").isEqualTo(1);
        assertThat(afterSecond).as("**重放不许二次扣库存**").isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("INSERT IGNORE 的返回值判据：第一次 1 行、第二次 0 行，且幂等键确实在库里")
    void insertIgnoreReturnsZeroOnDuplicate() {
        String idemKey = "IGNORE-TEST-" + System.nanoTime();
        long orderId = newOrderId();

        int first = ledgerMapper.insertIgnore(orderId, skuId, -1, StockLedger.REASON_ORDER_CREATE,
                null, null, null, idemKey);
        int second = ledgerMapper.insertIgnore(orderId, skuId, -1, StockLedger.REASON_ORDER_CREATE,
                null, null, null, idemKey);
        int exists = ledgerMapper.countByIdemKey(idemKey);

        System.out.printf("① 同键插两次：first=%d second=%d，库里该键计数=%d%n", first, second, exists);

        assertThat(first).as("第一次真插入").isEqualTo(1);
        assertThat(second).as("**第二次 0 行 —— 这就是「重放」的判据**").isZero();
        assertThat(exists).as("被判为重复时，幂等键必须真的存在（否则就是 IGNORE 吞了别的错误）").isEqualTo(1);
    }

    @Test
    @DisplayName("回补幂等：同一触发重放 0 件，台账与库存都不再变")
    void restoreReplayIsNoop() {
        long orderId = newOrderId();
        insertOrderItem(orderId, 3);
        assertThat(deduct(orderId, 2)).isTrue();
        int afterDeduct = stock();

        int first = restore(orderId, 2);
        int afterFirst = stock();
        int second = restore(orderId, 2);
        int afterSecond = stock();

        System.out.printf("① 回补：第一次 %d 件（库存 %d → %d），第二次 %d 件（库存 %d）%n",
                first, afterDeduct, afterFirst, second, afterSecond);

        assertThat(first).as("第一次回补 2 件").isEqualTo(2);
        assertThat(second).as("**重放 0 件**").isZero();
        assertThat(afterSecond).as("库存不再变化").isEqualTo(afterFirst);
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

    private int restore(long orderId, int qty) {
        StockLedgerContext.Attribution outer = StockLedgerContext.open(
                orderId, StockLedger.REASON_AUDIT_REJECT, null, null);
        try {
            return stockLedgerService.restore(skuId, qty);
        } finally {
            StockLedgerContext.close(outer);
        }
    }

    private void insertOrderItem(long orderId, int qty) {
        jdbc.update("INSERT INTO `order` (id, order_sn, member_id, total_amount, status, pay_type, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version) "
                        + "VALUES (?, ?, ?, ?, 0, 0, '台账写法收货人', '13700000000', '自动化测试地址（跑完即删）', 0, 0)",
                orderId, "LGI" + orderId, memberId, BigDecimal.TEN);
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, product_price, "
                        + "product_quantity) VALUES (?, ?, ?, '台账写法明细', ?, ?)",
                orderId, productId, skuId, new BigDecimal("9.90"), qty);
    }

    private long newOrderId() {
        // 台账无外键：合成订单号即可（回补用例会自己插一张真订单）
        long id = 920_000_000L + orderIds.size() + 1;
        while (!orderIds.add(id)) {
            id++;
        }
        return id;
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }

    private int ledgerRows(long orderId) {
        List<Integer> rows = jdbc.queryForList("SELECT id FROM stock_ledger WHERE order_id = ?", Integer.class, orderId);
        return rows.size();
    }
}
