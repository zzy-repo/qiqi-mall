package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.oms.service.DeliveryFlowService;
import com.qiqi.mall.oms.wms.MockWmsService;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 乐观锁（version）集成测试：真起后端 + 真打 HTTP + 真连库。
 *
 * <p>要证明的正是那个「最大的未解决风险」消失了：两个人同时编辑同一个商品，
 * 后保存的**不再**完整覆盖前保存的 —— 第二个人拿到 code=409 并被告知刷新后重试，
 * 数据保持第一个人的改动。
 *
 * <p>5 张热表各验一遍（product / sku / order / delivery_order / member），每个资源 4 条：
 * <pre>
 * ① 两方读同一行（version=V）→ A 带 V 保存成功 → version 变 V+1，落库是新值
 * ② B 拿旧的 V 再保存 → code=409 + 「数据已被他人修改（可能其他人在编辑），请刷新后重试」，
 *    且**数据保持 A 改后的值**（没有被覆盖）
 * ③ 不带 version 保存 → code=400 + 「缺少版本号，请刷新页面后重试」（fail-closed，不静默降级）
 * ④ 版本号乱填（V+99）→ code=409
 * </pre>
 * 另有 ⑥ 列表/详情接口都返回 version（前端要拿它回传）。
 *
 * <p>第二批（⑮~㉑）补的是「**内部 updateById 影响行数为 0**」这一类静默失败 ——
 * 它们没有客户端版本号参与，靠的是「读-改-写」时 UPDATE 的 WHERE version 没命中：
 * <pre>
 * ⑮⑯ 支付（最严重）：两个并发支付只能记 1 条 payment_record、状态只推进一次；
 *     冲突时给买家看的文案是「订单状态已变化，请刷新后重试」（不是「数据被他人修改」）
 * ⑰⑱ 退货审核：订单被别人改过 → 整笔回滚（申请状态/库存/退款流水一个都不许落）；
 *     两个并发审核只成功一个，库存只回补一次、退款流水只有 1 条
 * ⑲⑳ split：并发拆同一包裹时被拒的那次不许留下幽灵包裹（包裹数 = 1 + 成功次数）
 * ㉑ split / merge / delete 的 version 契约：缺 → 400、错 → 409、对 → 成功、dryRun 不需要 version
 * </pre>
 *
 * <p>本类**不依赖**任何确认码机制（confirmCode 整套已删除，见
 * {@code DryRunPreviewIntegrationTest}）：发货单的 PUT 现在是「存在性 → version → 状态守卫」三段，
 * 直接带 version 打即可。
 *
 * <p>数据自建（商品/SKU/订单/发货单/会员全部自己造，跑完全删），不复用种子里的可变数据。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OptimisticLockIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OptimisticLockIntegrationTest {

    static final String MSG_MISSING_VERSION = "缺少版本号，请刷新页面后重试";
    static final String MSG_CONFLICT = "数据已被他人修改（可能其他人在编辑），请刷新后重试";
    static final String MSG_ITEM_DELETED = "发货明细已被删除，请刷新后重试";
    static final String MSG_ORDER_STATE_CHANGED = "订单状态已变化，请刷新后重试";
    static final String MSG_RECORD_DELETED = "记录已被删除，请刷新后重试";

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
    private Long memberId;          // 种子会员：只当作订单的归属，不改它
    private Long categoryId;
    private Long typeId;
    private Long warehouseA;
    private Long warehouseB;

    @Autowired
    private MockWmsService mockWms;      // 为了数「WMS 到底被外呼了几次」

    @Autowired
    private DeliveryFlowService deliveryFlowService;   // ㉒ 直接触发聚合（模拟晚到的子单变化）

    @Autowired
    private DataSource dataSource;       // 造并发：独立连接上开事务，把行锁攥在手里

    private final List<Long> createdProducts = new ArrayList<>();
    private final List<Long> createdOrders = new ArrayList<>();
    private final List<Long> createdMembers = new ArrayList<>();
    private ExecutorService pool;        // ⑦ 双发 / 各用例的「并发请求」都跑在这儿

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
        List<Long> warehouses = jdbc.queryForList("SELECT id FROM warehouse ORDER BY id LIMIT 2", Long.class);
        assertThat(warehouses).as("库里至少要有 2 个仓（改仓用例要真的换个仓）").hasSizeGreaterThan(1);
        warehouseA = warehouses.get(0);
        warehouseB = warehouses.get(1);

        pool = Executors.newFixedThreadPool(4);

        System.out.printf("[准备] member=%d category=%d type=%d 仓=%d/%d%n",
                memberId, categoryId, typeId, warehouseA, warehouseB);
    }

    @AfterAll
    void cleanup() {
        if (pool != null) pool.shutdownNow();
        for (Long orderId : createdOrders) {
            // payment_record 只有 order_sn（没有外键、不级联）：按单号删，否则跑几次就攒一堆测试流水
            for (String sn : jdbc.queryForList("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId)) {
                jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", sn);
            }
            // delivery_logistics 的外键**没有** ON DELETE CASCADE：先清掉，
            // 否则删 order 级联到 delivery_order 时会被外键挡住（⑪ 回传出库会写运单）
            jdbc.update("DELETE l FROM delivery_logistics l JOIN delivery_order d ON d.id = l.delivery_id "
                    + "WHERE d.order_id = ?", orderId);
            // 台账没有外键（是审计证据，不随订单级联）：用例自己造的扣/补行要清掉，别攒在库里
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);      // 级联明细/发货单/包裹明细
        }
        for (Long productId : createdProducts) {
            jdbc.update("DELETE FROM product WHERE id = ?", productId);    // 级联 SKU/相册
        }
        for (Long id : createdMembers) {
            jdbc.update("DELETE FROM member WHERE id = ?", id);
        }
        System.out.printf("[清理] 自建商品 %d 个、订单 %d 张、会员 %d 个已删除%n",
                createdProducts.size(), createdOrders.size(), createdMembers.size());
    }

    // ================================================================== ①~④ product

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("① product：A 带 V 保存成功 → version V+1；B 用旧 V → 409 且没覆盖 A 的改动")
    void product_lostUpdatePrevented() {
        Long id = newProduct("乐观锁商品-主用例");
        int v = versionOf("product", id);
        assertThat(v).as("新插入的行 version 默认 0").isZero();

        // A 保存（带上读到的 V）
        ok(put("/admin/products/" + id, productBody("A 改的名字", v)));
        int vAfterA = versionOf("product", id);
        String nameAfterA = jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, id);
        System.out.printf("① product A 保存 → version %d→%d，name=%s%n", v, vAfterA, nameAfterA);
        assertThat(vAfterA).as("成功更新后 version 必须 +1").isEqualTo(v + 1);
        assertThat(nameAfterA).isEqualTo("A 改的名字");

        // B 还拿着老的 V（模拟「两个标签页都打开了编辑框」）
        JsonNode conflict = fail(put("/admin/products/" + id, productBody("B 改的名字（应该被拒）", v)), 409);
        System.out.printf("① product B 用旧 V=%d 保存 → code=409 message=%s%n", v, conflict.path("message").asText());
        assertThat(conflict.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, id))
                .as("B 的写入必须被拒，A 的改动不能被覆盖").isEqualTo("A 改的名字");
        assertThat(versionOf("product", id)).as("被拒的写入不能推进版本").isEqualTo(vAfterA);

        // 不带 version → 400（fail-closed）
        JsonNode missing = fail(put("/admin/products/" + id, productBody("C 没带版本", null)), 400);
        System.out.printf("① product 不带 version → code=400 message=%s%n", missing.path("message").asText());
        assertThat(missing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, id))
                .as("缺版本号的写入也必须被拒").isEqualTo("A 改的名字");

        // 乱填 version → 409
        JsonNode bogus = fail(put("/admin/products/" + id, productBody("D 乱填版本", vAfterA + 99)), 409);
        System.out.printf("① product 版本号乱填 V+99 → code=409 message=%s%n", bogus.path("message").asText());
        assertThat(bogus.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, id)).isEqualTo("A 改的名字");
    }

    // ================================================================== ①~④ sku

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("② sku：A 带 V 保存成功 → V+1；B 用旧 V → 409 且价格库存没被覆盖")
    void sku_lostUpdatePrevented() {
        Long productId = newProduct("乐观锁商品-SKU 宿主");
        Long id = newSku(productId, "10.00", 100);
        int v = versionOf("sku", id);

        ok(put("/admin/skus/" + id, skuBody(productId, "88.00", 66, v)));
        int vAfterA = versionOf("sku", id);
        System.out.printf("② sku A 保存 → version %d→%d，price=%s stock=%d%n",
                v, vAfterA, priceOf(id), stockOf(id));
        assertThat(vAfterA).isEqualTo(v + 1);
        assertThat(priceOf(id)).isEqualByComparingTo("88.00");
        assertThat(stockOf(id)).isEqualTo(66);

        JsonNode conflict = fail(put("/admin/skus/" + id, skuBody(productId, "1.00", 1, v)), 409);
        System.out.printf("② sku B 用旧 V=%d 保存 → code=409 message=%s%n", v, conflict.path("message").asText());
        assertThat(conflict.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(priceOf(id)).as("被拒的写入不能覆盖价格").isEqualByComparingTo("88.00");
        assertThat(stockOf(id)).as("被拒的写入不能覆盖库存").isEqualTo(66);

        JsonNode missing = fail(put("/admin/skus/" + id, skuBody(productId, "2.00", 2, null)), 400);
        System.out.printf("② sku 不带 version → code=400 message=%s%n", missing.path("message").asText());
        assertThat(missing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        assertThat(priceOf(id)).isEqualByComparingTo("88.00");

        JsonNode bogus = fail(put("/admin/skus/" + id, skuBody(productId, "3.00", 3, vAfterA + 99)), 409);
        System.out.printf("② sku 版本号乱填 V+99 → code=409 message=%s%n", bogus.path("message").asText());
        assertThat(bogus.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(priceOf(id)).isEqualByComparingTo("88.00");
    }

    // ================================================================== ①~④ order

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("③ order：A 带 V 保存成功 → V+1；B 用旧 V → 409 且收货人没被覆盖")
    void order_lostUpdatePrevented() {
        Long id = newOrder(0);
        int v = versionOf("`order`", id);

        ok(put("/admin/orders/" + id, orderBody("A 改的收货人", v)));
        int vAfterA = versionOf("`order`", id);
        System.out.printf("③ order A 保存 → version %d→%d，receiver=%s%n", v, vAfterA, receiverOf(id));
        assertThat(vAfterA).isEqualTo(v + 1);
        assertThat(receiverOf(id)).isEqualTo("A 改的收货人");
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, id))
                .as("PUT /admin/orders 不该改状态").isZero();

        JsonNode conflict = fail(put("/admin/orders/" + id, orderBody("B 改的收货人（应该被拒）", v)), 409);
        System.out.printf("③ order B 用旧 V=%d 保存 → code=409 message=%s%n", v, conflict.path("message").asText());
        assertThat(conflict.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(receiverOf(id)).as("A 的改动不能被覆盖").isEqualTo("A 改的收货人");

        JsonNode missing = fail(put("/admin/orders/" + id, orderBody("C 没带版本", null)), 400);
        System.out.printf("③ order 不带 version → code=400 message=%s%n", missing.path("message").asText());
        assertThat(missing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        assertThat(receiverOf(id)).isEqualTo("A 改的收货人");

        JsonNode bogus = fail(put("/admin/orders/" + id, orderBody("D 乱填版本", vAfterA + 99)), 409);
        System.out.printf("③ order 版本号乱填 V+99 → code=409 message=%s%n", bogus.path("message").asText());
        assertThat(bogus.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(receiverOf(id)).isEqualTo("A 改的收货人");
    }

    // ================================================================== ①~④ member

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("④ member：A 带 V 保存成功 → V+1；B 用旧 V → 409 且昵称没被覆盖")
    void member_lostUpdatePrevented() {
        Long id = newMember("optlock-member");
        int v = versionOf("member", id);

        ok(put("/admin/members/" + id, memberBody("A 改的昵称", v)));
        int vAfterA = versionOf("member", id);
        System.out.printf("④ member A 保存 → version %d→%d，nickname=%s%n", v, vAfterA, nicknameOf(id));
        assertThat(vAfterA).isEqualTo(v + 1);
        assertThat(nicknameOf(id)).isEqualTo("A 改的昵称");

        JsonNode conflict = fail(put("/admin/members/" + id, memberBody("B 改的昵称（应该被拒）", v)), 409);
        System.out.printf("④ member B 用旧 V=%d 保存 → code=409 message=%s%n", v, conflict.path("message").asText());
        assertThat(conflict.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(nicknameOf(id)).as("A 的改动不能被覆盖").isEqualTo("A 改的昵称");

        JsonNode missing = fail(put("/admin/members/" + id, memberBody("C 没带版本", null)), 400);
        System.out.printf("④ member 不带 version → code=400 message=%s%n", missing.path("message").asText());
        assertThat(missing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        assertThat(nicknameOf(id)).isEqualTo("A 改的昵称");

        JsonNode bogus = fail(put("/admin/members/" + id, memberBody("D 乱填版本", vAfterA + 99)), 409);
        System.out.printf("④ member 版本号乱填 V+99 → code=409 message=%s%n", bogus.path("message").asText());
        assertThat(bogus.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(nicknameOf(id)).isEqualTo("A 改的昵称");
    }

    // ================================================================== ①~④ delivery_order

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("⑤ delivery_order：A 带 V 改仓成功 → V+1；B 用旧 V → 409 且仓没被覆盖")
    void delivery_lostUpdatePrevented() {
        Long orderId = newOrder(8);
        Long id = newDelivery(orderId, warehouseA, 2);
        int v = versionOf("delivery_order", id);

        // A：带 V 改仓
        ok(put("/admin/deliveries/" + id, deliveryBody(warehouseB, v)));
        int vAfterA = versionOf("delivery_order", id);
        System.out.printf("⑤ delivery A 改仓 → 仓 %d→%d，version %d→%d%n", warehouseA, warehouseOf(id), v, vAfterA);
        assertThat(warehouseOf(id)).isEqualTo(warehouseB);
        assertThat(vAfterA).isEqualTo(v + 1);

        // B：还拿着旧 V
        JsonNode conflict = fail(put("/admin/deliveries/" + id, deliveryBody(warehouseA, v)), 409);
        System.out.printf("⑤ delivery B 用旧 V=%d 改仓 → code=409 message=%s%n", v, conflict.path("message").asText());
        assertThat(conflict.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(warehouseOf(id)).as("A 的改动不能被覆盖").isEqualTo(warehouseB);

        // 不带 version → 400（fail-closed）
        JsonNode missing = fail(put("/admin/deliveries/" + id, deliveryBody(warehouseA, null)), 400);
        System.out.printf("⑤ delivery 不带 version → code=400 message=%s%n", missing.path("message").asText());
        assertThat(missing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        assertThat(warehouseOf(id)).isEqualTo(warehouseB);

        // 乱填 version → 409
        JsonNode bogus = fail(put("/admin/deliveries/" + id, deliveryBody(warehouseA, vAfterA + 99)), 409);
        System.out.printf("⑤ delivery 版本号乱填 V+99 → code=409 message=%s%n", bogus.path("message").asText());
        assertThat(bogus.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(warehouseOf(id)).isEqualTo(warehouseB);
    }

    // ================================================================== ⑤ 列表/详情都返回 version

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("⑥ 列表与详情都返回 version（5 个资源逐个验）")
    void listAndDetail_exposeVersion() {
        Long productId = newProduct("乐观锁商品-版本可见性");
        Long skuId = newSku(productId, "5.00", 10);
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 1);
        Long newMemberId = newMember("optlock-member-visible");

        // 商品列表 + 详情
        JsonNode products = ok(get("/admin/products"));
        JsonNode productRow = findById(products, productId);
        assertThat(productRow.path("version").isNumber()).as("GET /admin/products 要带 version：%s", productRow).isTrue();
        JsonNode productDetail = ok(get("/admin/products/" + productId));
        assertThat(productDetail.path("product").path("version").isNumber()).as("GET /admin/products/{id} 要带 version").isTrue();

        // SKU 列表（手工拼 Map 的接口，最容易漏）+ 详情
        JsonNode skus = ok(get("/admin/skus?productId=" + productId));
        JsonNode skuRow = findById(skus, skuId);
        assertThat(skuRow.path("version").isNumber()).as("GET /admin/skus?productId= 要带 version：%s", skuRow).isTrue();
        JsonNode skuDetail = ok(get("/admin/skus/" + skuId));
        assertThat(skuDetail.path("sku").path("version").isNumber()).as("GET /admin/skus/{id} 要带 version").isTrue();

        // 订单列表（分页 records）+ 详情
        JsonNode orders = ok(get("/admin/orders?page=1&size=50"));
        JsonNode orderRow = findById(orders.path("records"), orderId);
        assertThat(orderRow.path("version").isNumber()).as("GET /admin/orders 的 records 要带 version：%s", orderRow).isTrue();
        JsonNode orderDetail = ok(get("/admin/orders/" + orderId));
        assertThat(orderDetail.path("order").path("version").isNumber()).as("GET /admin/orders/{id} 要带 version").isTrue();

        // 发货单列表（手工拼 Map 的接口，最容易漏）
        JsonNode deliveries = ok(get("/admin/deliveries?orderId=" + orderId));
        JsonNode deliveryRow = findById(deliveries, deliveryId);
        assertThat(deliveryRow.path("version").isNumber()).as("GET /admin/deliveries?orderId= 要带 version：%s", deliveryRow).isTrue();

        // 会员列表 + 详情
        JsonNode members = ok(get("/admin/members"));
        JsonNode memberRow = findById(members, newMemberId);
        assertThat(memberRow.path("version").isNumber()).as("GET /admin/members 要带 version：%s", memberRow).isTrue();
        JsonNode memberDetail = ok(get("/admin/members/" + newMemberId));
        assertThat(memberDetail.path("version").isNumber()).as("GET /admin/members/{id} 要带 version").isTrue();

        System.out.println("⑥ 列表/详情 version 可见性：product/SKU/order/delivery/member 全部 OK"
                + "（page.records 与手工拼 Map 的 SKU/发货单列表也带上了）");
    }

    // ================================================================== ⑦ audit-pass 双发

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("⑦ audit-pass 并发双发：只成功一个、只生成 1 张发货单（绝不多拆一套）")
    void auditPass_doubleFire_onlyOneSplit() {
        Long orderId = newOrderWithItem(7, 2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ResponseEntity<JsonNode>> call = () -> {
            barrier.await(30, TimeUnit.SECONDS);           // 两个请求同时出发（同一个 status=7）
            return post("/admin/orders/" + orderId + "/audit-pass", null);
        };

        Future<ResponseEntity<JsonNode>> f1 = async(call);
        Future<ResponseEntity<JsonNode>> f2 = async(call);
        JsonNode b1 = bodyOf(f1);
        JsonNode b2 = bodyOf(f2);

        int success = (b1.path("code").asInt() == 0 ? 1 : 0) + (b2.path("code").asInt() == 0 ? 1 : 0);
        JsonNode loser = b1.path("code").asInt() == 0 ? b2 : b1;
        System.out.printf("⑦ audit-pass 双发 → code=%d / code=%d（成功 %d 个），失败方 code=%d message=%s%n",
                b1.path("code").asInt(), b2.path("code").asInt(), success,
                loser.path("code").asInt(), loser.path("message").asText());

        assertThat(success).as("两个请求只能有一个审核通过：%s / %s", b1, b2).isEqualTo(1);
        assertThat(deliveryCount(orderId)).as("**关键：发货单必须恰好 1 张，不许重复拆单**").isEqualTo(1);
        assertThat(orderStatus(orderId)).as("订单状态必须是 8 待发货").isEqualTo(8);
        assertThat(orderSplitStatus(orderId)).as("父单 split_status 必须置 1").isEqualTo(1);
        assertThat(loser.path("message").asText()).as("失败方必须给出中文原因").isNotBlank();
    }

    // ================================================================== ⑧ audit-pass 交错窗口

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("⑧ audit-pass 静默失败已堵住：读到 status=7 后被别人改过 → 409，且一张发货单都没生成")
    void auditPass_interleavedWrite_409AndNoDelivery() {
        Long orderId = newOrderWithItem(7, 2);

        // 造窗口：别人在读之后、写之前改了这张订单（把 version 顶掉），不提交 → 被测请求卡在 UPDATE 上
        Connection hold = holdRow("UPDATE `order` SET version = version + 1 WHERE id = ?", orderId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> post("/admin/orders/" + orderId + "/audit-pass", null));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);                          // 别人提交 → 被测请求的 UPDATE 影响 0 行
        }

        JsonNode body = bodyOf(f);
        failBody(body, 409);
        System.out.printf("⑧ audit-pass 交错窗口 → code=409 message=%s；发货单数=%d，订单状态=%d%n",
                body.path("message").asText(), deliveryCount(orderId), orderStatus(orderId));

        assertThat(body.path("message").asText()).isEqualTo(MSG_CONFLICT);
        // 这两条才是修复的意义：修复前 409 根本不会出现，而 split 照样跑 → 「订单待审核 + 发货单已生成」
        assertThat(deliveryCount(orderId)).as("**关键：冲突的请求不许生成发货单**").isZero();
        assertThat(orderStatus(orderId)).as("订单必须还停在待审核（没有任何半截改动被提交）").isEqualTo(7);

        // 刷新后重试就成功（409 是「请刷新重试」，不是永久失败）
        ok(post("/admin/orders/" + orderId + "/audit-pass", null));
        assertThat(deliveryCount(orderId)).as("重试成功后应有 1 张发货单").isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo(8);
        System.out.printf("⑧ 刷新后重试 → 成功：发货单数=%d，订单状态=%d%n",
                deliveryCount(orderId), orderStatus(orderId));
    }

    // ================================================================== ⑨ dispatch

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("⑨ dispatch 并发：第二个 409，状态不动，且**没有重复外呼 WMS**")
    void dispatch_interleavedWrite_409AndNoWmsCall() {
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 2);
        int wmsBefore = mockWms.dispatchCount();

        Connection hold = holdRow("UPDATE delivery_order SET version = version + 1 WHERE id = ?", deliveryId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> post("/admin/deliveries/" + deliveryId + "/dispatch", null));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }

        JsonNode body = bodyOf(f);
        failBody(body, 409);
        int wmsAfterConflict = mockWms.dispatchCount();
        System.out.printf("⑨ dispatch 冲突 → code=409 message=%s；发货单状态=%d，WMS 外呼增量=%d%n",
                body.path("message").asText(), deliveryStatus(deliveryId), wmsAfterConflict - wmsBefore);

        assertThat(body.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(deliveryStatus(deliveryId)).as("状态必须还是待下发").isZero();
        assertThat(wmsAfterConflict - wmsBefore)
                .as("**关键：冲突的请求一次都不许外呼 WMS（落库在前、外呼在后）**").isZero();

        // 刷新后重试 → 正常下发，且 WMS 只被呼了这一次
        ok(post("/admin/deliveries/" + deliveryId + "/dispatch", null));
        assertThat(deliveryStatus(deliveryId)).isEqualTo(1);
        assertThat(mockWms.dispatchCount() - wmsBefore)
                .as("重试成功只应产生 1 次外呼").isEqualTo(1);
        System.out.printf("⑨ 重试 → 成功：状态=%d，WMS 外呼增量=%d%n",
                deliveryStatus(deliveryId), mockWms.dispatchCount() - wmsBefore);
    }

    // ================================================================== ⑩ cancel

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("⑩ cancel 并发：第二个 409，状态不动，库存**一次都没回补**")
    void cancel_interleavedWrite_409AndNoStockRestore() {
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 3);
        Long skuId = jdbc.queryForObject(
                "SELECT product_sku_id FROM delivery_item WHERE delivery_id = ? LIMIT 1", Long.class, deliveryId);
        int stockBefore = stockOf(skuId);

        Connection hold = holdRow("UPDATE delivery_order SET version = version + 1 WHERE id = ?", deliveryId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> post("/admin/deliveries/" + deliveryId + "/cancel", null));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }

        JsonNode body = bodyOf(f);
        failBody(body, 409);
        System.out.printf("⑩ cancel 冲突 → code=409 message=%s；状态=%d，sku %d 库存 %d→%d%n",
                body.path("message").asText(), deliveryStatus(deliveryId), skuId, stockBefore, stockOf(skuId));

        assertThat(body.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(deliveryStatus(deliveryId)).as("状态必须还是待下发").isZero();
        assertThat(stockOf(skuId)).as("**关键：冲突的请求不许回补库存（否则就是多退了货）**")
                .isEqualTo(stockBefore);
        assertThat(deliveryItemCount(deliveryId)).as("明细一条都不能少").isEqualTo(1);

        // 刷新后重试 → 真的取消，库存只回补这一次
        ok(post("/admin/deliveries/" + deliveryId + "/cancel", null));
        assertThat(deliveryStatus(deliveryId)).isEqualTo(9);
        assertThat(stockOf(skuId)).as("取消成功应回补 3 件").isEqualTo(stockBefore + 3);
        System.out.printf("⑩ 重试 → 已取消：状态=%d，库存 %d→%d%n",
                deliveryStatus(deliveryId), stockBefore, stockOf(skuId));

        // 这条用例往种子 SKU 上加了 3 件，跑完还原，别污染别的用例
        stockStore.resetStock(skuId, stockBefore);   // 还原权威库存（桶 + 缓存）
    }

    // ================================================================== ⑪ wms-callback

    @Test
    @org.junit.jupiter.api.Order(11)
    @DisplayName("⑪ wms-callback：重复回传幂等 + 并发回传 409，状态**绝不回退**")
    void wmsCallback_duplicateAndInterleaved_noRegression() {
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 1);
        ok(post("/admin/deliveries/" + deliveryId + "/dispatch", null));         // 0 → 1 已下发
        assertThat(deliveryStatus(deliveryId)).isEqualTo(1);

        // ① 第一次回传拣货：1 → 2
        ok(post("/admin/deliveries/" + deliveryId + "/wms-callback?event=pick", null));
        assertThat(deliveryStatus(deliveryId)).as("拣货回传应把状态推到 2").isEqualTo(2);

        // ② 同一事件重复回传：幂等（code=0、状态不动、不回退）
        ok(post("/admin/deliveries/" + deliveryId + "/wms-callback?event=pick", null));
        assertThat(deliveryStatus(deliveryId)).as("重复回传必须幂等，状态既不推进也不回退").isEqualTo(2);
        System.out.printf("⑪ 重复回传 pick → 幂等，状态仍为 %d%n", deliveryStatus(deliveryId));

        // ③ 并发窗口：读到 status=2 之后被别人改过 → 409，状态不许回退
        Connection hold = holdRow("UPDATE delivery_order SET version = version + 1 WHERE id = ?", deliveryId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> post("/admin/deliveries/" + deliveryId + "/wms-callback?event=outbound", null));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }
        JsonNode body = bodyOf(f);
        failBody(body, 409);
        System.out.printf("⑪ 交错窗口 outbound → code=409 message=%s；状态=%d（未回退）%n",
                body.path("message").asText(), deliveryStatus(deliveryId));

        assertThat(body.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(deliveryStatus(deliveryId)).as("**关键：冲突不能说成「已回退」，状态必须还是 2**").isEqualTo(2);

        // ④ 重试 outbound → 3 已出库，且写了一条运单
        ok(post("/admin/deliveries/" + deliveryId + "/wms-callback?event=outbound", null));
        assertThat(deliveryStatus(deliveryId)).isEqualTo(3);
        assertThat(logisticsCount(deliveryId)).as("出库回传应写 1 条运单").isEqualTo(1);
        System.out.printf("⑪ 重试 outbound → 状态=%d，运单数=%d%n",
                deliveryStatus(deliveryId), logisticsCount(deliveryId));
    }

    // ================================================================== ⑫ 父订单重算

    @Test
    @org.junit.jupiter.api.Order(12)
    @DisplayName("⑫ 父订单重算静默失败：改包裹时父单被别人改过 → 409，整笔回滚（包裹也没改）")
    void parentOrderRecompute_interleavedWrite_409() {
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 2);
        int deliveryVersionBefore = versionOf("delivery_order", deliveryId);

        // 别人先改了父订单（version 顶掉），不提交
        Connection hold = holdRow("UPDATE `order` SET version = version + 1 WHERE id = ?", orderId);
        int orderVersionHeld;
        Future<ResponseEntity<JsonNode>> f;
        try {
            orderVersionHeld = versionOf("`order`", orderId) + 1;    // 提交后就是它
            // 换仓：markManual 写 delivery_order（版本对得上）→ recomputeSplitStatus 写父订单 → 卡住
            f = async(() -> put("/admin/deliveries/" + deliveryId,
                    "{\"warehouseId\":" + warehouseB + ",\"version\":" + deliveryVersionBefore + "}"));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }

        JsonNode body = bodyOf(f);
        failBody(body, 409);
        System.out.printf("⑫ 父单重算冲突 → code=409 message=%s；包裹仓=%d（回滚），包裹 version=%d，父单 version=%d%n",
                body.path("message").asText(), warehouseOf(deliveryId), versionOf("delivery_order", deliveryId),
                versionOf("`order`", orderId));

        assertThat(body.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(warehouseOf(deliveryId)).as("**关键：整笔回滚，包裹的仓不能被改动**").isEqualTo(warehouseA);
        assertThat(versionOf("delivery_order", deliveryId))
                .as("包裹 version 也不能被推进（事务回滚了）").isEqualTo(deliveryVersionBefore);
        assertThat(versionOf("`order`", orderId))
                .as("父单 version 只应有「别人那一次」的 +1").isEqualTo(orderVersionHeld);

        // 刷新后重试 → 改仓成功，父单 split_status 被重算
        ok(put("/admin/deliveries/" + deliveryId,
                "{\"warehouseId\":" + warehouseB + ",\"version\":" + deliveryVersionBefore + "}"));
        assertThat(warehouseOf(deliveryId)).isEqualTo(warehouseB);
        assertThat(orderSplitStatus(orderId)).as("重算应当跑成功：还有未取消子单 → 1").isEqualTo(1);
        System.out.printf("⑫ 重试 → 改仓成功：仓=%d，父单 split_status=%d%n",
                warehouseOf(deliveryId), orderSplitStatus(orderId));
    }

    // ================================================================== ⑬ 明细被删（无 version 表）

    @Test
    @org.junit.jupiter.api.Order(13)
    @DisplayName("⑬ delivery_item 无 version：明细被别人删了 → 409「发货明细已被删除」，不静默继续")
    void deliveryItemDeleted_interleavedWrite_409() {
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 2);
        Long itemId = jdbc.queryForObject(
                "SELECT id FROM delivery_item WHERE delivery_id = ? LIMIT 1", Long.class, deliveryId);
        int deliveryVersion = versionOf("delivery_order", deliveryId);

        // 别人把这条明细删了（不提交）→ 被测请求的 UPDATE delivery_item 会卡住，提交后影响 0 行
        Connection hold = holdRow("DELETE FROM delivery_item WHERE id = ?", itemId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> put("/admin/deliveries/" + deliveryId,
                    "{\"version\":" + deliveryVersion + ",\"items\":[{\"deliveryItemId\":" + itemId
                            + ",\"quantity\":1}]}"));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }

        JsonNode body = bodyOf(f);
        failBody(body, 409);
        System.out.printf("⑬ 明细被并发删除 → code=409 message=%s；剩余明细=%d，包裹 version=%d%n",
                body.path("message").asText(), deliveryItemCount(deliveryId), versionOf("delivery_order", deliveryId));

        assertThat(body.path("message").asText())
                .as("delivery_item 没有 version 列，0 行的原因只能是「行被删了」，文案必须说准")
                .isEqualTo(MSG_ITEM_DELETED);
        assertThat(deliveryItemCount(deliveryId)).as("明细确实已经被删掉了（别人那次是真的）").isZero();
        assertThat(versionOf("delivery_order", deliveryId))
                .as("失败的事务不能推进包裹 version").isEqualTo(deliveryVersion);
    }

    // ================================================================== ⑭ HTTP 状态码

    @Test
    @org.junit.jupiter.api.Order(14)
    @DisplayName("⑭ 409/400 是真 HTTP 状态码（不是 HTTP 200 + body.code），成功仍是 200")
    void lockRejections_areRealHttpStatus() {
        Long productId = newProduct("乐观锁商品-HTTP 状态码");
        int v = versionOf("product", productId);

        // 成功 → HTTP 200
        ResponseEntity<JsonNode> success = put("/admin/products/" + productId, productBody("HTTP 成功", v));
        assertThat(success.getStatusCode().value()).as("成功必须是 HTTP 200").isEqualTo(200);
        assertThat(success.getBody().path("code").asInt()).isZero();

        // 冲突（旧版本号）→ 真 HTTP 409（fail() 内部已断言状态码）
        ResponseEntity<JsonNode> conflict = put("/admin/products/" + productId, productBody("冲突", v));
        fail(conflict, 409);
        System.out.printf("⑭ 商品冲突 → HTTP %d，body.code=%d，message=%s%n",
                conflict.getStatusCode().value(), conflict.getBody().path("code").asInt(),
                conflict.getBody().path("message").asText());

        // 缺版本号 → 真 HTTP 400
        ResponseEntity<JsonNode> missing = put("/admin/products/" + productId, productBody("没版本", null));
        fail(missing, 400);
        System.out.printf("⑭ 商品缺 version → HTTP %d，body.code=%d，message=%s%n",
                missing.getStatusCode().value(), missing.getBody().path("code").asInt(),
                missing.getBody().path("message").asText());

        // 发货单编辑走的是 service 抛异常那条路，同样要是真 409
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 1);
        int deliveryVersion = versionOf("delivery_order", deliveryId);
        ResponseEntity<JsonNode> deliveryConflict = put("/admin/deliveries/" + deliveryId,
                deliveryBody(warehouseB, deliveryVersion + 99));
        fail(deliveryConflict, 409);
        System.out.printf("⑭ 发货单冲突 → HTTP %d，body.code=%d%n",
                deliveryConflict.getStatusCode().value(), deliveryConflict.getBody().path("code").asInt());
    }


    // ================================================================== ⑮ 支付并发（最严重）

    @Test
    @org.junit.jupiter.api.Order(15)
    @DisplayName("⑮ 支付：读到 status=0 后订单被别人改过 → 409「订单状态已变化」，payment_record 一条都不记")
    void pay_interleavedWrite_409AndNoPaymentRecord() {
        PortalSession buyer = registerPortalMember();
        Long orderId = newOrderOfMember(0, buyer.memberId());
        String sn = orderSnOf(orderId);

        // 造窗口：别人（另一端/另一个进程）在读之后把这张订单改掉，不提交 → 被测支付卡在 UPDATE 上
        Connection hold = holdRow("UPDATE `order` SET version = version + 1 WHERE id = ?", orderId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> postWithToken("/portal/orders/" + orderId + "/pay?payType=1", buyer.jwt()));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }

        ResponseEntity<JsonNode> resp = responseOf(f);
        JsonNode body = fail(resp, 409);
        System.out.printf("⑮ 支付被并发改过 → HTTP %d code=%d message=%s；payment_record=%d，订单状态=%d%n",
                resp.getStatusCode().value(), body.path("code").asInt(), body.path("message").asText(),
                paymentCount(sn), orderStatus(orderId));

        assertThat(body.path("message").asText())
                .as("买家视角的文案：不能是「数据已被他人修改（可能其他人在编辑）」")
                .isEqualTo(MSG_ORDER_STATE_CHANGED);
        assertThat(body.path("message").asText()).doesNotContain("他人修改");
        assertThat(paymentCount(sn)).as("**关键：409 的支付一笔流水都不许记（否则就是收了钱没改状态）**")
                .isZero();
        assertThat(orderStatus(orderId)).as("订单状态不能被这次失败的支付推进").isZero();

        // 刷新后重试 → 真的付成功：状态推进到 7 待审核，流水恰好 1 条
        ok(postWithToken("/portal/orders/" + orderId + "/pay?payType=1", buyer.jwt()));
        System.out.printf("⑮ 重试 → 订单状态=%d，payment_record=%d%n", orderStatus(orderId), paymentCount(sn));
        assertThat(orderStatus(orderId)).isEqualTo(7);
        assertThat(paymentCount(sn)).as("重试成功后流水恰好 1 条").isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(16)
    @DisplayName("⑯ 支付并发双发：一个成功、一个被拒；**payment_record 只有 1 条**，订单状态只推进一次")
    void pay_doubleFire_onlyOnePaymentRecord() {
        PortalSession buyer = registerPortalMember();
        Long orderId = newOrderOfMember(0, buyer.memberId());
        String sn = orderSnOf(orderId);
        String path = "/portal/orders/" + orderId + "/pay?payType=1";

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ResponseEntity<JsonNode>> call = () -> {
            barrier.await(30, TimeUnit.SECONDS);          // 两个支付同时出发（读到的是同一个 status=0）
            return postWithToken(path, buyer.jwt());
        };
        Future<ResponseEntity<JsonNode>> f1 = async(call);
        Future<ResponseEntity<JsonNode>> f2 = async(call);
        JsonNode b1 = bodyOf(f1);
        JsonNode b2 = bodyOf(f2);

        int success = (b1.path("code").asInt() == 0 ? 1 : 0) + (b2.path("code").asInt() == 0 ? 1 : 0);
        JsonNode loser = b1.path("code").asInt() == 0 ? b2 : b1;
        int records = paymentCount(sn);
        System.out.printf("⑯ 支付双发 → code=%d / code=%d（成功 %d 个），失败方 code=%d message=%s%n",
                b1.path("code").asInt(), b2.path("code").asInt(), success,
                loser.path("code").asInt(), loser.path("message").asText());
        System.out.printf("⑯ 断言 → payment_record（order_sn=%s）=%d 条，订单状态=%d，订单 version=%d%n",
                sn, records, orderStatus(orderId), versionOf("`order`", orderId));

        assertThat(success).as("两个并发支付只能有一个成功：%s / %s", b1, b2).isEqualTo(1);
        assertThat(records).as("**关键：payment_record 必须只有 1 条（否则用户被扣两次钱）**").isEqualTo(1);
        assertThat(orderStatus(orderId)).as("订单状态只推进一次：0 → 7").isEqualTo(7);
        if (loser.path("code").asInt() == 409) {
            assertThat(loser.path("message").asText()).isEqualTo(MSG_ORDER_STATE_CHANGED);
        }
    }

    // ================================================================== ⑰ 退货审核并发

    @Test
    @org.junit.jupiter.api.Order(17)
    @DisplayName("⑰ 退货审核并发：订单被别人改过 → 409 且**整笔回滚**（申请没通过、库存没回补、没退款流水）")
    void returnAudit_interleavedWrite_409AndFullRollback() {
        Long orderId = newOrderWithItem(5, 2);            // 退款中（申请退货后就是 5）
        Long applyId = newReturnApply(orderId, memberId);
        Long skuId = jdbc.queryForObject(
                "SELECT product_sku_id FROM order_item WHERE order_id = ? LIMIT 1", Long.class, orderId);
        String sn = orderSnOf(orderId);
        int stockBefore = stockOf(skuId);

        // 别人先改了订单（version 顶掉），不提交 → 审核请求改 order 时卡住，提交后影响 0 行
        Connection hold = holdRow("UPDATE `order` SET version = version + 1 WHERE id = ?", orderId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> put("/admin/return-applies/" + applyId, "{\"status\":1}"));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }

        ResponseEntity<JsonNode> resp = responseOf(f);
        JsonNode body = fail(resp, 409);
        System.out.printf("⑰ 审核通过被并发改过 → HTTP %d code=%d message=%s%n",
                resp.getStatusCode().value(), body.path("code").asInt(), body.path("message").asText());
        System.out.printf("⑰ 回滚断言 → 申请状态=%d（仍是待处理），订单状态=%d，sku %d 库存 %d→%d，退款流水=%d 条%n",
                returnApplyStatusOf(applyId), orderStatus(orderId), skuId, stockBefore, stockOf(skuId),
                paymentCountByType(sn, 2));

        assertThat(body.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(returnApplyStatusOf(applyId)).as("申请状态必须回滚成待处理（绝不能「申请已通过、订单没退款」）")
                .isZero();
        assertThat(orderStatus(orderId)).as("订单状态不能被这次失败的审核推进").isEqualTo(5);
        assertThat(stockOf(skuId)).as("**关键：库存一次都不许回补**").isEqualTo(stockBefore);
        assertThat(paymentCountByType(sn, 2)).as("**关键：退款流水一条都不许记**").isZero();

        // 刷新后重试 → 真的通过：订单 5→6，退款流水 1 条，库存回补一次
        ok(put("/admin/return-applies/" + applyId, "{\"status\":1}"));
        System.out.printf("⑰ 重试 → 申请状态=%d，订单状态=%d，sku %d 库存 %d→%d，退款流水=%d 条%n",
                returnApplyStatusOf(applyId), orderStatus(orderId), skuId, stockBefore, stockOf(skuId),
                paymentCountByType(sn, 2));
        assertThat(returnApplyStatusOf(applyId)).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo(6);
        assertThat(stockOf(skuId)).as("通过后回补一次（2 件）").isEqualTo(stockBefore + 2);
        assertThat(paymentCountByType(sn, 2)).as("退款流水恰好 1 条").isEqualTo(1);

        // 这条用例往种子 SKU 上加了 2 件，跑完还原，别污染别的用例
        stockStore.resetStock(skuId, stockBefore);   // 还原权威库存（桶 + 缓存）
    }

    @Test
    @org.junit.jupiter.api.Order(18)
    @DisplayName("⑱ 退货审核双发：只成功一个；**库存只回补一次、退款流水只有 1 条**")
    void returnAudit_doubleFire_onlyOneRefund() {
        Long orderId = newOrderWithItem(5, 3);
        Long applyId = newReturnApply(orderId, memberId);
        Long skuId = jdbc.queryForObject(
                "SELECT product_sku_id FROM order_item WHERE order_id = ? LIMIT 1", Long.class, orderId);
        String sn = orderSnOf(orderId);
        int stockBefore = stockOf(skuId);
        String path = "/admin/return-applies/" + applyId;

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ResponseEntity<JsonNode>> call = () -> {
            barrier.await(30, TimeUnit.SECONDS);
            return put(path, "{\"status\":1}");
        };
        Future<ResponseEntity<JsonNode>> f1 = async(call);      // 两个 future 都要先提交，再等结果
        Future<ResponseEntity<JsonNode>> f2 = async(call);
        JsonNode b1 = bodyOf(f1);
        JsonNode b2 = bodyOf(f2);

        int success = (b1.path("code").asInt() == 0 ? 1 : 0) + (b2.path("code").asInt() == 0 ? 1 : 0);
        JsonNode loser = b1.path("code").asInt() == 0 ? b2 : b1;
        System.out.printf("⑱ 审核双发 → code=%d / code=%d（成功 %d 个），失败方 code=%d message=%s%n",
                b1.path("code").asInt(), b2.path("code").asInt(), success,
                loser.path("code").asInt(), loser.path("message").asText());
        System.out.printf("⑱ 断言 → 申请状态=%d，订单状态=%d，sku %d 库存 %d→%d，退款流水=%d 条%n",
                returnApplyStatusOf(applyId), orderStatus(orderId), skuId, stockBefore, stockOf(skuId),
                paymentCountByType(sn, 2));

        assertThat(success).as("同一张申请只能被审核成功一次：%s / %s", b1, b2).isEqualTo(1);
        assertThat(returnApplyStatusOf(applyId)).isEqualTo(1);
        assertThat(orderStatus(orderId)).as("订单只推进一次：5 → 6").isEqualTo(6);
        assertThat(stockOf(skuId)).as("**关键：库存只回补一次（3 件）**").isEqualTo(stockBefore + 3);
        assertThat(paymentCountByType(sn, 2)).as("**关键：退款流水只有 1 条**").isEqualTo(1);

        stockStore.resetStock(skuId, stockBefore);   // 还原权威库存（桶 + 缓存）
    }

    // ================================================================== ⑲ split 并发

    @Test
    @org.junit.jupiter.api.Order(19)
    @DisplayName("⑲ split 并发：包裹被别人改过 → 409 且**一个新包都没生成**（整笔回滚）")
    void split_interleavedWrite_409AndNoExtraParcel() {
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 3);
        Long itemId = deliveryItemIdOf(deliveryId);
        int version = versionOf("delivery_order", deliveryId);
        String payload = "{\"items\":[{\"deliveryItemId\":" + itemId + ",\"quantity\":1}],\"version\":" + version + "}";

        Connection hold = holdRow("UPDATE delivery_order SET version = version + 1 WHERE id = ?", deliveryId);
        Future<ResponseEntity<JsonNode>> f;
        try {
            f = async(() -> post("/admin/deliveries/" + deliveryId + "/split", payload));
            awaitRowLockWait();
        } finally {
            commitAndClose(hold);
        }

        ResponseEntity<JsonNode> resp = responseOf(f);
        JsonNode body = fail(resp, 409);
        System.out.printf("⑲ split 被并发改过 → HTTP %d code=%d message=%s；包裹数=%d，明细数=%d，包裹 version=%d%n",
                resp.getStatusCode().value(), body.path("code").asInt(), body.path("message").asText(),
                deliveryCount(orderId), deliveryItemCount(deliveryId), versionOf("delivery_order", deliveryId));

        assertThat(body.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(deliveryCount(orderId)).as("**关键：冲突的那次不许留下新包裹**").isEqualTo(1);
        assertThat(deliveryItemCount(deliveryId)).as("源包明细也不能被拆走").isEqualTo(1);
        assertThat(deliveryItemQuantity(itemId)).as("明细数量保持 3 件").isEqualTo(3);
        assertThat(versionOf("delivery_order", deliveryId)).as("失败的写入不能推进版本").isEqualTo(version + 1);

        // 刷新后重试（版本号回到当前值）→ 真的拆成 2 个包裹
        int retryVersion = versionOf("delivery_order", deliveryId);
        ok(post("/admin/deliveries/" + deliveryId + "/split",
                "{\"items\":[{\"deliveryItemId\":" + itemId + ",\"quantity\":1}],\"version\":" + retryVersion + "}"));
        System.out.printf("⑲ 重试 → 包裹数 1→%d%n", deliveryCount(orderId));
        assertThat(deliveryCount(orderId)).as("重试成功只应多 1 个包裹").isEqualTo(2);
    }

    @Test
    @org.junit.jupiter.api.Order(20)
    @DisplayName("⑳ split 双发：被拒的那次一个包都不生成（包裹数 = 1 + 成功次数）")
    void split_doubleFire_noPhantomParcel() {
        Long orderId = newOrder(8);
        Long deliveryId = newDelivery(orderId, warehouseA, 3);
        Long itemId = deliveryItemIdOf(deliveryId);
        int version = versionOf("delivery_order", deliveryId);
        String payload = "{\"items\":[{\"deliveryItemId\":" + itemId + ",\"quantity\":1}],\"version\":" + version + "}";
        String path = "/admin/deliveries/" + deliveryId + "/split";

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ResponseEntity<JsonNode>> call = () -> {
            barrier.await(30, TimeUnit.SECONDS);      // 两个管理员同时拆同一个包裹，拿的是同一个 version
            return post(path, payload);
        };
        Future<ResponseEntity<JsonNode>> f1 = async(call);      // 同上：先提交两个，再等结果
        Future<ResponseEntity<JsonNode>> f2 = async(call);
        JsonNode b1 = bodyOf(f1);
        JsonNode b2 = bodyOf(f2);

        int success = (b1.path("code").asInt() == 0 ? 1 : 0) + (b2.path("code").asInt() == 0 ? 1 : 0);
        int parcels = deliveryCount(orderId);
        System.out.printf("⑳ split 双发 → code=%d（%s） / code=%d（%s）（成功 %d 次），包裹数=%d%n",
                b1.path("code").asInt(), b1.path("message").asText(""),
                b2.path("code").asInt(), b2.path("message").asText(""), success, parcels);
        System.out.printf("⑳ 断言 → 包裹数必须恰好 = 1 + 成功次数（成功 %d → %d 个），明细总量=%d 件%n",
                success, 1 + success, totalItemQuantityOf(orderId));

        assertThat(success).as("至少有一个成功（两个都被拒说明守卫过度）：%s / %s", b1, b2).isGreaterThanOrEqualTo(1);
        assertThat(parcels).as("**关键：包裹数只随「成功次数」增长，被拒的那次不许留下幽灵包裹**")
                .isEqualTo(1 + success);
        assertThat(totalItemQuantityOf(orderId)).as("明细总量守恒：不能凭空多出货").isEqualTo(3);
        for (JsonNode b : List.of(b1, b2)) {
            if (b.path("code").asInt() == 409) {
                assertThat(b.path("message").asText()).isEqualTo(MSG_CONFLICT);
            }
        }
    }

    // ================================================================== split/merge/delete 的 version 契约

    @Test
    @org.junit.jupiter.api.Order(21)
    @DisplayName("㉑ split/merge/delete 的 version 契约：缺 → 400；错 → 409；对 → 成功；dryRun 不需要 version")
    void deliveryEditVersionContract() {
        Long orderId = newOrder(8);
        Long a = newDelivery(orderId, warehouseA, 3);      // 拆分/合并的源包
        Long b = newDelivery(orderId, warehouseA, 2);      // 合并目标

        // ---------------- split
        Long itemA = deliveryItemIdOf(a);
        String splitItems = "{\"items\":[{\"deliveryItemId\":" + itemA + ",\"quantity\":1}]";
        int versionA = versionOf("delivery_order", a);

        JsonNode missing = fail(post("/admin/deliveries/" + a + "/split", splitItems + "}"), 400);
        assertThat(missing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        JsonNode wrong = fail(post("/admin/deliveries/" + a + "/split",
                splitItems + ",\"version\":" + (versionA + 99) + "}"), 409);
        assertThat(wrong.path("message").asText()).isEqualTo(MSG_CONFLICT);
        // 预演不需要版本号（版本校验之前就返回）
        ok(post("/admin/deliveries/" + a + "/split?dryRun=true", splitItems + "}"));
        // 参数先于版本：body 里既没明细也没版本 → 报「请指定要拆出的明细与数量」(code=1)，不是 400
        failBody(post("/admin/deliveries/" + a + "/split", "{}").getBody(), 1);
        // 正确版本 → 成功
        ok(post("/admin/deliveries/" + a + "/split", splitItems + ",\"version\":" + versionA + "}"));
        System.out.printf("㉑ split：缺 version→400，错 version→409，dryRun→200，对 version→成功；包裹数=%d%n",
                deliveryCount(orderId));

        // ---------------- merge（把 b 并入 a）
        int versionB = versionOf("delivery_order", b);
        String mergeBody = "{\"targetId\":" + a;
        JsonNode mergeMissing = fail(post("/admin/deliveries/" + b + "/merge", mergeBody + "}"), 400);
        assertThat(mergeMissing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        JsonNode mergeWrong = fail(post("/admin/deliveries/" + b + "/merge",
                mergeBody + ",\"version\":" + (versionB + 99) + "}"), 409);
        assertThat(mergeWrong.path("message").asText()).isEqualTo(MSG_CONFLICT);
        ok(post("/admin/deliveries/" + b + "/merge?dryRun=true", mergeBody + "}"));
        ok(post("/admin/deliveries/" + b + "/merge", mergeBody + ",\"version\":" + versionB + "}"));
        System.out.printf("㉑ merge：缺 version→400，错 version→409，dryRun→200，对 version→成功；源包还在=%b%n",
                deliveryExists(b));

        // ---------------- delete（空包裹直接删；version 走 query）
        Long empty = newEmptyDelivery(orderId, warehouseA);
        int versionEmpty = versionOf("delivery_order", empty);
        JsonNode deleteMissing = fail(delete("/admin/deliveries/" + empty, null), 400);
        assertThat(deleteMissing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        JsonNode deleteWrong = fail(delete("/admin/deliveries/" + empty + "?version=" + (versionEmpty + 99), null), 409);
        assertThat(deleteWrong.path("message").asText()).isEqualTo(MSG_CONFLICT);
        ok(delete("/admin/deliveries/" + empty + "?dryRun=true", null));
        ok(delete("/admin/deliveries/" + empty + "?version=" + versionEmpty, null));
        System.out.printf("㉑ delete：缺 version→400，错 version→409，dryRun→200，对 version→成功；空包还在=%b%n",
                deliveryExists(empty));

        assertThat(deliveryExists(b)).as("合并后源包必须消失").isFalse();
        assertThat(deliveryExists(empty)).as("删除后包裹必须消失").isFalse();
    }

    // ================================================================== ㉒ 确认收货：子单是事实源（口径②）

    /*
     * 口径②（用户拍板）：**子单是事实源** —— 父单状态永远由包裹推导。
     *
     * 修之前这里的序列会真实倒退（就是这条用例当初标 @Disabled 固化的那个 Bug）：
     *   ① 买家点「确认收货」→ 老实现直接把父单写成 3 已完成，**一个包裹都不碰**
     *   ② 此时另一个包裹还在运输中（子单事实：一个已签收、一个未签收）
     *   ③ WMS 晚到的回传（已出库 → 运输中）触发聚合：allSigned=false、allOutbound=true
     *   ④ 父单被写回 2 已发货 ← 已完成变成已发货
     *
     * 修之后（口径②）：确认收货先把**所有在途包裹推到已签收**，再由聚合推导父单 = 3。
     * 于是「父单已完成 + 还有包裹在途」这个分叉的组合在库里根本不可能出现，晚到的回传也再算不出更早的状态。
     */
    @Test
    @org.junit.jupiter.api.Order(22)
    @DisplayName("㉒ 确认收货：所有在途包裹一并推到已签收、订单由子单推导为已完成；晚到回传/再聚合都不许退回已发货")
    void confirmReceipt_signsAllParcels_orderStaysCompleted() {
        PortalSession buyer = registerPortalMember();
        Long orderId = newOrderOfMember(2, buyer.memberId());        // 2 已发货：确认收货的前置状态
        Long signedParcel = newDelivery(orderId, warehouseA, 1);     // 包裹 A：已签收
        Long movingParcel = newDelivery(orderId, warehouseB, 1);     // 包裹 B：还在运输中
        jdbc.update("UPDATE delivery_order SET status = 5 WHERE id = ?", signedParcel);   // 5 已签收
        jdbc.update("UPDATE delivery_order SET status = 4 WHERE id = ?", movingParcel);   // 4 运输中
        System.out.printf("㉒ 初始：订单=%d（已发货），包裹A=%d（已签收），包裹B=%d（运输中）%n",
                orderStatus(orderId), deliveryStatus(signedParcel), deliveryStatus(movingParcel));

        // ① 买家确认收货
        JsonNode data = ok(postWithToken("/portal/orders/" + orderId + "/confirm", buyer.jwt()));
        int orderAfterConfirm = orderStatus(orderId);
        int signedAfter = deliveryStatus(signedParcel);
        int movingAfter = deliveryStatus(movingParcel);
        System.out.printf("㉒ ① 确认收货 → 订单=%d（已完成），包裹A=%d，包裹B=%d（在途包裹被一并推到已签收）%n",
                orderAfterConfirm, signedAfter, movingAfter);

        assertThat(orderAfterConfirm).as("确认收货后订单必须由子单推导成 3 已完成").isEqualTo(3);
        assertThat(data.path("order").path("status").asInt()).as("接口返回的也是聚合后的真实状态").isEqualTo(3);
        assertThat(signedAfter).as("已签收的包裹保持 5").isEqualTo(5);
        assertThat(movingAfter)
                .as("**关键：在途包裹必须被一并推到已签收(5)，不能只把父单盖成已完成**").isEqualTo(5);
        assertThat(logisticsCount(movingParcel))
                .as("被推成已签收的包裹必须有运单（否则物流页是空的）").isEqualTo(1);

        // ② 「晚到的 WMS 回传」再打一次：包裹 B 揽收事件（幂等，不推进也不回退）
        ok(post("/admin/deliveries/" + movingParcel + "/wms-callback?event=transit", null));
        System.out.printf("㉒ ② 晚到回传 transit（幂等）→ 订单=%d，包裹B=%d%n",
                orderStatus(orderId), deliveryStatus(movingParcel));
        assertThat(orderStatus(orderId)).as("幂等回传不许把订单改回 2").isEqualTo(3);

        // ③ 更硬的一条：直接触发一次聚合（模拟任意晚到的子单变化把聚合跑起来）
        deliveryFlowService.recomputeOrderStatus(orderId);
        System.out.printf("㉒ ③ 再跑一次聚合 recomputeOrderStatus → 订单=%d%n", orderStatus(orderId));
        assertThat(orderStatus(orderId)).as("**已完成是子单推导出来的结论，再聚合仍是 3，绝不能退回 2**")
                .isEqualTo(3);

        // ④ 真正晚到的写者：再塞一张待下发包裹并取消它（cancel 会触发聚合）→ 订单仍是 3
        Long lateParcel = newDelivery(orderId, warehouseA, 1);
        Long skuId = jdbc.queryForObject(
                "SELECT product_sku_id FROM delivery_item WHERE delivery_id = ? LIMIT 1", Long.class, lateParcel);
        int stockBefore = stockOf(skuId);
        ok(post("/admin/deliveries/" + lateParcel + "/cancel", null));
        int orderAfterLateWriter = orderStatus(orderId);
        stockStore.resetStock(skuId, stockBefore);   // 还原权威库存（桶 + 缓存）   // 撤销 cancel 的库存回补
        System.out.printf("㉒ ④ 晚到的写者（取消一张待下发包裹触发聚合）→ 订单=%d%n", orderAfterLateWriter);
        assertThat(orderAfterLateWriter).as("晚到的写者只能改包裹，不许把已完成订单退回已发货").isEqualTo(3);
        assertThat(deliveryStatus(lateParcel)).as("被取消的包裹自身状态确实变了（证明聚合真的跑过）").isEqualTo(9);
    }

    // ================================================================== ㉓ 终态不出让：已退款(6)/已关闭(4) 不参与聚合

    /*
     * 同类残余（口径② 之外的那一半）：「已关闭 / 已退款」的订单被晚到的包裹回传聚合成「已发货」。
     *
     *  ① 订单已经是 6 已退款（退货审核通过）或 4 已关闭（审核不通过），但还有一个在途包裹
     *  ② 晚到的 WMS 回传触发 recomputeOrderStatus
     *  ③ 旧实现按子单算出 allOutbound → 把订单写成 2 已发货 / 9 部分发货 ← 商业上明显不对
     *
     * 4/6 不在口径② 的射程内：口径② 说的是「订单**还没终局**时，状态由包裹推导」；
     * 已关闭 / 已退款是**外部业务动作**的结论（不是包裹推导出来的），一旦进入就是业务终局。
     * 3 已完成反而**不加**守卫 —— 它本来就由子单推导得出（全部已签收），再加一道就成了两套逻辑。
     */
    @Test
    @org.junit.jupiter.api.Order(23)
    @DisplayName("㉓ 已退款(6)/已关闭(4) + 在途包裹 + 晚到回传 → 订单状态不被聚合成 2/9（终态不出让）")
    void terminalStatusNotOverwrittenByAggregation() {
        // ---------------- 场景 A：已退款(6)
        Long refunded = newOrder(6);                                     // 6 已退款
        Long parcelA = newDelivery(refunded, warehouseA, 1);
        jdbc.update("UPDATE delivery_order SET status = 4 WHERE id = ?", parcelA);   // 4 运输中（在途）
        System.out.printf("㉓-A 初始：订单=%d（已退款），包裹=%d（运输中）%n",
                orderStatus(refunded), deliveryStatus(parcelA));

        deliveryFlowService.recomputeOrderStatus(refunded);               // ① 直接触发一次聚合
        System.out.printf("㉓-A ① 直接聚合 → 订单=%d（必须仍是 6）%n", orderStatus(refunded));
        assertThat(orderStatus(refunded)).as("已退款(6) 订单不许被聚合成 2 已发货").isEqualTo(6);

        // ② 晚到的 WMS 回传（运输中 → 已签收）同样会触发聚合
        ok(post("/admin/deliveries/" + parcelA + "/wms-callback?event=signed", null));
        System.out.printf("㉓-A ② 晚到回传 signed → 订单=%d，包裹=%d（包裹照常推进）%n",
                orderStatus(refunded), deliveryStatus(parcelA));
        assertThat(deliveryStatus(parcelA)).as("子单照常推进到已签收（被拦的只是父单聚合）").isEqualTo(5);
        assertThat(orderStatus(refunded)).as("**已退款(6) 是业务终局，晚到回传不许改写**").isEqualTo(6);

        // ---------------- 场景 B：已关闭(4)
        Long closed = newOrder(4);                                       // 4 已关闭
        Long parcelB = newDelivery(closed, warehouseA, 1);
        jdbc.update("UPDATE delivery_order SET status = 3 WHERE id = ?", parcelB);   // 3 已出库
        deliveryFlowService.recomputeOrderStatus(closed);
        System.out.printf("㉓-B 初始+聚合：订单=%d（已关闭），包裹=%d（已出库）%n",
                orderStatus(closed), deliveryStatus(parcelB));
        assertThat(orderStatus(closed)).as("已关闭(4) 订单不许被聚合成 2 已发货").isEqualTo(4);

        ok(post("/admin/deliveries/" + parcelB + "/wms-callback?event=transit", null));
        System.out.printf("㉓-B 晚到回传 transit → 订单=%d，包裹=%d%n",
                orderStatus(closed), deliveryStatus(parcelB));
        assertThat(deliveryStatus(parcelB)).as("子单照常推进到运输中").isEqualTo(4);
        assertThat(orderStatus(closed)).as("**已关闭(4) 是业务终局，晚到回传不许改写**").isEqualTo(4);
    }

    // ================================================================== ㉔ 确认收货守卫：还有包裹没出库 → 拒绝

    /*
     * 口径② 的正确形态是「父单状态由子单推导」，**不是**「买家一按就把包裹推到终点」。
     * 旧实现把该订单所有未取消、未达已签收的包裹一律推到已签收，于是：
     *   ① 一张「待下发」的包裹凭空变成「已签收」—— 下发/拣货/出库全跳过；
     *   ② 还凭空补了一条现实里不存在的运单号（saveLogisticsIfAbsent）。
     * 货还在商家手里，买家不可能收到，所以这里必须是**拒绝**而不是「硬推进」。
     */
    @Test
    @org.junit.jupiter.api.Order(24)
    @DisplayName("㉔ 确认收货守卫：还有包裹没出库（待下发）→ 拒绝并说明原因，包裹与运单都不许被凭空推进")
    void confirmRejectedWhenAnyParcelNotOutbound() {
        PortalSession buyer = registerPortalMember();
        Long orderId = newOrderOfMember(2, buyer.memberId());             // 2 已发货
        Long outbound = newDelivery(orderId, warehouseA, 1);              // 包裹 A：已出库（该有运单）
        Long pending = newDelivery(orderId, warehouseB, 1);               // 包裹 B：待下发（货还在商家手里）
        jdbc.update("UPDATE delivery_order SET status = 3 WHERE id = ?", outbound);   // 3 已出库
        // 出库回传会写运单：A 本来就有 1 条，B 一条都没有
        jdbc.update("INSERT INTO delivery_logistics (delivery_id, company, track_no) VALUES (?, '测试快递', ?)",
                outbound, "TRK" + System.nanoTime());
        int outboundLogisticsBefore = logisticsCount(outbound);
        int pendingLogisticsBefore = logisticsCount(pending);
        System.out.printf("㉔ 初始：订单=%d（已发货），包裹A=%d（已出库，运单 %d 条），包裹B=%d（待下发，运单 %d 条）%n",
                orderStatus(orderId), deliveryStatus(outbound), outboundLogisticsBefore,
                deliveryStatus(pending), pendingLogisticsBefore);

        ResponseEntity<JsonNode> resp = postWithToken("/portal/orders/" + orderId + "/confirm", buyer.jwt());
        JsonNode body = failBody(resp.getBody(), 1);                     // 业务失败（code=1），不是 500
        String message = body.path("message").asText();
        System.out.printf("㉔ ① 确认收货被拒：code=%d，message=%s%n", body.path("code").asInt(), message);

        assertThat(message).as("必须给出明确中文原因（还有几个包裹没出库 + 该怎么办）")
                .contains("尚未出库").contains("暂不能确认收货");
        assertThat(message).as("原因里要指出是哪个状态的包裹（待下发）").contains("待下发");
        assertThat(resp.getStatusCode().value())
                .as("业务守卫是 code=1 的可读失败（不是 HTTP 4xx/5xx）").isEqualTo(200);

        // 库里一个字节都不许变：包裹不推进、订单不写、运单不凭空产生
        assertThat(deliveryStatus(pending)).as("**待下发的包裹绝不能被凭空推到已签收**").isEqualTo(0);
        assertThat(deliveryStatus(outbound)).as("已出库的包裹也不该被这次失败的确认推进").isEqualTo(3);
        assertThat(orderStatus(orderId)).as("订单状态不动（确认收货被整笔拒绝）").isEqualTo(2);
        assertThat(logisticsCount(pending)).as("**没出过库的包裹不许凭空多出运单号**").isEqualTo(pendingLogisticsBefore);
        assertThat(logisticsCount(outbound)).as("已出库包裹的运单条数不变").isEqualTo(outboundLogisticsBefore);
        System.out.printf("㉔ ② 复核：订单=%d，包裹A=%d（运单 %d 条），包裹B=%d（运单 %d 条）%n",
                orderStatus(orderId), deliveryStatus(outbound), logisticsCount(outbound),
                deliveryStatus(pending), logisticsCount(pending));
    }

    // ================================================================== ㉕ 确认收货：只推在途包裹，不造运单

    @Test
    @org.junit.jupiter.api.Order(25)
    @DisplayName("㉕ 确认收货：已出库/运输中两张包裹都推到已签收、订单由子单推导为已完成，且不多造运单")
    void confirmSignsOnlyInTransitParcels_withoutFabricatingLogistics() {
        PortalSession buyer = registerPortalMember();
        Long orderId = newOrderOfMember(2, buyer.memberId());             // 2 已发货
        Long outbound = newDelivery(orderId, warehouseA, 1);              // 包裹 A：已出库(3)
        Long moving = newDelivery(orderId, warehouseB, 1);                // 包裹 B：运输中(4)
        jdbc.update("UPDATE delivery_order SET status = 3 WHERE id = ?", outbound);
        jdbc.update("UPDATE delivery_order SET status = 4 WHERE id = ?", moving);
        // 两张包裹都真出过库（出库回传会写运单）→ 各 1 条
        jdbc.update("INSERT INTO delivery_logistics (delivery_id, company, track_no) VALUES (?, '测试快递', ?)",
                outbound, "TRK" + System.nanoTime());
        jdbc.update("INSERT INTO delivery_logistics (delivery_id, company, track_no) VALUES (?, '测试快递', ?)",
                moving, "TRK" + System.nanoTime());
        int outboundLogisticsBefore = logisticsCount(outbound);
        int movingLogisticsBefore = logisticsCount(moving);
        System.out.printf("㉕ 初始：订单=%d（已发货），包裹A=%d（已出库，运单 %d 条），包裹B=%d（运输中，运单 %d 条）%n",
                orderStatus(orderId), deliveryStatus(outbound), outboundLogisticsBefore,
                deliveryStatus(moving), movingLogisticsBefore);

        JsonNode data = ok(postWithToken("/portal/orders/" + orderId + "/confirm", buyer.jwt()));
        System.out.printf("㉕ ① 确认收货成功 → 订单=%d（已完成），包裹A=%d，包裹B=%d；运单 A=%d 条、B=%d 条%n",
                orderStatus(orderId), deliveryStatus(outbound), deliveryStatus(moving),
                logisticsCount(outbound), logisticsCount(moving));

        assertThat(orderStatus(orderId)).as("全部子单已签收 → 父单由子单推导为 3 已完成").isEqualTo(3);
        assertThat(data.path("order").path("status").asInt()).as("接口回读的也是聚合后的真实状态").isEqualTo(3);
        assertThat(deliveryStatus(outbound)).as("已出库的包裹被推到已签收").isEqualTo(5);
        assertThat(deliveryStatus(moving)).as("运输中的包裹被推到已签收").isEqualTo(5);
        // 关键：两张包裹出库时已经各有 1 条运单，这次确认**不许**再补新的（不凭空造运单号）
        assertThat(logisticsCount(outbound)).as("**不许给已出库包裹再造一条运单**").isEqualTo(outboundLogisticsBefore);
        assertThat(logisticsCount(moving)).as("**不许给运输中包裹再造一条运单**").isEqualTo(movingLogisticsBefore);
        System.out.printf("㉕ ② 运单条数前后一致：A %d→%d，B %d→%d%n",
                outboundLogisticsBefore, logisticsCount(outbound), movingLogisticsBefore, logisticsCount(moving));
    }

    // ================================================================== ㉖ 退款中(5) 不许被包裹聚合覆盖

    /*
     * ①：订单进入 5 退款中之后，只要还有在途包裹，任何一次回传（transit/signed）都会触发聚合，
     * 旧实现按子单算出 allOutbound/allSigned，把订单写成 2 已发货 / 9 部分发货 / 3 已完成 ——
     * 和 4/6 是同一个病：**业务动作写下的"退款进行中"被包裹回传覆盖掉了**。
     * 5 不是终态，但它是「退款正在审批中」这个进行中的业务动作，下一步只能由退货审核写成 6。
     */
    @Test
    @org.junit.jupiter.api.Order(26)
    @DisplayName("㉖ 退款中(5) + 在途包裹：回传 transit/signed 不许把订单写成 2/9/3；5→6 的退货审核通过照常生效")
    void returnInProgressNotOverwrittenByAggregation() {
        Long orderId = newOrderWithItem(5, 1);                       // 5 退款中（买家申请退货后的状态）
        Long parcel = newDelivery(orderId, warehouseA, 1);
        jdbc.update("UPDATE delivery_order SET status = 3 WHERE id = ?", parcel);   // 3 已出库（在途）
        System.out.printf("㉖ 初始：订单=%d（退款中），包裹=%d（已出库）%n",
                orderStatus(orderId), deliveryStatus(parcel));

        ok(post("/admin/deliveries/" + parcel + "/wms-callback?event=transit", null));
        System.out.printf("㉖ ① 回传 transit → 订单=%d，包裹=%d%n", orderStatus(orderId), deliveryStatus(parcel));
        assertThat(deliveryStatus(parcel)).as("子单照常推进到运输中").isEqualTo(4);
        assertThat(orderStatus(orderId)).as("**退款中(5) 不许被包裹回传聚合成 2 已发货**").isEqualTo(5);

        ok(post("/admin/deliveries/" + parcel + "/wms-callback?event=signed", null));
        System.out.printf("㉖ ② 回传 signed → 订单=%d，包裹=%d%n", orderStatus(orderId), deliveryStatus(parcel));
        assertThat(deliveryStatus(parcel)).isEqualTo(5);
        assertThat(orderStatus(orderId)).as("**全部包裹已签收也不许把退款中写成 3 已完成**").isEqualTo(5);

        deliveryFlowService.recomputeOrderStatus(orderId);           // 直接聚合同样被跳过
        System.out.printf("㉖ ③ 直接聚合 → 订单=%d（仍必须 5）%n", orderStatus(orderId));
        assertThat(orderStatus(orderId)).as("**直接调用聚合也不许改写退款中**").isEqualTo(5);

        // 5 → 6 的正常流转不受影响（退货审核通过不走聚合）
        Long applyId = newReturnApply(orderId, memberId);
        Long skuId = jdbc.queryForObject(
                "SELECT product_sku_id FROM order_item WHERE order_id = ? ORDER BY id LIMIT 1", Long.class, orderId);
        int stockBefore = stockOf(skuId);
        ok(put("/admin/return-applies/" + applyId, "{\"status\":1}"));
        System.out.printf("㉖ ④ 退货审核通过 → 申请=%d，订单=%d（6 已退款）%n",
                returnApplyStatusOf(applyId), orderStatus(orderId));
        assertThat(returnApplyStatusOf(applyId)).isEqualTo(1);
        assertThat(orderStatus(orderId)).as("5→6 是退货审核写出来的，不许被上面的聚合守卫挡掉").isEqualTo(6);
        stockStore.resetStock(skuId, stockBefore);   // 还原权威库存（桶 + 缓存）   // 还原被回补的库存
    }

    // ================================================================== ㉗ 审核通过写 6 必须先校验原状态

    @Test
    @org.junit.jupiter.api.Order(27)
    @DisplayName("㉗ 已关闭(4) 订单 + 待处理退货申请 → 审核通过被拒（中文原因），订单仍是 4，不退款不回补")
    void returnAuditPass_requiresRefundInProgress() {
        Long orderId = newOrderWithItem(4, 2);                       // 4 已关闭
        Long applyId = newReturnApply(orderId, memberId);
        Long skuId = jdbc.queryForObject(
                "SELECT product_sku_id FROM order_item WHERE order_id = ? ORDER BY id LIMIT 1", Long.class, orderId);
        String sn = orderSnOf(orderId);
        int stockBefore = stockOf(skuId);

        // dryRun 与真做同一套守卫
        JsonNode dryBody = failBody(put("/admin/return-applies/" + applyId + "?dryRun=true", "{\"status\":1}").getBody(), 1);
        System.out.printf("㉗ ① 预演就被拒：code=%d message=%s%n",
                dryBody.path("code").asInt(), dryBody.path("message").asText());
        assertThat(dryBody.path("message").asText()).contains("退款中");

        ResponseEntity<JsonNode> resp = put("/admin/return-applies/" + applyId, "{\"status\":1}");
        JsonNode body = failBody(resp.getBody(), 1);
        System.out.printf("㉗ ② 审核通过被拒：HTTP %d code=%d message=%s%n",
                resp.getStatusCode().value(), body.path("code").asInt(), body.path("message").asText());

        assertThat(resp.getStatusCode().value()).as("业务守卫是 code=1 的可读失败").isEqualTo(200);
        assertThat(body.path("message").asText()).as("必须说清「为什么」：订单不是退款中")
                .contains("退款中").contains("已关闭");
        System.out.printf("㉗ ③ 复核：订单=%d（仍 4 已关闭），申请=%d（仍待处理），退款流水=%d 条，sku %d 库存 %d→%d%n",
                orderStatus(orderId), returnApplyStatusOf(applyId), paymentCountByType(sn, 2),
                skuId, stockBefore, stockOf(skuId));
        assertThat(orderStatus(orderId)).as("**已关闭(4) 绝不许被写成已退款(6)**").isEqualTo(4);
        assertThat(returnApplyStatusOf(applyId)).as("申请状态也不许被改（这笔请求整体不成立）").isZero();
        assertThat(paymentCountByType(sn, 2)).as("**一条退款流水都不许记**").isZero();
        assertThat(stockOf(skuId)).as("**库存一件都不许回补**").isEqualTo(stockBefore);
    }

    // ================================================================== ㉘ ship：8 待发货 / 1 已付款 + 出库闸门

    static final String MSG_PARCEL_NOT_OUTBOUND = "该订单还有包裹未出库，不能标记为已发货；请先在履约页下发并等待仓库回传出库";

    @Test
    @org.junit.jupiter.api.Order(28)
    @DisplayName("㉘ ship 接受待发货(8)/已付款(1)，但 8 且包裹全部未出库 → 拒绝（父单不许甩开子单）；其它状态仍拒绝")
    void ship_acceptsPendingShipmentAndPaid() {
        // ① 8 待发货 + 包裹停在「待下发」：**必须拒绝**。
        //    审核通过只生成待下发包裹，货还在商家手里；此刻把父单硬写成 2 已发货，
        //    父单说已发货、子单还在商家手上，之后任何一次回传按子单聚合又会把状态写回来（真实分叉）。
        Long pendingShip = newOrder(8);
        Long parcel = newEmptyDelivery(pendingShip, warehouseA);           // 待下发(0)
        JsonNode blocked = failBody(post("/admin/orders/" + pendingShip + "/ship", null).getBody(), 1);
        System.out.printf("㉘ ① 订单8（包裹待下发）发货被拒 → code=%d message=%s，订单=%d%n",
                blocked.path("code").asInt(), blocked.path("message").asText(), orderStatus(pendingShip));
        assertThat(blocked.path("message").asText()).isEqualTo(MSG_PARCEL_NOT_OUTBOUND);
        assertThat(orderStatus(pendingShip)).as("被拒时订单必须仍是 8 待发货").isEqualTo(8);

        // ①' 预演跑同一套守卫（不许「预演说能发、真做被拒」）
        JsonNode dryBlocked = failBody(post("/admin/orders/" + pendingShip + "/ship?dryRun=true", null).getBody(), 1);
        assertThat(dryBlocked.path("message").asText()).isEqualTo(MSG_PARCEL_NOT_OUTBOUND);
        assertThat(orderStatus(pendingShip)).as("预演零落库").isEqualTo(8);

        // ①'' 已下发 / 已拣货 同样不算出库：货还在仓库里，一样拒绝
        jdbc.update("UPDATE delivery_order SET status = 1 WHERE id = ?", parcel);
        assertThat(failBody(post("/admin/orders/" + pendingShip + "/ship", null).getBody(), 1)
                .path("message").asText()).isEqualTo(MSG_PARCEL_NOT_OUTBOUND);
        jdbc.update("UPDATE delivery_order SET status = 2 WHERE id = ?", parcel);
        assertThat(failBody(post("/admin/orders/" + pendingShip + "/ship", null).getBody(), 1)
                .path("message").asText()).isEqualTo(MSG_PARCEL_NOT_OUTBOUND);
        assertThat(orderStatus(pendingShip)).as("三种未出库状态都不许改父单").isEqualTo(8);

        // ② 包裹出库（≥ 已出库 3）之后才放行。这里直接把包裹置为已出库来构造
        //    「子单已出库、父单仍停在 8」这一历史分叉残留（正常链路里聚合会自己把父单推到 2）——
        //    守卫要保证的正是：这种残留状态下 ship 不再把父单与子单进一步拉开。
        jdbc.update("UPDATE delivery_order SET status = 3 WHERE id = ?", parcel);
        JsonNode dry = ok(post("/admin/orders/" + pendingShip + "/ship?dryRun=true", null));
        System.out.printf("㉘ ② 订单8（包裹已出库）预演 → willDo=%s%n", dry.path("willDo").asText());
        assertThat(dry.path("willDo").asText()).contains("待发货→已发货");
        ok(post("/admin/orders/" + pendingShip + "/ship", null));
        System.out.printf("㉘ ② 订单8 真调 → 订单=%d（2 已发货）%n", orderStatus(pendingShip));
        assertThat(orderStatus(pendingShip)).as("**至少一张包裹已出库 → 8 待发货可以发**").isEqualTo(2);

        // ②' 已取消的包裹不能替父单「作证出库」：唯一一张包裹是已取消 → 依然拒绝
        Long onlyCancelled = newOrder(8);
        Long cancelled = newEmptyDelivery(onlyCancelled, warehouseA);
        jdbc.update("UPDATE delivery_order SET status = 9 WHERE id = ?", cancelled);
        assertThat(failBody(post("/admin/orders/" + onlyCancelled + "/ship", null).getBody(), 1)
                .path("message").asText()).isEqualTo(MSG_PARCEL_NOT_OUTBOUND);
        assertThat(orderStatus(onlyCancelled)).as("已取消包裹不算出库").isEqualTo(8);

        // ② 1 已付款（历史直发单）仍照旧
        Long paid = newOrder(1);
        ok(post("/admin/orders/" + paid + "/ship", null));
        System.out.printf("㉘ ③ 订单1 真调 → 订单=%d（2 已发货）%n", orderStatus(paid));
        assertThat(orderStatus(paid)).as("1 已付款仍可发货（保持原行为）").isEqualTo(2);

        // ③ 其它状态仍拒绝：5 退款中
        Long refunding = newOrder(5);
        JsonNode body = failBody(post("/admin/orders/" + refunding + "/ship", null).getBody(), 1);
        System.out.printf("㉘ ④ 订单5 发货被拒 → code=%d message=%s，订单=%d%n",
                body.path("code").asInt(), body.path("message").asText(), orderStatus(refunding));
        assertThat(body.path("message").asText()).contains("待发货").contains("已付款").contains("退款中");
        assertThat(orderStatus(refunding)).as("被拒时订单状态不动").isEqualTo(5);
    }

    // ================================================================== ㉙ 退货被拒：交回包裹聚合

    /*
     * ④：退货被拒旧实现硬写 1 已付款。但那张订单的包裹可能已经出库/签收 —— 父单与子单分叉，
     * 之后任何一次回传按子单重新聚合又把它改回 2/9（真实的状态倒退）。
     * 口径②：子单是事实源，有包裹就让聚合推导；只有直发单（无包裹）才写 1。
     */
    @Test
    @org.junit.jupiter.api.Order(29)
    @DisplayName("㉙ 退货被拒：有在途包裹 → 订单由包裹聚合（出库=2、签收=3，绝不硬写 1）；直发单 → 1 已付款")
    void returnReject_aggregatesFromParcelsInsteadOfHardWritingPaid() {
        // A：包裹已出库 → 聚合出 2 已发货
        Long orderA = newOrderWithItem(5, 1);
        Long parcelA = newDelivery(orderA, warehouseA, 1);
        jdbc.update("UPDATE delivery_order SET status = 3 WHERE id = ?", parcelA);
        Long applyA = newReturnApply(orderA, memberId);
        ok(put("/admin/return-applies/" + applyA, "{\"status\":2}"));
        System.out.printf("㉙-A 包裹已出库 + 退货被拒 → 订单=%d（由子单推导），包裹=%d%n",
                orderStatus(orderA), deliveryStatus(parcelA));
        assertThat(orderStatus(orderA)).as("**包裹已出库 → 订单必须是 2 已发货，不是硬写的 1 已付款**").isEqualTo(2);
        assertThat(deliveryStatus(parcelA)).as("包裹自己不参与退货，状态不变").isEqualTo(3);

        // B：包裹已签收 → 聚合出 3 已完成（不会出现「已付款却挂着已签收包裹」）
        Long orderB = newOrderWithItem(5, 1);
        Long parcelB = newDelivery(orderB, warehouseB, 1);
        jdbc.update("UPDATE delivery_order SET status = 5 WHERE id = ?", parcelB);
        Long applyB = newReturnApply(orderB, memberId);
        ok(put("/admin/return-applies/" + applyB, "{\"status\":2}"));
        System.out.printf("㉙-B 包裹已签收 + 退货被拒 → 订单=%d（由子单推导），包裹=%d%n",
                orderStatus(orderB), deliveryStatus(parcelB));
        assertThat(orderStatus(orderB)).as("**包裹已签收 → 订单 3 已完成，绝不与子单分叉**").isEqualTo(3);

        // C：直发单（没有任何包裹）→ 才写 1 已付款
        Long direct = newOrder(5);
        Long applyC = newReturnApply(direct, memberId);
        ok(put("/admin/return-applies/" + applyC, "{\"status\":2}"));
        System.out.printf("㉙-C 直发单（无包裹）+ 退货被拒 → 订单=%d（1 已付款）%n", orderStatus(direct));
        assertThat(orderStatus(direct)).as("没有子单事实可依 → 回到已付款").isEqualTo(1);
    }

    // ================================================================== ㉚ 全部包裹取消 → 退回待审核

    @Test
    @org.junit.jupiter.api.Order(30)
    @DisplayName("㉚ 全部包裹被取消 → 订单从 8 退回 7 待审核（拆单标记清空），且能重新审核拆单")
    void allParcelsCancelled_returnsToPendingAudit() {
        Long orderId = newOrderWithItem(8, 1);                       // 8 待发货，split_status=1
        Long parcel = newDelivery(orderId, warehouseA, 1);           // 拆出来的唯一在途包裹
        System.out.printf("㉚ 初始：订单=%d（待发货），split_status=%d，包裹=%d（待下发）%n",
                orderStatus(orderId), orderSplitStatus(orderId), deliveryStatus(parcel));

        // cancel 会按包裹明细回补库存；这张单是直接插的（没走扣减）→ 不还原就会让 dev 库 sku 库存每轮虚增 1 件
        Long cancelledSku = jdbc.queryForObject(
                "SELECT product_sku_id FROM delivery_item WHERE delivery_id = ? LIMIT 1", Long.class, parcel);
        int stockBeforeCancel = stockOf(cancelledSku);
        ok(post("/admin/deliveries/" + parcel + "/cancel", null));
        stockStore.resetStock(cancelledSku, stockBeforeCancel);   // 撤销 cancel 的库存回补（桶 + 缓存一起还原）
        System.out.printf("㉚ ① 取消唯一包裹 → 包裹=%d（已取消），订单=%d（待审核），split_status=%d%n",
                deliveryStatus(parcel), orderStatus(orderId), orderSplitStatus(orderId));
        assertThat(deliveryStatus(parcel)).isEqualTo(9);
        assertThat(orderStatus(orderId)).as("**不再卡在 8 待发货，退回 7 待审核**").isEqualTo(7);
        assertThat(orderSplitStatus(orderId)).as("拆单标记必须清空，否则重新审核会返回那几张已取消的包裹").isZero();

        // ② 能真的重新审核拆单（生成新的在途包裹）
        ok(post("/admin/orders/" + orderId + "/audit-pass", null));
        List<Long> parcels = jdbc.queryForList(
                "SELECT id FROM delivery_order WHERE order_id = ? ORDER BY id", Long.class, orderId);
        List<Long> active = jdbc.queryForList(
                "SELECT id FROM delivery_order WHERE order_id = ? AND status <> 9 ORDER BY id", Long.class, orderId);
        System.out.printf("㉚ ② 重新审核通过 → 订单=%d，包裹共 %d 张，其中在途 %d 张（状态=%s）%n",
                orderStatus(orderId), parcels.size(), active.size(),
                active.isEmpty() ? "-" : String.valueOf(deliveryStatus(active.get(0))));
        assertThat(orderStatus(orderId)).isEqualTo(8);
        assertThat(active).as("**必须重新拆出新包裹（不是把已取消的包裹原样返回）**").isNotEmpty();
        assertThat(deliveryStatus(active.get(0))).as("新包裹是待下发").isZero();
    }

    // ================================================================== ㉛ 删「待处理」退货申请 → 订单交回聚合

    @Test
    @org.junit.jupiter.api.Order(31)
    @DisplayName("㉛ 删掉「待处理」退货申请后订单不再卡在 5 退款中：无包裹→7 待审核（可重走审核），有出库包裹→按子单聚合")
    void deletePendingReturnApply_releasesOrderFromRefundInProgress() {
        Long reasonId = jdbc.queryForObject("SELECT id FROM return_reason ORDER BY id LIMIT 1", Long.class);

        // A：直发单（一张包裹行都没有）—— 删掉待处理申请后必须回 7 待审核，并且能重新走完整个审核流程
        Long direct = newOrderWithItem(5, 1);
        Long applyA = newReturnApply(direct, memberId);
        ok(delete("/admin/return-applies/" + applyA, null));
        System.out.printf("㉛-A 直发单：删待处理申请 #%d → 订单 %d（应为 7 待审核），split_status=%d%n",
                applyA, orderStatus(direct), orderSplitStatus(direct));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_apply WHERE id = ?", Integer.class, applyA))
                .as("申请真的被删掉").isZero();
        assertThat(orderStatus(direct)).as("**没有包裹可依 → 回 7 待审核，不许永远卡在 5 退款中**").isEqualTo(7);
        assertThat(orderSplitStatus(direct)).as("拆单标记一并归零，重新审核时才能真的拆出新包裹").isZero();

        // A'：能重新走审核流程 —— 重新登记申请 → 订单回 5 → 审核通过 → 6 已退款
        JsonNode again = ok(post("/admin/return-applies", "{\"orderId\":" + direct + ",\"memberId\":" + memberId
                + ",\"returnReason\":\"重新申请退货\",\"reasonId\":" + reasonId + "}"));
        Long applyA2 = again.path("id").asLong();
        System.out.printf("㉛-A' 重新登记申请 #%d → 订单 %d（应为 5 退款中）%n", applyA2, orderStatus(direct));
        assertThat(orderStatus(direct)).isEqualTo(5);
        // 审核通过会回补库存；这张单是直接插的（没走扣减）→ 不还原就会让 dev 库 sku 库存每轮虚增
        Long directSku = jdbc.queryForObject(
                "SELECT product_sku_id FROM order_item WHERE order_id = ? ORDER BY id LIMIT 1", Long.class, direct);
        int directStockBefore = stockOf(directSku);
        ok(put("/admin/return-applies/" + applyA2, "{\"status\":1}"));
        System.out.printf("㉛-A'' 审核通过 → 订单 %d（应为 6 已退款）%n", orderStatus(direct));
        assertThat(orderStatus(direct)).as("重新走一遍审核流程要真的能通过").isEqualTo(6);
        stockStore.resetStock(directSku, directStockBefore);   // 还原被回补的库存（桶 + 缓存一起还原）

        // B：有已出库包裹 —— **不硬写 7**，交回聚合，按子单推导出 2 已发货
        Long shipped = newOrderWithItem(5, 1);
        Long parcel = newDelivery(shipped, warehouseA, 1);
        jdbc.update("UPDATE delivery_order SET status = 3 WHERE id = ?", parcel);
        Long applyB = newReturnApply(shipped, memberId);
        ok(delete("/admin/return-applies/" + applyB, null));
        System.out.printf("㉛-B 包裹已出库：删待处理申请 #%d → 订单 %d（应为 2 已发货，由子单聚合）%n",
                applyB, orderStatus(shipped));
        assertThat(orderStatus(shipped)).as("有子单事实就按子单聚合（2 已发货），不是硬写某个固定状态").isEqualTo(2);

        // C：该订单还有**另一条**待处理申请在审批中 → 退款流程没结束，状态不许动（仍 5）
        Long multi = newOrderWithItem(5, 1);
        Long applyC1 = newReturnApply(multi, memberId);
        Long applyC2 = newReturnApply(multi, memberId);
        ok(delete("/admin/return-applies/" + applyC1, null));
        System.out.printf("㉛-C 删一条（还有 #%d 待处理）→ 订单 %d（应仍为 5 退款中）%n", applyC2, orderStatus(multi));
        assertThat(orderStatus(multi)).as("还有别的申请在审批中 → 不动订单状态").isEqualTo(5);

        // D：删的是「已拒绝」的历史申请（不是待处理）→ 与退款流程无关，状态不许动（仍 5）
        Long history = newOrderWithItem(5, 1);
        Long applyD = newReturnApply(history, memberId);
        jdbc.update("UPDATE return_apply SET status = 2 WHERE id = ?", applyD);
        ok(delete("/admin/return-applies/" + applyD, null));
        System.out.printf("㉛-D 删的是已拒绝的历史申请 → 订单 %d（应仍为 5）%n", orderStatus(history));
        assertThat(orderStatus(history)).as("删历史结论不改订单状态").isEqualTo(5);
    }

    // ================================================================== ㉜ 后台建单扣库存 + 台账

    @Test
    @org.junit.jupiter.api.Order(32)
    @DisplayName("㉜ 后台建单扣库存并记 ORDER_CREATE 台账（与买家下单同口径）；库存不足 → 整笔拒绝，库存/订单/台账都不变")
    void adminCreateOrder_deductsStockAndWritesLedger() {
        Long productId = newProduct("后台建单扣库存商品");
        String productName = jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, productId);
        Long skuA = newSku(productId, "12.00", 10);
        Long skuB = newSku(productId, "13.00", 5);

        // ① 建单：2 条明细各 3 件 → 两个 SKU 各扣 3 件，台账多 2 行 ORDER_CREATE（delta = -3）
        int stockABefore = stockOf(skuA);
        int stockBBefore = stockOf(skuB);
        JsonNode created = ok(post("/admin/orders", createOrderBody(productId, skuA, skuB, 3)));
        Long orderId = created.path("id").asLong();
        createdOrders.add(orderId);
        System.out.printf("㉜ ① 建单成功 id=%d，总额=%s；sku%d 库存 %d→%d，sku%d 库存 %d→%d%n",
                orderId, created.path("totalAmount").asText(), skuA, stockABefore, stockOf(skuA),
                skuB, stockBBefore, stockOf(skuB));
        assertThat(stockOf(skuA)).as("**后台建单必须扣库存（skuA 10-3=7）**").isEqualTo(stockABefore - 3);
        assertThat(stockOf(skuB)).as("**后台建单必须扣库存（skuB 5-3=2）**").isEqualTo(stockBBefore - 3);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(product_quantity),0) FROM order_item WHERE order_id = ?",
                Integer.class, orderId)).as("明细数量落在库里").isEqualTo(6);

        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT sku_id, delta, reason, idem_key FROM stock_ledger WHERE order_id = ? ORDER BY sku_id", orderId);
        System.out.printf("㉜ ① 台账 %d 行：%s%n", ledger.size(), ledger);
        assertThat(ledger).as("**两条明细各记一行 ORDER_CREATE 台账**").hasSize(2);
        assertThat(ledger.get(0).get("reason").toString()).isEqualTo("ORDER_CREATE");
        assertThat(((Number) ledger.get(0).get("delta")).intValue()).isEqualTo(-3);
        assertThat(((Number) ledger.get(1).get("delta")).intValue()).isEqualTo(-3);

        // ② 库存不足：skuB 只剩 2 件，再建「3 件」的单 → 必须整笔拒绝，且库存 / 订单 / 台账都不许变
        int orderCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Integer.class);
        int ledgerCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger", Integer.class);
        JsonNode body = failBody(post("/admin/orders", createOrderBody(productId, skuA, skuB, 3)).getBody(), 1);
        System.out.printf("㉜ ② 库存不足建单被拒 → code=%d message=%s；sku%d 仍 %d 件；订单数 %d→%d；台账 %d→%d%n",
                body.path("code").asInt(), body.path("message").asText(), skuB, stockOf(skuB),
                orderCountBefore, jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Integer.class),
                ledgerCountBefore, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger", Integer.class));
        assertThat(body.path("message").asText()).isEqualTo("库存不足：" + productName + " 只剩 2 件");
        assertThat(stockOf(skuB)).as("被拒时库存一件都不许扣").isEqualTo(stockBBefore - 3);
        assertThat(stockOf(skuA)).as("同一笔里的前一条明细也要跟着回滚（不许只扣一半）").isEqualTo(stockABefore - 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Integer.class))
                .as("**不许建出「扣了库存但订单没建」或反过来的半截单**").isEqualTo(orderCountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger", Integer.class))
                .as("台账也要一起回滚，不留孤儿扣减行").isEqualTo(ledgerCountBefore);

        // ③ 扣完库存之后走「审核不通过」：回补 3 件 —— 库存回到下单前（净变化 0，不再虚增）
        int before = stockOf(skuA);
        ok(post("/admin/orders/" + orderId + "/audit-reject", null));
        System.out.printf("㉜ ③ 审核不通过回补：sku%d 库存 %d→%d（应回到建单前 %d）%n",
                skuA, before, stockOf(skuA), stockABefore);
        assertThat(stockOf(skuA)).as("**建单扣 3 + 审核不通过补 3 = 净 0，不再虚增**").isEqualTo(stockABefore);
    }

    // ================================================================== 数据构造

    private Long newProduct(String name) {
        String json = "{\"product\":{\"name\":\"" + name + "\",\"description\":\"乐观锁自动化用例\",\"categoryId\":"
                + categoryId + ",\"typeId\":" + typeId + ",\"publishStatus\":1}}";
        JsonNode data = ok(post("/admin/products", json));
        Long id = data.path("id").asLong();
        createdProducts.add(id);
        return id;
    }

    private Long newSku(Long productId, String price, int stock) {
        String json = "{\"sku\":{\"productId\":" + productId + ",\"price\":" + price + ",\"stock\":" + stock + "}}";
        return ok(post("/admin/skus", json)).path("id").asLong();
    }

    /** 直接插订单（走接口要造明细，这里只要一张干净的订单行） */
    private Long newOrder(int status) {
        Long id = insertReturningId(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, receiver_name, "
                        + "receiver_phone, receiver_address, split_status) VALUES (?, ?, 100.00, ?, 1, ?, ?, ?, ?)",
                "OL" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999),
                memberId, status, "乐观锁测试-原始收货人", "13700000000", "自动化测试地址（跑完即删）",
                status == 8 ? 1 : 0);
        createdOrders.add(id);
        return id;
    }

    /** 插一张带 1 条明细的订单（audit-pass 拆单的前提：订单要有明细，SKU 要有分仓库存） */
    private Long newOrderWithItem(int status, int quantity) {
        Long id = newOrder(status);
        Long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM inventory GROUP BY sku_id HAVING MAX(stock) > 0 ORDER BY sku_id LIMIT 1",
                Long.class);
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, product_price, "
                        + "product_quantity) VALUES (?, (SELECT product_id FROM sku WHERE id = ?), ?, "
                        + "'并发窗口用例明细', 10.00, ?)",
                id, skuId, skuId, quantity);
        return id;
    }

    /** 直接插一张待下发发货单 + 一条明细（admin 编辑包裹的前提数据） */
    private Long newDelivery(Long orderId, Long warehouseId, int quantity) {
        Long id = insertReturningId(
                "INSERT INTO delivery_order (delivery_sn, order_id, warehouse_id, status, receiver_name, "
                        + "receiver_phone, receiver_address, source) VALUES (?, ?, ?, 0, ?, ?, ?, 0)",
                "DL" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999),
                orderId, warehouseId, "乐观锁测试-原始收货人", "13700000000", "自动化测试地址（跑完即删）");
        Long orderItemId = insertReturningId(
                "INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, product_price, product_quantity) "
                        + "VALUES (?, (SELECT id FROM product ORDER BY id LIMIT 1), "
                        + "(SELECT id FROM sku ORDER BY id LIMIT 1), '乐观锁测试明细', 10.00, ?)",
                orderId, quantity);
        jdbc.update("INSERT INTO delivery_item (delivery_id, order_item_id, product_sku_id, quantity) "
                + "VALUES (?, ?, (SELECT id FROM sku ORDER BY id LIMIT 1), ?)", id, orderItemId, quantity);
        return id;
    }

    /** 一张属于指定会员的订单（C 端支付/确认收货要求 order.member_id == 登录会员） */
    private Long newOrderOfMember(int status, Long memberIdOfOrder) {
        Long id = newOrder(status);
        jdbc.update("UPDATE `order` SET member_id = ? WHERE id = ?", memberIdOfOrder, id);
        return id;
    }

    /** 空包裹（没有明细）：DELETE 用例要的就是「删一张空包裹」 */
    private Long newEmptyDelivery(Long orderId, Long warehouseId) {
        return insertReturningId(
                "INSERT INTO delivery_order (delivery_sn, order_id, warehouse_id, status, receiver_name, "
                        + "receiver_phone, receiver_address, source) VALUES (?, ?, ?, 0, ?, ?, ?, 0)",
                "DL" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999),
                orderId, warehouseId, "乐观锁测试-原始收货人", "13700000000", "自动化测试地址（跑完即删）");
    }

    /** 一条待处理的退货申请（return_apply 表没有 version 列，并发靠「WHERE status=0」的条件更新兜住） */
    private Long newReturnApply(Long orderId, Long applicantId) {
        Long reasonId = jdbc.queryForObject("SELECT id FROM return_reason ORDER BY id LIMIT 1", Long.class);
        return insertReturningId(
                "INSERT INTO return_apply (order_id, member_id, return_reason, description, status, reason_id) "
                        + "VALUES (?, ?, '并发测试-质量问题', '跑完即删', 0, ?)",
                orderId, applicantId, reasonId);
    }

    /**
     * 注册一个买家并拿到 C 端令牌：`POST /portal/auth/register` 成功即自动登录，
     * 返回值同时带 memberId 与 accessToken —— 支付/确认收货/退货这些接口都要求「本人订单」。
     */
    private PortalSession registerPortalMember() {
        String username = "optlock-buyer-" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"乐观锁测试买家\"}";
        ResponseEntity<JsonNode> resp = rest.postForEntity("/portal/auth/register", jsonEntity(json), JsonNode.class);
        JsonNode data = ok(resp);
        Long id = data.path("memberId").asLong();
        createdMembers.add(id);
        return new PortalSession(id, data.path("accessToken").asText());
    }

    private Long newMember(String usernamePrefix) {
        String json = "{\"username\":\"" + usernamePrefix + "-" + System.currentTimeMillis() + "\","
                + "\"nickname\":\"乐观锁测试会员\",\"status\":1}";
        JsonNode data = ok(post("/admin/members", json));
        Long id = data.path("id").asLong();
        createdMembers.add(id);
        return id;
    }

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

    // ------------------------------------------------------------------ 请求体

    private static String productBody(String name, Integer version) {
        return "{\"product\":{\"name\":\"" + name + "\"" + (version == null ? "" : ",\"version\":" + version) + "}}";
    }

    private static String skuBody(Long productId, String price, int stock, Integer version) {
        return "{\"sku\":{\"productId\":" + productId + ",\"price\":" + price + ",\"stock\":" + stock
                + (version == null ? "" : ",\"version\":" + version) + "}}";
    }

    /** 后台建单请求体：两条明细（skuA / skuB 各 quantity 件），总金额由后端按快照价重算；state=7 待审核便于接着走审核不通过 */
    private String createOrderBody(Long productId, Long skuA, Long skuB, int quantity) {
        return "{\"order\":{\"orderSn\":\"ADM" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(1000, 9999) + "\""
                + ",\"memberId\":" + memberId + ",\"status\":7,\"payType\":1"
                + ",\"receiverName\":\"后台建单用例\",\"receiverPhone\":\"13700000000\""
                + ",\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0}"
                + ",\"items\":["
                + "{\"productId\":" + productId + ",\"productSkuId\":" + skuA
                + ",\"productName\":\"后台建单用例-规格A\",\"productPrice\":12.00,\"productQuantity\":" + quantity + "},"
                + "{\"productId\":" + productId + ",\"productSkuId\":" + skuB
                + ",\"productName\":\"后台建单用例-规格B\",\"productPrice\":13.00,\"productQuantity\":" + quantity + "}"
                + "]}";
    }

    private static String orderBody(String receiverName, Integer version) {
        return "{\"receiverName\":\"" + receiverName + "\""
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    private static String memberBody(String nickname, Integer version) {
        return "{\"nickname\":\"" + nickname + "\"" + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    private static String deliveryBody(Long warehouseId, Integer version) {
        return "{\"warehouseId\":" + warehouseId
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    // ------------------------------------------------------------------ 状态查询

    private int versionOf(String table, Long id) {
        Integer v = jdbc.queryForObject("SELECT version FROM " + table + " WHERE id = ?", Integer.class, id);
        assertThat(v).as("%s#%d 必须有 version 列（迁移没跑？）", table, id).isNotNull();
        return v;
    }

    private BigDecimal priceOf(Long skuId) {
        return jdbc.queryForObject("SELECT price FROM sku WHERE id = ?", BigDecimal.class, skuId);
    }

    private int stockOf(Long skuId) {
        // 先按 SUM(sku_bucket.stock) 刷新展示缓存，再读 —— 断言的是权威库存，不是可能滞后的缓存
        stockStore.refreshDisplayCache(skuId);
        return jdbc.queryForObject("SELECT stock FROM sku WHERE id = ?", Integer.class, skuId);
    }

    private String receiverOf(Long orderId) {
        return jdbc.queryForObject("SELECT receiver_name FROM `order` WHERE id = ?", String.class, orderId);
    }

    private String nicknameOf(Long memberId) {
        return jdbc.queryForObject("SELECT nickname FROM member WHERE id = ?", String.class, memberId);
    }

    private Long warehouseOf(Long deliveryId) {
        return jdbc.queryForObject("SELECT warehouse_id FROM delivery_order WHERE id = ?", Long.class, deliveryId);
    }

    private int orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private int orderSplitStatus(Long orderId) {
        return jdbc.queryForObject("SELECT split_status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private int deliveryStatus(Long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
    }

    /** 该订单下有几张发货单 —— 「有没有多生成发货单」就靠它 */
    private int deliveryCount(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE order_id = ?", Integer.class, orderId);
    }

    private int deliveryItemCount(Long deliveryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_item WHERE delivery_id = ?", Integer.class, deliveryId);
    }

    private String orderSnOf(Long orderId) {
        return jdbc.queryForObject("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId);
    }

    /** 该订单在 payment_record 里共几条流水（对账键 = order_sn，表里没有 order_id 外键） */
    private int paymentCount(String orderSn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);
    }

    /** 该订单某类流水几条：type 1=支付 2=退款 */
    private int paymentCountByType(String orderSn, int type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ? AND type = ?",
                Integer.class, orderSn, type);
    }

    private int returnApplyStatusOf(Long applyId) {
        return jdbc.queryForObject("SELECT status FROM return_apply WHERE id = ?", Integer.class, applyId);
    }

    private Long deliveryItemIdOf(Long deliveryId) {
        return jdbc.queryForObject("SELECT id FROM delivery_item WHERE delivery_id = ? ORDER BY id LIMIT 1",
                Long.class, deliveryId);
    }

    private int deliveryItemQuantity(Long deliveryItemId) {
        return jdbc.queryForObject("SELECT quantity FROM delivery_item WHERE id = ?", Integer.class, deliveryItemId);
    }

    /** 该订单所有包裹上的明细件数总和 —— 「货有没有凭空多出来」就看它 */
    private int totalItemQuantityOf(Long orderId) {
        Integer total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(di.quantity),0) FROM delivery_item di "
                        + "JOIN delivery_order d ON d.id = di.delivery_id WHERE d.order_id = ?", Integer.class, orderId);
        return total == null ? 0 : total;
    }

    private boolean deliveryExists(Long deliveryId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
        return n != null && n > 0;
    }

    private int logisticsCount(Long deliveryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_logistics WHERE delivery_id = ?",
                Integer.class, deliveryId);
    }

    private static JsonNode findById(JsonNode array, Long id) {
        assertThat(array.isArray()).as("期望数组，实际：%s", array).isTrue();
        for (JsonNode node : array) {
            if (node.path("id").asLong() == id) return node;
        }
        throw new AssertionError("列表里找不到 id=" + id + "： " + array);
    }

    // ------------------------------------------------------------------ 造并发窗口（确定性）

    /*
     * 「读 →（别人写）→ 写」这个窗口怎么**确定性**地造出来：
     *
     *   ① holdRow(...)：在一条独立连接上开事务，改掉目标行（version + 1 / 删一行）但**不提交** ——
     *      行锁攥在手里。
     *   ② 被测 HTTP 请求在另一个线程里发出：它读的时候用的是 MVCC 快照（看不到未提交的改动），
     *      所以守卫、状态判断全部按「旧数据」通过；接着它自己的 UPDATE 要向这一行申请锁 → 卡住。
     *   ③ awaitRowLockWait()：在 performance_schema.data_lock_waits 里确认「真的有人在等锁」，
     *      说明被测请求已经读完、卡在 UPDATE 上了（这一步是关键 —— 有了它就不需要 sleep 赌时序）。
     *   ④ commitAndClose()：别人提交。被测请求的 UPDATE 随即执行，
     *      但 version 已经变了（或行已经没了）→ **影响 0 行** → 我们新加的检查抛 409。
     */

    /** 改掉一行并攥住行锁（不提交）。跑完必须 {@link #commitAndClose(Connection)} */
    private Connection holdRow(String sql, Object... args) {
        try {
            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                assertThat(ps.executeUpdate()).as("造并发：这条 SQL 必须改到 1 行：%s", sql).isEqualTo(1);
            }
            return conn;
        } catch (SQLException e) {
            throw new IllegalStateException("造并发窗口失败：" + sql, e);
        }
    }

    private static void commitAndClose(Connection conn) {
        if (conn == null) return;
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("提交「别人抢先的写」失败", e);
        } finally {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 关连接失败不影响断言
            }
        }
    }

    /** 等到被测请求真的卡在行锁上（20s 内没等到就判定「窗口没造出来」，免得静默通过） */
    private void awaitRowLockWait() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Integer.class);
            if (waiting != null && waiting > 0) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("并发窗口没造出来：20s 内没观察到行锁等待（被测请求没进来，或者根本没写库）");
    }

    private <T> Future<T> async(Callable<T> task) {
        return pool.submit(task);
    }

    private static JsonNode bodyOf(Future<ResponseEntity<JsonNode>> future) {
        try {
            ResponseEntity<JsonNode> resp = future.get(60, TimeUnit.SECONDS);
            assertThat(resp.getBody()).as("并发请求必须有响应").isNotNull();
            return resp.getBody();
        } catch (Exception e) {
            throw new IllegalStateException("等异步请求结果失败", e);
        }
    }

    // ------------------------------------------------------------------ HTTP 辅助

    /** code==0 断言，返回 data */
    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    /**
     * 指定 code 的失败断言（**同时校验真实 HTTP 状态码**），返回整个 body（含 message）。
     *
     * <p>为什么 HTTP 状态码也要断言：如果只写 {@code return Result.fail(409, …)}，Spring 会包成
     * HTTP 200 + body.code=409 —— 只看 HTTP 的调用方（curl 脚本/监控/网关日志/第三方）会把冲突记成成功。
     * 所以这里要求两者一致。
     */
    private JsonNode fail(ResponseEntity<JsonNode> resp, int expectedCode) {
        assertThat(resp.getStatusCode().value())
                .as("必须是真 HTTP %d（不是 HTTP 200 + body.code=%d）：%s",
                        expectedCode, expectedCode, resp.getBody())
                .isEqualTo(expectedCode);
        return failBody(resp.getBody(), expectedCode);
    }

    /** 同上，但直接给 body（并发用例里拿到的就是 body） */
    private JsonNode failBody(JsonNode node, int expectedCode) {
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望 code=%d，实际：%s", expectedCode, node).isEqualTo(expectedCode);
        assertThat(node.path("message").asText()).as("被拒时必须有中文原因").isNotBlank();
        return node;
    }

    private ResponseEntity<JsonNode> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    private ResponseEntity<JsonNode> post(String path, String json) {
        return exchange(HttpMethod.POST, path, json);
    }

    private ResponseEntity<JsonNode> delete(String path, String json) {
        return exchange(HttpMethod.DELETE, path, json);
    }

    /** 用**买家令牌**打 C 端接口（支付/确认收货只能本人操作） */
    private ResponseEntity<JsonNode> postWithToken(String path, String token) {
        return exchangeWithToken(HttpMethod.POST, path, null, token);
    }

    private ResponseEntity<JsonNode> exchangeWithToken(HttpMethod method, String path, String json, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private static HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    /** 并发用例要同时断言「body 的 code」和「真 HTTP 状态码」，所以得留着整个响应 */
    private static ResponseEntity<JsonNode> responseOf(Future<ResponseEntity<JsonNode>> future) {
        try {
            ResponseEntity<JsonNode> resp = future.get(60, TimeUnit.SECONDS);
            assertThat(resp.getBody()).as("并发请求必须有响应").isNotNull();
            return resp;
        } catch (Exception e) {
            throw new IllegalStateException("等异步请求结果失败", e);
        }
    }

    /** 买家的令牌 + 会员 id（C 端接口要求「本人订单」） */
    private record PortalSession(Long memberId, String jwt) {
    }

    private ResponseEntity<JsonNode> put(String path, String json) {
        return exchange(HttpMethod.PUT, path, json);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }
}
