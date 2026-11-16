package com.qiqi.mall.audit;

import com.qiqi.mall.audit.service.AuditQueryService;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>对账端点不许永久假红</b>：{@code pendingTagMissing} 只统计「该落标却没落」的那一类，
 * 天生不落标的结论（{@code AUTO_PASS} / {@code SKIPPED}）归 {@code noTagByDesign}，<b>不判红</b>。
 *
 * <h3>这条用例为什么必须存在（它不是「把阈值调松」的测试）</h3>
 * 旧判据把「{@code order.status=7} 且没有任何结论标」<b>一律</b>算缺口。但 V9（2026-11-16 16:38
 * 用户拍板「通过与否由状态表达，不由标表达」）之后 {@code AUTO_PASS} <b>恒不落标</b>
 * （{@link com.qiqi.mall.audit.service.ConclusionTags#codeOf} 回 {@code null}），{@code SKIPPED} 也不落
 * （它压根不写决策行时才不落 —— 历史库里却真有这种行）。这两类<b>永远补不上标</b>
 * ⇒ 只要库里真出现这么一行，{@code pendingTagMissing} 就再也回不到 0 ⇒ 端点变成<b>永久假红</b>。
 * 实测证据：一次性临时库 backfill 完 {@code pendingTagMissing=2}，正是 AUTO_PASS + SKIPPED 两行。
 *
 * <h3>三条用例各钉一件事（缺一条就只是「把数改小」）</h3>
 * <ol>
 *   <li><b>AUTO_PASS + status=7 + 无标 ⇒ {@code pendingTagMissing=0}、{@code noTagByDesign=1}</b>
 *       —— 假红消失，而且它是「被分到另一个桶」不是「被藏起来」：明细里查得到这张单；</li>
 *   <li><b>再加上 SKIPPED 同样只进 {@code noTagByDesign}</b> —— 实测里那第二行也是这么来的；</li>
 *   <li><b>真缺口照样报红</b>：{@code MANUAL}（{@code producesTag} 回 true）停在 7 且一条标都没有
 *       ⇒ {@code pendingTagMissing} 必须 +1、且<b>不许</b>混进 {@code noTagByDesign}。
 *       这条是"不许把 pendingTagMissing 直接清零"的护栏：清零会让这一单消失。</li>
 * </ol>
 *
 * <h3>为什么这些行是直插 SQL 造的（而不是跑一遍引擎）</h3>
 * 要复现的恰恰是「<b>半个事务</b>」这种数据形态：
 * <ul>
 *   <li>AUTO_PASS 行：引擎判完会把状态推到 8，<b>跑完整引擎造不出</b>「result=AUTO_PASS 且 status 还在 7」。
 *       而实测里那两行就是这个形态（决策行在、状态没动、标表 0 行）—— 端点必须容得下它；</li>
 *   <li>MANUAL 行：这是「决策写成功、落标失败」的形态（引擎漏落标），同样只有直插能造。</li>
 * </ul>
 * 造的是<b>数据形态</b>，不是假装跑过引擎：{@code run_id} 指向一条真实 {@code audit_run} 行，
 * 订单/台账/决策行按真实建单路径该有的样子写全，避免造出"永远不可能存在"的行让用例失真。
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditParityNoTagByDesignIntegrationTest}
 */
