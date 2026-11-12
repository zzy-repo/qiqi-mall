package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单硬删 × 库存台账（{@code stock_ledger}）的集成测试：真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要固化的缺陷</h3>
 * {@code stock_ledger} <b>故意不加外键</b> —— 台账是「同一件货有没有被补两次」的审计证据，
 * 不允许随订单被 {@code ON DELETE CASCADE} 静默带走。代价是：订单被硬删之后，
 * 那些台账行会变成「指向一张不存在的订单」的<b>孤儿行</b>，对账时既看不懂也查不动。
 *
 * <p>所以 {@code OrderPurgeService#purge} 在删主单之前显式按 {@code order_id} 清一遍台账，
 * 且<b>先打审计日志再删</b>（订单号 / 行数 / 各 reason 的件数合计）——
 * 行删了，「这单进出过多少件货」仍然能从应用日志里查回来（与支付流水那一步同一口径）。
 *
 * <h3>本用例断言的三件事</h3>
 * <ol>
 *   <li>造一笔**真的有台账**的订单：走真实业务入口
 *       （建单 → {@code POST /admin/orders/{id}/audit-reject}「审核不通过」按整单回补 → 记 AUDIT_REJECT 台账），
 *       不是手工往 {@code stock_ledger} 塞行；</li>
 *   <li>{@code DELETE /admin/orders/{id}} 之后，该订单的台账行数 <b>归 0</b>（不留孤儿行）；</li>
 *   <li>日志里有审计记录：{@code 删除订单前清理库存台账} + 订单号 / 订单 id + 本次删的行数与 reason 合计。</li>
 * </ol>
 *
 * <p>数据自建自清：订单/买家自己造，跑完全删；回补过的 {@code sku.stock} 在 @AfterAll 还原
 * （订单硬删不还原库存 —— 那是删除本身的语义，不属于本用例要验证的东西）。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OrderPurgeLedgerIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class OrderPurgeLedgerIntegrationTest {

    /** 审计日志的固定前缀（改文案要同步改断言 —— 这正是它存在的意义：日志是对账凭据） */
    static final String LEDGER_AUDIT_PREFIX = "删除订单前清理库存台账";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminJwt;
    private Long skuId;
    private Long productId;
    private Integer skuStockBefore;

    private final List<Long> createdMembers = new ArrayList<>();
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

        // 挑一个「有分仓库存」的 SKU：回补要走分仓归属那条路，没有分仓记录的单拆不出包裹
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
        for (Long orderId : createdOrders) {
            for (String sn : jdbc.queryForList("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId)) {
                jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", sn);
            }
            jdbc.update("DELETE l FROM delivery_logistics l JOIN delivery_order d ON d.id = l.delivery_id "
                    + "WHERE d.order_id = ?", orderId);
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);
        }
        for (Long memberId : createdMembers) {
            jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        }
        // 审核不通过回补过的库存还回去（用例要的是「有台账」，不是改库存）
        if (skuId != null && skuStockBefore != null) {
            jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", skuStockBefore, skuId);
        }
        System.out.printf("[清理] 自建订单 %d 张、会员 %d 个已删除，sku=%d 库存还原为 %s%n",
                createdOrders.size(), createdMembers.size(), skuId, skuStockBefore);
    }

    // ================================================================== 主用例

    @Test
    @DisplayName("硬删有台账的订单：台账行归 0（不留孤儿），且删前已把摘要打进审计日志")
    void purge_removesStockLedger_afterAuditing(CapturedOutput output) {
        int quantity = 2;

        // ① 真实业务入口造一笔「有台账」的订单：建单 → 审核不通过（按整单回补，记 AUDIT_REJECT 台账）
        Long memberId = registerBuyer();
        String orderSn = "PGL" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        Long orderId = createOrder(memberId, orderSn, quantity);
        createdOrders.add(orderId);
        // 建单口径已改：后台建单和买家下单同一套（扣库存 + 记 ORDER_CREATE 台账），所以此刻已有 1 行负数台账
        assertThat(ledgerCountOf(orderId)).as("建单即记一条 ORDER_CREATE 台账").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM stock_ledger WHERE order_id = ?",
                Integer.class, orderId)).as("建单台账是负数（扣减 %d 件）", quantity).isEqualTo(-quantity);

        ok(exchange(HttpMethod.POST, "/admin/orders/" + orderId + "/audit-reject", null));
        int orderStatusAfterReject = jdbc.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        int ledgerRows = ledgerCountOf(orderId);
        int ledgerDeltaSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(delta), 0) FROM stock_ledger WHERE order_id = ?", Integer.class, orderId);
        int stockAfterRestore = jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
        System.out.printf("① 自建订单 id=%d 单号=%s：审核不通过后订单=%d，台账 %d 行（delta 合计 %d），sku.stock %d → %d%n",
                orderId, orderSn, orderStatusAfterReject, ledgerRows, ledgerDeltaSum, skuStockBefore, stockAfterRestore);

        assertThat(orderStatusAfterReject).as("审核不通过 → 订单已关闭(4)").isEqualTo(4);
        assertThat(ledgerRows).as("回补必须留下台账（否则本用例造的不是「有台账的订单」）").isEqualTo(2);
        // 建单 -qty + 审核不通过 +qty = 0：建单扣的货被原样补回，库存净变化为 0（不再虚增）
        assertThat(ledgerDeltaSum).as("建单 -%d 与回补 +%d 相抵，台账合计必须为 0", quantity, quantity).isZero();
        assertThat(stockAfterRestore).as("库存回到建单前（不再虚增）").isEqualTo(skuStockBefore);

        // ② 硬删订单（真实接口：DELETE /admin/orders/{id} → OrderPurgeService.purge）
        ok(exchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null));

        // ③ 订单没了、台账也没了（不留「指向不存在订单」的孤儿行）
        assertThat(orderExists(orderId)).as("订单必须被删掉").isFalse();
        assertThat(ledgerCountOf(orderId)).as("**台账必须一并清掉，不能留孤儿行**").isZero();
        System.out.printf("② 删除后：订单还在=%b，台账行数=%d（期望 0）%n", orderExists(orderId), ledgerCountOf(orderId));

        // ④ 日志里有审计记录：删之前把「这单进出过多少件货」落到应用日志（行删了证据没断）
        String logs = output.getAll();
        assertThat(logs).as("日志里必须有台账审计记录（前缀「%s」）", LEDGER_AUDIT_PREFIX).contains(LEDGER_AUDIT_PREFIX);
        assertThat(logs).as("审计记录里要能定位到这张单（订单号）").contains(orderSn);
        assertThat(logs).as("审计记录里要能定位到这张单（订单 id）").contains("orderId=" + orderId);
        assertThat(logs).as("审计记录里要有本次删除的行数").contains("共 " + ledgerRows + " 行");
        assertThat(logs).as("审计记录里要有各 reason 的件数合计（AUDIT_REJECT=+%d）", quantity)
                .contains("AUDIT_REJECT=+" + quantity);
        System.out.printf("③ 审计日志已落：%s%n", ledgerAuditLineOf(logs, orderId));

        createdOrders.remove(orderId); // 已在用例里删掉，收尾不用再删
    }

    // ================================================================== 数据构造

    private Long registerBuyer() {
        String username = "purge-buyer-" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"台账删除用例买家\"}";
        ResponseEntity<JsonNode> resp = rest.postForEntity("/portal/auth/register", jsonEntity(json), JsonNode.class);
        Long id = ok(resp).path("memberId").asLong();
        createdMembers.add(id);
        return id;
    }

    /** 后台建单（建单即扣库存 + 记 ORDER_CREATE 台账）：造一张「待审核(7) + 1 条明细」的订单，返回订单 id */
    private Long createOrder(Long memberId, String orderSn, int quantity) {
        String json = "{\"order\":{\"memberId\":" + memberId + ",\"orderSn\":\"" + orderSn + "\",\"status\":7,"
                + "\"payType\":1,\"receiverName\":\"台账用例收货人\",\"receiverPhone\":\"13700000000\","
                + "\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId + ","
                + "\"productName\":\"台账删除用例明细\",\"productPrice\":10.00,\"productQuantity\":" + quantity + "}]}";
        return ok(exchange(HttpMethod.POST, "/admin/orders", json)).path("id").asLong();
    }

    // ------------------------------------------------------------------ 状态查询

    private int ledgerCountOf(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE order_id = ?", Integer.class, orderId);
    }

    private boolean orderExists(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?", Integer.class, orderId) > 0;
    }

    /** 从完整日志里挑出本次那条台账审计行（打印给人看；断言用的是 contains） */
    private static String ledgerAuditLineOf(String logs, Long orderId) {
        return logs.lines().filter(l -> l.contains(LEDGER_AUDIT_PREFIX) && l.contains("orderId=" + orderId))
                .findFirst().orElse("(没有找到审计行)").trim();
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private static HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }
}
