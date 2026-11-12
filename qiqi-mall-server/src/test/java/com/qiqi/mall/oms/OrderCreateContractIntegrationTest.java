package com.qiqi.mall.oms;

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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台建单（{@code POST /admin/orders}）入口的**另外四条收敛**：真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要固化的缺陷</h3>
 * 状态白名单（0/1/7）只挡住了「非法状态」，但同一个入口还能造出另外一类「流程永远产不出的组合」：
 * <ol>
 *   <li>请求体传 {@code splitStatus=2} 就原样落库 → 「已拆单（split_status=1/2）却零包裹」；</li>
 *   <li>{@code status=0} 却带着 {@code paymentTime} / {@code payType} → 「待付款却有支付时间」；
 *       {@code status=7} 同样能带支付痕迹 → 「已付款/待审核却没有任何支付流水」；</li>
 *   <li>{@code items=[]} 时总金额不重算 → 「总金额 999、0 条明细」（金额凭空存在，对账无据可查）；</li>
 *   <li>{@code memberId} 不存在 → 掉到外键 {@code fk_order_member}，只剩「数据处理失败，请稍后重试」；</li>
 *   <li>{@code items[0].productSkuId} 不存在 → 被说成「库存不足：规格 X 只剩 0 件」（文案误导，
 *       把「配错 id」说成「补货就行」）。</li>
 * </ol>
 *
 * <h3>现在的口径</h3>
 * <ul>
 *   <li>{@code splitStatus} 强制 0；</li>
 *   <li>{@code status ∈ {0, 7}} → {@code paymentTime = null}、{@code payType = 0}（未支付）；</li>
 *   <li>{@code items} 为空 → {@code totalAmount = 0}；</li>
 *   <li>{@code memberId} 不存在 → 中文原因「会员不存在（id=X），请先创建会员」，一行都不落库；</li>
 *   <li>{@code items[].productSkuId} 不存在 → 中文原因「规格不存在（id=X）」，一行都不落库。</li>
 * </ul>
 *
 * <p>数据自建自清：订单/买家自己造，跑完全删；{@code sku.stock} 在 @AfterAll 还原。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OrderCreateContractIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCreateContractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminJwt;
    private Long memberId;
    private Long productId;
    private Long skuId;
    private Integer skuStockBefore;

    private final List<Long> createdOrders = new ArrayList<>();

    // ------------------------------------------------------------------ 准备 / 清理

    @BeforeAll
    void prepare() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", "123456");
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login", body, JsonNode.class);
        adminJwt = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(adminJwt).as("admin 登录失败：%s", resp.getBody()).isNotBlank();

        memberId = registerBuyer();

        // 挑库存最充裕的 SKU（建单会真扣库存，别把种子数据扣成 0）
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, product_id, stock FROM sku ORDER BY stock DESC, id LIMIT 1");
        skuId = ((Number) row.get("id")).longValue();
        productId = ((Number) row.get("product_id")).longValue();
        skuStockBefore = ((Number) row.get("stock")).intValue();
        System.out.printf("[准备] member=%d sku=%d product=%d 原库存=%d%n",
                memberId, skuId, productId, skuStockBefore);
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : createdOrders) {
            for (String sn : jdbc.queryForList("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId)) {
                jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", sn);
            }
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);
        }
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        if (skuId != null && skuStockBefore != null) {
            jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", skuStockBefore, skuId);
        }
        System.out.printf("[清理] 自建订单 %d 张、会员 1 个已删除，sku=%d 库存还原为 %s%n",
                createdOrders.size(), skuId, skuStockBefore);
    }

    // ================================================================== 主用例

    @Test
    @DisplayName("① splitStatus 强制 0：请求体传 2（已拆单）也落 0，建不出「已拆单却零包裹」")
    void create_forcesSplitStatusZero() {
        String orderSn = sn("ADM-SPLIT");
        JsonNode data = ok(post("/admin/orders", orderBody(orderSn, 0, 1, 2, 0, null)));
        Long orderId = data.path("id").asLong();
        createdOrders.add(orderId);

        int splitStatus = jdbc.queryForObject("SELECT split_status FROM `order` WHERE id = ?", Integer.class, orderId);
        int parcels = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE order_id = ?",
                Integer.class, orderId);
        System.out.printf("① 建单 id=%d 单号=%s 请求体 splitStatus=2 → 库内 split_status=%d，包裹数=%d%n",
                orderId, orderSn, splitStatus, parcels);

        assertThat(splitStatus).as("splitStatus 由建单入口强制为 0（「已拆」只能由拆单流程写）").isZero();
        assertThat(data.path("splitStatus").asInt(-1)).as("响应里带回来的也是 0").isZero();
        assertThat(parcels).as("这张单确实一张包裹都没有 —— 更不该自称已拆单").isZero();
    }

    @Test
    @DisplayName("② status=0/7 清掉支付痕迹：paymentTime=null、payType=0（建不出「待付款却有支付时间」）")
    void create_clearsPaymentTraceForUnpaidAndPendingAudit() {
        for (int status : new int[] {0, 7}) {
            String orderSn = sn("ADM-PAY" + status);
            JsonNode data = ok(post("/admin/orders", orderBody(orderSn, status, 1, 0, 1, "2026-01-02 03:04:05")));
            Long orderId = data.path("id").asLong();
            createdOrders.add(orderId);

            Integer payType = jdbc.queryForObject("SELECT pay_type FROM `order` WHERE id = ?", Integer.class, orderId);
            Object paymentTime = jdbc.queryForObject("SELECT payment_time FROM `order` WHERE id = ?",
                    Object.class, orderId);
            System.out.printf("② status=%d 建单 id=%d 单号=%s：请求体给了 paymentTime/payType=2 → 库内 paymentTime=%s payType=%s%n",
                    status, orderId, orderSn, paymentTime, payType);

            assertThat(paymentTime).as("status=%d 在流程上没有支付流水，paymentTime 必须清空", status).isNull();
            assertThat(payType).as("status=%d 的 payType 必须落 0（未支付）", status).isEqualTo(0);
            assertThat(data.path("payType").asInt(-1)).as("响应里带回的也是 0").isZero();
        }
    }

    @Test
    @DisplayName("②b 边界：status=1 已付款不在此列（历史直发单的支付痕迹由调用方给出，不被抹掉）")
    void create_keepsPayTypeForPaidStatus() {
        String orderSn = sn("ADM-PAID");
        JsonNode data = ok(post("/admin/orders", orderBody(orderSn, 1, 1, 0, 1, null)));
        Long orderId = data.path("id").asLong();
        createdOrders.add(orderId);

        Integer payType = jdbc.queryForObject("SELECT pay_type FROM `order` WHERE id = ?", Integer.class, orderId);
        System.out.printf("②b status=1 建单 id=%d 单号=%s：payType=%d（期望 1，原样保留）%n", orderId, orderSn, payType);
        assertThat(payType).as("只清 0/7 这两个「流程上没有支付流水」的状态，1 不动").isEqualTo(1);
    }

    @Test
    @DisplayName("③ items 为空 → totalAmount 落 0（建不出「总金额 999、0 条明细」）")
    void create_zeroesTotalAmountWhenNoItems() {
        String orderSn = sn("ADM-EMPTY");
        String json = "{\"order\":{\"orderSn\":\"" + orderSn + "\",\"memberId\":" + memberId
                + ",\"status\":0,\"totalAmount\":999.00,\"receiverName\":\"建单契约用例收货人\""
                + ",\"receiverPhone\":\"13700000000\",\"receiverAddress\":\"自动化测试地址（跑完即删）\""
                + ",\"splitStatus\":0},\"items\":[]}";
        JsonNode data = ok(post("/admin/orders", json));
        Long orderId = data.path("id").asLong();
        createdOrders.add(orderId);

        BigDecimal total = jdbc.queryForObject("SELECT total_amount FROM `order` WHERE id = ?",
                BigDecimal.class, orderId);
        int items = jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id = ?", Integer.class, orderId);
        System.out.printf("③ 建单 id=%d 单号=%s：请求体 totalAmount=999、items=[] → 库内 total_amount=%s，明细 %d 条%n",
                orderId, orderSn, total, items);

        assertThat(total).as("没有明细就没有金额：totalAmount 必须落 0").isEqualByComparingTo("0");
        assertThat(items).as("明细确实为空").isZero();
    }

    @Test
    @DisplayName("④ memberId 不存在 → 中文原因「会员不存在（id=X），请先创建会员」，且一行都不落库")
    void create_rejectsMissingMemberWithChineseReason() {
        String orderSn = sn("ADM-NOMEM");
        long ghostMember = 999999L;
        JsonNode body = fail(post("/admin/orders", orderBodyForMember(orderSn, ghostMember)));
        String message = body.path("message").asText("");
        int rows = orderCountOf(orderSn);
        System.out.printf("④ memberId=%d → code=%s 落库行数=%d 原因=%s%n",
                ghostMember, body.path("code").asInt(), rows, message);

        assertThat(message).as("必须是中文业务原因，不是 SQL 原文").contains("会员不存在（id=" + ghostMember + "）")
                .contains("请先创建会员");
        assertThat(rows).as("被拒的建单一行都不许落库").isZero();
    }

    @Test
    @DisplayName("⑤ items[0].productSkuId 不存在 → 中文原因「规格不存在（id=X）」（不是「库存不足…只剩 0 件」）")
    void create_rejectsMissingSkuWithChineseReason() {
        String orderSn = sn("ADM-NOSKU");
        long ghostSku = 999999L;
        int stockBefore = stock();
        JsonNode body = fail(post("/admin/orders", orderBodyForSku(orderSn, ghostSku)));
        String message = body.path("message").asText("");
        int rows = orderCountOf(orderSn);
        int stockAfter = stock();
        System.out.printf("⑤ productSkuId=%d → code=%s 落库行数=%d 库存 %d → %d 原因=%s%n",
                ghostSku, body.path("code").asInt(), rows, stockBefore, stockAfter, message);

        assertThat(message).as("存在性先说清：是「规格不存在」，不是「库存不足」")
                .contains("规格不存在（id=" + ghostSku + "）");
        assertThat(message).as("**不许**再出现误导性的「库存不足」文案").doesNotContain("库存不足");
        assertThat(rows).as("被拒的建单一行都不许落库").isZero();
        assertThat(stockAfter).as("整笔事务回滚：库存一件都没扣").isEqualTo(stockBefore);
    }

    // ================================================================== 数据构造

    /** 标准建单请求体（1 条明细，指向真实存在的 sku） */
    private String orderBody(String orderSn, int status, int quantity, int splitStatus,
                             int payType, String paymentTime) {
        String paymentPart = paymentTime == null ? "" : ",\"paymentTime\":\"" + paymentTime + "\"";
        return "{\"order\":{\"orderSn\":\"" + orderSn + "\",\"memberId\":" + memberId
                + ",\"status\":" + status + ",\"payType\":" + payType + paymentPart
                + ",\"receiverName\":\"建单契约用例收货人\",\"receiverPhone\":\"13700000000\""
                + ",\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":" + splitStatus + "},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"建单契约用例-明细\",\"productPrice\":10.00,\"productQuantity\":"
                + quantity + "}]}";
    }

    private String orderBodyForMember(String orderSn, long ghostMemberId) {
        return "{\"order\":{\"orderSn\":\"" + orderSn + "\",\"memberId\":" + ghostMemberId
                + ",\"status\":0,\"receiverName\":\"建单契约用例收货人\",\"receiverPhone\":\"13700000000\""
                + ",\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"建单契约用例-明细\",\"productPrice\":10.00,\"productQuantity\":1}]}";
    }

    private String orderBodyForSku(String orderSn, long ghostSkuId) {
        return "{\"order\":{\"orderSn\":\"" + orderSn + "\",\"memberId\":" + memberId
                + ",\"status\":0,\"receiverName\":\"建单契约用例收货人\",\"receiverPhone\":\"13700000000\""
                + ",\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + ghostSkuId
                + ",\"productName\":\"建单契约用例-幽灵规格\",\"productPrice\":10.00,\"productQuantity\":1}]}";
    }

    private Long registerBuyer() {
        String username = "create-contract-" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"建单契约用例买家\"}";
        ResponseEntity<JsonNode> resp = rest.postForEntity("/portal/auth/register", jsonEntity(json), JsonNode.class);
        return ok(resp).path("memberId").asLong();
    }

    private static String sn(String prefix) {
        return prefix + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    // ------------------------------------------------------------------ 状态查询

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }

    private int orderCountOf(String orderSn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE order_sn = ?", Integer.class, orderSn);
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private ResponseEntity<JsonNode> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    private JsonNode fail(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望被拒，实际：%s", node).isNotZero();
        return node;
    }

    private HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return new HttpEntity<>(json, headers);
    }
}
