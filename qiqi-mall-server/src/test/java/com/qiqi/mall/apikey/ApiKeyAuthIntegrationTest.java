package com.qiqi.mall.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.apikey.entity.AiApiKey;
import com.qiqi.mall.apikey.mapper.AiApiKeyMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 接口 API Key 体系的集成测试（真起一个后端 + 真打 HTTP + 真连库）。
 *
 * <p>为什么是「后端集成测试」而不是 mcp-server/test/api-key.mjs：
 * 本次纪律明确不碰 mcp-server/**（MCP 改造是另一条工作线，同一批文件有人在改），
 * 而 API Key 的校验发生在后端过滤器里 —— 用后端集成测试能直接把「过滤器 + SecurityConfig +
 * 表结构」一起验证，还不需要给后端加任何新依赖（spring-boot-starter-test 本来就在 pom 里）。
 *
 * <p>覆盖 10 条用例：建 key / 列表不返明文 / 有权限 200 / 缺权限 403 / 写权限通过 /
 * 删了立刻 401 / 禁用 401 / 过期 401 / 管理员接口红线 403 / 不带 key 的 JWT 照常 200。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=ApiKeyAuthIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiKeyAuthIntegrationTest {

    private static final String ALL_SCOPES_JSON =
            "[\"order:read\",\"order:write\",\"catalog:read\",\"catalog:write\","
                    + "\"member:read\",\"member:write\",\"config:read\",\"config:write\",\"dashboard:read\"]";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AiApiKeyMapper apiKeyMapper;

    private String adminJwt;
    private Long idAll;
    private String keyAll;
    private final List<Long> createdKeys = new ArrayList<>();

    // ------------------------------------------------------------------ 准备

    @BeforeAll
    void loginAsAdmin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", "123456");
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login", body, JsonNode.class);
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        adminJwt = node.path("data").path("accessToken").asText(null);
        assertThat(adminJwt)
                .as("admin 登录失败（种子账号 admin/123456；若被连续失败锁验证码请稍后重试）：%s", node)
                .isNotBlank();
        System.out.println("[准备] admin 登录成功，JWT 前缀 = " + adminJwt.substring(0, 16) + "...");
    }

    @AfterAll
    void cleanup() {
        for (Long id : createdKeys) {
            try {
                call(HttpMethod.DELETE, "/admin/api-keys/" + id, null, null, adminJwt);
            } catch (Exception ignored) {
                // 已被用例自己删掉的忽略
            }
        }
        System.out.println("[清理] 测试期间创建的 key 已全部删除");
    }

    // ------------------------------------------------------------------ ① 新增

    @Test
    @Order(1)
    @DisplayName("① 新增 key → 拿到明文（ak_ 开头，长度固定 3+64）")
    void createKey_returnsPlaintextOnce() {
        JsonNode data = createKey("测试-全权", ALL_SCOPES_JSON);
        idAll = data.path("id").asLong();
        keyAll = data.path("key").asText();

        System.out.println("① 新建 key: " + data);
        assertThat(keyAll).startsWith("ak_");
        assertThat(keyAll).hasSize(67);                                    // ak_ + 32 字节 hex
        assertThat(keyAll.substring(0, 8)).isEqualTo(data.path("keyPrefix").asText());
        assertThat(data.path("scopes")).hasSize(9);
        assertThat(data.path("expiresAt").isNull()).isTrue();               // 不传 = 永不过期
    }

    // ------------------------------------------------------------------ ② 列表

    @Test
    @Order(2)
    @DisplayName("② 列表不含明文，只有前 8 位前缀")
    void list_neverExposesPlaintext() {
        JsonNode data = call(HttpMethod.GET, "/admin/api-keys", null, null, adminJwt).getBody().path("data");
        System.out.println("② 列表（截取第一条）: " + (data.size() > 0 ? data.get(0) : data));

        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isGreaterThan(0);
        assertThat(data.toString()).doesNotContain("\"key\"").doesNotContain("\"keyHash\"");
        JsonNode mine = null;
        for (JsonNode item : data) if (item.path("id").asLong() == idAll) mine = item;
        assertThat(mine).as("列表里应该能按 id 找到刚建的 key").isNotNull();
        assertThat(mine.path("keyPrefix").asText()).isEqualTo(keyAll.substring(0, 8));
        assertThat(mine.path("scopes")).hasSize(9);
    }

    // ------------------------------------------------------------------ ③ 有权限

    @Test
    @Order(3)
    @DisplayName("③ 用 key 调 GET /admin/orders（有 order:read）→ 200")
    void readWithScope_passes() {
        ResponseEntity<JsonNode> resp = call(HttpMethod.GET, "/admin/orders", null, keyAll, null);
        System.out.println("③ GET /admin/orders → HTTP " + resp.getStatusCode().value()
                + "，body.code=" + resp.getBody().path("code").asInt());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().path("code").asInt()).isZero();

        // 顺带验证 last_used_at 打点（首次使用立即写、之后 60 秒内节流）
        AiApiKey row = apiKeyMapper.selectById(idAll);
        System.out.println("   last_used_at = " + row.getLastUsedAt());
        assertThat(row.getLastUsedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ ④ 缺权限

    @Test
    @Order(4)
    @DisplayName("④ 只含 catalog:read 的 key 调 GET /admin/orders → 403「缺少权限：order:read」")
    void missingScope_forbidden() {
        String catalogOnly = createKey("测试-只读商品", "[\"catalog:read\"]").path("key").asText();
        ResponseEntity<JsonNode> resp = call(HttpMethod.GET, "/admin/orders", null, catalogOnly, null);
        String message = resp.getBody().path("message").asText();
        System.out.println("④ GET /admin/orders（catalog:read only）→ HTTP " + resp.getStatusCode().value()
                + "，message=" + message);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(message).isEqualTo("API Key 缺少权限：order:read");

        // 同一把 key 读商品是允许的（说明拒绝的是「缺哪个权限」而不是「整个 key」）
        ResponseEntity<JsonNode> ok = call(HttpMethod.GET, "/admin/products", null, catalogOnly, null);
        System.out.println("   对照：GET /admin/products（catalog:read）→ HTTP " + ok.getStatusCode().value());
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ ⑤ 写权限

    @Test
    @Order(5)
    @DisplayName("⑤ 用 key 调 POST /admin/orders/1/audit-pass（有 order:write）→ 通过鉴权")
    void writeWithScope_passes() {
        ResponseEntity<JsonNode> resp = call(HttpMethod.POST, "/admin/orders/1/audit-pass", null, keyAll, null);
        System.out.println("⑤ POST /admin/orders/1/audit-pass → HTTP " + resp.getStatusCode().value()
                + "，body=" + resp.getBody());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // 审核通过与否取决于订单状态（订单 1 是已付款，业务守卫会拦），但网关层面必须放行：
        // 响应里不该出现任何 API Key 鉴权失败字样
        assertThat(resp.getBody().toString()).doesNotContain("API Key");

        // 再拿一次「真成功」的写权限证据：拆单方案预览是 POST（写方法 → 需 order:write）且 dry-run 不落库。
        // 用一把 status=7（待审核）的订单，返回 code=0，说明写权限链路真的通了。
        ResponseEntity<JsonNode> pending = call(HttpMethod.GET, "/admin/orders?status=7&size=1", null, keyAll, null);
        JsonNode records = pending.getBody().path("data").path("records");
        if (records.size() > 0) {
            long pendingId = records.get(0).path("id").asLong();
            ResponseEntity<JsonNode> dryRun = call(HttpMethod.POST, "/admin/orders/" + pendingId + "/split-preview",
                    null, keyAll, null);
            System.out.println("   对照（dry-run，写方法 + 不落库）：POST /admin/orders/" + pendingId
                    + "/split-preview → HTTP " + dryRun.getStatusCode().value()
                    + "，code=" + dryRun.getBody().path("code").asInt());
            assertThat(dryRun.getStatusCode().value()).isEqualTo(200);
            assertThat(dryRun.getBody().path("code").asInt()).isZero();
        } else {
            System.out.println("   对照（dry-run）：库里没有 status=7 的订单，跳过真成功对照");
        }
    }

    // ------------------------------------------------------------------ ⑥ 删除即吊销

    @Test
    @Order(6)
    @DisplayName("⑥ 删除 key → 立即再调 → 401")
    void deleteKey_revokesImmediately() {
        JsonNode created = createKey("测试-待删除", ALL_SCOPES_JSON);
        long id = created.path("id").asLong();
        String key = created.path("key").asText();
        assertThat(call(HttpMethod.GET, "/admin/orders", null, key, null).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> del = call(HttpMethod.DELETE, "/admin/api-keys/" + id, null, null, adminJwt);
        ResponseEntity<JsonNode> after = call(HttpMethod.GET, "/admin/orders", null, key, null);
        System.out.println("⑥ 删除 id=" + id + " → " + del.getBody().path("message").asText()
                + "；再用该 key 调 GET /admin/orders → HTTP " + after.getStatusCode().value()
                + "，message=" + after.getBody().path("message").asText());
        assertThat(after.getStatusCode().value()).isEqualTo(401);
        assertThat(after.getBody().path("message").asText()).isEqualTo("API Key 无效或已过期");
    }

    // ------------------------------------------------------------------ ⑦ 禁用

    @Test
    @Order(7)
    @DisplayName("⑦ 禁用 key（status=0）→ 401")
    void disabledKey_unauthorized() {
        JsonNode created = createKey("测试-待禁用", ALL_SCOPES_JSON);
        long id = created.path("id").asLong();
        String key = created.path("key").asText();
        assertThat(call(HttpMethod.GET, "/admin/orders", null, key, null).getStatusCode().value()).isEqualTo(200);

        call(HttpMethod.PUT, "/admin/api-keys/" + id, "{\"status\":0}", null, adminJwt);
        ResponseEntity<JsonNode> after = call(HttpMethod.GET, "/admin/orders", null, key, null);
        System.out.println("⑦ PUT status=0 后 GET /admin/orders → HTTP " + after.getStatusCode().value()
                + "，message=" + after.getBody().path("message").asText());
        assertThat(after.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ ⑧ 过期

    @Test
    @Order(8)
    @DisplayName("⑧ 过期 key（expires_at 设过去时间）→ 401")
    void expiredKey_unauthorized() {
        JsonNode created = createKey("测试-待过期", ALL_SCOPES_JSON);
        long id = created.path("id").asLong();
        String key = created.path("key").asText();
        assertThat(call(HttpMethod.GET, "/admin/orders", null, key, null).getStatusCode().value()).isEqualTo(200);

        call(HttpMethod.PUT, "/admin/api-keys/" + id, "{\"expiresAt\":\"2020-01-01 00:00:00\"}", null, adminJwt);
        ResponseEntity<JsonNode> after = call(HttpMethod.GET, "/admin/orders", null, key, null);
        System.out.println("⑧ PUT expiresAt=2020-01-01 后 GET /admin/orders → HTTP " + after.getStatusCode().value()
                + "，message=" + after.getBody().path("message").asText());
        assertThat(after.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ ⑨ 红线

    @Test
    @Order(9)
    @DisplayName("⑨ 用 key 调 /admin/admins → 403（红线）")
    void adminAccounts_areJwtOnly() {
        ResponseEntity<JsonNode> admins = call(HttpMethod.GET, "/admin/admins", null, keyAll, null);
        ResponseEntity<JsonNode> keys = call(HttpMethod.GET, "/admin/api-keys", null, keyAll, null);
        System.out.println("⑨ 全权 key 调 GET /admin/admins → HTTP " + admins.getStatusCode().value()
                + "，message=" + admins.getBody().path("message").asText());
        System.out.println("   全权 key 调 GET /admin/api-keys → HTTP " + keys.getStatusCode().value()
                + "，message=" + keys.getBody().path("message").asText());
        assertThat(admins.getStatusCode().value()).isEqualTo(403);
        assertThat(keys.getStatusCode().value()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ ⑩ JWT 不受影响

    @Test
    @Order(10)
    @DisplayName("⑩ 不带 X-Api-Key 的请求走原 JWT 逻辑 → 200")
    void jwtStillWorks() {
        ResponseEntity<JsonNode> withJwt = call(HttpMethod.GET, "/admin/orders", null, null, adminJwt);
        ResponseEntity<JsonNode> noAuth = call(HttpMethod.GET, "/admin/orders", null, null, null);
        ResponseEntity<JsonNode> jwtOnRedLine = call(HttpMethod.GET, "/admin/admins", null, null, adminJwt);
        System.out.println("⑩ 无 X-Api-Key + admin JWT 调 GET /admin/orders → HTTP "
                + withJwt.getStatusCode().value() + "，code=" + withJwt.getBody().path("code").asInt());
        System.out.println("   无任何凭证调 GET /admin/orders → HTTP " + noAuth.getStatusCode().value()
                + "，message=" + noAuth.getBody().path("message").asText());
        System.out.println("   admin JWT 调 GET /admin/admins（红线仍对人工开放）→ HTTP "
                + jwtOnRedLine.getStatusCode().value());
        assertThat(withJwt.getStatusCode().value()).isEqualTo(200);
        assertThat(noAuth.getStatusCode().value()).isEqualTo(401);
        assertThat(jwtOnRedLine.getStatusCode().value()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 辅助

    /** 建一把 key，返回 data 节点；自动登记以便 @AfterAll 清理 */
    private JsonNode createKey(String name, String scopesJson) {
        String body = "{\"name\":\"" + name + "\",\"scopes\":" + scopesJson + "}";
        ResponseEntity<JsonNode> resp = call(HttpMethod.POST, "/admin/api-keys", body, null, adminJwt);
        JsonNode node = resp.getBody();
        assertThat(resp.getStatusCode().value()).as("建 key 失败：%s", node).isEqualTo(200);
        assertThat(node.path("code").asInt()).as("建 key 失败：%s", node).isZero();
        createdKeys.add(node.path("data").path("id").asLong());
        return node.path("data");
    }

    /** 统一调用：X-Api-Key / Authorization 按需带，body 用 JSON 字符串 */
    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String jsonBody, String apiKey, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) headers.set("X-Api-Key", apiKey);
        if (jwt != null) headers.setBearerAuth(jwt);
        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
        return rest.exchange(path, method, entity, JsonNode.class);
    }
}
