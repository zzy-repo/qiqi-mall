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
 * 后台建单的**初始状态白名单**（{@code POST /admin/orders}）：真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要固化的缺陷</h3>
 * {@code OrderController#create} 原来把请求体里的 {@code status} <b>原样落库</b>，没有任何白名单。
 * 于是能造出任何真实流程都产不出的状态组合：「8 待发货却一张包裹都没有」「6 已退款却没有退款流水」
 * 「3 已完成却没有任何包裹」。这些组合一旦落库，所有按状态聚合的图与文档都必须为它开例外 ——
 * 它是当时最大的状态分叉口。
 *
 * <h3>现在的口径</h3>
 * 只放行**不需要任何前置事实就成立**的三种初始状态：
 * <ul>
 *   <li>{@code 0 待付款}（请求体不传 status 时的默认值）；</li>
 *   <li>{@code 1 已付款}（退货被拒且无包裹时订单会落回 1，{@code ship} 的 1→2 直发通道正是为它保留的）；</li>
 *   <li>{@code 7 待审核}（后台人工建单后走审核的标准入口）。</li>
 * </ul>
 * 其余（2/3/4/5/6/8/9）各自都需要前置事实（包裹、退款流水、退货申请、审核不通过…），
 * 一律拒单，并给出一句说清「允许什么 / 收到什么 / 该走哪条流程」的中文原因，且<b>订单一行都不落库</b>。
 *
 * <p>数据自建自清：订单/买家自己造，跑完全删；sku.stock 在 @AfterAll 还原
 * （建单即扣库存，删单回补是另一条用例的事，这里只把库存还原回原值）。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OrderCreateStatusWhitelistIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCreateStatusWhitelistIntegrationTest {

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

        // 挑一个库存充裕的 SKU（建单会真扣库存，别把种子数据扣成 0）
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
    @DisplayName("建单不传 status → 落库 0 待付款（默认口径），并把请求体里乱填的 version 抹成 0")
    void create_withoutStatus_defaultsToUnpaid() {
        String orderSn = sn("ADM-DFL");
        JsonNode data = ok(post("/admin/orders", orderBody(orderSn, null, 1, 42)));

        Long orderId = data.path("id").asLong();
        createdOrders.add(orderId);
        int status = statusOf(orderId);
        int version = versionOf(orderId);
        System.out.printf("① 不传 status：id=%d 单号=%s 库内 status=%d version=%d%n",
                orderId, orderSn, status, version);

        assertThat(status).as("不传 status 必须落成 0 待付款").isZero();
        // 「新建的对象不该带版本号」：请求体里塞 42 也没用，一律 0 版
        assertThat(version).as("建单时请求体里的 version 必须被忽略，落库恒为 0").isZero();
        assertThat(data.path("status").asInt()).as("响应里带回的也是 0").isZero();
    }

    @Test
    @DisplayName("建单 status=7 待审核 / status=1 已付款 → 放行（后台表单与 MCP 的既有用法）")
    void create_withAllowedInitialStatuses_passes() {
        // 后台表单默认选中「待审核」、e2e createOrderViaUi 一直传 7；
        // MCP 的 ship_order 用例要造「1 已付款待直发」的样本 —— 两者都必须在白名单里
        for (int wanted : new int[] {7, 1}) {
            String orderSn = sn("ADM-OK" + wanted);
            JsonNode data = ok(post("/admin/orders", orderBody(orderSn, wanted, 1, null)));
            Long orderId = data.path("id").asLong();
            createdOrders.add(orderId);
            System.out.printf("② status=%d：id=%d 单号=%s 库内 status=%d%n",
                    wanted, orderId, orderSn, statusOf(orderId));
            assertThat(statusOf(orderId)).as("status=%d 是允许的初始状态", wanted).isEqualTo(wanted);
        }
    }

    @Test
    @DisplayName("建单 status=8/3/6/4/2/5/9 → 全部被拒：中文原因说清「只允许 0/1/7」，且订单一行都不落库")
    void create_withIllegalStatuses_isRejected() {
        // 8 待发货（没有包裹）、3 已完成（没有包裹）、6 已退款（没有退款流水）、
        // 4 已关闭（只能由审核不通过产生）、2 已发货、5 退款中、9 部分发货 —— 全都要前置事实
        int[] illegal = {8, 3, 6, 4, 2, 5, 9};
        for (int status : illegal) {
            String orderSn = sn("ADM-BAD" + status);
            JsonNode body = fail(post("/admin/orders", orderBody(orderSn, status, 1, null)));
            String message = body.path("message").asText("");
            int rows = orderCountOf(orderSn);
            System.out.printf("③ status=%d → code=%s 订单行数=%d 原因=%s%n",
                    status, body.path("code").asInt(), rows, message.replace("\n", " / "));

            assertThat(rows).as("被拒的建单不许落库（status=%d）", status).isZero();
            assertThat(message).as("中文原因要说清白名单（status=%d）", status)
                    .contains("建单只允许").contains("作为初始状态")
                    .contains("必须由业务流程流转产生");
            // 具体状态名也要点到，别让调用方猜自己传的是什么
            assertThat(message).as("原因里要写出收到的那个状态（status=%d）", status)
                    .contains("收到「" + statusTextOf(status) + "」");
        }
    }

    // ================================================================== 数据构造

    /** 建单请求体：{order:{orderSn, memberId, status, ...}, items:[1 条]}。status=null 时**不带**这个字段 */
    private String orderBody(String orderSn, Integer status, int quantity, Integer version) {
        String statusPart = status == null ? "" : ",\"status\":" + status;
        String versionPart = version == null ? "" : ",\"version\":" + version;
        return "{\"order\":{\"orderSn\":\"" + orderSn + "\",\"memberId\":" + memberId + statusPart
                + ",\"payType\":1,\"receiverName\":\"建单状态白名单用例\",\"receiverPhone\":\"13700000000\""
                + ",\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0" + versionPart + "},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"建单状态白名单用例-明细\",\"productPrice\":10.00,\"productQuantity\":"
                + quantity + "}]}";
    }

    private Long registerBuyer() {
        String username = "status-buyer-" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"建单状态用例买家\"}";
        ResponseEntity<JsonNode> resp = rest.postForEntity("/portal/auth/register", jsonEntity(json), JsonNode.class);
        Long id = ok(resp).path("memberId").asLong();
        return id;
    }

    private static String sn(String prefix) {
        return prefix + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    /** 状态码 → 中文名（与 OrderController.STATUS_TEXT 同口径；断言里用来点名「收到的是哪个状态」） */
    private static String statusTextOf(int status) {
        String[] text = {"待付款", "已付款", "已发货", "已完成", "已关闭", "退款中", "已退款", "待审核", "待发货", "部分发货"};
        return status >= 0 && status < text.length ? text[status] : String.valueOf(status);
    }

    // ------------------------------------------------------------------ 状态查询

    private int statusOf(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private int versionOf(Long orderId) {
        return jdbc.queryForObject("SELECT version FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private int orderCountOf(String orderSn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE order_sn = ?", Integer.class, orderSn);
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    /** 期望被拒：返回整个 body（含 message），并断言 code != 0 */
    private JsonNode fail(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望被拒，实际：%s", node).isNotZero();
        return node;
    }

    private ResponseEntity<JsonNode> post(String path, String json) {
        return rest.exchange(path, HttpMethod.POST, jsonEntity(json), JsonNode.class);
    }

    private HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return new HttpEntity<>(json, headers);
    }
}