@SpringBootTest(properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditParityNoTagByDesignIntegrationTest {

    private static final String MEMBER = "audit_parity_member";
    private static final String PRODUCT = "对账不落标测试商品";

    /** AUTO_PASS 且停在 7 且无标（实测里让端点假红的行） */
    private static final String SN_AUTO_PASS = "QT-3901";
    /** SKIPPED 且停在 7 且无标（实测里那第二行） */
    private static final String SN_SKIPPED = "QT-3902";
    /** MANUAL 且停在 7 且无标 —— **真缺口**，必须照旧报红 */
    private static final String SN_REAL_GAP = "QT-3903";

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
    private Long runId;

    /** 还没造任何决策行时的基线（本类之外的单不该有真缺口，否则下面的绝对值断言会当场炸掉并把原因说清） */
    private long basePending;
    private long baseNoTagByDesign;

    // ================================================================== 准备

    @BeforeAll
    void seed() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedCatalogMemberProduct();
        seedOrder(SN_AUTO_PASS, "淘宝", "268.00", 7);
        // 这两张单先只建出来（status=7、**没有决策行** ⇒ 不进队列，不影响下面的基线），
        // 决策行由各自的用例按需补上 —— 这样每一例都能单独读出「我刚才加的这一行把哪个桶 +1」
        seedOrder(SN_SKIPPED, "抖音", "1580.00", 7);
        seedOrder(SN_REAL_GAP, "淘宝", "268.00", 7);
        runId = insertAndReturnKey(
                "INSERT INTO audit_run (trigger_type, idem_key, total, auto_pass, manual, blocked, frozen, no_match) "
                        + "VALUES ('MANUAL', NULL, 0, 0, 0, 0, 0, 0)");

        // 基线：此刻本类三张单里只有 SN_AUTO_PASS 存在、且它还没有决策行 ⇒ 不进队列。
        Map<String, Object> base = queryService.conclusionParity(20);
        basePending = number(base.get("pendingTagMissing"));
        baseNoTagByDesign = number(base.get("noTagByDesign"));
        assertThat(basePending)
                .as("用例前提：跑本例之前库里没有真缺口（否则绝对值断言会把上一例的残留算进来）")
                .isZero();
        assertThat(baseNoTagByDesign).as("用例前提：跑本例之前库里没有「天生不落标」的行").isZero();
        System.out.println("[准备] 基线 scanned=" + base.get("scanned")
                + "，pendingTagMissing=" + basePending + "，noTagByDesign=" + baseNoTagByDesign);
    }

    @AfterAll
    void restore() {
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, "QT-390%"));
        deleted.putAll(ItCleanup.purgeCatalog(jdbc, PRODUCT, MEMBER));
        System.out.println("[清理] 删除行数：" + deleted);
        ItCleanup.residueOfOrders(jdbc, "QT-390%")
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        ItCleanup.residueOfCatalog(jdbc, PRODUCT, MEMBER)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
    }

    // ============================================ ① AUTO_PASS：不判红，且看得见

    @Test
    @Order(1)
    @DisplayName("①AUTO_PASS 且 status=7 且无标：pendingTagMissing=0、noTagByDesign=1（不是藏起来，是分到另一个桶）")
    void autoPassWithoutTagIsNotAGap() {
        seedDecision(SN_AUTO_PASS, "AUTO_PASS");
        // 前提必须成立：这张单**真的**停在 7、**真的**一条标都没有
        assertThat(orderStatusOf(SN_AUTO_PASS)).as("造数前提：订单还停在待审核").isEqualTo(7);
        assertThat(activeConclusionCodes(SN_AUTO_PASS)).as("造数前提：身上没有任何结论标").isEmpty();
        assertThat(latestResultOf(SN_AUTO_PASS)).as("造数前提：最新一轮判成 AUTO_PASS").isEqualTo("AUTO_PASS");

        Map<String, Object> parity = queryService.conclusionParity(20);
        assertThat(number(parity.get("pendingTagMissing")))
                .as("AUTO_PASS 天生不落标（V9，用户 16:38 拍板）⇒ **绝不许**算成缺口："
                        + "旧判据在这里会回 1，端点从此再也回不到 0（永久假红）")
                .isZero();
        assertThat(number(parity.get("noTagByDesign")))
                .as("它必须被数出来（分到另一个桶），而不是被跳过").isEqualTo(1);
        assertThat(number(parity.get("inconsistent")))
                .as("inconsistent = 真冲突 + 真缺口，不含 noTagByDesign").isZero();

        // 「可见但不判红」：明细里查得到这张单，且它不在「不一致」明细里
        Map<String, Object> soft = sampleOf(parity, "noTagByDesignSamples", SN_AUTO_PASS);
        assertThat(soft.get("result")).isEqualTo("AUTO_PASS");
        assertThat(soft.get("orderStatus")).isEqualTo(7);
        assertThat(strings(soft.get("activeConclusionTags"))).isEmpty();
        assertThat(samplesOf(parity))
                .as("它不该出现在 samples（那一份是「不一致」的明细）")
                .noneMatch(s -> SN_AUTO_PASS.equals(String.valueOf(s.get("orderSn"))));

        // 配对分布也如实：AUTO_PASS 这一栏就是「无结论标」
        @SuppressWarnings("unchecked")
        Map<String, Object> byPair = (Map<String, Object>) parity.get("byPair");
        assertThat(((Number) byPair.getOrDefault("AUTO_PASS → (无结论标)", 0)).intValue())
                .as("配对分布能看出「这条结论没有标」，与「缺口」是两件事").isGreaterThanOrEqualTo(1);

        System.out.println("[①] AUTO_PASS+status7+无标 ⇒ pendingTagMissing=" + parity.get("pendingTagMissing")
                + "，noTagByDesign=" + parity.get("noTagByDesign")
                + "，inconsistent=" + parity.get("inconsistent")
                + "，明细=" + soft);
    }

    // ============================================ ② SKIPPED 同理

    @Test
    @Order(2)
    @DisplayName("②SKIPPED 且 status=7 且无标：同样只进 noTagByDesign（实测里那第二行），pendingTagMissing 仍为 0")
    void skippedWithoutTagIsNotAGapEither() {
        seedDecision(SN_SKIPPED, "SKIPPED");
        assertThat(orderStatusOf(SN_SKIPPED)).isEqualTo(7);
        assertThat(activeConclusionCodes(SN_SKIPPED)).isEmpty();

        Map<String, Object> parity = queryService.conclusionParity(20);
        assertThat(number(parity.get("pendingTagMissing")))
                .as("SKIPPED 也不落标（它的「为什么没判」由那枚 active 冻结标回答）⇒ 不算缺口").isZero();
        assertThat(number(parity.get("noTagByDesign")))
                .as("AUTO_PASS + SKIPPED 两行都在这一档 —— 正是实测中 backfill 完 pendingTagMissing=2 的那两行")
                .isEqualTo(2);
        assertThat(sampleOf(parity, "noTagByDesignSamples", SN_SKIPPED).get("result")).isEqualTo("SKIPPED");

        System.out.println("[②] 再加一行 SKIPPED ⇒ pendingTagMissing=" + parity.get("pendingTagMissing")
                + "，noTagByDesign=" + parity.get("noTagByDesign"));
    }

    // ============================================ ③ 真缺口照样报红（护栏）

    @Test
    @Order(3)
    @DisplayName("③真缺口照样报红：MANUAL 停在 7 且无标 ⇒ pendingTagMissing=1，且不许混进 noTagByDesign")
    void realGapStillReported() {
        seedDecision(SN_REAL_GAP, "MANUAL");
        assertThat(orderStatusOf(SN_REAL_GAP)).isEqualTo(7);
        assertThat(activeConclusionCodes(SN_REAL_GAP)).isEmpty();

        Map<String, Object> parity = queryService.conclusionParity(20);
        assertThat(number(parity.get("pendingTagMissing")))
                .as("MANUAL 是 producesTag=true 的一类 ⇒ 引擎漏落了标就是真缺口，一条都不许漏报")
                .isEqualTo(1);
        assertThat(number(parity.get("noTagByDesign")))
                .as("真缺口不许被挪进「天生不落标」的桶里（这就是「把 pendingTagMissing 清零」会犯的错）")
                .isEqualTo(2);
        assertThat(number(parity.get("inconsistent")))
                .as("真缺口必须计入 inconsistent（端点该红的时候还是红的）").isEqualTo(1);

        Map<String, Object> gap = samplesOf(parity).stream()
                .filter(s -> SN_REAL_GAP.equals(String.valueOf(s.get("orderSn"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("对账明细里没有真缺口的 " + SN_REAL_GAP
                        + "：" + samplesOf(parity)));
        assertThat(gap.get("classification")).isEqualTo("PENDING_TAG_MISSING");
        assertThat(gap.get("orderStatus")).isEqualTo(7);
        assertThat(gap.get("result")).isEqualTo("MANUAL");

        System.out.println("[③] 真缺口（MANUAL 停在 7 无标）⇒ pendingTagMissing=" + parity.get("pendingTagMissing")
                + "，inconsistent=" + parity.get("inconsistent")
                + "，noTagByDesign=" + parity.get("noTagByDesign")
                + "，缺口明细分类=" + gap.get("classification"));
    }

    // ================================================================== 造数 / 工具

    private void cleanOurData() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE 'QT-390%'", Long.class);
        for (Long id : ids) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE 'QT-390%'");
        jdbc.update("DELETE FROM member WHERE username = ?", MEMBER);
        jdbc.update("DELETE FROM product WHERE name = ?", PRODUCT);
    }

    private void seedCatalogMemberProduct() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '对账不落标测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '对账不落标测试类型')");
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '对账不落标测试会员', 1, NOW(), 0, '普通')", MEMBER);
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES (?, '对账不落标用例用', ?, ?, 1, 0)", PRODUCT, categoryId, typeId);
    }

    private void seedOrder(String sn, String channel, String amount, int status) {
        Long skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, ?, ?, 0, ?)",
                productId, new BigDecimal(amount), 1000, new BigDecimal("200.00"));
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, ?)", skuId, 1000);
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, ?, 1, NOW(), NOW(), '李四', '13900000000', '北京市海淀区yy路2号', 0, 0, 0, "
                        + "?, '对账不落标旗舰店', ?, NULL, 0)",
                sn, memberId, new BigDecimal(amount), status, channel, new BigDecimal("62.00"));
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                        + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, ?, ?, 1, ?)",
                orderId, productId, skuId, PRODUCT, new BigDecimal(amount), new BigDecimal("200.00"));
        // 出生台账（真实建单路径会写）：**没有任何 from_status=7 的行** —— 这张单从没离开过待审核
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) VALUES (?, NULL, ?, 'ORDER_CREATE', "
                        + "'对账不落标用例造数', 'SYSTEM', 'system', ?)",
                orderId, status, orderId + ":0");
    }

    /** 给这张单补一条决策行（round 1）；**不落任何标** —— 这正是要测的那种「半个事务」形态 */
    private void seedDecision(String sn, String result) {
        Long orderId = orderIdOf(sn);
        jdbc.update("INSERT INTO order_audit_decision (order_id, run_id, round_no, result, "
                        + "operator_type, operator_name, idem_key) "
                        + "VALUES (?, ?, 1, ?, 'SYSTEM', 'system', ?)",
                orderId, runId, result, orderId + ":1");
    }

    private Map<String, Object> sampleOf(Map<String, Object> parity, String field, String orderSn) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) parity.get(field);
        assertThat(list).as("端点必须把「天生不落标」的单也列出来（可见），字段 %s", field).isNotNull();
        return list.stream()
                .filter(s -> orderSn.equals(String.valueOf(s.get("orderSn"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(field + " 里没有 " + orderSn + "：" + list));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> samplesOf(Map<String, Object> parity) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) parity.get("samples");
        return list == null ? List.of() : list;
    }

    private int orderStatusOf(String sn) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE order_sn = ?", Integer.class, sn);
    }

    private String latestResultOf(String sn) {
        return jdbc.queryForObject(
                "SELECT result FROM order_audit_decision WHERE order_id = ? ORDER BY id DESC LIMIT 1",
                String.class, orderIdOf(sn));
    }

    /** 与 e2e 的 activeConclusionTagsOf 同口径：这一单现在挂着哪些「结论」档标 */
    private List<String> activeConclusionCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 "
                        + "AND (t.tag_code LIKE 'FROZEN%' OR c.category = '" + AuditTagCatalogService.CATEGORY_CONCLUSION + "') "
                        + "ORDER BY t.id ASC",
                String.class, sn);
    }

    private Long orderIdOf(String sn) {
        return jdbc.queryForObject("SELECT id FROM `order` WHERE order_sn = ?", Long.class, sn);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        return raw == null ? List.of() : (List<String>) raw;
    }

    private static long number(Object raw) {
        return raw == null ? -1L : ((Number) raw).longValue();
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
