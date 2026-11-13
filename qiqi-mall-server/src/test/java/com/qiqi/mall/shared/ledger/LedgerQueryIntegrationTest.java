package com.qiqi.mall.shared.ledger;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 台账查询接口（{@code GET /admin/ledgers}）的集成测试：真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要固化的三件事（前端页面已经在用这些口径）</h3>
 * <ol>
 *   <li><b>分页参数边界</b>：{@code page}/{@code size} 是前端写死的参数名，
 *       {@code size > 50} 必须被夹到 50（否则一次拉全表的 UNION 查询会拖垮库）；</li>
 *   <li><b>字段齐全</b>：{@code LedgerRow} 的 15 个字段一个不少 —— 少一个前端就白屏或显示「（无编码）」；</li>
 *   <li><b>六类台账能一次查全，也能按类型/时间/操作人筛</b>：一条不带 type 的请求，
 *       用 {@code bizId} 就能把一个订单的状态、它的包裹、它的售后、它的审计与库存流水一起查出来。</li>
 * </ol>
 *
 * <h3>数据自建自清</h3>
 * 自己造订单 + 包裹 + 三张序号台账（{@code created_at} 固定成 2020-01-01，时间窗用例才是确定性的），
 * 跑完按 id 全删。**只断言自己造的行**，不碰库里别人的数据（这个库同时被 e2e 反复重置）。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=LedgerQueryIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerQueryIntegrationTest {

    /** 固定的台账时间：时间窗用例靠它做确定性断言（不跟别人抢「今天」） */
    private static final Timestamp LEDGER_TIME = Timestamp.valueOf("2020-01-01 12:00:00");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminJwt;
    private Long orderId;
    private Long deliveryId;
    private Long returnApplyId;
    private String orderSn;
    private String deliverySn;
    private final List<Long> createdApiKeyIds = new ArrayList<>();

    // ------------------------------------------------------------------ 准备

    @BeforeAll
    void prepareData() {
        adminJwt = loginAsAdmin();

        Long memberId = jdbc.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse ORDER BY id LIMIT 1", Long.class);
        assertThat(memberId).as("库里至少要有一个会员（订单表有外键 fk_order_member）").isNotNull();
        assertThat(warehouseId).as("库里至少要有一个仓库（发货单表有外键）").isNotNull();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        orderSn = "QQTESTLGR" + suffix;
        deliverySn = "DLTESTLGR" + suffix;

        jdbc.update("INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, "
                        + "receiver_name, created_at, split_status, version, status_seq) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                orderSn, memberId, new BigDecimal("12.34"), 7, 0, "台账集成测试", LEDGER_TIME, 1, 0, 1);
        orderId = jdbc.queryForObject("SELECT id FROM `order` WHERE order_sn = ?", Long.class, orderSn);

        jdbc.update("INSERT INTO delivery_order (delivery_sn, order_id, warehouse_id, status, "
                        + "receiver_name, receiver_phone, receiver_address, source, version, status_seq) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                deliverySn, orderId, warehouseId, 0, "台账集成测试", "13800000000", "广东省深圳市测试路 1 号", 0, 0, 0);
        deliveryId = jdbc.queryForObject("SELECT id FROM delivery_order WHERE delivery_sn = ?", Long.class, deliverySn);

        returnApplyId = 9_900_000L + (long) (Math.random() * 1_000_000);

        // 订单状态台账：出生行（from_status = NULL）+ 一次流转
        insertOrderLedger(null, 0, "ORDER_CREATE", "MEMBER", memberId, "台账测试会员");
        insertOrderLedger(0, 7, "ORDER_PAY", "MEMBER", memberId, "台账测试会员");
        // 包裹状态台账：出生行（带单号快照 + 冗余 order_id，与 StatusLedgerWriter 的写入口径一致）
        jdbc.update("INSERT INTO delivery_status_ledger (delivery_id, delivery_sn, order_id, from_status, "
                        + "to_status, reason, operator_type, operator_id, operator_name, idem_key, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                deliveryId, deliverySn, orderId, null, 0, "DELIVERY_CREATE", "ADMIN", 1L, "admin",
                uniqueKey(), LEDGER_TIME);
        // 售后台账：出生行（冗余 order_id，删除订单后仍能按订单反查）
        jdbc.update("INSERT INTO return_status_ledger (return_apply_id, order_id, from_status, to_status, "
                        + "reason, operator_type, operator_id, operator_name, idem_key, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                returnApplyId, orderId, null, 0, "RETURN_CREATE", "MEMBER", 0L, "台账测试会员",
                uniqueKey(), LEDGER_TIME);

        System.out.println("[准备] 自建台账数据：orderId=" + orderId + "(" + orderSn + ") deliveryId=" + deliveryId
                + "(" + deliverySn + ") returnApplyId=" + returnApplyId);
    }

    @AfterAll
    void cleanUp() {
        try {
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM delivery_status_ledger WHERE delivery_id = ?", deliveryId);
            jdbc.update("DELETE FROM return_status_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM delivery_order WHERE id = ?", deliveryId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);
        } catch (RuntimeException e) {
            System.out.println("[清理] 台账自建数据清理失败（不影响结论，请手工核对 order " + orderId + "）：" + e.getMessage());
        }
        for (Long id : createdApiKeyIds) {
            try {
                call(HttpMethod.DELETE, "/admin/api-keys/" + id, null, null, adminJwt);
            } catch (RuntimeException ignored) {
                // 已被用例自己删掉
            }
        }
        System.out.println("[清理] 自建台账数据与临时 API Key 已删除");
    }

    // ------------------------------------------------------------------ ① 六类一次查全

    @Test
    @DisplayName("① bizId 不带 type → 订单/包裹/售后三个分支的行一次全出来，且 15 个字段齐全")
    void allTypes_byBizId_returnsEveryBranchWithAllFields() {
        JsonNode data = get("/admin/ledgers?page=1&size=50&bizId=" + orderId).path("data");
        System.out.println("① /admin/ledgers?bizId=" + orderId + " → total=" + data.path("total").asInt()
                + " 类型分布=" + typeCount(data.path("records")));

        Map<String, Integer> counts = typeCount(data.path("records"));
        assertThat(counts.getOrDefault("ORDER_STATUS", 0))
                .as("订单自己的状态流水应该有 2 行（出生 + 支付）").isEqualTo(2);
        assertThat(counts.getOrDefault("DELIVERY_STATUS", 0))
                .as("该订单的包裹流水（走冗余 order_id / delivery_order 兜底）应该有 1 行").isEqualTo(1);
        assertThat(counts.getOrDefault("RETURN_STATUS", 0))
                .as("该订单的售后流水（经冗余 order_id）应该有 1 行").isEqualTo(1);

        // 字段齐全：前端按列名直接读，缺一个就是空白/白屏
        List<String> requiredKeys = List.of("id", "type", "bizId", "bizSn", "fromStatus", "toStatus",
                "reason", "reasonDetail", "operatorType", "operatorId", "operatorName",
                "refType", "refId", "summary", "createdAt");
        for (JsonNode row : data.path("records")) {
            for (String key : requiredKeys) {
                assertThat(row.has(key)).as("返回行必须带字段 %s：%s", key, row).isTrue();
            }
            assertThat(row.path("id").isNull()).as("id 不能为空").isFalse();
            assertThat(row.path("type").asText()).isNotEmpty();
            assertThat(row.path("bizId").isNull()).as("bizId 不能为空").isFalse();
            assertThat(row.path("reason").asText()).isNotEmpty();
            assertThat(row.path("operatorType").asText()).isNotEmpty();
            assertThat(row.path("createdAt").asText()).as("createdAt 必须是 yyyy-MM-dd HH:mm:ss").hasSize(19);
        }

        // 状态行必须有 from/to；包裹行必须带单号快照（删包后唯一的追踪线索）
        for (JsonNode row : data.path("records")) {
            if ("ORDER_STATUS".equals(row.path("type").asText())) {
                assertThat(row.path("toStatus").isNull()).as("订单状态行的 toStatus 必须有值").isFalse();
                assertThat(row.path("bizSn").asText()).isEqualTo(orderSn);
            }
            if ("DELIVERY_STATUS".equals(row.path("type").asText())) {
                assertThat(row.path("bizSn").asText()).isEqualTo(deliverySn);
            }
        }
    }

    // ------------------------------------------------------------------ ② 分页参数边界

    @Test
    @DisplayName("② size=999 被夹到 50；page=0 被夹到 1（前端参数名就是 page/size）")
    void pageParams_areClamped() {
        JsonNode big = get("/admin/ledgers?page=1&size=999").path("data");
        System.out.println("② size=999 → data.size=" + big.path("size").asLong()
                + "，本页行数=" + big.path("records").size() + "，total=" + big.path("total").asLong());
        assertThat(big.path("size").asLong()).as("size 上限是 50").isEqualTo(50);
        assertThat(big.path("records").size()).isLessThanOrEqualTo(50);

        JsonNode zeroPage = get("/admin/ledgers?page=0&size=3").path("data");
        assertThat(zeroPage.path("current").asLong()).as("page 下限是 1").isEqualTo(1);

        JsonNode zeroSize = get("/admin/ledgers?page=1&size=0").path("data");
        assertThat(zeroSize.path("size").asLong()).as("size 下限是 1").isEqualTo(1);
    }

    // ------------------------------------------------------------------ ③ 单类型 / 时间窗 / 操作人

    @Test
    @DisplayName("③ type=ORDER_STATUS 直查单一台账；时间窗按 'yyyy-MM-dd HH:mm:ss' 生效；非法类型给中文原因")
    void singleType_timeWindow_andValidation() {
        JsonNode onlyOrder = get("/admin/ledgers?page=1&size=50&type=ORDER_STATUS&bizId=" + orderId).path("data");
        System.out.println("③ type=ORDER_STATUS → total=" + onlyOrder.path("total").asInt());
        assertThat(onlyOrder.path("total").asInt()).isEqualTo(2);
        for (JsonNode row : onlyOrder.path("records")) {
            assertThat(row.path("type").asText()).isEqualTo("ORDER_STATUS");
        }

        // 时间窗：台账时间固定在 2020-01-01 12:00:00，窗口卡在这一秒里
        JsonNode inWindow = get("/admin/ledgers", "page", "1", "size", "50", "bizId", String.valueOf(orderId),
                "from", "2020-01-01 11:59:59", "to", "2020-01-01 12:00:01").path("data");
        assertThat(inWindow.path("total").asInt()).as("窗口内应该正好是自建的 4 行").isEqualTo(4);

        JsonNode outWindow = get("/admin/ledgers", "page", "1", "size", "50", "bizId", String.valueOf(orderId),
                "from", "2020-01-02 00:00:00", "to", "2020-01-02 23:59:59").path("data");
        assertThat(outWindow.path("total").asInt()).as("窗口外应该是 0 行").isZero();

        // 纯日期写法（手写 curl 常见）：to 补成当天 23:59:59，所以这一天仍然包含在内
        JsonNode dateOnly = get("/admin/ledgers?page=1&size=50&bizId=" + orderId + "&from=2020-01-01&to=2020-01-01")
                .path("data");
        assertThat(dateOnly.path("total").asInt()).as("纯日期应覆盖当天整天").isEqualTo(4);

        JsonNode badType = get("/admin/ledgers?type=NOT_A_TYPE");
        assertThat(badType.path("code").asInt()).as("类型拼错必须报错，不能静默返回空数组").isNotZero();
        assertThat(badType.path("message").asText()).contains("ORDER_STATUS");

        JsonNode badTime = get("/admin/ledgers?from=2020/01/01");
        assertThat(badTime.path("code").asInt()).isNotZero();
        assertThat(badTime.path("message").asText()).contains("yyyy-MM-dd HH:mm:ss");

        // 反向时间窗必须报错（静默返回 0 条会被误读成「这段时间没有台账」）
        JsonNode reversed = get("/admin/ledgers",
                "from", "2020-01-02 00:00:00", "to", "2020-01-01 00:00:00");
        assertThat(reversed.path("code").asInt()).isNotZero();
        assertThat(reversed.path("message").asText()).contains("开始时间不能晚于结束时间");
    }

    @Test
    @DisplayName("④ operatorType=ADMIN 时库存分支不参与（stock_ledger 没有操作人列，不许假装是管理员干的）")
    void operatorTypeFilter_excludesStockBranch() {
        JsonNode stockWithAdmin = get("/admin/ledgers?page=1&size=50&type=STOCK&operatorType=ADMIN").path("data");
        System.out.println("④ type=STOCK&operatorType=ADMIN → total=" + stockWithAdmin.path("total").asLong());
        assertThat(stockWithAdmin.path("total").asLong()).isZero();

        JsonNode adminRows = get("/admin/ledgers?page=1&size=50&operatorType=ADMIN&bizId=" + orderId).path("data");
        for (JsonNode row : adminRows.path("records")) {
            assertThat(row.path("operatorType").asText()).isEqualTo("ADMIN");
        }
    }

    // ------------------------------------------------------------------ ⑤ AI 密钥访问

    @Test
    @DisplayName("⑤ 新前缀没有登记进 ApiKeyAuthFilter → 带全权限 API Key 也只能拿到 403（fail-closed）")
    void apiKey_isDeniedByDefault() {
        String scopes = "[\"order:read\",\"order:write\",\"catalog:read\",\"catalog:write\","
                + "\"member:read\",\"member:write\",\"config:read\",\"config:write\",\"dashboard:read\"]";
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/api-keys",
                "{\"name\":\"台账越权测试\",\"scopes\":" + scopes + "}", null, adminJwt);
        JsonNode keyData = created.getBody().path("data");
        createdApiKeyIds.add(keyData.path("id").asLong());
        String plaintext = keyData.path("key").asText();

        ResponseEntity<JsonNode> denied = call(HttpMethod.GET, "/admin/ledgers?page=1&size=1", null, plaintext, null);
        System.out.println("⑤ 带全权限 key 调 /admin/ledgers → HTTP " + denied.getStatusCode().value()
                + "，body.message=" + denied.getBody().path("message").asText());
        assertThat(denied.getStatusCode().value()).as("未登记的 admin 前缀对 AI 一律 403").isEqualTo(403);
        assertThat(denied.getBody().path("message").asText()).contains("无权访问");
    }

    // ------------------------------------------------------------------ ⑥ 删包裹后仍能按订单反查

    @Test
    @DisplayName("⑥ 包裹行被物理删除（delivery_order 无此行）后，仍能按订单查到它的状态流水：冗余 order_id")
    void deliveryLedger_queryableByOrder_afterDeliveryRowPurged() {
        long purgedDeliveryId = 9_500_000L + (long) (Math.random() * 900_000);
        String purgedSn = "DLPURGE" + purgedDeliveryId;
        // 造一行「包裹已被物理删除」的台账：delivery_order 里查不到这个 id，
        // 只有台账自己带着冗余 order_id —— 这正是 OrderPurgeService 删包后的真实形态。
        jdbc.update("INSERT INTO delivery_status_ledger (delivery_id, delivery_sn, order_id, from_status, "
                        + "to_status, reason, operator_type, operator_id, operator_name, idem_key, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                purgedDeliveryId, purgedSn, orderId, 0, 1, "DELIVERY_DISPATCH", "ADMIN", 1L, "admin",
                uniqueKey(), LEDGER_TIME);
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE id = ?",
                    Long.class, purgedDeliveryId))
                    .as("前提：delivery_order 里没有这一行 = 包裹已被物理删除").isZero();

            JsonNode data = get("/admin/ledgers?page=1&size=50&type=DELIVERY_STATUS&bizId=" + orderId).path("data");
            List<Long> bizIds = new ArrayList<>();
            for (JsonNode row : data.path("records")) {
                bizIds.add(row.path("bizId").asLong());
            }
            System.out.println("⑥ 删包后按订单查包裹流水 → bizId=" + bizIds);
            assertThat(bizIds)
                    .as("包裹行已不存在，只能靠 delivery_status_ledger.order_id 冗余列命中")
                    .contains(purgedDeliveryId);
        } finally {
            jdbc.update("DELETE FROM delivery_status_ledger WHERE delivery_id = ?", purgedDeliveryId);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private String loginAsAdmin() {
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login",
                Map.of("username", "admin", "password", "123456"), JsonNode.class);
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        String token = node.path("data").path("accessToken").asText(null);
        assertThat(token).as("admin 登录失败（种子账号 admin/123456）：%s", node).isNotBlank();
        return token;
    }

    private JsonNode get(String path) {
        return get(path, new String[0]);
    }

    /**
     * 带查询参数的调用：用 {@link UriComponentsBuilder#encode()} 做**恰好一次**编码。
     *
     * <p>为什么不能直接在 URL 字符串里写 {@code %20}：{@code rest.exchange(String, ...)} 会把它
     * 再编码一遍成 {@code %2520}，后端拿到的是 {@code 2020-01-01%2011:59:59} → 时间解析失败。
     * 这是「时间窗用例第一次跑就红」的真实原因，写在这里免得下一个人踩同一个坑。
     */
    private JsonNode get(String path, String... keyValuePairs) {
        // fromUriString：路径里可能已经带 ?a=b（普通查询），也可能要多加参数（时间窗），两种都要能用
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(path);
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            builder.queryParam(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        java.net.URI uri = builder.build().encode(StandardCharsets.UTF_8).toUri();
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(null, headers), JsonNode.class).getBody();
    }

    /** 统一调用：X-Api-Key / Authorization 按需带，body 用 JSON 字符串 */
    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String jsonBody, String apiKey, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) headers.set("X-Api-Key", apiKey);
        if (jwt != null) headers.setBearerAuth(jwt);
        return rest.exchange(path, method, new HttpEntity<>(jsonBody, headers), JsonNode.class);
    }

    private static Map<String, Integer> typeCount(JsonNode records) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode row : records) {
            counts.merge(row.path("type").asText(), 1, Integer::sum);
        }
        return counts;
    }

    /** 三张台账的 uk_idem 是全局唯一索引：测试行必须用不会撞的键 */
    private static String uniqueKey() {
        return "T" + UUID.randomUUID().toString().replace("-", "");
    }

    private void insertOrderLedger(Integer from, int to, String reason, String operatorType,
                                   Long operatorId, String operatorName) {
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_id, operator_name, idem_key, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                orderId, from, to, reason, null, operatorType, operatorId, operatorName, uniqueKey(), LEDGER_TIME);
    }
}
