package com.qiqi.mall.audit;

import com.qiqi.mall.audit.service.AuditQueryService;
import com.qiqi.mall.support.ItCleanup;
import com.qiqi.mall.support.ItDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>队列的两个查询必须是同一口径：{@code total} == 翻页拿到的行数</b>。
 *
 * <h3>钉的是哪个 bug（T6 实测出来的那一处）</h3>
 * {@code selectQueue} 有 {@code JOIN `order` o ON o.id = d.order_id}，{@code countQueue} 曾经没有。
 * 决策台账<b>刻意不建指向 order 的外键</b>（V1 注释：「证据不随订单级联删除」），
 * 所以「订单被删、决策行还在」的<b>孤儿行</b>是合法数据，不是脏数据：
 * <pre>
 *   selectQueue: FROM order_audit_decision d JOIN `order` o ...  ⇒ 孤儿行<b>不出现在 rows 里</b>
 *   countQueue : FROM order_audit_decision d（无 JOIN）          ⇒ 孤儿行<b>被算进 total</b>
 *   ⇒ total &gt; rows.length，分页器多出一页空白，e2e 的「total 与行数同口径」当场红
 * </pre>
 * 上一轮为了让 e2e 变绿，把 dev 库里那 2 条孤儿行删了 —— 那是治症状。本类治本：
 * <b>造一条孤儿行，断言 total 与 rows 逐个组合相等</b>。修复前必然红（total 比 rows 多 1），修复后绿。
 *
 * <h3>为什么要连 {@code ?result=} / {@code ?tag=} 一起钉</h3>
 * 只补 JOIN 而不逐条比对 where 子句，是这类 bug 最常见的"补了一半"：
 * 两边各有一条 {@code <if>}，{@code tag} 那条是 {@code EXISTS}（不是 JOIN，因为一单多标会复制行）。
 * 所以本类把「无筛选 / 仅 result / 仅 tag / tag+result / 不存在的 tag」五种组合都过一遍，
 * 每种都断言 {@code total == 实际翻出来的行数 == 带 JOIN 的独立 COUNT(*)}。
 *
 * <h3>顺带钉住的三件事（P0 的三个冻结子查询不许变成过滤条件）</h3>
 * {@code frozen / frozen_reason / frozen_tag_code} 在 {@code selectQueue} 里是
 * <b>SELECT 列表里的相关子查询</b>（返回列，不影响行数），不是 JOIN、更不是 WHERE：
 * <ul>
 *   <li>没冻结的单必须<b>照样出现</b>（若 EXISTS 掉进 WHERE，它就会消失）；</li>
 *   <li>冻结的单<b>只出现一行</b>（若改成 JOIN 标表，一单多标就会复制行，total 又对不上）。</li>
 * </ul>
 *
 * <p>造数（隔离库 qiqi_mall_it，用完即重建；本类自建自清）：
 * <pre>
 *   QT-7101  result=MANUAL    无标
 *   QT-7102  result=NO_MATCH  挂着 active NO_POLICY_MATCH
 *   QT-7103  result=MANUAL    挂着 active FROZEN（冻结事实，验证子查询不是过滤条件）
 *   999000001（**订单表里没有这个 id**）result=MANUAL + active NO_POLICY_MATCH ⇒ 孤儿行
 * </pre>
 *
 * <p>运行：{@code ./scripts/run-it.sh -Dtest=AuditQueueCountParityIntegrationTest}
 */
