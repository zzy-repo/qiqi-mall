package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
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

    private String adminJwt;
    private Long memberId;          // 种子会员：只当作订单的归属，不改它
    private Long categoryId;
    private Long typeId;
    private Long warehouseA;
    private Long warehouseB;

    @Autowired
    private MockWmsService mockWms;      // 为了数「WMS 到底被外呼了几次」

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
        jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", stockBefore, skuId);
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
        jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", stockBefore, skuId);
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

        jdbc.update("UPDATE sku SET stock = ? WHERE id = ?", stockBefore, skuId);
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
