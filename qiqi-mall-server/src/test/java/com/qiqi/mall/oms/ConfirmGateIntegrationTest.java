package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.order.mapper.OrderItemMapper;
import com.qiqi.mall.order.mapper.OrderMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
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

import java.sql.PreparedStatement;
import java.sql.Statement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认码（confirmCode）覆盖 **14 个 L3 高危写接口** 的集成测试：真起后端 + 真打 HTTP + 真连库。
 *
 * <p>统一契约（每个端点都验三条）：
 * <pre>
 * ① dryRun=true 预演 → 返回 willDo（具体人话）+ confirmCode（36 位 UUID）+ expiresInSec=300，且**库里数据零变化**
 * ② 不带码执行 → 中文错误（缺确认码）
 * ③ 带码执行 → 成功；假码 / 复用码 → 被拒
 * </pre>
 * 另外挑 3 个端点验「指纹变化被拒」：audit-pass（改方案）、dispatch（改包裹结构）、delete-product（改引用数）。
 *
 * <p>数据自建：订单/明细/商品/SKU/购物车/退货申请全部自己造，跑完全删；
 * 只有「分仓库存、会员、分类、类型、退货原因」这类目录基础数据是复用现成的只读事实。
 * 会改 sku.stock 的用例（audit-reject / cancel / return-audit 回补库存）在 @BeforeAll 记下原值、@AfterAll 还原。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=ConfirmGateIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfirmGateIntegrationTest {

    private static final Pattern UUID36 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    private String adminJwt;
    private Long memberId;
    private Long categoryId;
    private Long typeId;
    private Long returnReasonId;
    private Long skuA;
    private Long skuB;
    private Long productIdOfSkuA;
    private Long productIdOfSkuB;
    private final List<Long> createdOrders = new ArrayList<>();
    private final List<Long> createdProducts = new ArrayList<>();
    private final List<Long> createdCarts = new ArrayList<>();
    private final Map<Long, Integer> originalStock = new LinkedHashMap<>();

    // ------------------------------------------------------------------ 准备 / 清理

    @BeforeAll
    void prepare() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", "123456");
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login", body, JsonNode.class);
        adminJwt = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(adminJwt).as("admin 登录失败：%s", resp.getBody()).isNotBlank();

        memberId = jdbc.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
        categoryId = jdbc.queryForObject("SELECT id FROM category ORDER BY id LIMIT 1", Long.class);
        typeId = jdbc.queryForObject("SELECT id FROM product_type ORDER BY id LIMIT 1", Long.class);
        returnReasonId = jdbc.queryForObject("SELECT id FROM return_reason ORDER BY id LIMIT 1", Long.class);

        // 两个「发货仓不同」的 SKU（SKU 发货仓 = 库存最多的仓）→ 拆单方案必然是 2 个包裹
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id AS sku_id, s.product_id AS product_id, i.warehouse_id AS wh, s.stock AS stock "
                        + "FROM sku s JOIN inventory i ON i.sku_id = s.id "
                        + "WHERE i.stock > 0 AND i.stock = (SELECT MAX(stock) FROM inventory i2 WHERE i2.sku_id = s.id) "
                        + "ORDER BY s.id");
        assertThat(rows.size()).as("库里至少要有两个有分仓库存的 SKU").isGreaterThan(1);
        Map<String, Object> first = rows.get(0);
        Map<String, Object> second = rows.stream()
                .filter(r -> !r.get("wh").equals(first.get("wh")))
                .findFirst().orElse(rows.get(1));
        skuA = ((Number) first.get("sku_id")).longValue();
        productIdOfSkuA = ((Number) first.get("product_id")).longValue();
        skuB = ((Number) second.get("sku_id")).longValue();
        productIdOfSkuB = ((Number) second.get("product_id")).longValue();
        originalStock.put(skuA, ((Number) first.get("stock")).intValue());
        originalStock.put(skuB, ((Number) second.get("stock")).intValue());

        System.out.printf("[准备] member=%d skuA=%d(仓%s) skuB=%d(仓%s) 原库存=%s%n",
                memberId, skuA, first.get("wh"), skuB, second.get("wh"), originalStock);
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : createdOrders) {
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);   // 级联明细/发货单/退货申请
        }
        for (Long cartId : createdCarts) {
            jdbc.update("DELETE FROM cart WHERE id = ?", cartId);
        }
        for (Long productId : createdProducts) {
            jdbc.update("DELETE FROM product WHERE id = ?", productId);  // 级联下辖 SKU
        }
        originalStock.forEach((skuId, stock) -> jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", stock, skuId));
        System.out.printf("[清理] 自建订单 %d 张、商品 %d 个、购物车 %d 条已删除，SKU 库存已还原%n",
                createdOrders.size(), createdProducts.size(), createdCarts.size());
    }

    // ================================================================== 订单域（4）

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("① audit-pass：dryRun 预演 → 缺码 → 带码执行（含复用被拒）")
    void auditPass_confirmed() {
        Long orderId = newOrder(7, skuA, skuB);
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/audit-pass"), null));
        String code = dry.path("confirmCode").asText();
        System.out.printf("① audit-pass 预演 → willDo=%s | code=%s | 有效期=%ds | 订单状态仍=%d、发货单数=%d%n",
                dry.path("willDo").asText(), code, dry.path("expiresInSec").asInt(), statusOf(orderId), deliveryCountOf(orderId));
        assertThat(dry.path("willDo").asText()).contains("审核通过").contains("2 个发货单");
        assertThat(code).matches(UUID36.pattern());
        assertThat(dry.path("expiresInSec").asLong()).isEqualTo(300L);
        assertThat(statusOf(orderId)).as("预演不能改状态").isEqualTo(7);
        assertThat(deliveryCountOf(orderId)).as("预演不能生成发货单").isZero();

        JsonNode noCode = fail(post("/admin/orders/" + orderId + "/audit-pass", "{}"));
        System.out.printf("① audit-pass 缺码 → %s%n", noCode.path("message").asText());
        assertThat(noCode.path("message").asText()).isEqualTo("缺少确认码：请先预览拆单方案");

        JsonNode done = ok(post("/admin/orders/" + orderId + "/audit-pass", body(code)));
        System.out.printf("① audit-pass 带码执行 → 生成发货单 %d 个，订单状态=%d%n", done.size(), statusOf(orderId));
        assertThat(done.size()).isEqualTo(2);
        assertThat(statusOf(orderId)).isEqualTo(8);

        JsonNode again = fail(post("/admin/orders/" + orderId + "/audit-pass", body(code)));
        System.out.printf("① audit-pass 复用同一个码 → %s%n", again.path("message").asText());
        assertThat(again.path("message").asText()).contains("确认码无效");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("② audit-reject：dryRun → 缺码 → 带码（回补库存）")
    void auditReject_confirmed() {
        Long orderId = newOrder(7, skuA, skuB);
        int stockBefore = stockOf(skuA);
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/audit-reject"), null));
        System.out.printf("② audit-reject 预演 → willDo=%s | code=%s | 订单状态仍=%d%n",
                dry.path("willDo").asText(), dry.path("confirmCode").asText(), statusOf(orderId));
        assertThat(dry.path("willDo").asText()).contains("审核不通过").contains("回补").contains("件");
        assertThat(statusOf(orderId)).isEqualTo(7);

        JsonNode noCode = fail(post("/admin/orders/" + orderId + "/audit-reject", null));
        System.out.printf("② audit-reject 缺码 → %s%n", noCode.path("message").asText());
        assertThat(noCode.path("message").asText()).startsWith("缺少确认码");

        ok(post("/admin/orders/" + orderId + "/audit-reject", body(dry.path("confirmCode").asText())));
        System.out.printf("② audit-reject 带码执行 → 订单状态=%d，SKU%d 库存 %d→%d（回补）%n",
                statusOf(orderId), skuA, stockBefore, stockOf(skuA));
        assertThat(statusOf(orderId)).isEqualTo(4);
        assertThat(stockOf(skuA)).isGreaterThan(stockBefore);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("③ ship：dryRun → 缺码 → 带码")
    void ship_confirmed() {
        Long orderId = newOrder(1, skuA, skuB);
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/ship"), null));
        System.out.printf("③ ship 预演 → willDo=%s | 订单状态仍=%d%n", dry.path("willDo").asText(), statusOf(orderId));
        assertThat(dry.path("willDo").asText()).contains("已发货");
        assertThat(statusOf(orderId)).isEqualTo(1);

        System.out.printf("③ ship 缺码 → %s%n", fail(post("/admin/orders/" + orderId + "/ship", null)).path("message").asText());

        ok(post("/admin/orders/" + orderId + "/ship", body(dry.path("confirmCode").asText())));
        System.out.printf("③ ship 带码执行 → 订单状态=%d%n", statusOf(orderId));
        assertThat(statusOf(orderId)).isEqualTo(2);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("④ DELETE 订单：dryRun → 缺码 → 带码（级联删除）")
    void orderDelete_confirmed() {
        Long orderId = newOrder(7, skuA, skuB);
        JsonNode dry = ok(delete(withDry("/admin/orders/" + orderId), null));
        System.out.printf("④ 删订单 预演 → willDo=%s | 订单仍存在=%s%n",
                dry.path("willDo").asText(), orderExists(orderId));
        assertThat(dry.path("willDo").asText()).contains("级联删除").contains("2 条明细");
        assertThat(orderExists(orderId)).isTrue();

        System.out.printf("④ 删订单 缺码 → %s%n", fail(delete("/admin/orders/" + orderId, null)).path("message").asText());
        assertThat(orderExists(orderId)).isTrue();

        ok(delete("/admin/orders/" + orderId, body(dry.path("confirmCode").asText())));
        createdOrders.remove(orderId);
        System.out.printf("④ 删订单 带码执行 → 订单仍存在=%s，明细数=%d%n", orderExists(orderId), itemCountOfDeleted(orderId));
        assertThat(orderExists(orderId)).isFalse();
    }

    // ================================================================== 履约域（7）

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("⑤ dispatch：dryRun → 缺码 → 带码")
    void dispatch_confirmed() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveries.get(0);
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/dispatch"), null));
        System.out.printf("⑤ dispatch 预演 → willDo=%s | 包裹状态仍=%d%n", dry.path("willDo").asText(), deliveryStatusOf(id));
        assertThat(dry.path("willDo").asText()).contains("下发给 WMS");
        assertThat(deliveryStatusOf(id)).isZero();

        System.out.printf("⑤ dispatch 缺码 → %s%n", fail(post("/admin/deliveries/" + id + "/dispatch", null)).path("message").asText());
        assertThat(deliveryStatusOf(id)).isZero();

        ok(post("/admin/deliveries/" + id + "/dispatch", body(dry.path("confirmCode").asText())));
        System.out.printf("⑤ dispatch 带码执行 → 包裹状态=%d%n", deliveryStatusOf(id));
        assertThat(deliveryStatusOf(id)).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("⑥ PUT 编辑包裹：dryRun → 缺码 → 带码（改仓）")
    void deliveryEdit_confirmed() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveries.get(0);
        Long targetWarehouse = otherWarehouseThan(warehouseOf(id));   // 必须真的换个仓，否则没有变化
        String payload = "{\"warehouseId\":" + targetWarehouse + "}";

        JsonNode dry = ok(put(withDry("/admin/deliveries/" + id), payload));
        System.out.printf("⑥ 编辑包裹 预演 → willDo=%s | 仓库仍=%d%n", dry.path("willDo").asText(), warehouseOf(id));
        assertThat(dry.path("willDo").asText()).contains("发货仓改为");
        assertThat(warehouseOf(id)).isNotEqualTo(targetWarehouse);

        System.out.printf("⑥ 编辑包裹 缺码 → %s%n", fail(put("/admin/deliveries/" + id, payload)).path("message").asText());
        assertThat(warehouseOf(id)).isNotEqualTo(targetWarehouse);

        // 乐观锁改造后，执行必须带 version（fail-closed：不带会回 code=400）
        int version = versionOfDelivery(id);
        ok(put("/admin/deliveries/" + id, "{\"warehouseId\":" + targetWarehouse
                + ",\"version\":" + version
                + ",\"confirmCode\":\"" + dry.path("confirmCode").asText() + "\"}"));
        System.out.printf("⑥ 编辑包裹 带码+version=%d 执行 → 仓库=%d，version→%d%n",
                version, warehouseOf(id), versionOfDelivery(id));
        assertThat(warehouseOf(id)).isEqualTo(targetWarehouse);
        assertThat(versionOfDelivery(id)).as("成功编辑后 version 必须 +1").isEqualTo(version + 1);
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("⑦ split：dryRun → 缺码 → 带码（拆成 3 个包裹）")
    void deliverySplit_confirmed() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveryWithLineQtyAtLeast(deliveries, 2);   // 拆「多件」的包，拆完源包还剩 1 件
        int before = deliveriesOfOrder(orderIdOfDelivery(id)).size();
        Long itemId = firstDeliveryItemId(id);
        String payload = "{\"items\":[{\"deliveryItemId\":" + itemId + ",\"quantity\":1}]}";

        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/split"), payload));
        System.out.printf("⑦ 拆包 预演 → willDo=%s | 包裹数仍=%d%n", dry.path("willDo").asText(), before);
        assertThat(dry.path("willDo").asText()).contains("拆成 2 个包裹").contains("新包");
        assertThat(deliveriesOfOrder(orderIdOfDelivery(id))).hasSize(before);

        System.out.printf("⑦ 拆包 缺码 → %s%n", fail(post("/admin/deliveries/" + id + "/split", payload)).path("message").asText());

        ok(post("/admin/deliveries/" + id + "/split", "{\"items\":[{\"deliveryItemId\":" + itemId
                + ",\"quantity\":1}],\"confirmCode\":\"" + dry.path("confirmCode").asText() + "\"}"));
        int after = deliveriesOfOrder(orderIdOfDelivery(id)).size();
        System.out.printf("⑦ 拆包 带码执行 → 包裹数 %d→%d%n", before, after);
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("⑧ merge：dryRun → 缺码 → 带码")
    void deliveryMerge_confirmed() {
        List<Long> deliveries = newPendingDeliveries();
        Long a = deliveries.get(0);
        Long b = deliveries.get(1);
        String payload = "{\"targetId\":" + b + "}";

        JsonNode dry = ok(post(withDry("/admin/deliveries/" + a + "/merge"), payload));
        System.out.printf("⑧ 合并 预演 → willDo=%s | 两个包裹都还在=%s%n",
                dry.path("willDo").asText(), deliveryExists(a) && deliveryExists(b));
        assertThat(dry.path("willDo").asText()).contains("并入");

        System.out.printf("⑧ 合并 缺码 → %s%n", fail(post("/admin/deliveries/" + a + "/merge", payload)).path("message").asText());
        assertThat(deliveryExists(a)).isTrue();

        ok(post("/admin/deliveries/" + a + "/merge", "{\"targetId\":" + b
                + ",\"confirmCode\":\"" + dry.path("confirmCode").asText() + "\"}"));
        System.out.printf("⑧ 合并 带码执行 → 源包还在=%s，目标包还在=%s%n", deliveryExists(a), deliveryExists(b));
        assertThat(deliveryExists(a)).isFalse();
        assertThat(deliveryExists(b)).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("⑨ DELETE 包裹：dryRun → 缺码 → 带码（有明细=并入目标）")
    void deliveryDelete_confirmed() {
        List<Long> deliveries = newPendingDeliveries();
        Long a = deliveries.get(0);
        Long b = deliveries.get(1);
        String path = "/admin/deliveries/" + a + "?targetId=" + b;

        JsonNode dry = ok(delete(withDry(path), null));
        System.out.printf("⑨ 删包裹 预演 → willDo=%s | 源包仍在=%s%n", dry.path("willDo").asText(), deliveryExists(a));
        assertThat(dry.path("willDo").asText()).contains("删除等同于并入");

        System.out.printf("⑨ 删包裹 缺码 → %s%n", fail(delete(path, null)).path("message").asText());
        assertThat(deliveryExists(a)).isTrue();

        ok(delete(path, body(dry.path("confirmCode").asText())));
        System.out.printf("⑨ 删包裹 带码执行 → 源包还在=%s%n", deliveryExists(a));
        assertThat(deliveryExists(a)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("⑩ cancel：dryRun → 缺码 → 带码（回补库存）")
    void deliveryCancel_confirmed() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveries.get(0);
        int stockBefore = stockOf(skuA);

        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/cancel"), null));
        System.out.printf("⑩ 取消 预演 → willDo=%s | 包裹状态仍=%d%n", dry.path("willDo").asText(), deliveryStatusOf(id));
        assertThat(dry.path("willDo").asText()).contains("已取消").contains("回补");
        assertThat(deliveryStatusOf(id)).isZero();

        System.out.printf("⑩ 取消 缺码 → %s%n", fail(post("/admin/deliveries/" + id + "/cancel", null)).path("message").asText());

        ok(post("/admin/deliveries/" + id + "/cancel", body(dry.path("confirmCode").asText())));
        System.out.printf("⑩ 取消 带码执行 → 包裹状态=%d，SKU%d 库存 %d→%d%n",
                deliveryStatusOf(id), skuA, stockBefore, stockOf(skuA));
        assertThat(deliveryStatusOf(id)).isEqualTo(9);
        assertThat(stockOf(skuA)).isGreaterThan(stockBefore);
    }

    @Test
    @org.junit.jupiter.api.Order(11)
    @DisplayName("⑪ wms-callback：dryRun → 缺码 → 带码")
    void wmsCallback_confirmed() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveries.get(0);
        // 先合法下发（本身也要码）
        String dispatchCode = ok(post(withDry("/admin/deliveries/" + id + "/dispatch"), null))
                .path("confirmCode").asText();
        ok(post("/admin/deliveries/" + id + "/dispatch", body(dispatchCode)));

        String path = "/admin/deliveries/" + id + "/wms-callback?event=pick";
        JsonNode dry = ok(post(withDry(path), null));
        System.out.printf("⑪ WMS 回传 预演 → willDo=%s | 包裹状态仍=%d%n", dry.path("willDo").asText(), deliveryStatusOf(id));
        assertThat(dry.path("willDo").asText()).contains("拣货");
        assertThat(deliveryStatusOf(id)).isEqualTo(1);

        System.out.printf("⑪ WMS 回传 缺码 → %s%n", fail(post(path, null)).path("message").asText());
        assertThat(deliveryStatusOf(id)).isEqualTo(1);

        ok(post(path, body(dry.path("confirmCode").asText())));
        System.out.printf("⑪ WMS 回传 带码执行 → 包裹状态=%d%n", deliveryStatusOf(id));
        assertThat(deliveryStatusOf(id)).isEqualTo(2);
    }

    // ================================================================== 退货（1）

    @Test
    @org.junit.jupiter.api.Order(12)
    @DisplayName("⑫ PUT 退货审核：dryRun → 缺码 → 带码（退款+回补）")
    void returnAudit_confirmed() {
        Long orderId = newOrder(5, skuA, skuB);          // 退款中
        Long applyId = newReturnApply(orderId);
        String payload = "{\"status\":1}";

        JsonNode dry = ok(put(withDry("/admin/return-applies/" + applyId), payload));
        System.out.printf("⑫ 退货审核 预演 → willDo=%s | 申请状态仍=%d，订单状态仍=%d%n",
                dry.path("willDo").asText(), jdbc.queryForObject(
                        "SELECT status FROM return_apply WHERE id = ?", Integer.class, applyId), statusOf(orderId));
        assertThat(dry.path("willDo").asText()).contains("通过").contains("退款");
        assertThat(statusOf(orderId)).isEqualTo(5);

        System.out.printf("⑫ 退货审核 缺码 → %s%n", fail(put("/admin/return-applies/" + applyId, payload)).path("message").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM return_apply WHERE id = ?", Integer.class, applyId)).isZero();

        ok(put("/admin/return-applies/" + applyId, "{\"status\":1,\"confirmCode\":\""
                + dry.path("confirmCode").asText() + "\"}"));
        System.out.printf("⑫ 退货审核 带码执行 → 申请状态=%d，订单状态=%d%n",
                jdbc.queryForObject("SELECT status FROM return_apply WHERE id = ?", Integer.class, applyId), statusOf(orderId));
        assertThat(jdbc.queryForObject("SELECT status FROM return_apply WHERE id = ?", Integer.class, applyId)).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(6);
    }

    // ================================================================== 商品（2）

    @Test
    @org.junit.jupiter.api.Order(13)
    @DisplayName("⑬ DELETE 商品：dryRun → 缺码 → 带码")
    void productDelete_confirmed() {
        Long productId = newBareProduct("确认码测试商品A");
        newBareSku(productId, "1.00", 10);
        newBareSku(productId, "2.00", 5);

        JsonNode dry = ok(delete(withDry("/admin/products/" + productId), null));
        System.out.printf("⑬ 删商品 预演 → willDo=%s | 商品仍存在=%s%n",
                dry.path("willDo").asText(), productExists(productId));
        assertThat(dry.path("willDo").asText()).contains("下辖 2 个 SKU").contains("删除后不可恢复");
        assertThat(productExists(productId)).isTrue();

        System.out.printf("⑬ 删商品 缺码 → %s%n", fail(delete("/admin/products/" + productId, null)).path("message").asText());
        assertThat(productExists(productId)).isTrue();

        ok(delete("/admin/products/" + productId, body(dry.path("confirmCode").asText())));
        createdProducts.remove(productId);
        System.out.printf("⑬ 删商品 带码执行 → 商品仍存在=%s%n", productExists(productId));
        assertThat(productExists(productId)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Order(14)
    @DisplayName("⑭ DELETE 规格：dryRun → 缺码 → 带码")
    void skuDelete_confirmed() {
        Long productId = newBareProduct("确认码测试商品B");
        Long skuId = newBareSku(productId, "9.90", 8);

        JsonNode dry = ok(delete(withDry("/admin/skus/" + skuId), null));
        System.out.printf("⑭ 删规格 预演 → willDo=%s | SKU 仍存在=%s%n", dry.path("willDo").asText(), skuExists(skuId));
        assertThat(dry.path("willDo").asText()).contains("将删除规格");
        assertThat(skuExists(skuId)).isTrue();

        System.out.printf("⑭ 删规格 缺码 → %s%n", fail(delete("/admin/skus/" + skuId, null)).path("message").asText());
        assertThat(skuExists(skuId)).isTrue();

        ok(delete("/admin/skus/" + skuId, body(dry.path("confirmCode").asText())));
        System.out.printf("⑭ 删规格 带码执行 → SKU 仍存在=%s%n", skuExists(skuId));
        assertThat(skuExists(skuId)).isFalse();
    }

    // ================================================================== 指纹变化（3）

    @Test
    @org.junit.jupiter.api.Order(15)
    @DisplayName("⑮ 指纹变化：audit-pass 预演后改明细数量 → 拒绝")
    void auditPass_planChanged_rejected() {
        Long orderId = newOrder(7, skuA, skuB);
        String code = ok(post(withDry("/admin/orders/" + orderId + "/audit-pass"), null))
                .path("confirmCode").asText();
        jdbc.update("UPDATE order_item SET product_quantity = product_quantity + 3 WHERE order_id = ?", orderId);

        JsonNode resp = fail(post("/admin/orders/" + orderId + "/audit-pass", body(code)));
        System.out.printf("⑮ audit-pass 预演后改明细数量 → %s（订单状态仍=%d）%n",
                resp.path("message").asText(), statusOf(orderId));
        assertThat(resp.path("message").asText()).contains("拆单方案已变化");
        assertThat(statusOf(orderId)).isEqualTo(7);
    }

    @Test
    @org.junit.jupiter.api.Order(16)
    @DisplayName("⑯ 指纹变化：dispatch 预演后该包裹明细被改 → 拒绝")
    void dispatch_packageChanged_rejected() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveries.get(0);
        String code = ok(post(withDry("/admin/deliveries/" + id + "/dispatch"), null))
                .path("confirmCode").asText();

        // 预演之后这个包裹的明细被改动（件数变了）→ 要下发的东西变了，码作废
        int changed = jdbc.update("UPDATE delivery_item SET quantity = quantity + 1 WHERE delivery_id = ?", id);
        assertThat(changed).isGreaterThan(0);

        JsonNode resp = fail(post("/admin/deliveries/" + id + "/dispatch", body(code)));
        System.out.printf("⑯ dispatch 预演后包裹明细被改（%d 条）→ %s（包裹状态仍=%d）%n",
                changed, resp.path("message").asText(), deliveryStatusOf(id));
        assertThat(resp.path("message").asText()).contains("操作内容已变化");
        assertThat(deliveryStatusOf(id)).isZero();
    }

    @Test
    @org.junit.jupiter.api.Order(17)
    @DisplayName("⑰ 指纹变化：delete-product 预演后引用数变了 → 拒绝")
    void productDelete_referenceChanged_rejected() {
        Long productId = newBareProduct("确认码测试商品C");
        Long skuId = newBareSku(productId, "3.00", 3);
        String code = ok(delete(withDry("/admin/products/" + productId), null))
                .path("confirmCode").asText();

        // 预演之后有人把这个 SKU 放进了购物车 → 引用数 0→1
        createdCarts.add(insertReturningId(
                "INSERT INTO cart (member_id, product_id, product_sku_id, quantity) VALUES (?, ?, ?, 1)",
                memberId, productId, skuId));

        JsonNode resp = fail(delete("/admin/products/" + productId, body(code)));
        System.out.printf("⑰ delete-product 预演后引用数变化 → %s（商品仍存在=%s）%n",
                resp.path("message").asText(), productExists(productId));
        assertThat(resp.path("message").asText()).contains("操作内容已变化");
        assertThat(productExists(productId)).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(18)
    @DisplayName("⑱ 校验顺序回归：非法状态/非法参数 + 不带码 → 一律先报「缺少确认码」")
    void orderOfChecks_codeBeforeGuards() {
        List<Long> deliveries = newPendingDeliveries();
        Long pending = deliveries.get(0);
        Long other = deliveries.get(1);

        // 造一个「已下发」的包裹（先合法下发）
        String dispatchCode = ok(post(withDry("/admin/deliveries/" + pending + "/dispatch"), null))
                .path("confirmCode").asText();
        ok(post("/admin/deliveries/" + pending + "/dispatch", body(dispatchCode)));

        // 造一个「已取消」的包裹
        String cancelCode = ok(post(withDry("/admin/deliveries/" + other + "/cancel"), null))
                .path("confirmCode").asText();
        ok(post("/admin/deliveries/" + other + "/cancel", body(cancelCode)));

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("dispatch（包裹已下发）", fail(post("/admin/deliveries/" + pending + "/dispatch", null)).path("message").asText());
        cases.put("cancel（包裹已取消）", fail(post("/admin/deliveries/" + other + "/cancel", null)).path("message").asText());
        cases.put("wms-callback（非法 event）", fail(post("/admin/deliveries/" + pending + "/wms-callback?event=bogus", null)).path("message").asText());
        cases.put("merge（忘传 targetId）", fail(post("/admin/deliveries/" + pending + "/merge", "{}")).path("message").asText());
        cases.put("DELETE（有明细但没 targetId）", fail(delete("/admin/deliveries/" + pending, null)).path("message").asText());
        cases.put("PUT 编辑（包裹已下发）", fail(put("/admin/deliveries/" + pending, "{\"warehouseId\":1}")).path("message").asText());
        cases.put("split（包裹已下发）", fail(post("/admin/deliveries/" + pending + "/split",
                "{\"items\":[{\"deliveryItemId\":" + firstDeliveryItemId(pending) + ",\"quantity\":1}]}")).path("message").asText());

        cases.forEach((endpoint, message) -> {
            System.out.printf("⑱ 顺序回归 %s + 不带码 → %s%n", endpoint, message);
            assertThat(message)
                    .as("%s 必须先报「缺少确认码」，而不是业务守卫错误（否则闸门降级成探针）", endpoint)
                    .startsWith("缺少确认码");
        });
    }

    // ================================================================== 数据构造

    /** 造一张订单（status 可指定），返回 id */
    private Long newOrder(int status, Long... skus) {
        Order order = new Order();
        order.setOrderSn("CG" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999));
        order.setMemberId(memberId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(status);
        order.setPayType(1);
        order.setReceiverName("确认码测试");
        order.setReceiverPhone("13700000000");
        order.setReceiverAddress("自动化测试地址（跑完即删）");
        order.setSplitStatus(0);
        orderMapper.insert(order);
        createdOrders.add(order.getId());

        Long[] productIds = {productIdOfSkuA, productIdOfSkuB};
        for (int i = 0; i < skus.length; i++) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(productIds[Math.min(i, productIds.length - 1)]);
            item.setProductSkuId(skus[i]);
            item.setProductName("确认码测试商品SKU" + skus[i]);
            item.setProductPrice(new BigDecimal("10.00"));
            item.setProductQuantity(1 + i);
            orderItemMapper.insert(item);
        }
        return order.getId();
    }

    /** 造一张订单并审核通过（走真接口拿码），返回其 2 个待下发包裹 id */
    private List<Long> newPendingDeliveries() {
        Long orderId = newOrder(7, skuA, skuB);
        String code = ok(post("/admin/orders/" + orderId + "/split-preview", null))
                .path("confirmCode").asText();
        JsonNode done = ok(post("/admin/orders/" + orderId + "/audit-pass", body(code)));
        List<Long> ids = new ArrayList<>();
        done.forEach(node -> ids.add(node.path("id").asLong()));
        assertThat(ids).as("审核通过后应有 2 个待下发包裹").hasSize(2);
        return ids;
    }

    private Long newReturnApply(Long orderId) {
        return insertReturningId(
                "INSERT INTO return_apply (order_id, member_id, return_reason, description, status, reason_id) "
                        + "VALUES (?, ?, '确认码测试-不想要了', '自动化用例', 0, ?)",
                orderId, memberId, returnReasonId);
    }

    private Long newBareProduct(String name) {
        Long id = insertReturningId(
                "INSERT INTO product (name, description, category_id, type_id, publish_status) VALUES (?, ?, ?, ?, 1)",
                name, "自动化用例", categoryId, typeId);
        createdProducts.add(id);
        return id;
    }

    private Long newBareSku(Long productId, String price, int stock) {
        return insertReturningId(
                "INSERT INTO sku (product_id, price, stock, pic) VALUES (?, ?, ?, NULL)",
                productId, new BigDecimal(price), stock);
    }

    /** INSERT 并取回自增主键（JdbcTemplate#queryForObject 不能跑 INSERT） */
    private Long insertReturningId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("INSERT 应返回自增主键：%s", sql).isNotNull();
        return key.longValue();
    }

    /** 与给定仓不同的另一个仓（种子库有 3 个仓） */
    private Long otherWarehouseThan(Long warehouseId) {
        return jdbc.queryForObject("SELECT id FROM warehouse WHERE id <> ? ORDER BY id LIMIT 1",
                Long.class, warehouseId);
    }

    /** 从一组包裹里挑出「有明细数量 >= minQty」的那个（拆包用，避免把源包拆空） */
    private Long deliveryWithLineQtyAtLeast(List<Long> deliveryIds, int minQty) {
        for (Long id : deliveryIds) {
            Integer max = jdbc.queryForObject("SELECT MAX(quantity) FROM delivery_item WHERE delivery_id = ?",
                    Integer.class, id);
            if (max != null && max >= minQty) return id;
        }
        throw new IllegalStateException("没有可拆的「多件」包裹，测试数据不满足前提");
    }

    // ================================================================== 状态查询

    private int statusOf(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private boolean orderExists(Long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?", Integer.class, orderId);
        return n != null && n > 0;
    }

    private int itemCountOfDeleted(Long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id = ?", Integer.class, orderId);
        return n == null ? -1 : n;
    }

    private int deliveryCountOf(Long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE order_id = ?", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private List<Long> deliveriesOfOrder(Long orderId) {
        return jdbc.queryForList("SELECT id FROM delivery_order WHERE order_id = ? ORDER BY id", Long.class, orderId);
    }

    private Long orderIdOfDelivery(Long deliveryId) {
        return jdbc.queryForObject("SELECT order_id FROM delivery_order WHERE id = ?", Long.class, deliveryId);
    }

    private int deliveryStatusOf(Long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
    }

    private boolean deliveryExists(Long deliveryId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
        return n != null && n > 0;
    }

    private Long firstDeliveryItemId(Long deliveryId) {
        return jdbc.queryForObject("SELECT id FROM delivery_item WHERE delivery_id = ? ORDER BY id LIMIT 1",
                Long.class, deliveryId);
    }

    private Long warehouseOf(Long deliveryId) {
        return jdbc.queryForObject("SELECT warehouse_id FROM delivery_order WHERE id = ?", Long.class, deliveryId);
    }

    /** 发货单当前乐观锁版本号（PUT /admin/deliveries/{id} 执行时必须原样带回） */
    private int versionOfDelivery(Long deliveryId) {
        return jdbc.queryForObject("SELECT version FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
    }

    private int stockOf(Long skuId) {
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }

    private boolean productExists(Long productId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id = ?", Integer.class, productId);
        return n != null && n > 0;
    }

    private boolean skuExists(Long skuId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id = ?", Integer.class, skuId);
        return n != null && n > 0;
    }

    // ================================================================== HTTP 辅助

    private static String withDry(String path) {
        return path + (path.contains("?") ? "&" : "?") + "dryRun=true";
    }

    private static String body(String confirmCode) {
        return "{\"confirmCode\":\"" + confirmCode + "\"}";
    }

    /** code==0 断言，返回 data 节点 */
    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    /** code==1 断言，返回整个 body（含 message） */
    private JsonNode fail(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望被拒，实际：%s", node).isEqualTo(1);
        assertThat(node.path("message").asText()).as("被拒时必须有中文原因").isNotBlank();
        return node;
    }

    private ResponseEntity<JsonNode> post(String path, String json) {
        return exchange(HttpMethod.POST, path, json);
    }

    private ResponseEntity<JsonNode> put(String path, String json) {
        return exchange(HttpMethod.PUT, path, json);
    }

    private ResponseEntity<JsonNode> delete(String path, String json) {
        return exchange(HttpMethod.DELETE, path, json);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }
}
