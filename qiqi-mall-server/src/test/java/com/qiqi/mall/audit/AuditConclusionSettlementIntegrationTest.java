package com.qiqi.mall.audit;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>结论标结算挂在「状态唯一写入口」上</b>的可执行口径 —— 用<b>真 HTTP 入口</b>跑一条以前会漏的路径。
 *
 * <h3>为什么要有这一条（期 1.6 的架构缺口）</h3>
 * 期 1.6 只在两个入口手工结算了结论标：
 * <pre>
 * OrderController#auditPass   7→8 之后调 releaseAllConclusionTags(SYSTEM:AUDIT_PASS)
 * OrderController#auditReject 7→4 之后调 releaseAllConclusionTags(SYSTEM:AUDIT_REJECT)
 * </pre>
 * 但<b>离开「待审核(7)」的出口不止两个</b>。本条覆盖的就是漏掉的那条：
 * <pre>
 * 7 → 5 申请退货（后台代客登记：POST /admin/return-applies / 买家自助：PortalReturnController#apply）
 *   走过这条路的单会带着 AUDIT_PENDING（blocking=1）离开 7
 *   ⇒ 期 2 在「下发 WMS 之前」按 blocking 拦一道时，这些单会被<b>误拦</b>，
 *     而队列里它已经不是待审核 —— 没有任何动作能解掉那条残留标，保险变成第二道死锁。
 * </pre>
 * 现在结算收在 {@code OrderStatusService#change} 里（离开 7 就结算，包含这条），所以本类断言：
 * <ol>
 *   <li>7→5 之后 active 结论标 == []（不是带着 AUDIT_PENDING 走），解除人 = {@code SYSTEM:RETURN_APPLY}；</li>
 *   <li>顺带核「进入 7」这一侧：删掉待处理退货申请把单交回聚合（5→7）时<b>不需要</b>额外处理 ——
 *       进 7 时身上本来就是空的，结论标由下一次判定重新落（本类最后再跑一轮来证明）。</li>
 * </ol>
 *
 * <h3>造数走真实入口（重要）</h3>
 * 建单 {@code POST /admin/orders}（status=7 待审核）→ 跑一轮 {@code POST /admin/order-audit/run}
 * （判成 MANUAL，落 AUDIT_PENDING）→ 申请退货 {@code POST /admin/return-applies}（7→5）。
 * 状态、结论标<b>一行都不直插</b>：直插的「AUDIT_PENDING」证明不了任何入口会结算它 ——
 * 那正是本条要钉的东西。只有商品/规格/桶/退货原因字典这些<b>夹具</b>是直插的（与其它 IT 同口径，
 * 它们不是被测对象）。
 *
 * <p>单号前缀 {@code QT-400x}（自建自清，绝不与 QT-100x / QT-200x / QT-300x 混）。
 * 跑：{@code ./scripts/run-it.sh -Dtest=AuditConclusionSettlementIntegrationTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditConclusionSettlementIntegrationTest {

    /** admin 口令：默认种子值；改过口令（scripts/set-admin-password.sh）时用环境变量 ADMIN_PASSWORD 覆盖 */
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "123456");

    private static final String ORDER_SN = "QT-4001";
    private static final String SN_LIKE = "QT-400%";
    /** 本类专用渠道：策略只认它，别的单不会被这套条件命中 */
    private static final String CHANNEL = "结论结算测试渠道";
    private static final String POLICY_NAME = "IT-AUTO-结论结算";

    @DynamicPropertySource
    static void itDatasource(DynamicPropertyRegistry registry) {
        ItDatabase.prepare();
        registry.add("spring.datasource.url", ItDatabase::url);
        registry.add("spring.datasource.username", ItDatabase::username);
        registry.add("spring.datasource.password", ItDatabase::password);
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    /**
     * 造一个 admin 账号：隔离库（{@code qiqi_mall_it}）是每轮 DROP+CREATE 的空库，
     * {@code admin} 表里<b>没有种子账号</b>（种子在演示库/测试库的 sql/testdb 里）。
     * 用项目自己的 {@link PasswordEncoder} 现算哈希，口令与其它 IT 同一个来源（ADMIN_PASSWORD）。
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminJwt;
    private Long memberId;
    private Long productId;
    private Long skuId;
    private Long reasonId;

    // ================================================================== 准备 / 清理

    @BeforeAll
    void prepare() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedFixtures();
        adminJwt = login();
        memberId = registerBuyer();
        System.out.printf("[准备] member=%d product=%d sku=%d reason=%d 就绪%n",
                memberId, productId, skuId, reasonId);
    }

    @AfterAll
    void cleanup() {
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, SN_LIKE));
        deleted.put("audit_policy_version", jdbc.update("DELETE FROM audit_policy_version"));
        deleted.put("audit_policy_condition", jdbc.update("DELETE FROM audit_policy_condition"));
        deleted.put("audit_policy", jdbc.update("DELETE FROM audit_policy"));
        deleted.put("audit_run", jdbc.update("DELETE FROM audit_run"));
        deleted.put("sku_bucket", jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId));
        deleted.put("sku", jdbc.update("DELETE FROM sku WHERE id = ?", skuId));
        deleted.put("product", jdbc.update("DELETE FROM product WHERE id = ?", productId));
        deleted.put("return_reason", jdbc.update("DELETE FROM return_reason WHERE id = ?", reasonId));
        deleted.put("member", jdbc.update("DELETE FROM member WHERE id = ?", memberId));
        deleted.put("admin", jdbc.update("DELETE FROM admin WHERE username = 'admin'"));
        System.out.println("[清理] 删除行数：" + deleted);

        ItCleanup.residueOfOrders(jdbc, SN_LIKE)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE order_sn LIKE ?",
                Integer.class, SN_LIKE)).as("本类的单不许留给下一个测试类（它们会数本轮候选）").isZero();
    }

    // ================================================================== 主用例

    @Test
    @Order(1)
    @DisplayName("7→5 申请退货：AUDIT_PENDING 必须结算干净（released_by=SYSTEM:RETURN_APPLY）")
    void returnApplyFromPendingAuditSettlesConclusionTags() {
        // ---------------- ① 真实入口建单：status=7 待审核 ----------------
        Long orderId = createPendingOrder();
        assertThat(statusOf(orderId)).as("建单入口允许的初始状态之一：待审核(7)").isEqualTo(7);
        assertThat(activeConclusionCodes(orderId)).as("刚建的单身上还没有任何结论标").isEmpty();

        // ---------------- ② 真实入口跑一轮审核：判成 MANUAL，落 AUDIT_PENDING ----------------
        createAutoPolicyWithGlobalExclusion();
        JsonNode run = ok(post("/admin/order-audit/run", "{}"));
        assertThat(run.path("manual").asInt()).as("本轮应有一笔转人工：%s", run).isGreaterThanOrEqualTo(1);
        assertThat(decisionResult(orderId)).as("被全局排除 ⇒ MANUAL（留人工）").isEqualTo("MANUAL");
        assertThat(activeConclusionCodes(orderId))
                .as("起点：MANUAL 的单身上就是 AUDIT_PENDING（blocking=1）")
                .containsExactly("AUDIT_PENDING");
        assertThat(blockingConclusionCount(orderId)).as("AUDIT_PENDING 是 blocking=1 —— 期 2 的保险就按它拦")
                .isEqualTo(1);

        // ---------------- ③ 真实入口申请退货：7 待审核 → 5 退款中 ----------------
        JsonNode apply = ok(post("/admin/return-applies",
                "{\"orderId\":" + orderId + ",\"memberId\":" + memberId + ",\"reasonId\":" + reasonId
                        + ",\"returnReason\":\"结论结算用例：申请退货\"}"));
        Long applyId = apply.path("id").asLong();
        assertThat(applyId).as("退货申请应真的落库（走的是 ReturnApplyController#create）").isPositive();

        assertThat(statusOf(orderId)).as("7 待审核 → 5 退款中").isEqualTo(5);

        // ---------------- ④ 核心断言：结论标被结算 ----------------
        assertThat(activeConclusionCodes(orderId))
                .as("7→5 也是「离开待审核」：不许带着 AUDIT_PENDING（blocking=1）走，否则期 2 的下发保险会误拦")
                .isEmpty();
        Map<String, Object> released = anyTagRow(orderId, "AUDIT_PENDING");
        assertThat(((Number) released.get("active")).intValue()).as("不是删行，是置为已解除").isZero();
        assertThat(released.get("released_at")).as("解除时间必须记下").isNotNull();
        assertThat(String.valueOf(released.get("released_by")))
                .as("解除人要一眼看出「因为流转到了 5」")
                .isEqualTo("SYSTEM:RETURN_APPLY");

        System.out.println("[7→5] 订单 " + ORDER_SN + "（MANUAL → 申请退货）status=" + statusOf(orderId)
                + "，active 结论标 = " + activeConclusionCodes(orderId)
                + "；AUDIT_PENDING released_by=" + released.get("released_by"));

        // ---------------- ⑤ 进入 7 这一侧：删申请交回聚合（5→7）不需要任何额外处理 ----------------
        ok(exchange(HttpMethod.DELETE, "/admin/return-applies/" + applyId, null));
        assertThat(statusOf(orderId)).as("无包裹的直发单交回聚合 ⇒ 回待审核(7)").isEqualTo(7);
        assertThat(activeConclusionCodes(orderId))
                .as("进 7 时身上本来就是空的（上一次离开 7 已经结算干净）").isEmpty();

        // 下一轮判定重新落标 —— 这就是「结论标由下一次判定重新落」的可执行版本
        ok(post("/admin/order-audit/run", "{}"));
        assertThat(decisionResult(orderId)).as("重新判一轮，仍是 MANUAL").isEqualTo("MANUAL");
        assertThat(activeConclusionCodes(orderId))
                .as("交回重算后由下一轮判定重新落标（旧的已解除、新的这条是新的结论）")
                .containsExactly("AUDIT_PENDING");

        System.out.println("[5→7] 订单 " + ORDER_SN + "（删申请交回聚合）status=" + statusOf(orderId)
                + "，active 结论标 = " + activeConclusionCodes(orderId) + "（由下一轮判定重新落）");
    }

    // ================================================================== 真实入口

    /** POST /admin/orders：status=7 待审核（唯一允许「出生即待审核」的入口） */
    private Long createPendingOrder() {
        String json = "{\"order\":{\"orderSn\":\"" + ORDER_SN + "\",\"memberId\":" + memberId
                + ",\"status\":7,\"channel\":\"" + CHANNEL + "\""
                + ",\"csRemark\":\"客户改地址：朝阳区it路1号\""
                + ",\"receiverName\":\"结论结算用例收货人\",\"receiverPhone\":\"13700000000\""
                + ",\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"结论结算用例-明细\",\"productPrice\":10.00,\"productQuantity\":1}]}";
        JsonNode data = ok(post("/admin/orders", json));
        Long id = data.path("id").asLong();
        assertThat(id).as("建单应返回 id：%s", data).isPositive();
        return id;
    }

    /**
     * POST /admin/audit-policies：AUTO 命中但被<b>全局排除</b>挡下 ⇒ MANUAL（这正是 AUDIT_PENDING 的来路）。
     *
     * <p>为什么不直接造一条 AUDIT_PENDING：标是判定链的产物，直插的标证明不了任何入口会结算它。
     */
    private void createAutoPolicyWithGlobalExclusion() {
        String json = "{\"type\":\"AUTO\",\"priority\":50,\"name\":\"" + POLICY_NAME + "\",\"enabled\":true,"
                + "\"conditions\":[{\"kind\":\"MATCH\",\"scope\":\"POLICY\",\"fieldCode\":\"CHANNEL\","
                + "\"opCode\":\"EQ\",\"valueType\":\"TEXT\",\"valueText\":\"" + CHANNEL + "\"}],"
                + "\"globalExclusions\":[{\"kind\":\"EXCLUDE\",\"scope\":\"ALL_AUTO\",\"fieldCode\":\"CS_REMARK\","
                + "\"opCode\":\"CONTAINS\",\"valueType\":\"TEXT\",\"valueText\":\"改\"}]}";
        ok(post("/admin/audit-policies", json));
    }

    // ================================================================== 夹具（字典 / 商品 / 规格 / 桶）

    private void seedFixtures() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '结论结算测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '结论结算测试类型')");
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES ('结论结算测试商品', '结论结算集成测试用', ?, ?, 1, 0)", categoryId, typeId);
        skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, 10.00, 1000, 0, 8.00)",
                productId);
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, 1000)", skuId);
        reasonId = insertAndReturnKey(
                "INSERT INTO return_reason (name) VALUES ('结论结算用例原因')");
        insertAndReturnKey("INSERT INTO admin (username, password, nickname, status) VALUES (?, ?, '结论结算用例管理员', 1)",
                "admin", passwordEncoder.encode(ADMIN_PASSWORD));
    }

    private String login() {
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login",
                Map.of("username", "admin", "password", ADMIN_PASSWORD), JsonNode.class);
        String token = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(token).as("admin 登录失败：%s", resp.getBody()).isNotBlank();
        return token;
    }

    private Long registerBuyer() {
        String username = "conclusion-settlement-" + System.currentTimeMillis();
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"结论结算用例买家\"}";
        return ok(post("/portal/auth/register", json)).path("memberId").asLong();
    }

    // ================================================================== 查库工具

    private int statusOf(Long orderId) {
        Integer s = jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        return s == null ? -1 : s;
    }

    private String decisionResult(Long orderId) {
        List<String> rows = jdbc.queryForList(
                "SELECT result FROM order_audit_decision WHERE order_id = ? ORDER BY round_no DESC, id DESC LIMIT 1",
                String.class, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 这单身上「结论」档的 active 标码：判据 = 标目录 {@code category='结论'} <b>或</b> {@code FROZEN%} 前缀
     * （与 {@code OrderAuditTagService#isConclusionTag} 同口径 —— 细分冻结码还没登记进目录）。
     */
    private List<String> activeConclusionCodes(Long orderId) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE t.order_id = ? AND t.active = 1 "
                        + "  AND (c.category = '结论' OR t.tag_code LIKE 'FROZEN%') ORDER BY t.id",
                String.class, orderId);
    }

    /** active 结论标里 blocking=1 的条数 —— 期 2「下发前按 blocking 拦」的口径 */
    private int blockingConclusionCount(Long orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE t.order_id = ? AND t.active = 1 AND c.category = '结论' AND c.blocking = 1",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private Map<String, Object> anyTagRow(Long orderId, String tagCode) {
        return jdbc.queryForMap("SELECT * FROM order_audit_tag WHERE order_id = ? AND tag_code = ? "
                + "ORDER BY id DESC LIMIT 1", orderId, tagCode);
    }

    private void cleanOurData() {
        ItCleanup.purgeOrders(jdbc, SN_LIKE);
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
    }

    // ================================================================== HTTP 辅助

    private ResponseEntity<JsonNode> post(String path, String json) {
        return exchange(HttpMethod.POST, path, json);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (adminJwt != null) headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    private Long insertAndReturnKey(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("自增主键应取到：%s", sql).isNotNull();
        return key.longValue();
    }
}
