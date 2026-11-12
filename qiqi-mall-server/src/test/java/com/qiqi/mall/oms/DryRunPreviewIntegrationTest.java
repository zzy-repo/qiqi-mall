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

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预演（{@code ?dryRun=true}）集成测试：真起后端 + 真打 HTTP + 真连库。
 *
 * <p>确认码（confirmCode）整套机制已删除，{@link com.qiqi.mall.shared.common.PreviewResult}
 * 只剩「将要发生什么」这一件事。本测试要证明的正是：
 * <pre>
 * ① 14 个 L3 端点 {@code ?dryRun=true} → {@code code=0} + {@code willDo} 非空中文，
 *    且**库里数据零变化**、**不再签发任何凭证**（响应里既没有 confirmCode 也没有 expiresInSec）
 * ② 真调（不带 dryRun）→ 成功干成事：有 version 的端点带 version，其余端点不带 version 也照样成功
 * </pre>
 *
 * <p>另有 5 条「校验顺序回归」（见文件末尾）：用「违规状态 + 缺 version」打
 * {@code PUT /admin/deliveries/{id}}，断言报的是 **400 缺版本号**，而不是「只有待下发包裹可以编辑」——
 * 业务守卫绝不许抢到 version 校验前面，否则「数据被别人改了」会被误报成「状态不对」，用户查错方向。
 *
 * <p>并发控制现状（本测试顺带固化下来）：
 * <ul>
 *   <li>14 个端点里**只有** {@code PUT /admin/deliveries/{id}} 收客户端 {@code version}（delivery_order 表有 version 列）</li>
 *   <li>其余 13 个端点靠各自的状态守卫（重放必然被状态拦住），它们不传 version 也必须成功</li>
 *   <li>5 张热表（order / delivery_order / product / sku / member）的乐观锁本身由
 *       {@code OptimisticLockIntegrationTest} 覆盖，这里不重复</li>
 * </ul>
 *
 * <p>数据自建：订单/明细/商品/SKU/退货申请全部自己造，跑完全删；
 * 会改 sku.stock 的用例（audit-reject / cancel / return-audit 回补库存）在 @BeforeAll 记下原值、@AfterAll 还原。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=DryRunPreviewIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DryRunPreviewIntegrationTest {

    static final String MSG_MISSING_VERSION = "缺少版本号，请刷新页面后重试";
    static final String MSG_CONFLICT = "数据已被他人修改（可能其他人在编辑），请刷新后重试";

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
        for (Long productId : createdProducts) {
            jdbc.update("DELETE FROM product WHERE id = ?", productId);  // 级联下辖 SKU
        }
        originalStock.forEach((skuId, stock) -> jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", stock, skuId));
        System.out.printf("[清理] 自建订单 %d 张、商品 %d 个已删除，SKU 库存已还原%n",
                createdOrders.size(), createdProducts.size());
    }

    // ================================================================== 订单域（4）

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("① audit-pass：dryRun 只回 willDo（零变化、无凭证）→ 真调成功")
    void auditPass_previewThenExecute() {
        Long orderId = newOrder(7, skuA, skuB);

        Map<String, Object> before = dbSnapshot();
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/audit-pass"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("审核通过").contains("待发货").contains("发货单");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(before);
        assertThat(statusOf(orderId)).as("预演不能改订单状态").isEqualTo(7);
        assertThat(deliveriesOfOrder(orderId)).as("预演不能生成发货单").isEmpty();
        System.out.printf("① audit-pass 预演 → willDo=%s%n", dry.path("willDo").asText());

        // ② 真调（该端点没有 version，靠 status=7 守卫）
        JsonNode done = ok(post("/admin/orders/" + orderId + "/audit-pass", null));
        System.out.printf("① audit-pass 真调 → 订单状态=%d，生成发货单=%d 个%n",
                statusOf(orderId), deliveriesOfOrder(orderId).size());
        assertThat(statusOf(orderId)).isEqualTo(8);
        assertThat(done.size()).as("按方案生成了 2 个发货单").isEqualTo(2);

        // 拆单方案预览接口同样只剩 willDo + plan
        Long another = newOrder(7, skuA, skuB);
        JsonNode plan = ok(post("/admin/orders/" + another + "/split-preview", null));
        assertOnlyWillDo(plan);
        assertThat(plan.path("plan").isArray()).as("split-preview 仍带结构化方案").isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("② audit-reject：dryRun 只回 willDo（零变化）→ 真调关单并回补库存")
    void auditReject_previewThenExecute() {
        Long orderId = newOrder(7, skuA, skuB);
        int stockBefore = stockOf(skuA);

        Map<String, Object> before = dbSnapshot();
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/audit-reject"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("审核不通过").contains("回补").contains("已关闭");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(before);
        System.out.printf("② audit-reject 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(post("/admin/orders/" + orderId + "/audit-reject", null));
        System.out.printf("② audit-reject 真调 → 订单状态=%d，SKU%d 库存 %d→%d%n",
                statusOf(orderId), skuA, stockBefore, stockOf(skuA));
        assertThat(statusOf(orderId)).isEqualTo(4);
        assertThat(stockOf(skuA)).isGreaterThan(stockBefore);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("③ ship：dryRun 只回 willDo（零变化）→ 真调置已发货")
    void ship_previewThenExecute() {
        Long orderId = newOrder(1, skuA, skuB);          // 已付款

        Map<String, Object> before = dbSnapshot();
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/ship"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("已发货").contains("直发路径");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(before);
        System.out.printf("③ ship 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(post("/admin/orders/" + orderId + "/ship", null));
        System.out.printf("③ ship 真调 → 订单状态=%d%n", statusOf(orderId));
        assertThat(statusOf(orderId)).isEqualTo(2);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("④ DELETE 订单：dryRun 只回 willDo（零变化）→ 真调级联删除")
    void orderDelete_previewThenExecute() {
        Long orderId = newOrder(1, skuA, skuB);

        Map<String, Object> before = dbSnapshot();
        JsonNode dry = ok(delete(withDry("/admin/orders/" + orderId), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("将删除订单").contains("级联删除").contains("不可恢复");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(before);
        System.out.printf("④ DELETE 订单 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(delete("/admin/orders/" + orderId, null));
        createdOrders.remove(orderId);
        System.out.printf("④ DELETE 订单 真调 → 订单还在=%s，明细剩=%d 条%n",
                orderExists(orderId), itemCountOf(orderId));
        assertThat(orderExists(orderId)).isFalse();
        assertThat(itemCountOf(orderId)).isZero();
    }

    // ================================================================== 履约域（7）

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("⑤ dispatch：dryRun 只回 willDo（零变化）→ 真调下发")
    void dispatch_previewThenExecute() {
        Long id = newPendingDeliveries().get(0);

        Map<String, Object> before = dbSnapshot();
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/dispatch"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("下发").contains("待下发→已下发");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(before);
        assertThat(deliveryStatusOf(id)).isZero();
        System.out.printf("⑤ dispatch 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(post("/admin/deliveries/" + id + "/dispatch", null));
        System.out.printf("⑤ dispatch 真调 → 包裹状态=%d%n", deliveryStatusOf(id));
        assertThat(deliveryStatusOf(id)).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("⑥ PUT 编辑包裹（唯一带 version 的端点）：dryRun 零变化 → 带 version 真调成功")
    void deliveryEdit_previewThenExecute() {
        Long id = newPendingDeliveries().get(0);
        Long targetWarehouse = otherWarehouseThan(warehouseOf(id));
        String payload = "{\"warehouseId\":" + targetWarehouse + "}";

        Map<String, Object> before = dbSnapshot();
        JsonNode dry = ok(put(withDry("/admin/deliveries/" + id), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("发货仓改为").contains("人工调整");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(before);
        assertThat(warehouseOf(id)).isNotEqualTo(targetWarehouse);
        System.out.printf("⑥ 编辑包裹 预演（不带 version 也能预演）→ willDo=%s%n", dry.path("willDo").asText());

        // ② 真调必须带 version（乐观锁 fail-closed）
        int version = versionOfDelivery(id);
        ok(put("/admin/deliveries/" + id, "{\"warehouseId\":" + targetWarehouse + ",\"version\":" + version + "}"));
        System.out.printf("⑥ 编辑包裹 真调（version=%d）→ 仓库=%d，version→%d%n",
                version, warehouseOf(id), versionOfDelivery(id));
        assertThat(warehouseOf(id)).isEqualTo(targetWarehouse);
        assertThat(versionOfDelivery(id)).isEqualTo(version + 1);
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("⑦ split：dryRun 只回 willDo（零变化）→ 真调拆成多一个包裹")
    void split_previewThenExecute() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveryWithLineQtyAtLeast(deliveries, 2);
        Long orderId = orderIdOfDelivery(id);
        int before = deliveriesOfOrder(orderId).size();
        Long itemId = firstDeliveryItemId(id);
        String payload = "{\"items\":[{\"deliveryItemId\":" + itemId + ",\"quantity\":1}]}";

        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/split"), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("拆成 2 个包裹").contains("新包");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(deliveriesOfOrder(orderId)).hasSize(before);
        System.out.printf("⑦ split 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(post("/admin/deliveries/" + id + "/split", payload));
        System.out.printf("⑦ split 真调 → 包裹数 %d→%d%n", before, deliveriesOfOrder(orderId).size());
        assertThat(deliveriesOfOrder(orderId)).hasSize(before + 1);
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("⑧ merge：dryRun 只回 willDo（零变化）→ 真调源包并入目标包")
    void merge_previewThenExecute() {
        List<Long> deliveries = newPendingDeliveries();
        Long a = deliveries.get(0);
        Long b = deliveries.get(1);
        String payload = "{\"targetId\":" + b + "}";

        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + a + "/merge"), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("并入").contains("源包消失");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(deliveryExists(a) && deliveryExists(b)).isTrue();
        System.out.printf("⑧ merge 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(post("/admin/deliveries/" + a + "/merge", payload));
        System.out.printf("⑧ merge 真调 → 源包还在=%s，目标包还在=%s%n", deliveryExists(a), deliveryExists(b));
        assertThat(deliveryExists(a)).isFalse();
        assertThat(deliveryExists(b)).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("⑨ DELETE 包裹（有明细 → 并入 targetId）：dryRun 零变化 → 真调成功")
    void deliveryDelete_previewThenExecute() {
        List<Long> deliveries = newPendingDeliveries();
        Long a = deliveries.get(0);
        Long b = deliveries.get(1);
        String path = "/admin/deliveries/" + a + "?targetId=" + b;

        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(delete(withDry(path), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("删除等同于并入");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(deliveryExists(a)).isTrue();
        System.out.printf("⑨ DELETE 包裹 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(delete(path, null));
        System.out.printf("⑨ DELETE 包裹 真调 → 源包还在=%s%n", deliveryExists(a));
        assertThat(deliveryExists(a)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("⑩ cancel：dryRun 只回 willDo（零变化）→ 真调取消并回补库存")
    void cancel_previewThenExecute() {
        Long id = newPendingDeliveries().get(0);
        int stockBefore = stockOf(skuA);

        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/cancel"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("已取消").contains("回补");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(deliveryStatusOf(id)).isZero();
        System.out.printf("⑩ cancel 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(post("/admin/deliveries/" + id + "/cancel", null));
        System.out.printf("⑩ cancel 真调 → 包裹状态=%d，SKU%d 库存 %d→%d%n",
                deliveryStatusOf(id), skuA, stockBefore, stockOf(skuA));
        assertThat(deliveryStatusOf(id)).isEqualTo(9);
        assertThat(stockOf(skuA)).isGreaterThan(stockBefore);
    }

    @Test
    @org.junit.jupiter.api.Order(11)
    @DisplayName("⑪ wms-callback：dryRun 只回 willDo（零变化）→ 真调回传拣货")
    void wmsCallback_previewThenExecute() {
        Long id = newPendingDeliveries().get(0);
        ok(post("/admin/deliveries/" + id + "/dispatch", null));      // 先合法下发（守卫要求已下发）

        String path = "/admin/deliveries/" + id + "/wms-callback?event=pick";
        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(post(withDry(path), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("拣货").contains("已下发→已拣货");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(deliveryStatusOf(id)).isEqualTo(1);
        System.out.printf("⑪ wms-callback 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(post(path, null));
        System.out.printf("⑪ wms-callback 真调 → 包裹状态=%d%n", deliveryStatusOf(id));
        assertThat(deliveryStatusOf(id)).isEqualTo(2);
    }

    // ================================================================== 退货（1）

    @Test
    @org.junit.jupiter.api.Order(12)
    @DisplayName("⑫ PUT 退货审核：dryRun 只回 willDo（零变化）→ 真调退款 + 回补库存")
    void returnAudit_previewThenExecute() {
        Long orderId = newOrder(5, skuA, skuB);          // 退款中
        Long applyId = newReturnApply(orderId);
        String payload = "{\"status\":1}";
        int stockBefore = stockOf(skuA);

        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(put(withDry("/admin/return-applies/" + applyId), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("通过").contains("退款").contains("回补");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(returnApplyStatusOf(applyId)).isZero();
        assertThat(statusOf(orderId)).isEqualTo(5);
        System.out.printf("⑫ 退货审核 预演 → willDo=%s%n", dry.path("willDo").asText());

        int paymentsBefore = paymentRecordCount();
        ok(put("/admin/return-applies/" + applyId, payload));
        System.out.printf("⑫ 退货审核 真调 → 申请状态=%d，订单状态=%d，退款流水 +%d，SKU%d 库存 %d→%d%n",
                returnApplyStatusOf(applyId), statusOf(orderId), paymentRecordCount() - paymentsBefore,
                skuA, stockBefore, stockOf(skuA));
        assertThat(returnApplyStatusOf(applyId)).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(6);
        assertThat(paymentRecordCount()).isEqualTo(paymentsBefore + 1);
        assertThat(stockOf(skuA)).isGreaterThan(stockBefore);
    }

    // ================================================================== 商品（2）

    @Test
    @org.junit.jupiter.api.Order(13)
    @DisplayName("⑬ DELETE 商品：dryRun 只回 willDo（零变化）→ 真调删除")
    void productDelete_previewThenExecute() {
        Long productId = newBareProduct("预演测试商品A");
        newBareSku(productId, "1.00", 10);
        newBareSku(productId, "2.00", 5);

        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(delete(withDry("/admin/products/" + productId), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("下辖 2 个 SKU").contains("删除后不可恢复");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(productExists(productId)).isTrue();
        System.out.printf("⑬ DELETE 商品 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(delete("/admin/products/" + productId, null));
        createdProducts.remove(productId);
        System.out.printf("⑬ DELETE 商品 真调 → 商品还在=%s%n", productExists(productId));
        assertThat(productExists(productId)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Order(14)
    @DisplayName("⑭ DELETE 规格：dryRun 只回 willDo（零变化）→ 真调删除")
    void skuDelete_previewThenExecute() {
        Long productId = newBareProduct("预演测试商品B");
        Long skuId = newBareSku(productId, "9.90", 8);

        Map<String, Object> snapshot = dbSnapshot();
        JsonNode dry = ok(delete(withDry("/admin/skus/" + skuId), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("将删除规格").contains("删除后不可恢复");
        assertThat(dbSnapshot()).as("预演必须零落库").isEqualTo(snapshot);
        assertThat(skuExists(skuId)).isTrue();
        System.out.printf("⑭ DELETE 规格 预演 → willDo=%s%n", dry.path("willDo").asText());

        ok(delete("/admin/skus/" + skuId, null));
        System.out.printf("⑭ DELETE 规格 真调 → SKU 还在=%s%n", skuExists(skuId));
        assertThat(skuExists(skuId)).isFalse();
    }

    // ==================================================================
    // 校验顺序回归：version（400/409）必须抢在业务守卫前面
    // ==================================================================

    @Test
    @org.junit.jupiter.api.Order(15)
    @DisplayName("⑮ 顺序回归①：已下发包裹 + 缺 version → 400 缺版本号（不是「只有待下发…」）")
    void orderRegression_missingVersionBeatsStateGuard() {
        Long id = newDispatchedDelivery();

        JsonNode resp = fail(put("/admin/deliveries/" + id, "{\"warehouseId\":1}"), 400);
        System.out.printf("⑮ 已下发 + 缺 version → code=400 message=%s%n", resp.path("message").asText());
        assertThat(resp.path("message").asText()).as("必须报缺版本号，不能报状态守卫").isEqualTo(MSG_MISSING_VERSION);
        assertThat(resp.path("message").asText()).doesNotContain("只有待下发");
        assertThat(deliveryStatusOf(id)).as("被拒的请求不改数据").isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(16)
    @DisplayName("⑯ 顺序回归②：已下发包裹 + 错 version → 409 版本冲突（不是「只有待下发…」）")
    void orderRegression_conflictBeatsStateGuard() {
        Long id = newDispatchedDelivery();

        JsonNode resp = fail(put("/admin/deliveries/" + id, "{\"warehouseId\":1,\"version\":" + (versionOfDelivery(id) + 99) + "}"), 409);
        System.out.printf("⑯ 已下发 + 错 version → code=409 message=%s%n", resp.path("message").asText());
        assertThat(resp.path("message").asText()).as("必须报版本冲突，不能报状态守卫").isEqualTo(MSG_CONFLICT);
        assertThat(resp.path("message").asText()).doesNotContain("只有待下发");
        assertThat(deliveryStatusOf(id)).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(17)
    @DisplayName("⑰ 顺序回归③：已下发包裹 + 正确 version → code=1 状态守卫（守卫仍在，且只报状态）")
    void orderRegression_stateGuardStillFires() {
        Long id = newDispatchedDelivery();

        JsonNode resp = fail(put("/admin/deliveries/" + id, "{\"warehouseId\":1,\"version\":" + versionOfDelivery(id) + "}"), 1);
        System.out.printf("⑰ 已下发 + 正确 version → code=1 message=%s%n", resp.path("message").asText());
        assertThat(resp.path("message").asText()).as("版本对得上才轮到状态守卫，且是 code=1").startsWith("只有待下发包裹可以编辑");
        assertThat(deliveryStatusOf(id)).isEqualTo(1);
        assertThat(warehouseOf(id)).as("被守卫拦下的请求不改数据").isNotNull();
    }

    @Test
    @org.junit.jupiter.api.Order(18)
    @DisplayName("⑱ 顺序回归④：发货单不存在 + 缺 version → code=1 存在性先于 version（①②③ 次序）")
    void orderRegression_existenceBeatsVersion() {
        Long ghost = 999999999L;

        JsonNode resp = fail(put("/admin/deliveries/" + ghost, "{\"warehouseId\":1}"), 1);
        System.out.printf("⑱ 不存在 + 缺 version → code=1 message=%s%n", resp.path("message").asText());
        assertThat(resp.path("message").asText()).as("存在性排在 version 之前（① 参数/存在性 → ② version）")
                .startsWith("发货单不存在");
    }

    @Test
    @org.junit.jupiter.api.Order(19)
    @DisplayName("⑲ 顺序回归⑤：没有 version 的端点遇到违规状态 → code=1 业务守卫（不被压成 400/409）")
    void orderRegression_noVersionEndpointReportsStateGuard() {
        Long id = newDispatchedDelivery();

        JsonNode dispatch = fail(post("/admin/deliveries/" + id + "/dispatch", null), 1);
        JsonNode split = fail(post("/admin/deliveries/" + id + "/split",
                "{\"items\":[{\"deliveryItemId\":" + firstDeliveryItemId(id) + ",\"quantity\":1}]}"), 1);
        Long closedOrder = newOrder(4, skuA);          // 已关闭
        JsonNode auditPass = fail(post("/admin/orders/" + closedOrder + "/audit-pass", null), 1);

        System.out.printf("⑲ 无 version 端点 + 违规状态 → dispatch=%s | split=%s | audit-pass=%s%n",
                dispatch.path("message").asText(), split.path("message").asText(), auditPass.path("message").asText());
        assertThat(dispatch.path("message").asText()).isEqualTo("只有待下发发货单可以下发");
        assertThat(split.path("message").asText()).startsWith("只有待下发包裹可以拆分");
        assertThat(auditPass.path("message").asText()).isEqualTo("只有待审核订单可以审核通过");
    }

    // ================================================================== 数据构造

    /** 造一张订单（status 可指定），返回 id */
    private Long newOrder(int status, Long... skus) {
        Order order = new Order();
        order.setOrderSn("DR" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999));
        order.setMemberId(memberId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(status);
        order.setPayType(1);
        order.setReceiverName("预演测试");
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
            item.setProductName("预演测试商品SKU" + skus[i]);
            item.setProductPrice(new BigDecimal("10.00"));
            item.setProductQuantity(1 + i);
            orderItemMapper.insert(item);
        }
        return order.getId();
    }

    /** 造一张订单并真审核通过，返回其 2 个待下发包裹 id */
    private List<Long> newPendingDeliveries() {
        Long orderId = newOrder(7, skuA, skuB);
        JsonNode done = ok(post("/admin/orders/" + orderId + "/audit-pass", null));
        List<Long> ids = new ArrayList<>();
        done.forEach(node -> ids.add(node.path("id").asLong()));
        assertThat(ids).as("审核通过后应有 2 个待下发包裹").hasSize(2);
        return ids;
    }

    /** 造一张「已下发」的包裹（状态守卫会被触发的反面样本） */
    private Long newDispatchedDelivery() {
        Long id = newPendingDeliveries().get(0);
        ok(post("/admin/deliveries/" + id + "/dispatch", null));
        assertThat(deliveryStatusOf(id)).isEqualTo(1);
        return id;
    }

    private Long newReturnApply(Long orderId) {
        return insertReturningId(
                "INSERT INTO return_apply (order_id, member_id, return_reason, description, status, reason_id) "
                        + "VALUES (?, ?, '预演测试-不想要了', '自动化用例', 0, ?)",
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

    /** 从一组包裹里挑出「有条明细数量 >= minQty」的那个（拆包用，避免把源包拆空） */
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

    private int itemCountOf(Long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id = ?", Integer.class, orderId);
        return n == null ? -1 : n;
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

    private int returnApplyStatusOf(Long applyId) {
        return jdbc.queryForObject("SELECT status FROM return_apply WHERE id = ?", Integer.class, applyId);
    }

    private int paymentRecordCount() {
        return scalar("SELECT COUNT(*) FROM payment_record").intValue();
    }

    private boolean productExists(Long productId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id = ?", Integer.class, productId);
        return n != null && n > 0;
    }

    private boolean skuExists(Long skuId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id = ?", Integer.class, skuId);
        return n != null && n > 0;
    }

    /**
     * 「库里数据零变化」的指纹：关键表的行数 + 状态和 + 版本和 + 库存和。
     *
     * <p>为什么这么拼：预演要证明的是「什么都没写」。行数能抓到插入/删除，
     * status 和能抓到状态流转，version 和能抓到隐式更新（乐观锁每次更新 +1），
     * stock 和能抓到库存增减 —— 四类覆盖了 14 个端点全部可能的副作用。
     */
    private Map<String, Object> dbSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order行数", scalar("SELECT COUNT(*) FROM `order`"));
        m.put("order状态和", scalar("SELECT COALESCE(SUM(status),0) FROM `order`"));
        m.put("order版本和", scalar("SELECT COALESCE(SUM(version),0) FROM `order`"));
        m.put("orderItem行数", scalar("SELECT COUNT(*) FROM order_item"));
        m.put("delivery行数", scalar("SELECT COUNT(*) FROM delivery_order"));
        m.put("delivery状态和", scalar("SELECT COALESCE(SUM(status),0) FROM delivery_order"));
        m.put("delivery版本和", scalar("SELECT COALESCE(SUM(version),0) FROM delivery_order"));
        m.put("deliveryItem行数", scalar("SELECT COUNT(*) FROM delivery_item"));
        m.put("sku库存和", scalar("SELECT COALESCE(SUM(stock),0) FROM sku"));
        m.put("product行数", scalar("SELECT COUNT(*) FROM product"));
        m.put("returnApply状态和", scalar("SELECT COALESCE(SUM(status),0) FROM return_apply"));
        m.put("paymentRecord行数", scalar("SELECT COUNT(*) FROM payment_record"));
        return m;
    }

    private Long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    // ================================================================== HTTP 辅助

    private static String withDry(String path) {
        return path + (path.contains("?") ? "&" : "?") + "dryRun=true";
    }

    /** 预演响应体断言：只有 willDo，且是中文；**不再有任何凭证** */
    private void assertOnlyWillDo(JsonNode data) {
        String willDo = data.path("willDo").asText();
        assertThat(willDo).as("预演必须给出具体的中文描述：%s", data).isNotBlank();
        assertThat(willDo).as("willDo 必须是中文：%s", willDo).containsPattern("[\\u4e00-\\u9fa5]");
        assertThat(data.has("confirmCode")).as("确认码机制已删除，预演不能再签发凭证：%s", data).isFalse();
        assertThat(data.has("expiresInSec")).as("确认码机制已删除，预演不能再有有效期：%s", data).isFalse();
    }

    /** code==0 断言，返回 data 节点 */
    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    /** 指定业务码的失败断言，返回整个 body（含 message） */
    private JsonNode fail(ResponseEntity<JsonNode> resp, int expectedCode) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望 code=%d，实际：%s", expectedCode, node).isEqualTo(expectedCode);
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
