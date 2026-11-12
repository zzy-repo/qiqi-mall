package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.oms.service.ConfirmCodeService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.order.mapper.OrderItemMapper;
import com.qiqi.mall.order.mapper.OrderMapper;
import com.qiqi.mall.shared.security.KvStore;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作确认码（confirmCode）下沉到后端的集成测试：真起一个后端 + 真打 HTTP + 真连库。
 *
 * <p>为什么是后端集成测试（照 {@code ApiKeyAuthIntegrationTest} 的写法）：
 * 这次要塞住的口子正是「绕过 MCP 直接 curl 后端」，所以必须在后端这一层验证 ——
 * 真 HTTP 请求 + 真 KV + 真数据库，才能证明「不给码就写不进去」。
 *
 * <p>覆盖 10 条用例（编号与验收清单一致）：
 * ① 预览返回 confirmCode（36 位 UUID）+ expiresInSec=300（顺带做指纹稳定性自检）
 * ② 不带 confirmCode → 中文错误「缺少确认码」
 * ③ 假码 → 「确认码无效…」
 * ④ 真码 → 执行成功，订单 7→8，发货单数 == 方案包裹数
 * ⑤ 同码复用 → 被拒（一次性）
 * ⑥ 预览后改 order_item.quantity → 「方案已变化」
 * ⑦ 码过期（把 KV 里的 TTL 改成 1 毫秒模拟 5 分钟到期）→ 被拒
 * ⑧ A 单的码用到 B 单 → 「确认码与订单不匹配」
 * ⑨ status != 7 的订单 → 仍返回原有守卫错误
 * ⑩ audit-reject 不需要码（跑一次 reject 不报缺码）
 *
 * <p>数据自建：测试只复用目录域里「已有 SKU + 已有分仓库存」的只读事实，
 * 订单/明细全部自己插、跑完全部删（order 级联清掉 order_item / delivery_order / delivery_item）。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=OrderConfirmCodeIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderConfirmCodeIntegrationTest {

    /** 36 位带横线 UUID（ConfirmCodeService 签发格式） */
    private static final Pattern UUID36 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final String MSG_NO_CODE = "缺少确认码：请先预览拆单方案";
    private static final String MSG_BAD_CODE = "确认码无效、已使用或已过期（5 分钟），请重新预览拆单方案";
    private static final String MSG_WRONG_ORDER = "确认码与订单不匹配，请重新预览";
    private static final String MSG_PLAN_CHANGED = "拆单方案已变化（仓库或库存被改动），拒绝执行，请重新预览";
    private static final String MSG_STATUS_GUARD = "只有待审核订单可以审核通过";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    /** 测试里要「把 TTL 改成 1 毫秒」模拟过期，所以直接拿 KV */
    @Autowired
    private KvStore kvStore;

    /** 仅用于指纹稳定性自检（同一份方案换顺序/换键序必须算出同一个 hash） */
    @Autowired
    private ConfirmCodeService confirmCodeService;

    private String adminJwt;
    private Long memberId;
    private Long skuA;
    private Long skuB;
    private Long productIdA;
    private Long productIdB;
    private final List<Long> createdOrders = new ArrayList<>();
    /** ④ 用真码跑成功后留下的码与订单，给 ⑤ 验证「同码复用」 */
    private String reusedCode;
    private Long reusedOrderId;

    // ------------------------------------------------------------------ 准备

    @BeforeAll
    void prepare() {
        // 后台登录（种子账号）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", "123456");
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login", body, JsonNode.class);
        adminJwt = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(adminJwt)
                .as("admin 登录失败（种子账号 admin/123456；若被连续失败锁验证码请稍后重试）：%s", resp.getBody())
                .isNotBlank();

        memberId = jdbc.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);

        // 挑两个「发货仓不同」的 SKU（SKU 的发货仓 = 其库存最多的仓，见 SplitOrderService）
        // 这样方案至少是 2 个包裹，才能验证「发货单数 == 方案包裹数」
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id AS sku_id, s.product_id AS product_id, i.warehouse_id AS wh "
                        + "FROM sku s JOIN inventory i ON i.sku_id = s.id "
                        + "WHERE i.stock > 0 AND i.stock = (SELECT MAX(stock) FROM inventory i2 WHERE i2.sku_id = s.id) "
                        + "ORDER BY s.id");
        assertThat(rows.size()).as("库里至少要有两个有分仓库存的 SKU 才能跑本测试").isGreaterThan(1);
        Map<String, Object> first = rows.get(0);
        Map<String, Object> second = rows.stream()
                .filter(r -> !r.get("wh").equals(first.get("wh")))
                .findFirst().orElse(rows.get(1));
        skuA = ((Number) first.get("sku_id")).longValue();
        productIdA = ((Number) first.get("product_id")).longValue();
        skuB = ((Number) second.get("sku_id")).longValue();
        productIdB = ((Number) second.get("product_id")).longValue();

        System.out.printf("[准备] member=%d skuA=%d(仓%s) skuB=%d(仓%s)%n",
                memberId, skuA, first.get("wh"), skuB, second.get("wh"));
    }

    @AfterAll
    void cleanup() {
        int removed = 0;
        for (Long id : createdOrders) {
            // order 级联清 order_item / delivery_order / delivery_item
            removed += jdbc.update("DELETE FROM `order` WHERE id = ?", id);
        }
        System.out.printf("[清理] 自建订单已全部删除（%d 张）%n", removed);
    }

    // ------------------------------------------------------------------ ① 预览发码

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("① split-preview 返回 confirmCode（36 位 UUID）+ expiresInSec=300")
    void preview_issuesConfirmCode() {
        Long orderId = newPendingOrder(7, skuA, skuB);

        JsonNode data = preview(orderId);
        String code = data.path("confirmCode").asText(null);
        JsonNode plan = data.path("plan");
        System.out.printf("① POST /admin/orders/%d/split-preview → confirmCode=%s，expiresInSec=%d，包裹数=%d%n",
                orderId, code, data.path("expiresInSec").asInt(), plan.size());

        assertThat(plan.isArray()).isTrue();
        assertThat(plan.size()).as("两个不同仓的 SKU 应拆成 2 个包裹").isEqualTo(2);
        assertThat(code).as("确认码非空").isNotBlank();
        assertThat(code).as("确认码必须是 36 位带横线 UUID").matches(UUID36.pattern());
        assertThat(code).hasSize(36);
        assertThat(data.path("expiresInSec").asLong()).as("默认有效期 5 分钟").isEqualTo(300L);

        // 指纹稳定性自检：包裹顺序反过来、包裹内明细顺序反着放，hash 必须一模一样
        List<Map<String, Object>> plain = toList(plan);
        List<Map<String, Object>> shuffled = new ArrayList<>();
        for (int i = plain.size() - 1; i >= 0; i--) {
            Map<String, Object> pkg = plain.get(i);
            // 用「键序也不同」的 Map 重建，证明不依赖 JSON 库的键顺序
            Map<String, Object> rebuilt = new LinkedHashMap<>();
            rebuilt.put("items", reversed(itemsOf(pkg)));
            rebuilt.put("warehouseName", pkg.get("warehouseName"));
            rebuilt.put("warehouseId", pkg.get("warehouseId"));
            shuffled.add(rebuilt);
        }
        String h1 = confirmCodeService.hashPlan(plain);
        String h2 = confirmCodeService.hashPlan(shuffled);
        System.out.printf("   指纹稳定性自检：hash(原序)=%s… hash(逆序+换键序)=%s… → %s%n",
                h1.substring(0, 12), h2.substring(0, 12), h1.equals(h2) ? "一致" : "不一致");
        assertThat(h2).as("同一份方案换顺序/换键序必须算出同一个指纹").isEqualTo(h1);
    }

    // ------------------------------------------------------------------ ② 缺码

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("② 不带 confirmCode 调 audit-pass → 「缺少确认码」")
    void auditPass_withoutCode_rejected() {
        Long orderId = newPendingOrder(7, skuA, skuB);

        JsonNode body = auditPass(orderId, null);
        System.out.printf("② POST /admin/orders/%d/audit-pass（body={}）→ code=%d，message=%s，订单状态仍=%d%n",
                orderId, body.path("code").asInt(), body.path("message").asText(), statusOf(orderId));

        assertThat(body.path("code").asInt()).isEqualTo(1);
        assertThat(body.path("message").asText()).isEqualTo(MSG_NO_CODE);
        assertThat(statusOf(orderId)).as("被拒时不能改状态").isEqualTo(7);
    }

    // ------------------------------------------------------------------ ③ 假码

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("③ 假码 → 「确认码无效、已使用或已过期」")
    void auditPass_fakeCode_rejected() {
        Long orderId = newPendingOrder(7, skuA, skuB);

        JsonNode body = auditPass(orderId, "11111111-2222-3333-4444-555555555555");
        System.out.printf("③ POST /admin/orders/%d/audit-pass（假码）→ code=%d，message=%s%n",
                orderId, body.path("code").asInt(), body.path("message").asText());

        assertThat(body.path("code").asInt()).isEqualTo(1);
        assertThat(body.path("message").asText()).isEqualTo(MSG_BAD_CODE);
        assertThat(statusOf(orderId)).isEqualTo(7);
    }

    // ------------------------------------------------------------------ ④ 真码执行

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("④ 真码 → 执行成功，订单 7→8，发货单数 == 方案包裹数")
    void auditPass_realCode_executes() {
        Long orderId = newPendingOrder(7, skuA, skuB);
        JsonNode preview = preview(orderId);
        String code = preview.path("confirmCode").asText();
        int packages = preview.path("plan").size();

        ResponseEntity<JsonNode> http = call(HttpMethod.POST, "/admin/orders/" + orderId + "/audit-pass",
                "{\"confirmCode\":\"" + code + "\"}", adminJwt);
        JsonNode body = http.getBody();
        int deliveries = body.path("data").isArray() ? body.path("data").size() : -1;
        System.out.printf("④ 预览 %d 个包裹 → audit-pass(真码) → HTTP %d，code=%d，生成发货单=%d，订单状态=%d%n",
                packages, http.getStatusCode().value(), body.path("code").asInt(), deliveries, statusOf(orderId));

        assertThat(http.getStatusCode().value()).isEqualTo(200);
        assertThat(body.path("code").asInt()).as("真码必须放行：%s", body).isZero();
        assertThat(statusOf(orderId)).as("审核通过后 7 → 8").isEqualTo(8);
        assertThat(deliveries).as("发货单数必须等于方案包裹数").isEqualTo(packages);
        assertThat(jdbc.queryForObject("SELECT split_status FROM `order` WHERE id = ?", Integer.class, orderId))
                .as("拆单标记置 1").isEqualTo(1);
        // 顺带留下这张单的码，给 ⑤ 复用同一张码
        reusedCode = code;
        reusedOrderId = orderId;
    }

    // ------------------------------------------------------------------ ⑤ 一次性

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("⑤ 同码复用 → 被拒（一次性）")
    void auditPass_sameCodeTwice_rejected() {
        assertThat(reusedCode).as("④ 必须先把码跑出来").isNotBlank();

        JsonNode body = auditPass(reusedOrderId, reusedCode);
        System.out.printf("⑤ 用④里同一个码再调一次 audit-pass → code=%d，message=%s%n",
                body.path("code").asInt(), body.path("message").asText());

        assertThat(body.path("code").asInt()).isEqualTo(1);
        assertThat(body.path("message").asText()).isEqualTo(MSG_BAD_CODE);
        assertThat(statusOf(reusedOrderId)).as("第二次不能重复执行").isEqualTo(8);
    }

    // ------------------------------------------------------------------ ⑥ 方案变化

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("⑥ 预览后改 order_item.quantity → 「方案已变化」")
    void auditPass_planChanged_rejected() {
        Long orderId = newPendingOrder(7, skuA, skuB);
        String code = preview(orderId).path("confirmCode").asText();

        // 预览之后改明细数量（等价于「仓库/库存被改动」导致方案不可复现）
        int changed = jdbc.update("UPDATE order_item SET product_quantity = product_quantity + 3 WHERE order_id = ?", orderId);
        assertThat(changed).isGreaterThan(0);

        JsonNode body = auditPass(orderId, code);
        System.out.printf("⑥ 预览后改 %d 条明细数量 → audit-pass → code=%d，message=%s，订单状态=%d%n",
                changed, body.path("code").asInt(), body.path("message").asText(), statusOf(orderId));

        assertThat(body.path("code").asInt()).isEqualTo(1);
        assertThat(body.path("message").asText()).isEqualTo(MSG_PLAN_CHANGED);
        assertThat(statusOf(orderId)).as("方案变了就不能执行").isEqualTo(7);
    }

    // ------------------------------------------------------------------ ⑦ 过期

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("⑦ 码过期（KV 里 TTL 改成 1 毫秒）→ 被拒「已过期」")
    void auditPass_expiredCode_rejected() {
        Long orderId = newPendingOrder(7, skuA, skuB);
        String code = preview(orderId).path("confirmCode").asText();

        // 把这条码的 TTL 改成 1 毫秒（KV 档位无关：memory 靠 expireAt 判定，redis 靠自身过期）
        kvStore.put("audit:code:" + code, orderId + "|expired-by-test", Duration.ofMillis(1));
        sleep(50);

        JsonNode body = auditPass(orderId, code);
        String message = body.path("message").asText();
        System.out.printf("⑦ 把 KV 里该码的 TTL 改成 1 毫秒并等 50ms → audit-pass → code=%d，message=%s%n",
                body.path("code").asInt(), message);

        assertThat(body.path("code").asInt()).isEqualTo(1);
        assertThat(message).as("过期与「已使用」共用同一条兜底文案（都是取不出来）").isEqualTo(MSG_BAD_CODE);
        assertThat(message).contains("已过期");
        assertThat(statusOf(orderId)).isEqualTo(7);
    }

    // ------------------------------------------------------------------ ⑧ 码与订单不匹配

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("⑧ A 单的码用到 B 单 → 「确认码与订单不匹配」")
    void auditPass_codeOfAnotherOrder_rejected() {
        Long orderA = newPendingOrder(7, skuA, skuB);
        Long orderB = newPendingOrder(7, skuA, skuB);
        String codeOfA = preview(orderA).path("confirmCode").asText();

        JsonNode body = auditPass(orderB, codeOfA);
        System.out.printf("⑧ 订单 %d 的码用去审订单 %d → code=%d，message=%s（B 状态仍=%d）%n",
                orderA, orderB, body.path("code").asInt(), body.path("message").asText(), statusOf(orderB));

        assertThat(body.path("code").asInt()).isEqualTo(1);
        assertThat(body.path("message").asText()).isEqualTo(MSG_WRONG_ORDER);
        assertThat(statusOf(orderB)).isEqualTo(7);
        assertThat(statusOf(orderA)).as("A 单自己没被审").isEqualTo(7);
    }

    // ------------------------------------------------------------------ ⑨ 状态守卫回归

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("⑨ status != 7 的订单 → 仍返回原有守卫错误")
    void auditPass_statusGuardStillWorks() {
        Long orderId = newPendingOrder(7, skuA, skuB);
        String code = preview(orderId).path("confirmCode").asText();

        // 模拟「预览之后别人先把这单处理掉了」：状态直接改成已付款(1)
        jdbc.update("UPDATE `order` SET status = 1 WHERE id = ?", orderId);

        JsonNode body = auditPass(orderId, code);
        System.out.printf("⑨ 预览后把订单状态改成 1 → 用真码 audit-pass → code=%d，message=%s%n",
                body.path("code").asInt(), body.path("message").asText());

        assertThat(body.path("code").asInt()).isEqualTo(1);
        assertThat(body.path("message").asText())
                .as("码对了也要过原有 status 守卫（且报的是状态原因，不是方案原因）")
                .isEqualTo(MSG_STATUS_GUARD);
        assertThat(statusOf(orderId)).as("守卫拦下后状态不变").isEqualTo(1);
    }

    // ------------------------------------------------------------------ ⑩ reject 不要码

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("⑩ audit-reject 不需要确认码（不报缺码）")
    void auditReject_doesNotNeedCode() {
        Long orderId = newPendingOrder(7, skuA, skuB);

        ResponseEntity<JsonNode> http = call(HttpMethod.POST, "/admin/orders/" + orderId + "/audit-reject", null, adminJwt);
        JsonNode body = http.getBody();
        System.out.printf("⑩ POST /admin/orders/%d/audit-reject（不带码）→ HTTP %d，code=%d，message=%s，订单状态=%d%n",
                orderId, http.getStatusCode().value(), body.path("code").asInt(),
                body.path("message").asText(), statusOf(orderId));

        assertThat(http.getStatusCode().value()).isEqualTo(200);
        assertThat(body.path("code").asInt()).as("reject 不该被确认码拦住：%s", body).isZero();
        assertThat(body.path("message").asText()).doesNotContain("确认码");
        assertThat(statusOf(orderId)).as("审核不通过 → 已关闭(4)").isEqualTo(4);
    }

    // ------------------------------------------------------------------ 辅助

    /** 自建一张待审核订单（status 可指定），返回订单 id；跑完由 @AfterAll 删除 */
    private Long newPendingOrder(int status, Long... skus) {
        Order order = new Order();
        order.setOrderSn("CC" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999));
        order.setMemberId(memberId);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setStatus(status);
        order.setPayType(0);
        order.setReceiverName("确认码测试");
        order.setReceiverPhone("13700000000");
        order.setReceiverAddress("自动化测试地址（跑完即删）");
        order.setSplitStatus(0);
        orderMapper.insert(order);
        createdOrders.add(order.getId());

        Long[] productIds = {productIdA, productIdB};
        for (int i = 0; i < skus.length; i++) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(productIds[Math.min(i, productIds.length - 1)]);
            item.setProductSkuId(skus[i]);
            item.setProductName("确认码测试商品SKU" + skus[i]);
            item.setProductPrice(new BigDecimal("1.00"));
            item.setProductQuantity(1 + i);
            orderItemMapper.insert(item);
        }
        return order.getId();
    }

    private JsonNode preview(Long orderId) {
        ResponseEntity<JsonNode> http = call(HttpMethod.POST, "/admin/orders/" + orderId + "/split-preview", null, adminJwt);
        JsonNode body = http.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asInt()).as("预览失败：%s", body).isZero();
        return body.path("data");
    }

    /** 调 audit-pass；code 传 null 表示「不传确认码」的请求体 {} */
    private JsonNode auditPass(Long orderId, String code) {
        String body = code == null ? "{}" : "{\"confirmCode\":\"" + code + "\"}";
        ResponseEntity<JsonNode> http = call(HttpMethod.POST, "/admin/orders/" + orderId + "/audit-pass", body, adminJwt);
        assertThat(http.getBody()).isNotNull();
        return http.getBody();
    }

    private int statusOf(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String jsonBody, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) headers.setBearerAuth(jwt);
        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
        return rest.exchange(path, method, entity, JsonNode.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toList(JsonNode array) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode node : array) {
            Map<String, Object> pkg = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (e.getKey().equals("items")) {
                    List<Map<String, Object>> lines = new ArrayList<>();
                    for (JsonNode line : e.getValue()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        line.fields().forEachRemaining(le -> row.put(le.getKey(), le.getValue().isNumber()
                                ? le.getValue().numberValue() : le.getValue().asText()));
                        lines.add(row);
                    }
                    pkg.put("items", lines);
                } else {
                    pkg.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText());
                }
            });
            out.add(pkg);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> pkg) {
        return (List<Map<String, Object>>) pkg.get("items");
    }

    private static List<Map<String, Object>> reversed(List<Map<String, Object>> lines) {
        List<Map<String, Object>> out = new ArrayList<>(lines);
        java.util.Collections.reverse(out);
        return out;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
