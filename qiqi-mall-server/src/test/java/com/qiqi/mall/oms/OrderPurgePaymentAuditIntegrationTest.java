package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删单 × 支付流水 × 台账（{@code admin_audit_log}）的集成测试：真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要固化的缺陷（文档侧先发现、代码侧补的缺口）</h3>
 * {@code OrderPurgeService#purgePaymentRecords} 会按 {@code order_sn} <b>显式</b>删掉该单的支付流水
 * （不可逆），而 {@code payment_record} 没有外键、删了就是删了 —— 可是这段代码<b>一次都没有调过</b>
 * {@code AdminAuditWriter}：只在删之前打了一条 {@code log.warn}（应用日志，会轮转、台账页查不到）。
 * 于是删单之后「这单删之前收过几次钱、每次多少钱」在<b>台账七类里没有任何凭证</b> ——
 * 与台账体系「宁可多记，也不要库里没有但事情发生了」直接冲突。
 * 更糟的是那条 {@code warn} 的文案自己写着「已先落审计日志再删除」：文案在骗人。
 *
 * <h3>本用例断言的四件事</h3>
 * <ol>
 *   <li>造一张**真的有支付流水**的订单（走真实业务入口：后台建单 → 买家 {@code POST /portal/orders/{id}/pay}
 *       真支付 → {@code payment_record} 1 行 type=1），不是手工往表里塞行；</li>
 *   <li>{@code DELETE /admin/orders/{id}} 之后，{@code payment_record} 归 0，
 *       而 {@code admin_audit_log} 里<b>恰好多出 1 行</b> {@code action=PAYMENT_PURGE}；</li>
 *   <li>那一行里必须有判断「钱发生过」的全部要素：<b>订单号、流水条数、金额合计、每条的
 *       {@code tradeNo / type / amount}</b>（{@code summary} 人话摘要 + {@code before_json} 全量快照），
 *       且 {@code target_type/target_id/target_sn/order_id} 能反查到这张单（订单行已经没了，这是唯一线索）；</li>
 *   <li><b>重复删单不多写</b>：同一张单再删一次 → 「订单不存在」，{@code PAYMENT_PURGE} 仍然恰好 1 行。</li>
 * </ol>
 *
 * <h3>幂等口径（为什么不需要业务键幂等）</h3>
 * 审计的 {@code idem_key} 由 {@code request_id} 派生（{@code request_id:序号}），这是
 * {@code AdminAuditWriter} 的红线，<b>绝不由 order_sn 派生</b>（业务键派生会让「连点两次」的第二次
 * 撞 {@code uk_idem} → INSERT 失败 → 事务回滚 → 证据被抹掉）。「重复删单不多写」靠的是<b>业务前置条件</b>：
 * 流水数为 0 时 {@code purgePaymentRecords} 直接返回；第二次删单在「订单不存在」处就返回，连 purge 都进不去。
 * 本用例把这两条都断言了（③ 行数、④ 再删一次的响应与行数）。
 *
 * <p>数据自建自清：订单/买家/审计行自己造，跑完全删；{@code sku.stock} 在 @AfterAll 还原。
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=OrderPurgePaymentAuditIntegrationTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPurgePaymentAuditIntegrationTest {

    /** admin 口令：默认种子值；改过口令（scripts/set-admin-password.sh）时用环境变量 ADMIN_PASSWORD 覆盖 */
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "123456");

    /** 被固化的动作码：删单流水线里清理支付流水 —— 台账七类「审计」那一支按这个 action 检索 */
    private static final String ACTION_PAYMENT_PURGE = "PAYMENT_PURGE";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.qiqi.mall.stock.StockStore stockStore;

    private final ObjectMapper json = new ObjectMapper();

    private String adminJwt;
    private Long skuId;
    private Long productId;
    private Integer skuStockBefore;

    private final List<Long> createdMembers = new ArrayList<>();

    // ------------------------------------------------------------------ 准备 / 清理

    @BeforeAll
    void prepare() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", ADMIN_PASSWORD);
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login", body, JsonNode.class);
        adminJwt = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(adminJwt).as("admin 登录失败：%s", resp.getBody()).isNotBlank();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT s.id AS sku_id, s.product_id AS product_id, s.stock AS stock "
                        + "FROM sku s JOIN inventory i ON i.sku_id = s.id "
                        + "WHERE i.stock > 0 ORDER BY s.id LIMIT 1");
        skuId = ((Number) row.get("sku_id")).longValue();
        productId = ((Number) row.get("product_id")).longValue();
        skuStockBefore = ((Number) row.get("stock")).intValue();
        System.out.printf("[准备] sku=%d product=%d 原库存=%d%n", skuId, productId, skuStockBefore);
    }

    @AfterAll
    void cleanup() {
        for (Long memberId : createdMembers) {
            jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        }
        if (skuId != null && skuStockBefore != null) {
            stockStore.resetStock(skuId, skuStockBefore);   // 建单扣的货随删单回补，这里兜底还原
        }
        System.out.printf("[清理] 自建会员 %d 个已删除，sku=%d 库存还原为 %s%n",
                createdMembers.size(), skuId, skuStockBefore);
    }

    // ================================================================== 主用例

    @Test
    @DisplayName("删单：台账恰好多 1 行 PAYMENT_PURGE（含订单号/条数/金额/流水号），重复删单不再多写")
    void purgePaymentRecords_writesExactlyOneAuditRow_andRepeatDeleteWritesNone() throws Exception {
        // ① 造一张「真的有支付流水」的订单：真买家 + 后台建单(待付款 0) + 买家真支付
        Buyer buyer = registerBuyer();
        String orderSn = "PPA" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        Long orderId = createOrder(buyer.memberId(), orderSn, 1);
        ok(postAsBuyer("/portal/orders/" + orderId + "/pay?payType=1", buyer.jwt()));

        List<Map<String, Object>> payments = jdbc.queryForList(
                "SELECT trade_no, type, amount FROM payment_record WHERE order_sn = ? ORDER BY id", orderSn);
        assertThat(payments).as("支付成功后该单号下必须恰好 1 条流水（本用例造的是「有流水的订单」）").hasSize(1);
        String tradeNo = (String) payments.get(0).get("trade_no");
        int type = ((Number) payments.get(0).get("type")).intValue();
        BigDecimal amount = (BigDecimal) payments.get(0).get("amount");
        assertThat(type).as("买家支付记的是「支付入账」").isEqualTo(1);
        System.out.printf("① 造单 id=%d 单号=%s：支付流水 1 条 tradeNo=%s type=%d amount=%s，订单状态=%d%n",
                orderId, orderSn, tradeNo, type, amount.toPlainString(), orderStatus(orderId));

        // ② 记删前的审计水位（只认这之后新出现的行，避免受库里历史行干扰）
        long fromId = maxAuditId();

        // ③ 真删（真实接口：DELETE /admin/orders/{id} → OrderPurgeService.purge）
        ok(adminExchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null));

        // ④ 流水与订单都没了 —— 所以「这单收过钱」这件事只能靠台账
        assertThat(orderExists(orderId)).as("订单必须被物理删除").isFalse();
        assertThat(paymentCount(orderSn)).as("**支付流水必须一并清理（无外键，靠显式删）**").isZero();

        // ⑤ 台账：恰好多 1 行 PAYMENT_PURGE
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT id, action, target_type, target_id, target_sn, order_id, request_id, idem_key, "
                        + "summary, before_json, operator_type, operator_name "
                        + "FROM admin_audit_log WHERE id > ? AND action = ? ORDER BY id", fromId, ACTION_PAYMENT_PURGE);
        assertThat(audits).as("删单清理支付流水必须在 admin_audit_log 里留下**恰好 1 行** %s", ACTION_PAYMENT_PURGE)
                .hasSize(1);
        Map<String, Object> audit = audits.get(0);
        String summary = String.valueOf(audit.get("summary"));
        String beforeJson = String.valueOf(audit.get("before_json"));
        System.out.printf("⑤ 台账行：id=%s action=%s operator=%s/%s target=%s#%s(单号 %s) order_id=%s%n",
                audit.get("id"), audit.get("action"), audit.get("operator_type"), audit.get("operator_name"),
                audit.get("target_type"), audit.get("target_id"), audit.get("target_sn"), audit.get("order_id"));
        System.out.printf("⑤ summary：%s%n", summary);
        System.out.printf("⑤ before_json：%s%n", beforeJson);
        System.out.printf("⑤ request_id=%s idem_key=%s%n", audit.get("request_id"), audit.get("idem_key"));

        // 5.1 能反查到这张单（订单行已经不存在了，台账里的快照是唯一线索）
        assertThat(audit.get("target_type")).as("目标类型：被清理的是这张单名下的流水，按订单检索").isEqualTo("ORDER");
        assertThat(((Number) audit.get("target_id")).longValue()).as("目标 id = 被删订单 id").isEqualTo(orderId);
        assertThat(audit.get("target_sn")).as("目标单号快照").isEqualTo(orderSn);
        assertThat(((Number) audit.get("order_id")).longValue()).as("order_id 冗余列，可按订单检索").isEqualTo(orderId);
        assertThat(audit.get("operator_type")).as("操作者：点删单的管理员").isEqualTo("ADMIN");

        // 5.2 摘要里必须有订单号 / 条数 / 金额合计 / 每条的 tradeNo·type·amount
        assertThat(summary).as("摘要要能定位到订单号").contains(orderSn);
        assertThat(summary).as("摘要要写明被清理的条数").contains("1 条支付流水");
        assertThat(summary).as("摘要要写明金额合计").contains("金额合计 ¥" + amount.toPlainString());
        assertThat(summary).as("摘要要带每条的流水号").contains("tradeNo=" + tradeNo);
        assertThat(summary).as("摘要要带每笔的类型").contains("type=" + type);
        assertThat(summary).as("摘要要带每笔的金额").contains("amount=" + amount.toPlainString());

        // 5.3 before_json 是全量事实（摘要会被列宽裁，金额/流水号这类要素必须无损）
        JsonNode before = json.readTree(beforeJson);
        assertThat(before.path("count").asInt()).as("快照里记条数").isEqualTo(1);
        assertThat(before.path("records")).as("快照里逐条记流水").hasSize(1);
        JsonNode first = before.path("records").get(0);
        assertThat(first.path("tradeNo").asText()).as("快照里记流水号").isEqualTo(tradeNo);
        assertThat(first.path("type").asInt()).as("快照里记类型").isEqualTo(type);
        assertThat(first.path("amount").decimalValue())
                .as("快照里记金额（数值比较，不受 10.00 / 10.0 的写法影响）")
                .isEqualByComparingTo(amount);

        // 5.4 幂等键红线：只能是「请求号 + :序号」，不许夹任何业务键（order_sn / 动作码都不行）
        String requestId = String.valueOf(audit.get("request_id"));
        String idemKey = String.valueOf(audit.get("idem_key"));
        assertThat(requestId).as("审计行必须有请求号").isNotBlank();
        assertThat(idemKey).as("幂等键必须由请求号派生").startsWith(requestId + ":");
        assertThat(idemKey.substring(requestId.length() + 1)).as("请求号之后只能跟一个序号").matches("^\\d+$");
        assertThat(idemKey).as("幂等键绝不能带业务键（否则第二次点会撞 uk_idem、把证据回滚掉）")
                .doesNotContain(orderSn).doesNotContain(ACTION_PAYMENT_PURGE).doesNotContain(String.valueOf(orderId));

        // ⑥ 这条单在审计里一共 2 行：删单一行 + 清理流水一行（同一请求、request_id 相同、序号不同）
        List<String> actionsOfOrder = jdbc.queryForList(
                "SELECT action FROM admin_audit_log WHERE order_id = ? ORDER BY id", String.class, orderId);
        assertThat(actionsOfOrder).as("删单这一笔在台账里恰好两行：ORDER_DELETE + PAYMENT_PURGE")
                .containsExactly("ORDER_DELETE", ACTION_PAYMENT_PURGE);
        Long sameRequest = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT request_id) FROM admin_audit_log WHERE order_id = ?", Long.class, orderId);
        assertThat(sameRequest).as("两行属于同一次请求（同一 request_id）").isEqualTo(1L);
        System.out.printf("⑥ 该单审计动作序列：%s（同一 request_id=%s）%n", actionsOfOrder, requestId);

        // ⑦ 重复删单：不多写一行（幂等靠「订单不存在」这道业务前置条件，不靠业务键幂等）
        ResponseEntity<JsonNode> repeat = adminExchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null);
        JsonNode repeatBody = repeat.getBody();
        assertThat(repeatBody).isNotNull();
        assertThat(repeatBody.path("code").asInt()).as("重复删单必须失败（订单已不存在）：%s", repeatBody).isNotZero();
        assertThat(repeatBody.path("message").asText()).as("失败原因要说人话").contains("订单不存在");
        long afterRepeat = countPaymentPurgeSince(fromId);
        assertThat(afterRepeat).as("**重复删单不许再多写一行 PAYMENT_PURGE**").isEqualTo(1L);
        System.out.printf("⑦ 再删一次 → code=%d message=%s；PAYMENT_PURGE 仍是 %d 行，该单审计共 %d 行%n",
                repeatBody.path("code").asInt(), repeatBody.path("message").asText(), afterRepeat,
                jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE order_id = ?",
                        Integer.class, orderId));

        // ⑧ 收尾：这张单的审计行是本用例造的，删掉（订单/流水已被 purge 清空，这里只补审计与会员）
        int removedAudit = jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", orderId);
        assertThat(removedAudit).as("收尾应删掉本用例造的两行审计").isEqualTo(2);
    }

    // ================================================================== 数据构造

    private record Buyer(Long memberId, String jwt) {
    }

    private Buyer registerBuyer() {
        String username = "purge-pay-buyer-" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        String body = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"支付审计用例买家\"}";
        JsonNode data = ok(postJson("/portal/auth/register", body));
        Long memberId = data.path("memberId").asLong();
        createdMembers.add(memberId);
        return new Buyer(memberId, data.path("accessToken").asText());
    }

    /** 后台建单：造一张「待付款(0) + 1 条明细」的订单（建单即扣库存），返回订单 id */
    private Long createOrder(Long memberId, String orderSn, int quantity) {
        String body = "{\"order\":{\"memberId\":" + memberId + ",\"orderSn\":\"" + orderSn + "\",\"status\":0,"
                + "\"payType\":1,\"receiverName\":\"支付审计用例收货人\",\"receiverPhone\":\"13700000000\","
                + "\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ","
                + "\"productSkuId\":" + skuId + ",\"productName\":\"支付审计用例明细\","
                + "\"productPrice\":10.00,\"productQuantity\":" + quantity + "}]}";
        return ok(adminExchange(HttpMethod.POST, "/admin/orders", body)).path("id").asLong();
    }

    // ------------------------------------------------------------------ 状态查询

    private long maxAuditId() {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_audit_log", Long.class);
        return id == null ? 0L : id;
    }

    private long countPaymentPurgeSince(long fromId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_log WHERE id > ? AND action = ?",
                Long.class, fromId, ACTION_PAYMENT_PURGE);
        return n == null ? 0L : n;
    }

    private int paymentCount(String orderSn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);
    }

    private int orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private boolean orderExists(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?", Integer.class, orderId) > 0;
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private ResponseEntity<JsonNode> adminExchange(HttpMethod method, String path, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(jsonBody, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postAsBuyer(String path, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postJson(String path, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), JsonNode.class);
    }

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }
}
