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
 * 删订单 × 库存回补（{@code OrderPurgeService#purge}）：真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要固化的缺陷</h3>
 * {@code purge()} 会按 {@code order_id} 删掉该订单的 {@code stock_ledger} 行（不留孤儿账，这是对的），
 * 但原来<b>删之前不回补库存</b> → 后台建单 / 买家下单扣掉的货随删单<b>永久消失</b>（「扣了、账也抹了」）。
 * 实测：跑完一轮测试 sku1 从 1000 掉到 983，全是「造单 → 删单」来回留下的窟窿。
 *
 * <h3>现在的口径</h3>
 * 删台账之前先按该订单台账的**净额**回补：对每个 {@code sku_id} 求 {@code net = Σ(delta)}，
 * 只有 {@code net < 0}（这单净扣了货）才回补 {@code |net|} 件，走库存接口（不裸 UPDATE）；
 * {@code net >= 0}（已经补过 / 压根没扣过）一件都不补。回补数量写进删前那条 warn 审计日志
 * （{@code …按台账净额将回补 N 件…}）。守卫（有物流 / 有退货 → 拒删）必须先生效。
 *
 * <p>数据自建自清：订单/买家/包裹自己造，跑完全删；sku.stock 在 @AfterAll 还原。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OrderPurgeStockRestoreIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class OrderPurgeStockRestoreIntegrationTest {

    /** 审计日志的固定片段（改文案要同步改断言 —— 日志本身就是「补了几件」的对账凭据） */
    static final String RESTORE_AUDIT = "将回补";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 库存落点（启动时定：legacy = sku.stock 就是库存 / bucket = 权威在分桶，sku.stock 是缓存）。
     * 本类断言前先 refreshDisplayCache 一次，这样「读 sku.stock」= 读权威值，断言口径不变；
     * 还原库存用 resetStock（legacy 写 sku.stock，bucket 重铺桶），两种模式都能跑。
     */
    @Autowired
    private com.qiqi.mall.stock.StockStore stockStore;

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

        // 挑库存最充裕的 SKU：本类会反复扣 3 件，别把种子规格扣成 0
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
            jdbc.update("DELETE l FROM delivery_logistics l JOIN delivery_order d ON d.id = l.delivery_id "
                    + "WHERE d.order_id = ?", orderId);
            jdbc.update("DELETE FROM return_apply WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);
        }
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        if (skuId != null && skuStockBefore != null) {
            stockStore.resetStock(skuId, skuStockBefore);   // 还原权威库存（桶 + 缓存）
        }
        System.out.printf("[清理] 自建订单 %d 张、会员 1 个已删除，sku=%d 库存还原为 %s%n",
                createdOrders.size(), skuId, skuStockBefore);
    }

    // ================================================================== 主用例

    @Test
    @DisplayName("净扣货的订单被硬删：库存回补到建单前，日志写明「将回补 3 件」，台账不留残行")
    void purge_restoresStockByLedgerNet(CapturedOutput output) {
        int qty = 3;
        int before = stock();
        String orderSn = sn("PRS-CUT");
        Long orderId = createOrder(orderSn, qty);
        createdOrders.add(orderId);

        int afterCreate = stock();
        int ledgerNet = ledgerNetOf(orderId);
        System.out.printf("① 建单 id=%d 单号=%s 数量=%d：库存 %d → %d，台账净额 %d%n",
                orderId, orderSn, qty, before, afterCreate, ledgerNet);
        assertThat(afterCreate).as("建单即扣库存（%d 件）", qty).isEqualTo(before - qty);
        assertThat(ledgerNet).as("建单记一条 ORDER_CREATE 负数台账").isEqualTo(-qty);

        ok(exchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null));

        int afterDelete = stock();
        String auditLine = restoreAuditLineOf(output.getAll(), orderId);
        System.out.printf("② 删单后：库存 %d → %d（期望回到 %d），台账行数 %d，审计行：%s%n",
                afterCreate, afterDelete, before, ledgerCountOf(orderId), auditLine);

        assertThat(orderExists(orderId)).as("订单必须被删掉").isFalse();
        assertThat(afterDelete).as("**净扣货的订单被删后，扣掉的货必须回补**").isEqualTo(before);
        assertThat(ledgerCountOf(orderId)).as("台账随之清空，不留「指向不存在订单」的孤儿行").isZero();
        assertThat(output.getAll()).as("删前审计日志必须带上回补数量（将回补 %d 件）", qty)
                .contains(RESTORE_AUDIT + " " + qty + " 件");
        assertThat(auditLine).as("审计日志要能按订单号/订单 id 定位").contains(orderSn).contains("orderId=" + orderId);

        createdOrders.remove(orderId);   // 已在用例里删掉
    }

    @Test
    @DisplayName("净额为 0 的订单（审核不通过 -3+3）被硬删：一件都不回补，库存不变")
    void purge_doesNotRestoreWhenNetIsZero(CapturedOutput output) {
        int qty = 3;
        int before = stock();
        String orderSn = sn("PRS-ZERO");
        Long orderId = createOrder(orderSn, qty);
        createdOrders.add(orderId);

        int afterCreate = stock();
        ok(exchange(HttpMethod.POST, "/admin/orders/" + orderId + "/audit-reject", null));
        int afterReject = stock();
        int ledgerNet = ledgerNetOf(orderId);
        System.out.printf("① 建单 %d → 审核不通过之后：库存 %d → %d（qty=%d），台账净额 %d%n",
                afterCreate, afterReject, stock(), qty, ledgerNet);
        assertThat(ledgerNet).as("建单 -%d + 审核不通过 +%d = 净 0", qty, qty).isZero();

        ok(exchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null));

        int afterDelete = stock();
        String auditLine = restoreAuditLineOf(output.getAll(), orderId);
        System.out.printf("② 删单后：库存 %d（期望仍为 %d），台账行数 %d，审计行：%s%n",
                afterDelete, before, ledgerCountOf(orderId), auditLine);

        assertThat(orderExists(orderId)).as("订单必须被删掉").isFalse();
        assertThat(afterDelete).as("**净额为 0 = 已经补过，删单不许再补一次**").isEqualTo(before);
        assertThat(ledgerCountOf(orderId)).as("台账清空").isZero();
        assertThat(output.getAll()).as("审计日志要写明这次回补 0 件（而不是悄悄不说）")
                .contains(RESTORE_AUDIT + " 0 件");

        createdOrders.remove(orderId);
    }

    @Test
    @DisplayName("有物流记录的订单仍拒删（守卫在回补之前）：订单/台账/库存一件不动")
    void purge_stillBlockedWhenLogisticsExist() {
        int qty = 3;
        int before = stock();
        String orderSn = sn("PRS-LOGI");
        Long orderId = createOrder(orderSn, qty);
        createdOrders.add(orderId);
        int afterCreate = stock();
        Long deliveryId = insertDeliveryWithLogistics(orderId, orderSn);
        System.out.printf("① 造包裹：订单 id=%d 单号=%s，delivery=%d（已插物流回传），库存 %d → %d%n",
                orderId, orderSn, deliveryId, before, afterCreate);

        JsonNode body = fail(exchange(HttpMethod.DELETE, "/admin/orders/" + orderId, null));
        String message = body.path("message").asText("");
        int stockAfterBlocked = stock();
        System.out.printf("② 删单被拒：code=%s 原因=%s｜订单还在=%b 库存=%d 台账行数=%d%n",
                body.path("code").asInt(), message, orderExists(orderId), stockAfterBlocked, ledgerCountOf(orderId));

        assertThat(message).as("拒删原因要说是「物流记录」并给出出路").contains("物流记录").contains("无法删除");
        assertThat(orderExists(orderId)).as("订单必须还在").isTrue();
        assertThat(ledgerCountOf(orderId)).as("被拒的删单不许清台账").isEqualTo(1);
        assertThat(stockAfterBlocked).as("**守卫在回补之前：被拒时库存一件都不许回补**").isEqualTo(afterCreate);

        // 收尾：先拆掉物流这层证据，再把订单清干净（@AfterAll 也会兜一遍）
        jdbc.update("DELETE FROM delivery_logistics WHERE delivery_id = ?", deliveryId);
        jdbc.update("DELETE FROM delivery_order WHERE id = ?", deliveryId);
    }

    // ================================================================== 数据构造

    /** 后台建单（建单即扣库存 + 记 ORDER_CREATE 台账）：造一张「待审核(7) + 1 条明细」的订单 */
    private Long createOrder(String orderSn, int quantity) {
        String json = "{\"order\":{\"memberId\":" + memberId + ",\"orderSn\":\"" + orderSn + "\",\"status\":7,"
                + "\"payType\":1,\"receiverName\":\"删单回补用例收货人\",\"receiverPhone\":\"13700000000\","
                + "\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"删单回补用例明细\",\"productPrice\":10.00,\"productQuantity\":"
                + quantity + "}]}";
        return ok(exchange(HttpMethod.POST, "/admin/orders", json)).path("id").asLong();
    }

    /** 给订单挂一个「有物流回传」的包裹（守卫要拦的就是它：货可能已经出去了，删单回补不成立） */
    private Long insertDeliveryWithLogistics(Long orderId, String orderSn) {
        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO delivery_order (delivery_sn, order_id, warehouse_id, status, "
                        + "receiver_name, receiver_phone, receiver_address) VALUES (?, ?, ?, 3, ?, ?, ?)",
                "PRSDL" + orderSn, orderId, warehouseId, "删单回补用例收货人", "13700000000", "自动化测试地址");
        Long deliveryId = jdbc.queryForObject("SELECT id FROM delivery_order WHERE delivery_sn = ?",
                Long.class, "PRSDL" + orderSn);
        jdbc.update("INSERT INTO delivery_logistics (delivery_id, company, track_no) VALUES (?, '测试快递', ?)",
                deliveryId, "TRK" + orderSn);
        return deliveryId;
    }

    private Long registerBuyer() {
        String username = "purge-restore-" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"删单回补用例买家\"}";
        ResponseEntity<JsonNode> resp = rest.postForEntity("/portal/auth/register", jsonEntity(json), JsonNode.class);
        return ok(resp).path("memberId").asLong();
    }

    private static String sn(String prefix) {
        return prefix + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    // ------------------------------------------------------------------ 状态查询

    private int stock() {
        // 先按 SUM(sku_bucket.stock) 刷新展示缓存，再读 —— 断言的是权威库存，不是可能滞后的缓存
        stockStore.refreshDisplayCache(skuId);
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }

    private int ledgerCountOf(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE order_id = ?", Integer.class, orderId);
    }

    private int ledgerNetOf(Long orderId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM stock_ledger WHERE order_id = ?",
                Integer.class, orderId);
    }

    private boolean orderExists(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?", Integer.class, orderId) > 0;
    }

    /** 从完整日志里挑出本次那条回补审计行（打印给人看；断言用的是 contains） */
    private static String restoreAuditLineOf(String logs, Long orderId) {
        return logs.lines().filter(l -> l.contains(RESTORE_AUDIT) && l.contains("orderId=" + orderId))
                .findFirst().orElse("(没有找到审计行)").trim();
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
