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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删单守卫的第 3 条：**已出库但物流还没回传**的包裹也要拒删（{@code OrderPurgeService#blockReason}）：
 * 真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要固化的缺陷</h3>
 * 守卫原来只拦「有物流记录 / 有退货申请」。但 {@code delivery_order.status=3（已出库）} 表示
 * **货已经离开商家仓库**，只是 WMS 的运单号还没回传到 {@code delivery_logistics} 而已 ——
 * 这种订单原来<b>可以删</b>，而删单会走 {@code settleStockLedger} 把这单扣掉的货回补进总池：
 * 货在买家路上，库里却凭空多出一份，口径上偏乐观（等于货凭空回到仓库）。
 *
 * <h3>现在的口径</h3>
 * 该订单存在**未取消（≠9）且已出库（status ≥ 3）**的包裹时拒删，中文原因（需求钉死的原文）：
 * <pre>该订单有 N 个包裹已出库（货已离开仓库），不能删除；请先完成签收或改为关闭订单</pre>
 * 口径与 ship 闸门（{@code OrderController#countOutboundDeliveries}）完全一致：已取消(9) 不算 ——
 * 取消时货已经回补过，不能拿它替「已出库」作证。
 *
 * <p>并且这条守卫必须**在回补之前**生效：被拒时订单/台账/库存一件都不许动。
 *
 * <p>数据自建自清：订单/买家/包裹自己造，跑完全删；{@code sku.stock} 在 @AfterAll 还原。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OrderPurgeOutboundGuardIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPurgeOutboundGuardIntegrationTest {

    /** 需求钉死的拒删原因（改文案要同步改这里 —— 它是对外承诺，不是日志） */
    static final String OUTBOUND_BLOCK = "个包裹已出库（货已离开仓库），不能删除；请先完成签收或改为关闭订单";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminJwt;
    private Long memberId;
    private Long productId;
    private Long skuId;
    private Integer skuStockBefore;
    private Long warehouseId;

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

        // 挑库存最充裕的 SKU（本类会反复扣 3 件，别把种子规格扣成 0）
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, product_id, stock FROM sku ORDER BY stock DESC, id LIMIT 1");
        skuId = ((Number) row.get("id")).longValue();
        productId = ((Number) row.get("product_id")).longValue();
        skuStockBefore = ((Number) row.get("stock")).intValue();
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouse ORDER BY id LIMIT 1", Long.class);
        System.out.printf("[准备] member=%d sku=%d product=%d warehouse=%d 原库存=%d%n",
                memberId, skuId, productId, warehouseId, skuStockBefore);
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : createdOrders) {
            for (String sn : jdbc.queryForList("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId)) {
                jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", sn);
            }
            jdbc.update("DELETE l FROM delivery_logistics l JOIN delivery_order d ON d.id = l.delivery_id "
                    + "WHERE d.order_id = ?", orderId);
            jdbc.update("DELETE FROM delivery_item WHERE delivery_id IN "
                    + "(SELECT id FROM delivery_order WHERE order_id = ?)", orderId);
            jdbc.update("DELETE FROM delivery_order WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM return_apply WHERE order_id = ?", orderId);
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
    @DisplayName("有「已出库、物流还没回传」的包裹 → 拒删：原因含「已出库」，订单/台账/库存一件不动")
    void delete_blockedWhenParcelIsOutboundWithoutLogistics() {
        int qty = 3;
        String orderSn = sn("POG-OUT");
        Long orderId = createOrder(orderSn, qty);
        createdOrders.add(orderId);

        int afterCreate = stock();
        int ledgerNetAfterCreate = ledgerNetOf(orderId);
        // 关键：包裹推到「已出库(3)」，但**一条 delivery_logistics 都不插** —— 物流回传还没到
        Long deliveryId = insertParcel(orderId, orderSn, 3);
        int logisticsRows = logisticsCountOf(deliveryId);
        System.out.printf("① 建单 id=%d 单号=%s qty=%d：库存 %d，台账净额 %d；包裹 delivery=%d 状态=3 物流行数=%d%n",
                orderId, orderSn, qty, afterCreate, ledgerNetAfterCreate, deliveryId, logisticsRows);

        assertThat(logisticsRows).as("本用例造的是「已出库但物流还没回传」：delivery_logistics 必须为 0 行").isZero();

        JsonNode body = fail(exchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null));
        String message = body.path("message").asText("");
        int stockAfterBlocked = stock();
        System.out.printf("② DELETE → code=%s 原因=%s%n   订单还在=%b 台账行数=%d 台账净额=%d 库存=%d（建单后是 %d）%n",
                body.path("code").asInt(), message, orderExists(orderId),
                ledgerCountOf(orderId), ledgerNetOf(orderId), stockAfterBlocked, afterCreate);

        assertThat(message).as("拒删原因必须是需求原文：N 个包裹已出库…不能删除…").contains(OUTBOUND_BLOCK);
        assertThat(message).as("原因里要写清是几个包裹").contains("该订单有 1 " + OUTBOUND_BLOCK);
        assertThat(orderExists(orderId)).as("订单必须还在（守卫拦住了，不是删了一半）").isTrue();
        assertThat(ledgerCountOf(orderId)).as("被拒的删单不许清台账").isEqualTo(1);
        assertThat(ledgerNetOf(orderId)).as("被拒的删单不许记回补（台账净额不变）").isEqualTo(ledgerNetAfterCreate);
        assertThat(stockAfterBlocked).as("**守卫在回补之前：被拒时库存一件都不许回补**").isEqualTo(afterCreate);
        assertThat(deliveryStatusOf(deliveryId)).as("包裹状态也不许被删单改动").isEqualTo(3);
    }

    @Test
    @DisplayName("只有「已取消(9)」的包裹 → 不拦（取消时货已回补，不能替「已出库」作证），删单照常并回补")
    void delete_allowedWhenParcelIsCancelled() {
        int qty = 3;
        String orderSn = sn("POG-CXL");
        Long orderId = createOrder(orderSn, qty);
        createdOrders.add(orderId);

        int before = stock();
        Long deliveryId = insertParcel(orderId, orderSn, 9);   // 已取消
        System.out.printf("① 建单 id=%d 单号=%s 包裹 delivery=%d 状态=9（已取消）；库存 %d%n",
                orderId, orderSn, deliveryId, before);

        ok(exchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null));
        int afterDelete = stock();
        System.out.printf("② 删单成功：订单还在=%b 包裹还在=%b 库存 %d → %d（期望回到 %d）%n",
                orderExists(orderId), deliveryStatusOf(deliveryId) != null, before, afterDelete, before + qty);

        assertThat(orderExists(orderId)).as("已取消的包裹不构成「已出库」，删单必须放行").isFalse();
        assertThat(afterDelete).as("可删的单照常按台账净额回补（-%d 件补回来）", qty).isEqualTo(before + qty);

        createdOrders.remove(orderId);   // 已在用例里删掉
    }

    // ================================================================== 数据构造

    /** 后台建单（建单即扣库存 + 记 ORDER_CREATE 台账）：造一张「待审核(7) + 1 条明细」的订单 */
    private Long createOrder(String orderSn, int quantity) {
        String json = "{\"order\":{\"memberId\":" + memberId + ",\"orderSn\":\"" + orderSn + "\",\"status\":7,"
                + "\"payType\":1,\"receiverName\":\"已出库拒删用例收货人\",\"receiverPhone\":\"13700000000\","
                + "\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"已出库拒删用例明细\",\"productPrice\":10.00,\"productQuantity\":"
                + quantity + "}]}";
        return ok(exchange(HttpMethod.POST, "/admin/orders", json)).path("id").asLong();
    }

    /**
     * 直接插一个指定状态的包裹。
     *
     * <p>为什么不用 wms-callback 走到 3：{@code outbound} 回传**会顺手写一条 delivery_logistics**
     * （{@code saveLogisticsIfAbsent}），那样测到的就是「有物流记录」那条老守卫，
     * 测不到本用例要固化的「已出库**但物流还没回传**」这一格。所以这里精确造出那一格。
     */
    private Long insertParcel(Long orderId, String orderSn, int status) {
        String deliverySn = "POGDL" + orderSn;
        jdbc.update("INSERT INTO delivery_order (delivery_sn, order_id, warehouse_id, status, "
                        + "receiver_name, receiver_phone, receiver_address) VALUES (?, ?, ?, ?, ?, ?, ?)",
                deliverySn, orderId, warehouseId, status,
                "已出库拒删用例收货人", "13700000000", "自动化测试地址");
        return jdbc.queryForObject("SELECT id FROM delivery_order WHERE delivery_sn = ?", Long.class, deliverySn);
    }

    private Long registerBuyer() {
        String username = "purge-outbound-" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"已出库拒删用例买家\"}";
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

    private int ledgerCountOf(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE order_id = ?", Integer.class, orderId);
    }

    private int ledgerNetOf(Long orderId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM stock_ledger WHERE order_id = ?",
                Integer.class, orderId);
    }

    private int logisticsCountOf(Long deliveryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_logistics WHERE delivery_id = ?",
                Integer.class, deliveryId);
    }

    /** 包裹状态；包裹已被删掉时返回 null（不能用 queryForObject：0 行会抛 EmptyResultDataAccess） */
    private Integer deliveryStatusOf(Long deliveryId) {
        var rows = jdbc.queryForList("SELECT status FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean orderExists(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?", Integer.class, orderId) > 0;
    }

    // ------------------------------------------------------------------ HTTP 辅助

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

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return new HttpEntity<>(json, headers);
    }
}
