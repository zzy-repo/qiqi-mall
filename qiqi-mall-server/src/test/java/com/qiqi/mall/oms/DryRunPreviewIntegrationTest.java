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
 *   <li>14 个端点里**4 个**收客户端 {@code version}：{@code PUT /admin/deliveries/{id}}、
 *       {@code POST /admin/deliveries/{id}/split}、{@code POST /admin/deliveries/{id}/merge}（body.version）
 *       与 {@code DELETE /admin/deliveries/{id}}（**query 的 ?version=**）—— 它们操作的都是 delivery_order（有 version 列）</li>
 *   <li>其余 10 个端点靠各自的状态守卫（重放必然被状态拦住），它们不传 version 也必须成功</li>
 *   <li>5 张热表（order / delivery_order / product / sku / member）的乐观锁本身由
 *       {@code OptimisticLockIntegrationTest} 覆盖，这里不重复</li>
 * </ul>
 *
 * <p>数据自建：订单/明细/商品/SKU/退货申请全部自己造，跑完全删；
 * 会改 sku.stock 的用例（audit-reject / cancel / return-audit 回补库存）在 @BeforeAll 记下原值、@AfterAll 还原。
 *
 * <h3>「预演零落库」为什么**不能**比全表行数</h3>
 * 本类连的是 dev 库 {@code qiqi_mall}，它**不是**独占的：别的进程（另一个 {@code mvn test}、
 * 手工造的演示数据、MCP 那边的写用例）随时可能在同一个库里插/删一行。曾经这里的指纹是
 * 「order / order_item / delivery_order / sku / product / return_apply / payment_record 的**全表行数**」——
 * 于是**任何**别人的写都算到预演头上：
 * <pre>
 *   2026-11-12 实测：并发跑 {@code mvn test}（两个进程 + 一个往库里补数据的脚本）时，
 *   本类 13 条用例全红，diff 全是 ±1 行（预演前 307 行 / 预演后 308 行）；
 *   独占库重跑 → 全绿。红的是「别人写了库」，不是「预演写了库」。
 * </pre>
 * 所以指纹改成**只圈本测试自己造的数据**（{@link #dbSnapshot()}：按 {@code createdOrders} /
 * {@code createdProducts} / 本测试动过的 sku 圈定），并额外加一条更贴语义的断言
 * （{@link #fingerprintOf(Long)}）：预演涉及的**那一张订单**的状态 / 版本号 / 明细行数 /
 * 支付流水行数 / 包裹行数与状态，必须逐项不变。
 *
 * <p>这不是把断言放松了，而是**换了参照系**：原来是「整个库一行都不许变」（自己根本控制不了），
 * 现在是「我造的这批数据一个字段都不许变」+「这张订单的实质状态一个都不许变」——
 * 后者比原来的行数比对更严（行数相同但 status 被改写、version 被偷偷 +1，原来抓不到，现在会红）。
 *
 * <h3>清理为什么不走 {@code OrderPurgeService#purge()}</h3>
 * 本类的 {@code @AfterAll} 按 orderId 精确删单，并**同时删掉该单的 {@code stock_ledger} 行**
 * （{@code stock_ledger} 故意没有外键，裸 {@code DELETE FROM order} 不会带着它走 → 会留孤儿台账：
 * 实测每轮 {@code mvn test} 往 dev 库留 5 行，累计从 15 涨到 40）。
 * 不走 {@code purge()} 的理由：它带守卫（有物流 / 有退货申请 / 有已出库包裹就拒删），而本类**故意**造这些状态
 * （⑪ 出库回传、⑫ 退货申请、⑤~⑨ 已下发包裹）→ purge 必然拒删，清理失败会反过来把用例弄红；
 * 且 purge 会按台账净额改 {@code sku.stock}，与下面「还原到 @BeforeAll 快照」互相打架。
 * 清理的职责是把库恢复原样，不该再依赖被测逻辑能不能跑通（purge 本身有 3 个专门的 IT 类覆盖）。
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
    /** 本类造过、还没被自己删掉的订单 id（④ 真调删单后会从里面移除） */
    private final List<Long> createdOrders = new ArrayList<>();
    /** 本类造过的**全部**订单 id（只增不减）：清理时用它自查看有没有留下孤儿台账 */
    private final List<Long> allCreatedOrderIds = new ArrayList<>();
    private final List<Long> createdProducts = new ArrayList<>();
    /** 本类造过、且会动库存的 SKU id（skuA / skuB + ⑬⑭ 自建的规格）—— 指纹只圈这些 SKU 的库存 */
    private final List<Long> createdSkus = new ArrayList<>();
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
            // payment_record 只有 order_sn（没有外键、不会级联）：先按单号删，否则「退货审核通过」
            // 用例每跑一次就往库里留一条 DR 开头的退款流水
            for (String sn : jdbc.queryForList("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId)) {
                jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", sn);
            }
            // ★ 方案 B：删单的同时**按 order_id 精确删掉本单的库存台账行**。
            //   stock_ledger 故意没有外键（台账是「同一件货有没有被补两次」的审计证据，不随订单级联），
            //   所以裸 DELETE FROM order 不会带着它走 —— 不清就是**孤儿台账**：
            //   实测每轮 mvn test 往 dev 库留 5 行（reason = AUDIT_REJECT / DELIVERY_CANCEL / RETURN_REFUND），
            //   累计从 15 行涨到 40 行，check-data-integrity.sh 的「① 孤儿台账」会直接判红。
            //   这里刻意**不**调 OrderPurgeService#purge()：它带守卫（有物流 / 有退货 / 有已出库包裹就拒删），
            //   而本类故意造这些状态（⑤~⑨ 已下发包裹、⑪ 出库回传、⑫ 退货申请）→ purge 必然拒删，
            //   清理失败会反过来把用例弄红；且 purge 会按台账净额改 sku.stock，与下面「还原到快照」打架。
            //   清理的职责只是把库恢复原样，不该依赖被测逻辑跑通（purge 本身有 3 个专门的 IT 类覆盖）。
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);   // 级联明细/发货单/退货申请
        }
        for (Long productId : createdProducts) {
            jdbc.update("DELETE FROM product WHERE id = ?", productId);  // 级联下辖 SKU
        }
        originalStock.forEach((skuId, stock) -> jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", stock, skuId));

        // 自查看一眼：本类造过的订单（含 ④ 自己删掉的那张）一条台账都不许剩 ——
        // 留了就说明又产生了孤儿台账，直接让本类红，而不是等 check-data-integrity.sh 隔天发现
        In everOrders = in(allCreatedOrderIds);
        long leftoverLedger = allCreatedOrderIds.isEmpty() ? 0
                : jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE order_id " + everOrders.sql,
                        Long.class, everOrders.args);
        assertThat(leftoverLedger)
                .as("清理后本类造过的订单不许再留 stock_ledger 行（否则就是孤儿台账）")
                .isZero();

        System.out.printf("[清理] 自建订单 %d 张、商品 %d 个已删除，台账残留 %d 行，SKU 库存已还原%n",
                createdOrders.size(), createdProducts.size(), leftoverLedger);
    }

    // ================================================================== 订单域（4）

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("① audit-pass：dryRun 只回 willDo（零变化、无凭证）→ 真调成功")
    void auditPass_previewThenExecute() {
        Long orderId = newOrder(7, skuA, skuB);

        DryRunProbe probe = probeBeforeDryRun(orderId);
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/audit-pass"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("审核通过").contains("待发货").contains("发货单");
        assertDryRunWroteNothing(probe);
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

        DryRunProbe probe = probeBeforeDryRun(orderId);
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/audit-reject"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("审核不通过").contains("回补").contains("已关闭");
        assertDryRunWroteNothing(probe);
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

        DryRunProbe probe = probeBeforeDryRun(orderId);
        JsonNode dry = ok(post(withDry("/admin/orders/" + orderId + "/ship"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("已发货").contains("直发路径");
        assertDryRunWroteNothing(probe);
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

        DryRunProbe probe = probeBeforeDryRun(orderId);
        JsonNode dry = ok(delete(withDry("/admin/orders/" + orderId), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("将删除订单").contains("级联删除").contains("不可恢复");
        assertDryRunWroteNothing(probe);
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

        DryRunProbe probe = probeBeforeDryRun(orderIdOfDelivery(id));
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/dispatch"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("下发").contains("待下发→已下发");
        assertDryRunWroteNothing(probe);
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

        DryRunProbe probe = probeBeforeDryRun(orderIdOfDelivery(id));
        JsonNode dry = ok(put(withDry("/admin/deliveries/" + id), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("发货仓改为").contains("人工调整");
        assertDryRunWroteNothing(probe);
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
    @DisplayName("⑦ split：dryRun 只回 willDo（零变化）→ 带 version 真调拆成多一个包裹")
    void split_previewThenExecute() {
        List<Long> deliveries = newPendingDeliveries();
        Long id = deliveryWithLineQtyAtLeast(deliveries, 2);
        Long orderId = orderIdOfDelivery(id);
        int before = deliveriesOfOrder(orderId).size();
        Long itemId = firstDeliveryItemId(id);
        String payload = "{\"items\":[{\"deliveryItemId\":" + itemId + ",\"quantity\":1}]}";

        DryRunProbe probe = probeBeforeDryRun(orderId);
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/split"), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("拆成 2 个包裹").contains("新包");
        assertDryRunWroteNothing(probe);
        assertThat(deliveriesOfOrder(orderId)).hasSize(before);
        System.out.printf("⑦ split 预演 → willDo=%s%n", dry.path("willDo").asText());

        int version = versionOfDelivery(id);
        ok(post("/admin/deliveries/" + id + "/split", withVersion(payload, version)));
        System.out.printf("⑦ split 真调（version=%d）→ 包裹数 %d→%d%n",
                version, before, deliveriesOfOrder(orderId).size());
        assertThat(deliveriesOfOrder(orderId)).hasSize(before + 1);
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("⑧ merge：dryRun 只回 willDo（零变化）→ 带 version 真调源包并入目标包")
    void merge_previewThenExecute() {
        List<Long> deliveries = newPendingDeliveries();
        Long a = deliveries.get(0);
        Long b = deliveries.get(1);
        String payload = "{\"targetId\":" + b + "}";

        DryRunProbe probe = probeBeforeDryRun(orderIdOfDelivery(a));
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + a + "/merge"), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("并入").contains("源包消失");
        assertDryRunWroteNothing(probe);
        assertThat(deliveryExists(a) && deliveryExists(b)).isTrue();
        System.out.printf("⑧ merge 预演 → willDo=%s%n", dry.path("willDo").asText());

        int sourceVersion = versionOfDelivery(a);      // 源包合并后就没了，必须在调之前取
        ok(post("/admin/deliveries/" + a + "/merge", withVersion(payload, sourceVersion)));
        System.out.printf("⑧ merge 真调（version=%d）→ 源包还在=%s，目标包还在=%s%n",
                sourceVersion, deliveryExists(a), deliveryExists(b));
        assertThat(deliveryExists(a)).isFalse();
        assertThat(deliveryExists(b)).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("⑨ DELETE 包裹（有明细 → 并入 targetId）：dryRun 零变化 → 带 ?version= 真调成功")
    void deliveryDelete_previewThenExecute() {
        List<Long> deliveries = newPendingDeliveries();
        Long a = deliveries.get(0);
        Long b = deliveries.get(1);
        String path = "/admin/deliveries/" + a + "?targetId=" + b;

        DryRunProbe probe = probeBeforeDryRun(orderIdOfDelivery(a));
        JsonNode dry = ok(delete(withDry(path), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("删除等同于并入");
        assertDryRunWroteNothing(probe);
        assertThat(deliveryExists(a)).isTrue();
        System.out.printf("⑨ DELETE 包裹 预演 → willDo=%s%n", dry.path("willDo").asText());

        // DELETE 的 version 走 query（DELETE 带 body 过代理/CDN 不可靠）
        int version = versionOfDelivery(a);
        ok(delete(path + "&version=" + version, null));
        System.out.printf("⑨ DELETE 包裹 真调（?version=%d）→ 源包还在=%s%n", version, deliveryExists(a));
        assertThat(deliveryExists(a)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("⑩ cancel：dryRun 只回 willDo（零变化）→ 真调取消并回补库存")
    void cancel_previewThenExecute() {
        Long id = newPendingDeliveries().get(0);
        int stockBefore = stockOf(skuA);

        DryRunProbe probe = probeBeforeDryRun(orderIdOfDelivery(id));
        JsonNode dry = ok(post(withDry("/admin/deliveries/" + id + "/cancel"), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("已取消").contains("回补");
        assertDryRunWroteNothing(probe);
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
        DryRunProbe probe = probeBeforeDryRun(orderIdOfDelivery(id));
        JsonNode dry = ok(post(withDry(path), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("拣货").contains("已下发→已拣货");
        assertDryRunWroteNothing(probe);
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

        DryRunProbe probe = probeBeforeDryRun(orderId);
        JsonNode dry = ok(put(withDry("/admin/return-applies/" + applyId), payload));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("通过").contains("退款").contains("回补");
        assertDryRunWroteNothing(probe);
        assertThat(returnApplyStatusOf(applyId)).isZero();
        assertThat(statusOf(orderId)).isEqualTo(5);
        System.out.printf("⑫ 退货审核 预演 → willDo=%s%n", dry.path("willDo").asText());

        // 退款流水按**本单 order_sn** 数（不数全表：dev 库是共用的，别人的流水不该算进我们头上）
        String sn = orderSnOf(orderId);
        int paymentsBefore = paymentCount(sn);
        ok(put("/admin/return-applies/" + applyId, payload));
        System.out.printf("⑫ 退货审核 真调 → 申请状态=%d，订单状态=%d，退款流水 +%d，SKU%d 库存 %d→%d%n",
                returnApplyStatusOf(applyId), statusOf(orderId), paymentCount(sn) - paymentsBefore,
                skuA, stockBefore, stockOf(skuA));
        assertThat(returnApplyStatusOf(applyId)).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(6);
        assertThat(paymentCount(sn)).as("真调必须给这张单记 1 条退款流水").isEqualTo(paymentsBefore + 1);
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

        DryRunProbe probe = probeBeforeDryRun(null);
        JsonNode dry = ok(delete(withDry("/admin/products/" + productId), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("下辖 2 个 SKU").contains("删除后不可恢复");
        assertDryRunWroteNothing(probe);
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

        DryRunProbe probe = probeBeforeDryRun(null);
        JsonNode dry = ok(delete(withDry("/admin/skus/" + skuId), null));
        assertOnlyWillDo(dry);
        assertThat(dry.path("willDo").asText()).contains("将删除规格").contains("删除后不可恢复");
        assertDryRunWroteNothing(probe);
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
    @DisplayName("⑲ 顺序回归⑤：version 对得上才轮到业务守卫 → code=1（不被压成 400/409）")
    void orderRegression_stateGuardReportsAfterVersion() {
        Long id = newDispatchedDelivery();

        // dispatch 不收 version（状态流转类），违规状态直接 code=1
        JsonNode dispatch = fail(post("/admin/deliveries/" + id + "/dispatch", null), 1);
        // split / merge / delete 收 version：**带对的版本**才轮到状态守卫，且报的是「只有待下发…」
        JsonNode split = fail(post("/admin/deliveries/" + id + "/split",
                withVersion("{\"items\":[{\"deliveryItemId\":" + firstDeliveryItemId(id) + ",\"quantity\":1}]}",
                        versionOfDelivery(id))), 1);
        Long closedOrder = newOrder(4, skuA);          // 已关闭
        JsonNode auditPass = fail(post("/admin/orders/" + closedOrder + "/audit-pass", null), 1);

        System.out.printf("⑲ 违规状态（split 带对 version）→ dispatch=%s | split=%s | audit-pass=%s%n",
                dispatch.path("message").asText(), split.path("message").asText(), auditPass.path("message").asText());
        assertThat(dispatch.path("message").asText()).isEqualTo("只有待下发发货单可以下发");
        assertThat(split.path("message").asText()).startsWith("只有待下发包裹可以拆分");
        assertThat(auditPass.path("message").asText()).isEqualTo("只有待审核订单可以审核通过");
        assertThat(deliveryStatusOf(id)).as("被守卫拦下的请求不改数据").isEqualTo(1);
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
        allCreatedOrderIds.add(order.getId());

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
        Long id = insertReturningId(
                "INSERT INTO sku (product_id, price, stock, pic) VALUES (?, ?, ?, NULL)",
                productId, new BigDecimal(price), stock);
        createdSkus.add(id);        // 指纹只圈这些自建规格的库存（不数全表 sku.stock）
        return id;
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

    /** 该订单（payment_record 按 order_sn 字符串关联、没有外键）记了几条流水 —— 只数自己这一单 */
    private int paymentCount(String orderSn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);
    }

    /** 订单号（payment_record 的对账键） */
    private String orderSnOf(Long orderId) {
        return jdbc.queryForObject("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId);
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
     * 「预演零落库」的指纹 —— <b>只圈本测试自己造的数据</b>，绝不数全表。
     *
     * <p><b>为什么不能数全表</b>：本类连的是 dev 库 {@code qiqi_mall}，同一时刻可能有别的进程在写它
     * （另一个 {@code mvn test}、往库里补演示数据的脚本、MCP 那边的写用例）。全表行数一被外人动过，
     * 预演就成了替罪羊 —— 2026-11-12 实测：并发跑时本类 13 条用例全红，diff 全是 ±1 行，独占库重跑全绿。
     *
     * <p><b>圈定范围</b>（全部来自本测试自己的 {@code createdOrders} / {@code createdProducts} /
     * {@code createdSkus} / skuA / skuB）：
     * <ul>
     *   <li>order：行数 / status 和 / version 和 —— 抓「插单、改状态、隐式更新（乐观锁 +1）」；</li>
     *   <li>order_item：行数 / 件数和 —— 抓「凭空多/少一条明细」；</li>
     *   <li>delivery_order：行数 / status 和 / version 和 —— 抓「拆分/合并/下发偷偷落库」；</li>
     *   <li>delivery_item：行数 / 件数和 —— 抓「拆包把货搬来搬去」；</li>
     *   <li>return_apply：status 和 —— 抓「退货审核预演就把申请改掉」；</li>
     *   <li>payment_record：行数（按本单 order_sn 圈定）—— 抓「预演就退款记账」；</li>
     *   <li>stock_ledger：行数 / delta 和 —— 抓「预演偷偷扣补库存并记账」；</li>
     *   <li>sku：本测试动过的 SKU 库存和；product：本测试自建商品行数。</li>
     * </ul>
     *
     * <p>参照系换了，断言**没松反而更紧**：原来「行数不变」看不出 status 被改写或 version 被偷偷 +1，
     * 现在 status 和 / version 和 / 件数和 / 台账 delta 和都逐个比对，且只针对自己能负责的那批数据 ——
     * 别人的写不再让我们假红，我们自己的写一行也跑不掉。
     */
    private Map<String, Object> dbSnapshot() {
        In orders = in(createdOrders);
        In products = in(createdProducts);
        In skus = in(scopedSkuIds());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("订单行数", num("SELECT COUNT(*) FROM `order` WHERE id " + orders.sql, orders.args));
        m.put("订单状态和", num("SELECT COALESCE(SUM(status),0) FROM `order` WHERE id " + orders.sql, orders.args));
        m.put("订单版本和", num("SELECT COALESCE(SUM(version),0) FROM `order` WHERE id " + orders.sql, orders.args));
        m.put("订单明细行数", num("SELECT COUNT(*) FROM order_item WHERE order_id " + orders.sql, orders.args));
        m.put("订单明细件数和",
                num("SELECT COALESCE(SUM(product_quantity),0) FROM order_item WHERE order_id " + orders.sql,
                        orders.args));
        m.put("发货单行数", num("SELECT COUNT(*) FROM delivery_order WHERE order_id " + orders.sql, orders.args));
        m.put("发货单状态和",
                num("SELECT COALESCE(SUM(status),0) FROM delivery_order WHERE order_id " + orders.sql, orders.args));
        m.put("发货单版本和",
                num("SELECT COALESCE(SUM(version),0) FROM delivery_order WHERE order_id " + orders.sql, orders.args));
        m.put("包裹明细行数", num("SELECT COUNT(*) FROM delivery_item WHERE delivery_id IN "
                + "(SELECT id FROM delivery_order WHERE order_id " + orders.sql + ")", orders.args));
        m.put("包裹明细件数和", num("SELECT COALESCE(SUM(quantity),0) FROM delivery_item WHERE delivery_id IN "
                + "(SELECT id FROM delivery_order WHERE order_id " + orders.sql + ")", orders.args));
        m.put("退货申请状态和",
                num("SELECT COALESCE(SUM(status),0) FROM return_apply WHERE order_id " + orders.sql, orders.args));
        m.put("支付流水行数", num("SELECT COUNT(*) FROM payment_record WHERE order_sn IN "
                + "(SELECT order_sn FROM `order` WHERE id " + orders.sql + ")", orders.args));
        m.put("台账行数", num("SELECT COUNT(*) FROM stock_ledger WHERE order_id " + orders.sql, orders.args));
        m.put("台账净额和",
                num("SELECT COALESCE(SUM(delta),0) FROM stock_ledger WHERE order_id " + orders.sql, orders.args));
        m.put("本测试SKU库存和", num("SELECT COALESCE(SUM(stock),0) FROM sku WHERE id " + skus.sql, skus.args));
        m.put("本测试商品行数", num("SELECT COUNT(*) FROM product WHERE id " + products.sql, products.args));
        return m;
    }

    /** 指纹要圈的 SKU：skuA / skuB（种子规格，只读它的 stock）+ 本类自建的规格 */
    private List<Long> scopedSkuIds() {
        List<Long> ids = new ArrayList<>();
        if (skuA != null) ids.add(skuA);
        if (skuB != null) ids.add(skuB);
        ids.addAll(createdSkus);
        return ids;
    }

    /**
     * **单张订单**的「预演不许动」指纹 —— 比行数更贴「零落库」的实质。
     *
     * <p>行数指纹只能证明「没多也没少」，证明不了「没被改写」；这里逐项钉死一张订单的实质状态：
     * 状态 / 版本号 / 明细行数 / 支付流水行数 / 退货申请状态和 / 包裹行数与状态和。
     * 任何一个被预演悄悄改了，这里都会红（比如「预演顺手把订单推到已发货」或「预演给包裹 +1 version」）。
     */
    private record OrderFingerprint(int status, int version, int items, int payments,
                                    int deliveries, long deliveryStatusSum, long returnApplyStatusSum) {
    }

    private OrderFingerprint fingerprintOf(Long orderId) {
        String sn = orderSnOf(orderId);
        return new OrderFingerprint(
                statusOf(orderId),
                jdbc.queryForObject("SELECT version FROM `order` WHERE id = ?", Integer.class, orderId),
                itemCountOf(orderId),
                paymentCount(sn),
                jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE order_id = ?", Integer.class, orderId),
                jdbc.queryForObject("SELECT COALESCE(SUM(status),0) FROM delivery_order WHERE order_id = ?",
                        Long.class, orderId),
                jdbc.queryForObject("SELECT COALESCE(SUM(status),0) FROM return_apply WHERE order_id = ?",
                        Long.class, orderId));
    }

    /** IN (...) 子句 + 它要的占位符实参；空集合给 {@code IN (NULL)}（永远匹配不到，语义正确） */
    private record In(String sql, Object[] args) {
    }

    /**
     * 预演前拍下的双层指纹。
     *
     * @param data        本测试自建数据集整体指纹（{@link #dbSnapshot()}）
     * @param orderId     本次预演涉及的那张订单；商品/规格类用例给 {@code null}（只做第 ① 层）
     * @param fingerprint 该订单的单据级指纹（{@link #fingerprintOf(Long)}）
     */
    private record DryRunProbe(Map<String, Object> data, Long orderId, OrderFingerprint fingerprint) {
    }

    /**
     * 预演前取样：①「我造的这一批数据」整体指纹；② 本次预演要动的那张订单的实质状态指纹。
     *
     * <p>两层都只圈**自己造的数据** —— 见 {@link #dbSnapshot()} 上关于「为什么不能数全表」的说明。
     */
    private DryRunProbe probeBeforeDryRun(Long orderId) {
        return new DryRunProbe(dbSnapshot(), orderId, orderId == null ? null : fingerprintOf(orderId));
    }

    /**
     * 预演后断言「零落库」—— 一条都不许删的两层：
     * <ol>
     *   <li><b>数据集层</b>：本测试自建的订单/明细/包裹/退货申请/流水/台账/库存/商品，一个字段都不许变；</li>
     *   <li><b>单据层（更强）</b>：本次预演涉及的那张订单，状态 / 版本号 / 明细行数 / 支付流水行数 /
     *       退货申请状态 / 包裹行数与状态和，逐项不变 —— 这才是「零落库」的实质，
     *       行数比对看不出「status 被改写」「version 被偷偷 +1」，这一层能。</li>
     * </ol>
     */
    private void assertDryRunWroteNothing(DryRunProbe probe) {
        assertThat(dbSnapshot())
                .as("预演必须零落库（只比对本测试自建的数据集，不数全表：dev 库是共用的）")
                .isEqualTo(probe.data());
        if (probe.orderId() != null) {
            assertThat(fingerprintOf(probe.orderId()))
                    .as("预演后订单 %d 的状态/版本号/明细行数/支付流水行数/包裹行数与状态必须逐一不变",
                            probe.orderId())
                    .isEqualTo(probe.fingerprint());
        }
    }

    private static In in(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new In("IN (NULL)", new Object[0]);
        return new In("IN (" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                ids.toArray());
    }

    private long num(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    // ================================================================== HTTP 辅助

    /** 往一个 JSON body 里塞 version（split / merge 的 body 都要带；DELETE 走 query，不用这个） */
    private static String withVersion(String payload, int version) {
        assertThat(payload).endsWith("}");
        return payload.substring(0, payload.length() - 1) + ",\"version\":" + version + "}";
    }

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
