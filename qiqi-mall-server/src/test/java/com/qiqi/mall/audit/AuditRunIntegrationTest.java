package com.qiqi.mall.audit;

import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.DecisionRowDto;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.service.AuditExecutor;
import com.qiqi.mall.audit.service.AuditPolicyService;
import com.qiqi.mall.audit.service.AuditQueryService;
import com.qiqi.mall.audit.service.OrderAuditTagService;
import com.qiqi.mall.oms.controller.OrderController;
import com.qiqi.mall.shared.common.Result;
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

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库集成测试（<b>qiqi_mall_test</b>）：按用户原表造 6 笔单（QT-1001..1006）→ 真跑 run →
 * 断言五类计数与 6 行 decision → 补「人工审核 7→4」链路证据 → 解冻后再跑第二轮。
 *
 * <h3>六笔单（用户原表，与 {@link AuditScenario} / seed 脚本逐字一致）</h3>
 * <pre>
 * QT-1001 淘宝 268 元 2 件 毛利 62            → AUTO_PASS  （淘宝-标准单）
 * QT-1002 淘宝 268 元 2 件 客服备注「改地址」  → MANUAL     （命中但被全局排除）
 * QT-1003 抖音 1580 元 8 件                   → NO_MATCH   （差在 应收 1580 &lt; 500）
 * QT-1004 直营门店 199 元 毛利 −12            → BLOCKED    （毛利不为正；would_be=直营-小单）
 * QT-1005 淘宝 320 元 已有退款申请             → FROZEN →（处理申请+解冻）→ 二轮 AUTO_PASS
 * QT-1006 淘宝 420 元 6 件 6 个样             → NO_MATCH   （差在 种类数 6 ≤ 5）
 * </pre>
 *
 * <h3>为什么要单独验「QT-1006 人工审核 7→4」</h3>
 * 自动引擎对它是 NO_MATCH（不动状态、不打标）；库存不足是**人工审核那一刻**才暴露的事实，
 * 处理方式是既有的「审核不通过」7→4（{@code OrderController#auditReject}）—— 不归自动引擎管。
 * 这条链是 e2e 会依赖的，所以这里直接调 controller 方法（与 HTTP 同一条代码路径与事务边界）
 * 并把状态台账 / 库存回补台账都断言掉。
 *
 * <h3>与库里既有数据的隔离</h3>
 * 候选范围是「status=7 或已冻结」，测试库里本来就有别的待审核单 → 造数前把它们临时置 8，
 * 跑完 {@code @AfterAll} 原值恢复（只改 status 一列）。本类自己的数据跑完**保留**（报告要贴 SQL），
 * 下次运行前按 {@code order_sn LIKE 'QT-100%'} 清理。
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditRunIntegrationTest}
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/qiqi_mall_test"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=root",
        "spring.datasource.password=qiqi123456",
        // 关掉「sku.stock 缓存回写」：本类要让 sku.stock 保持造数时的值，避免后台任务改写断言依据
        "qiqi.stock.cache-refresh-ms=3600000",
        // 关掉本上下文里的定时调度：后台线程若在断言之间跑一轮，计数与轮次都会漂
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditRunIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuditPolicyService policyService;
    @Autowired
    private AuditExecutor executor;
    @Autowired
    private OrderAuditTagService tagService;
    @Autowired
    private AuditQueryService queryService;
    /** 人工审核入口（7→8 / 7→4）：直接调 controller 方法 = 与 HTTP 同一路径 */
    @Autowired
    private OrderController orderController;

    private Long memberUser;
    private Long memberStore;
    private Long productId;
    private Long skuTaobao;
    private Long skuDouyin;
    private Long skuDirect;
    private final List<Long> skuX = new ArrayList<>();          // QT-1006 的 6 个样
    private Long returnReasonId;
    private final Map<Long, Integer> parkedOrders = new LinkedHashMap<>();

    // ================================================================== 准备

    @BeforeAll
    void seed() {
        cleanOurData();
        parkOtherPendingOrders();
        seedCatalogAndMembers();
        seedSixOrders();
        seedPolicies();
        System.out.println("[准备] QT-1001..QT-1006 已就绪；临时挪开的既有待审核单：" + parkedOrders.keySet());
    }

    @AfterAll
    void restore() {
        parkedOrders.forEach((id, status) ->
                jdbc.update("UPDATE `order` SET status = ? WHERE id = ?", status, id));
        System.out.println("[恢复] 既有待审核单状态已还原：" + parkedOrders.keySet());
        printDecisions();
    }

    // ================================================================== ① 第一轮

    @Test
    @Order(1)
    @DisplayName("第一轮：6 笔单 → 自动过审 1 / 被排除转人工 1 / 拦截 1 / 冻结 1 / 无匹配 2")
    void round1() {
        RunResultDto run = executor.run("MANUAL", null);

        // 用户口径「被拦 2（阻止 1 + 冻结 1）」= blocked(1) + frozen(1)；「不命中 2」= no_match(2)
        assertThat(run.total()).isEqualTo(6);
        assertThat(run.autoPass()).as("QT-1001").isEqualTo(1);
        assertThat(run.manual()).as("QT-1002 被全局排除").isEqualTo(1);
        assertThat(run.blocked()).as("QT-1004 毛利不为正").isEqualTo(1);
        assertThat(run.frozen()).as("QT-1005 有退款申请").isEqualTo(1);
        assertThat(run.noMatch()).as("QT-1003 抖音超金额 + QT-1006 种类数超限").isEqualTo(2);
        assertThat(run.skipped()).isZero();

        // ---------------- QT-1001 → AUTO_PASS（淘宝-标准单），真的 7→8 ----------------
        Map<String, Object> d1 = decision("QT-1001");
        assertThat(d1.get("result")).isEqualTo("AUTO_PASS");
        assertThat(d1.get("matched_policy_name")).isEqualTo("淘宝-标准单");
        assertThat(String.valueOf(d1.get("reason")))
                .contains("自动过审").contains("应收金额 268.00 < 500.00").contains("商品种类数 1 ≤ 5");
        assertThat(statusOf("QT-1001")).isEqualTo(8);
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT l.to_status, l.reason, l.ref_type, l.ref_id FROM order_status_ledger l "
                        + "JOIN `order` o ON o.id = l.order_id WHERE o.order_sn = 'QT-1001' "
                        + "ORDER BY l.id DESC LIMIT 1");
        assertThat(ledger.get("reason")).isEqualTo("AUDIT_AUTO_PASS");
        assertThat(ledger.get("ref_type")).isEqualTo("AUDIT_RUN");
        assertThat(((Number) ledger.get("ref_id")).longValue()).isEqualTo(run.runId());

        // ---------------- QT-1002 → MANUAL（命中淘宝-标准单但被全局排除） ----------------
        Map<String, Object> d2 = decision("QT-1002");
        assertThat(d2.get("result")).isEqualTo("MANUAL");
        assertThat(d2.get("matched_policy_name")).isEqualTo("淘宝-标准单");
        assertThat(String.valueOf(d2.get("reason"))).contains("被排除").contains("客服备注 含「改」");
        assertThat(String.valueOf(d2.get("excluded_by_json"))).contains("CS_REMARK");
        assertThat(statusOf("QT-1002")).isEqualTo(7);

        // ---------------- QT-1003 → NO_MATCH（差额是应收 1580 < 500） ----------------
        Map<String, Object> d3 = decision("QT-1003");
        assertThat(d3.get("result")).isEqualTo("NO_MATCH");
        assertThat(d3.get("matched_policy_id")).isNull();
        String miss3 = String.valueOf(d3.get("miss_reason_json"));
        assertThat(miss3).contains("抖音-标准单").contains("应收金额 1580.00 < 500.00");
        assertThat(statusOf("QT-1003")).isEqualTo(7);

        // ---------------- QT-1004 → BLOCKED（毛利 −12），would_be=直营-小单 ----------------
        Map<String, Object> d4 = decision("QT-1004");
        assertThat(d4.get("result")).isEqualTo("BLOCKED");
        assertThat(d4.get("matched_policy_name")).isEqualTo("毛利不为正");
        assertThat(String.valueOf(d4.get("reason"))).contains("毛利 −12.00 ≤ 0");
        assertThat(d4.get("would_be_auto_policy_name"))
                .as("被拦截的单必须回填「本来会命中哪条 AUTO 策略」").isEqualTo("直营-小单");
        assertThat(statusOf("QT-1004")).isEqualTo(7);

        // ---------------- QT-1005 → FROZEN（打标；不写状态台账） ----------------
        Map<String, Object> d5 = decision("QT-1005");
        assertThat(d5.get("result")).isEqualTo("FROZEN");
        assertThat(d5.get("matched_policy_name")).isEqualTo("退款申请冻结");
        assertThat(d5.get("would_be_auto_policy_name")).isEqualTo("淘宝-标准单");
        assertThat(d5.get("round_no")).isEqualTo(1);
        assertThat(statusOf("QT-1005")).as("冻结不是状态码（决策 J1）").isEqualTo(7);
        Integer frozenTags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = 'QT-1005' AND t.active = 1 AND t.tag_code = 'FROZEN'", Integer.class);
        assertThat(frozenTags).isEqualTo(1);
        Integer freezeLedger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_status_ledger l JOIN `order` o ON o.id = l.order_id "
                        + "WHERE o.order_sn = 'QT-1005' AND l.reason = 'AUDIT_FREEZE'", Integer.class);
        assertThat(freezeLedger).as("冻结绝不写 order_status_ledger（7→7 会被 TRANSITIONS 拦）").isZero();

        // ---------------- QT-1006 → NO_MATCH（差在种类数 6 ≤ 5；库存不足不归自动引擎管） ----------------
        Map<String, Object> d6 = decision("QT-1006");
        assertThat(d6.get("result")).isEqualTo("NO_MATCH");
        assertThat(d6.get("downgrade_reason"))
                .as("QT-1006 不许被库存预检降级成 STOCK_SHORT —— 它根本没进 AUTO_PASS")
                .isNull();
        assertThat(String.valueOf(d6.get("miss_reason_json")))
                .contains("淘宝-标准单").contains("商品种类数 6 ≤ 5");
        assertThat(statusOf("QT-1006")).isEqualTo(7);
        Integer shortTags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = 'QT-1006' AND t.active = 1", Integer.class);
        assertThat(shortTags).as("NO_MATCH 不打任何挂起标").isZero();
    }

    // ================================================================== ② 人工审核 7→4（离线事实：库存不足）

    @Test
    @Order(2)
    @DisplayName("QT-1006 走人工审核：先看清库存不足，再「审核不通过」7→4（既有链路，不归自动引擎）")
    void manualRejectChain() {
        Long orderId = orderIdOf("QT-1006");

        // 人工审核那一刻才暴露的事实：6 个样里有一个桶是 0（需求 1 件也发不出）
        List<Map<String, Object>> stockRows = jdbc.queryForList(
                "SELECT i.product_sku_id AS sku_id, i.product_quantity AS need, "
                        + "       (SELECT COALESCE(SUM(b.stock),0) FROM sku_bucket b WHERE b.sku_id = i.product_sku_id) AS buckets "
                        + "FROM order_item i WHERE i.order_id = ? ORDER BY i.product_sku_id", orderId);
        assertThat(stockRows).hasSize(6);
        assertThat(stockRows).anySatisfy(r -> assertThat(((Number) r.get("buckets")).intValue())
                .as("至少有一个 SKU 的桶库存为 0 —— 这正是人工审核要拒单的原因").isZero());

        // 审核不通过（dry-run 先预演，再真跑）：7 → 4
        Result<?> preview = orderController.auditReject(orderId, Boolean.TRUE);
        assertThat(preview.getCode()).isZero();
        assertThat(String.valueOf(preview.getData()))
                .as("预演要说清「待审核→已关闭 + 回补哪些 SKU」")
                .contains("待审核→已关闭").contains("回补");

        Result<?> rejected = orderController.auditReject(orderId, null);
        assertThat(rejected.getCode()).as("7→4 是既有合法流转（J3 纠正：TRANSITIONS.get(4)=∅ 只表示从 4 出不去）")
                .isZero();
        assertThat(statusOf("QT-1006")).isEqualTo(4);

        Map<String, Object> last = jdbc.queryForMap(
                "SELECT from_status, to_status, reason FROM order_status_ledger "
                        + "WHERE order_id = ? ORDER BY id DESC LIMIT 1", orderId);
        assertThat(last.get("from_status")).isEqualTo(7);
        assertThat(last.get("to_status")).isEqualTo(4);
        assertThat(last.get("reason")).isEqualTo("AUDIT_REJECT");

        // 库存回补也要留台账（6 条明细各 1 件）
        Integer restoreRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE order_id = ? AND reason = 'AUDIT_REJECT'",
                Integer.class, orderId);
        assertThat(restoreRows).isEqualTo(6);
    }

    // ================================================================== ③ 第二轮（QT-1005 解冻后过审）

    @Test
    @Order(3)
    @DisplayName("第二轮：处理退款申请 + 解冻 → QT-1005 FROZEN 变 AUTO_PASS（round_no 1→2）")
    void round2AfterUnfreeze() {
        jdbc.update("UPDATE return_apply SET status = 2 WHERE order_id = ?", orderIdOf("QT-1005"));
        com.qiqi.mall.order.entity.Order order = jdbc.queryForObject(
                "SELECT id, order_sn, status FROM `order` WHERE order_sn = 'QT-1005'", (rs, i) -> {
                    com.qiqi.mall.order.entity.Order o = new com.qiqi.mall.order.entity.Order();
                    o.setId(rs.getLong("id"));
                    o.setOrderSn(rs.getString("order_sn"));
                    o.setStatus(rs.getInt("status"));
                    return o;
                });
        assertThat(tagService.isFrozen(order.getId())).isTrue();
        assertThat(tagService.unfreeze(order)).isEqualTo(1);
        assertThat(tagService.isFrozen(order.getId())).isFalse();

        RunResultDto run = executor.run("MANUAL", null);
        // 候选 = status 7：QT-1002/1003/1004/1005（QT-1001 已 8；QT-1006 已 4 已关闭）
        assertThat(run.total()).isEqualTo(4);
        assertThat(run.autoPass()).as("QT-1005 解冻后可过审").isEqualTo(1);
        assertThat(run.manual()).as("QT-1002 仍被排除").isEqualTo(1);
        assertThat(run.blocked()).as("QT-1004").isEqualTo(1);
        assertThat(run.noMatch()).as("QT-1003").isEqualTo(1);
        assertThat(run.frozen()).isZero();

        Map<String, Object> d5 = decision("QT-1005");
        assertThat(d5.get("round_no")).isEqualTo(2);
        assertThat(d5.get("result")).isEqualTo("AUTO_PASS");
        assertThat(d5.get("matched_policy_name")).isEqualTo("淘宝-标准单");
        assertThat(statusOf("QT-1005")).isEqualTo(8);

        // 解冻留了审计（仍不写状态台账）
        Integer unfreezeAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_log WHERE action = 'AUDIT_UNFREEZE' AND order_id = ?",
                Integer.class, order.getId());
        assertThat(unfreezeAudits).isEqualTo(1);
    }

    // ================================================================== ④ 查询面

    @Test
    @Order(4)
    @DisplayName("队列与时间线：queue 取每单最新一轮；trail 把决策 + 状态台账 + 操作审计按时间拼")
    void queueAndTrail() {
        Map<String, Object> manualQueue = queryService.queue("MANUAL", 1, 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> manualRows = (List<Map<String, Object>>) manualQueue.get("rows");
        assertThat(manualRows).anySatisfy(r -> {
            assertThat(String.valueOf(r.get("order_sn"))).isEqualTo("QT-1002");
            assertThat(String.valueOf(r.get("reason_text"))).contains("客服备注 含「改」");
        });

        List<Map<String, Object>> trail = queryService.trail(orderIdOf("QT-1001"));
        assertThat(trail).extracting(e -> e.get("segment")).contains("DECISION", "STATUS");
        assertThat(trail).anySatisfy(e -> {
            assertThat(String.valueOf(e.get("segment"))).isEqualTo("STATUS");
            assertThat(String.valueOf(e.get("title"))).contains("AUDIT_AUTO_PASS");
        });

        List<Map<String, Object>> trail6 = queryService.trail(orderIdOf("QT-1006"));
        assertThat(trail6).anySatisfy(e -> {
            assertThat(String.valueOf(e.get("segment"))).isEqualTo("STATUS");
            assertThat(String.valueOf(e.get("title"))).contains("AUDIT_REJECT").contains("7 → 4");
        });
    }

    private Long orderIdOf(String orderSn) {
        return jdbc.queryForObject("SELECT id FROM `order` WHERE order_sn = ?", Long.class, orderSn);
    }

    // ================================================================== 造数（与 sql/seed-order-audit-demo.sql 逐字一致）

    private void cleanOurData() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE 'QT-100%'", Long.class);
        for (Long id : ids) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE 'QT-100%'");
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
        jdbc.update("DELETE FROM member WHERE username IN ('audit_qt_member', 'audit_qt_store')");
        jdbc.update("DELETE FROM product WHERE name = '自动审核测试商品'");
    }

    /** 既有待审核单临时挪到 8（只改 status 一列，跑完恢复），否则会混进六笔单的计数 */
    private void parkOtherPendingOrders() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, status FROM `order` WHERE status = 7");
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            parkedOrders.put(id, ((Number) row.get("status")).intValue());
            jdbc.update("UPDATE `order` SET status = 8 WHERE id = ?", id);
        }
    }

    private void seedCatalogAndMembers() {
        // 普通会员（QT-1001/1002/1003/1005/1006）/ 直营门店会员（QT-1004 命中「直营-小单」）
        memberUser = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES ('audit_qt_member', '', '自动审核测试-普通', 1, NOW(), 0, '普通')");
        memberStore = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES ('audit_qt_store', '', '自动审核测试-直营门店', 1, NOW(), 0, '直营门店')");
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES ('自动审核测试商品', '自动审核集成测试用', ?, ?, 1, 0)", categoryId, typeId);

        skuTaobao = newSku("268.00", "200.00", 1000);
        skuDouyin = newSku("150.00", "100.00", 1000);
        skuDirect = newSku("199.00", "150.00", 1000);
        for (int i = 0; i < 6; i++) {
            // QT-1006 的 6 个样；其中第 5 个（下标 4）库存 0 —— 人工审核时的「库存不足」
            skuX.add(newSku("70.00", "50.00", i == 4 ? 0 : 1000));
        }
        returnReasonId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM return_reason", Long.class);
    }

    private Long newSku(String price, String cost, int stock) {
        Long skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, ?, ?, 0, ?)",
                productId, new BigDecimal(price), stock, new BigDecimal(cost));
        // 桶是权威值（一个桶即可：SUM 就是真库存）
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, ?)", skuId, stock);
        return skuId;
    }

    private void seedSixOrders() {
        // 单号 / 渠道 / 应收 / 件数 / 毛利 / 客服备注 / 会员 —— 用户原表照抄
        createOrder("QT-1001", "淘宝", "268.00", "62.00", null, memberUser, skuTaobao, 2);
        createOrder("QT-1002", "淘宝", "268.00", "62.00", "客户改地址：海淀区yy路2号", memberUser, skuTaobao, 2);
        createOrder("QT-1003", "抖音", "1580.00", "300.00", null, memberUser, skuDouyin, 8);
        createOrder("QT-1004", "直营", "199.00", "-12.00", null, memberStore, skuDirect, 1);
        createOrder("QT-1005", "淘宝", "320.00", "80.00", null, memberUser, skuTaobao, 2);
        // QT-1006：6 个样各 1 件（种类数 6 > 5）
        Long order6 = createOrder("QT-1006", "淘宝", "420.00", "100.00", null, memberUser, null, 0);
        for (Long skuId : skuX) {
            jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                            + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, '自动审核测试商品', 70.00, 1, 50.00)",
                    order6, productId, skuId);
        }

        // QT-1005：待处理退款申请 → 异常策略触发
        Long order5 = orderIdOf("QT-1005");
        jdbc.update("INSERT INTO return_apply (order_id, member_id, return_reason, description, status, reason_id, status_seq) "
                        + "VALUES (?, ?, '不想要了', '自动审核测试：待处理退款申请', 0, ?, 0)",
                order5, memberUser, returnReasonId);
    }

    /** @return 订单 id（QT-1006 需要继续插 6 条明细） */
    private Long createOrder(String sn, String channel, String amount, String grossProfit, String csRemark,
                             Long memberId, Long skuId, int qty) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, 7, 1, NOW(), NOW(), '张三', '13800000000', '北京市朝阳区xx路1号', 0, 0, 0, "
                        + "?, '官方旗舰店', ?, ?, 0)",
                sn, memberId, new BigDecimal(amount), channel, new BigDecimal(grossProfit), csRemark);
        if (skuId != null) {
            BigDecimal cost = skuId.equals(skuTaobao) ? new BigDecimal("200.00")
                    : skuId.equals(skuDouyin) ? new BigDecimal("100.00") : new BigDecimal("150.00");
            jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                            + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, '自动审核测试商品', ?, ?, ?)",
                    orderId, productId, skuId, new BigDecimal(amount).divide(new BigDecimal(qty), 2, java.math.RoundingMode.HALF_UP),
                    qty, cost);
        }
        // 出生台账（真实建单路径会写；造数补上，让时间线里「这单怎么来的」也完整）
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) VALUES (?, NULL, 7, 'ORDER_CREATE', "
                        + "'自动审核集成测试造数', 'SYSTEM', 'system', ?)",
                orderId, orderId + ":0");
        return orderId;
    }

    private void seedPolicies() {
        // AUTO-1 直营-小单：客户标签=直营门店 且 应收 < 300
        policyService.create(new PolicyRequest("AUTO", 100, "直营-小单", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CUSTOMER_TAG", "EQ", "TEXT", null, "直营门店", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("300"), null, null)), null));
        // AUTO-2 淘宝-标准单（+ 全局排除：客服备注含「改」）
        policyService.create(new PolicyRequest("AUTO", 200, "淘宝-标准单", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("500"), null, null),
                        new ConditionRequest("MATCH", "POLICY", "ITEM_KIND_COUNT", "LTE", "NUM",
                                new BigDecimal("5"), null, null)),
                List.of(new ConditionRequest("EXCLUDE", "ALL_AUTO", "CS_REMARK", "CONTAINS", "TEXT",
                        null, "改", null))));
        // AUTO-3 抖音-标准单
        policyService.create(new PolicyRequest("AUTO", 300, "抖音-标准单", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "抖音", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("500"), null, null),
                        new ConditionRequest("MATCH", "POLICY", "ITEM_KIND_COUNT", "LTE", "NUM",
                                new BigDecimal("5"), null, null)), null));
        // BLOCK-1 毛利不为正 / BLOCK-2 双地址
        policyService.create(new PolicyRequest("BLOCK", 10, "毛利不为正", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "GROSS_PROFIT", "LTE", "NUM",
                        BigDecimal.ZERO, null, null)), null));
        policyService.create(new PolicyRequest("BLOCK", 20, "双地址", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "ADDRESS_MULTI", "EQ", "BOOL",
                        BigDecimal.ONE, null, null)), null));
        // EXCEPTION-1 退款申请 / EXCEPTION-2 平台备注（设计里的「或」= 两条策略，见 AuditScenario 注释）
        policyService.create(new PolicyRequest("EXCEPTION", 10, "退款申请冻结", true, "FROZEN", "存在待处理退款申请",
                List.of(new ConditionRequest("MATCH", "POLICY", "HAS_RETURN_APPLY", "EQ", "BOOL",
                        BigDecimal.ONE, null, null)), null));
        policyService.create(new PolicyRequest("EXCEPTION", 20, "平台备注冻结", true, "FROZEN", "平台备注 24 小时内被修改过",
                List.of(new ConditionRequest("MATCH", "POLICY", "PLATFORM_REMARK_UPDATED", "LTE", "NUM",
                        new BigDecimal("24"), null, null)), null));
    }

    // ================================================================== 工具

    private Map<String, Object> decision(String orderSn) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.*, JSON_UNQUOTE(JSON_EXTRACT(d.evidence_json, '$.reason')) AS reason "
                        + "FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn = ? ORDER BY d.round_no DESC LIMIT 1", orderSn);
        assertThat(rows).as("订单 %s 没有任何决策行", orderSn).isNotEmpty();
        return rows.get(0);
    }

    private int statusOf(String orderSn) {
        Integer status = jdbc.queryForObject("SELECT status FROM `order` WHERE order_sn = ?", Integer.class, orderSn);
        return status == null ? -1 : status;
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

    /** 报告用：把决策行打出来（与报告里贴的 SQL 结果一致） */
    private void printDecisions() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.order_sn, d.round_no, d.result, d.matched_policy_name, d.would_be_auto_policy_name, "
                        + "d.downgrade_reason, JSON_UNQUOTE(JSON_EXTRACT(d.evidence_json, '$.reason')) AS reason "
                        + "FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn LIKE 'QT-100%' ORDER BY o.order_sn, d.round_no");
        System.out.println("┌─ order_audit_decision（真实库 qiqi_mall_test）");
        for (Map<String, Object> r : rows) {
            System.out.println("│ " + r.get("order_sn") + " | round=" + r.get("round_no") + " | " + r.get("result")
                    + " | " + r.get("matched_policy_name") + " | would_be=" + r.get("would_be_auto_policy_name")
                    + " | downgrade=" + r.get("downgrade_reason") + " | " + r.get("reason"));
        }
        System.out.println("└─ 共 " + rows.size() + " 行");
    }
}