@SpringBootTest(properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditQueueCountParityIntegrationTest {

    private static final String MEMBER = "audit_queuecount_member";
    private static final String PRODUCT = "队列口径测试商品";
    private static final String SN_PREFIX = "QT-710";

    /** 订单表里**不存在**的 id：用它造「决策行指向已删除订单」的孤儿行 */
    private static final long ORPHAN_ORDER_ID = 999_000_001L;

    @DynamicPropertySource
    static void itDatasource(DynamicPropertyRegistry registry) {
        ItDatabase.prepare();
        registry.add("spring.datasource.url", ItDatabase::url);
        registry.add("spring.datasource.username", ItDatabase::username);
        registry.add("spring.datasource.password", ItDatabase::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuditQueryService queryService;

    private Long memberId;
    private Long productId;
    private Long skuId;

    // ================================================================== 准备

    @BeforeAll
    void seed() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedMemberAndProduct();

        createOrder("QT-7101", 7);
        createOrder("QT-7102", 7);
        createOrder("QT-7103", 7);

        // 决策行：每单最新一轮（round 1）。run_id 无外键，取一个常数即可。
        insertDecision(orderIdOf("QT-7101"), "MANUAL", "T7：真实单（无标）");
        insertDecision(orderIdOf("QT-7102"), "NO_MATCH", "T7：真实单（NO_POLICY_MATCH）");
        insertDecision(orderIdOf("QT-7103"), "MANUAL", "T7：真实单（FROZEN）");
        // ★ 孤儿行：order_id 指向一张**不存在的单** —— 合法数据（台账无外键、证据不级联删除）
        insertDecision(ORPHAN_ORDER_ID, "MANUAL", "T7：孤儿决策行（订单已删除，决策行留下）");
        // 孤儿行也挂标：?tag= 那条 EXISTS 只有两边都有才会一起漏，所以孤儿行必须能同时命中 result 与 tag
        insertTag(ORPHAN_ORDER_ID, "NO_POLICY_MATCH", "T7：孤儿单上的标");

        insertTag(orderIdOf("QT-7102"), "NO_POLICY_MATCH", "T7：不命中");
        insertTag(orderIdOf("QT-7103"), "FROZEN", "T7：人工冻结");

        // 造数自证：孤儿行真的在库里、且它的订单真的不存在
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_audit_decision WHERE order_id = ?",
                Integer.class, ORPHAN_ORDER_ID)).as("造数前提：孤儿决策行已写入").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?",
                Integer.class, ORPHAN_ORDER_ID)).as("造数前提：订单表里没有这个 id ⇒ 这是一条孤儿行").isZero();
        System.out.println("[准备] QT-7101/7102/7103 + 孤儿决策行（order_id=" + ORPHAN_ORDER_ID + "）就绪");
    }

    @AfterAll
    void restore() {
        // 自建自清：孤儿行不在任何订单名下，purgeOrders 看不见它，必须显式删
        int orphanDecisions = jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", ORPHAN_ORDER_ID);
        int orphanTags = jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", ORPHAN_ORDER_ID);
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, SN_PREFIX + "%"));
        deleted.putAll(ItCleanup.purgeCatalog(jdbc, PRODUCT, MEMBER));
        System.out.println("[清理] 孤儿决策行 " + orphanDecisions + " 条 / 孤儿标 " + orphanTags + " 条；按订单清：" + deleted);
        ItCleanup.residueOfOrders(jdbc, SN_PREFIX + "%")
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        ItCleanup.residueOfCatalog(jdbc, PRODUCT, MEMBER)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_audit_decision WHERE order_id = ?",
                Integer.class, ORPHAN_ORDER_ID)).as("清理后孤儿决策行必须为 0").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_audit_tag WHERE order_id = ?",
                Integer.class, ORPHAN_ORDER_ID)).as("清理后孤儿标必须为 0").isZero();
    }

    // ============================================================ ① 无筛选

    @Test
    @Order(1)
    @DisplayName("①无筛选：total == 行数（孤儿行被两边一致地排除，不再只算进 total）")
    void totalMatchesRowsWithoutFilter() {
        assertSameGauge("无筛选", null, null);
        QueuePage q = page(null, null);
        assertThat(snsOf(q.rows())).as("三张真实单都在队列里（孤儿行不在，它没有订单可 JOIN）")
                .contains("QT-7101", "QT-7102", "QT-7103");
        assertThat(q.rows()).as("孤儿行绝不出现在 rows 里").noneMatch(this::isOrphanRow);
        System.out.println("[①] total=" + q.total() + "，rows=" + q.rows().size()
                + "，孤儿行在库里但不被计数（total 已与 rows 同口径）");
    }

    // ============================================================ ② result 过滤

    @Test
    @Order(2)
    @DisplayName("②?result=MANUAL：total == 行数（孤儿行也是 MANUAL，修复前 total 会比行数多 1）")
    void totalMatchesRowsWithResultFilter() {
        assertSameGauge("result=MANUAL", "MANUAL", null);
        assertSameGauge("result=NO_MATCH", "NO_MATCH", null);
        QueuePage manual = page("MANUAL", null);
        assertThat(snsOf(manual.rows())).as("QT-7101/7103 是 MANUAL；孤儿行（也是 MANUAL）不该被算进来")
                .contains("QT-7101", "QT-7103");
        System.out.println("[②] result=MANUAL → total=" + manual.total() + "，rows=" + manual.rows().size());
    }

    // ============================================================ ③ tag 过滤（EXISTS 必须两边一致）

    @Test
    @Order(3)
    @DisplayName("③?tag=：EXISTS 子查询两边逐字一致（含 tag+result 并列、不存在的标）")
    void totalMatchesRowsWithTagFilter() {
        assertSameGauge("tag=NO_POLICY_MATCH", null, "NO_POLICY_MATCH");
        assertSameGauge("tag+result", "NO_MATCH", "NO_POLICY_MATCH");
        // 这一组最锋利：孤儿行是 MANUAL + 挂着 NO_POLICY_MATCH，修复前 countQueue 会数到它、selectQueue 数不到
        assertSameGauge("tag=NO_POLICY_MATCH + result=MANUAL", "MANUAL", "NO_POLICY_MATCH");
        assertSameGauge("tag=NO_SUCH_TAG", null, "NO_SUCH_TAG");

        QueuePage byTag = page(null, "NO_POLICY_MATCH");
        assertThat(snsOf(byTag.rows())).as("挂着 NO_POLICY_MATCH 的真实单要筛得到").contains("QT-7102");
        assertThat(byTag.rows()).as("按标筛出来的每一行必须真的挂着这个标")
                .allMatch(r -> strings(r.get("allTagCodes")).contains("NO_POLICY_MATCH"));
        assertThat(page("MANUAL", "NO_POLICY_MATCH").rows())
                .as("MANUAL 且挂 NO_POLICY_MATCH 的**真实单**一张都没有 ⇒ rows 必须为空（孤儿行不算）").isEmpty();
        assertThat(page(null, "NO_SUCH_TAG").total()).as("不存在的标 → 空集合，不是全返回").isZero();
        System.out.println("[③] tag=NO_POLICY_MATCH → total=" + byTag.total() + "，rows=" + byTag.rows().size()
                + "；tag+result(MANUAL) → total=" + page("MANUAL", "NO_POLICY_MATCH").total()
                + "，rows=" + page("MANUAL", "NO_POLICY_MATCH").rows().size());
    }

    // ============================================================ ④ 冻结三列只是返回列

    @Test
    @Order(4)
    @DisplayName("④frozen/frozen_reason/frozen_tag_code 是 SELECT 列不是过滤条件：不冻的照样在、冻结的不复制行")
    void frozenFactsAreSelectedColumnsNotFilters() {
        QueuePage all = page(null, null);
        List<Map<String, Object>> frozenRows = all.rows().stream()
                .filter(r -> "QT-7103".equals(String.valueOf(r.get("order_sn")))).toList();
        assertThat(frozenRows).as("同一张单只出一行（三个子查询都是相关子查询，没有 JOIN 标表）").hasSize(1);
        Map<String, Object> frozenRow = frozenRows.get(0);
        assertThat(frozenRow.get("frozen")).as("frozen 恒为 boolean").isEqualTo(true);
        assertThat(frozenRow.get("frozenTagCode")).isEqualTo("FROZEN");
        assertThat(frozenRow.get("frozenReason")).as("冻结原因来自标表的 reason 列").isEqualTo("T7：人工冻结");

        Map<String, Object> plainRow = all.rows().stream()
                .filter(r -> "QT-7101".equals(String.valueOf(r.get("order_sn")))).findFirst().orElseThrow();
        assertThat(plainRow.get("frozen")).as("没挂冻结标的单 frozen=false，且**照样出现在队列里**"
                + "（若这条 EXISTS 掉进 WHERE，它就会整行消失）").isEqualTo(false);
        assertThat(plainRow.get("frozenTagCode")).isNull();
        System.out.println("[④] QT-7103 frozen=" + frozenRow.get("frozen") + " tag=" + frozenRow.get("frozenTagCode")
                + "，QT-7101 frozen=" + plainRow.get("frozen") + "（两张单都在 rows 里）");
    }

    // ------------------------------------------------------------------ 断言工具

    /**
     * 同一个筛选组合下三把尺子必须量出同一个数：
     * {@code total}（countQueue 的答案）、实际翻出来的行数、以及测试自己写的「带 JOIN 的」参考计数。
     */
    private void assertSameGauge(String what, String result, String tag) {
        QueuePage q = page(result, tag);
        assertThat(q.total())
                .as("[%s] total 必须等于实际翻出来的行数（修复前：countQueue 少了 JOIN，孤儿行把 total 撑大）", what)
                .isEqualTo(q.rows().size());
        assertThat(q.total())
                .as("[%s] total 必须等于带 JOIN 的独立 COUNT(*)（两边 where 也必须逐条一致）", what)
                .isEqualTo(referenceCount(result, tag));
        assertThat(q.rows()).as("[%s] 孤儿行不许出现在 rows 里", what).noneMatch(this::isOrphanRow);
    }

    /** 翻完所有页，返回 total 与累积行数（不靠"库正好很小"这种运气） */
    private QueuePage page(String result, String tag) {
        long total = -1;
        List<Map<String, Object>> all = new ArrayList<>();
        for (int p = 1; p <= 50; p++) {
            Map<String, Object> one = queryService.queue(result, tag, p, 100);
            total = ((Number) one.get("total")).longValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) one.get("rows");
            all.addAll(rows);
            if (rows.size() < 100) break;
        }
        return new QueuePage(total, all);
    }

    private record QueuePage(long total, List<Map<String, Object>> rows) {
    }

    /** 参考口径：与 selectQueue 同一条 JOIN + 同两个筛选（测试自己写一遍，不复用被测 SQL） */
    private long referenceCount(String result, String tag) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM order_audit_decision d "
                + "JOIN `order` o ON o.id = d.order_id "
                + "WHERE d.id IN (SELECT MAX(id) FROM order_audit_decision GROUP BY order_id)");
        List<Object> args = new ArrayList<>();
        if (result != null) {
            sql.append(" AND d.result = ?");
            args.add(result);
        }
        if (tag != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM order_audit_tag ft WHERE ft.order_id = d.order_id "
                    + "AND ft.active = 1 AND ft.tag_code = ?)");
            args.add(tag);
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? -1L : n;
    }

    private boolean isOrphanRow(Map<String, Object> row) {
        Object raw = row.get("order_id");
        return raw != null && ((Number) raw).longValue() == ORPHAN_ORDER_ID;
    }

    private List<String> snsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> String.valueOf(r.get("order_sn"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        return raw == null ? List.of() : (List<String>) raw;
    }

    // ------------------------------------------------------------------ 造数工具

    private void cleanOurData() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE ?", Long.class, SN_PREFIX + "%");
        for (Long id : ids) {
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE ?", SN_PREFIX + "%");
        jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", ORPHAN_ORDER_ID);
        jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", ORPHAN_ORDER_ID);
        jdbc.update("DELETE FROM member WHERE username = ?", MEMBER);
        jdbc.update("DELETE FROM product WHERE name = ?", PRODUCT);
    }

    private void seedMemberAndProduct() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '队列口径测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '队列口径测试类型')");
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '队列口径测试会员', 1, NOW(), 0, '普通')", MEMBER);
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES (?, '队列口径用', ?, ?, 1, 0)", PRODUCT, categoryId, typeId);
        skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, ?, ?, 0, ?)",
                productId, new BigDecimal("268.00"), 1000, new BigDecimal("200.00"));
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, ?)", skuId, 1000);
    }

    private void createOrder(String sn, int status) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, ?, 1, NOW(), NOW(), '李四', '13900000000', '北京市海淀区yy路2号', 0, 0, 0, "
                        + "'淘宝', '队列口径旗舰店', ?, NULL, 0)",
                sn, memberId, new BigDecimal("268.00"), status, new BigDecimal("68.00"));
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                        + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, productId, skuId, PRODUCT, new BigDecimal("268.00"), 1, new BigDecimal("200.00"));
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) VALUES (?, NULL, ?, 'ORDER_CREATE', "
                        + "'队列口径集成测试造数', 'SYSTEM', 'system', ?)",
                orderId, status, orderId + ":0");
    }

    private void insertDecision(long orderId, String result, String reason) {
        jdbc.update("INSERT INTO order_audit_decision (order_id, run_id, round_no, result, operator_type, idem_key, "
                        + "evidence_json, created_at) VALUES (?, 1, 1, ?, 'SYSTEM', ?, JSON_OBJECT('reason', ?), NOW(3))",
                orderId, result, orderId + ":1", reason);
    }

    private void insertTag(long orderId, String tagCode, String reason) {
        jdbc.update("INSERT INTO order_audit_tag (order_id, tag_code, active, reason, created_at) "
                + "VALUES (?, ?, 1, ?, NOW(3))", orderId, tagCode, reason);
    }

    private Long orderIdOf(String orderSn) {
        return jdbc.queryForObject("SELECT id FROM `order` WHERE order_sn = ?", Long.class, orderSn);
    }

    private Long insertAndReturnKey(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("插入未返回主键：" + sql);
        return key.longValue();
    }
}
