package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 另有 ⑤ 列表/详情接口都返回 version（前端要拿它回传），⑥ 见上面 5 个资源。
 * ⑦ 回归：{@code ConfirmGateIntegrationTest}(18) + {@code OrderConfirmCodeIntegrationTest}(10)
 * + {@code ApiKeyAuthIntegrationTest}(10) 必须仍全绿 —— 另一个命令跑，见 README「乐观锁」一节。
 *
 * <p>数据自建（商品/SKU/订单/发货单/会员全部自己造，跑完全删），不复用种子里的可变数据。
 * 发货单的 PUT 还要过确认码闸门，所以这里走「真预演拿码 → 带码 + 带 version 执行」的完整链路，
 * 顺带证明 version 校验没有抢到码校验前面（缺码时仍先报缺码）。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OptimisticLockIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OptimisticLockIntegrationTest {

    static final String MSG_MISSING_VERSION = "缺少版本号，请刷新页面后重试";
    static final String MSG_CONFLICT = "数据已被他人修改（可能其他人在编辑），请刷新后重试";

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

    private final List<Long> createdProducts = new ArrayList<>();
    private final List<Long> createdOrders = new ArrayList<>();
    private final List<Long> createdMembers = new ArrayList<>();

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

        System.out.printf("[准备] member=%d category=%d type=%d 仓=%d/%d%n",
                memberId, categoryId, typeId, warehouseA, warehouseB);
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : createdOrders) {
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
    @DisplayName("⑤ delivery_order：A 带 V 改仓成功 → V+1；B 用旧 V → 409 且仓没被覆盖（码顺序不被打乱）")
    void delivery_lostUpdatePrevented() {
        Long orderId = newOrder(8);
        Long id = newDelivery(orderId, warehouseA, 2);
        int v = versionOf("delivery_order", id);

        // 缺码 → 仍然先报缺码（乐观锁不许抢跑）
        JsonNode noCode = fail(put("/admin/deliveries/" + id, deliveryBody(warehouseB, v, null)), 1);
        System.out.printf("⑤ delivery 缺确认码 → message=%s%n", noCode.path("message").asText());
        assertThat(noCode.path("message").asText()).startsWith("缺少确认码");
        assertThat(warehouseOf(id)).isEqualTo(warehouseA);

        // A：预演拿码 → 带码 + 带 V 执行
        String codeA = previewDeliveryEdit(id, warehouseB);
        ok(put("/admin/deliveries/" + id, deliveryBody(warehouseB, v, codeA)));
        int vAfterA = versionOf("delivery_order", id);
        System.out.printf("⑤ delivery A 改仓 → 仓 %d→%d，version %d→%d%n", warehouseA, warehouseOf(id), v, vAfterA);
        assertThat(warehouseOf(id)).isEqualTo(warehouseB);
        assertThat(vAfterA).isEqualTo(v + 1);

        // B：重新预演拿一张新码（码是一次性的），但还拿着旧 V
        String codeB = previewDeliveryEdit(id, warehouseA);
        JsonNode conflict = fail(put("/admin/deliveries/" + id, deliveryBody(warehouseA, v, codeB)), 409);
        System.out.printf("⑤ delivery B 用旧 V=%d 改仓 → code=409 message=%s%n", v, conflict.path("message").asText());
        assertThat(conflict.path("message").asText()).isEqualTo(MSG_CONFLICT);
        assertThat(warehouseOf(id)).as("A 的改动不能被覆盖").isEqualTo(warehouseB);

        // 不带 version → 400（code 会先被消费掉，所以每次都要重新预演）
        String codeC = previewDeliveryEdit(id, warehouseA);
        JsonNode missing = fail(put("/admin/deliveries/" + id, deliveryBody(warehouseA, null, codeC)), 400);
        System.out.printf("⑤ delivery 不带 version → code=400 message=%s%n", missing.path("message").asText());
        assertThat(missing.path("message").asText()).isEqualTo(MSG_MISSING_VERSION);
        assertThat(warehouseOf(id)).isEqualTo(warehouseB);

        // 乱填 version → 409
        String codeD = previewDeliveryEdit(id, warehouseA);
        JsonNode bogus = fail(put("/admin/deliveries/" + id, deliveryBody(warehouseA, vAfterA + 99, codeD)), 409);
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

    private static String deliveryBody(Long warehouseId, Integer version, String confirmCode) {
        return "{\"warehouseId\":" + warehouseId
                + (version == null ? "" : ",\"version\":" + version)
                + (confirmCode == null ? "" : ",\"confirmCode\":\"" + confirmCode + "\"") + "}";
    }

    /** 真预演拿一张编辑包裹的确认码（码是一次性的，每执行一次就得重新拿） */
    private String previewDeliveryEdit(Long deliveryId, Long warehouseId) {
        JsonNode dry = ok(put("/admin/deliveries/" + deliveryId + "?dryRun=true",
                "{\"warehouseId\":" + warehouseId + "}"));
        String code = dry.path("confirmCode").asText();
        assertThat(code).as("预演必须签发确认码：%s", dry).isNotBlank();
        return code;
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

    private static JsonNode findById(JsonNode array, Long id) {
        assertThat(array.isArray()).as("期望数组，实际：%s", array).isTrue();
        for (JsonNode node : array) {
            if (node.path("id").asLong() == id) return node;
        }
        throw new AssertionError("列表里找不到 id=" + id + "： " + array);
    }

    // ------------------------------------------------------------------ HTTP 辅助

    /** code==0 断言，返回 data */
    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    /** 指定 code 的失败断言，返回整个 body（含 message） */
    private JsonNode fail(ResponseEntity<JsonNode> resp, int expectedCode) {
        JsonNode node = resp.getBody();
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
