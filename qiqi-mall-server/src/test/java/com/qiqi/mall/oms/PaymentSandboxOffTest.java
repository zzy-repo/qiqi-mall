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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付沙箱端点的**关闸**形态：{@code qiqi.payment.sandbox-endpoints-enabled=false} 时，
 * 渠道回调重放一律拒绝。
 *
 * <h3>为什么这条必须单独一个上下文</h3>
 * dev / test 的 yml 里这个开关是 {@code true}（e2e 要能调它），所以「关着会怎样」只能在
 * 测试里显式覆盖配置来验 —— 否则这个开关的关闭分支**永远没有人跑过**，
 * 而生产跑的恰恰就是那个分支。「默认 false + 从没被验证过」＝ 等于没有。
 *
 * <h3>断言的是三件事，缺一条都不算数</h3>
 * <ol>
 *   <li>拒绝要**可读**（code≠0 + 文案里说明「只在非生产环境开放」），不是 404 也不是静默成功；</li>
 *   <li>拒绝发生在**任何写库动作之前**：库里那条流水一行都没动；</li>
 *   <li>拒绝与「流水是否存在」无关（先用一个不存在的 id 试，也被同一句文案挡住）——
 *       不然开关就变成了「能探测哪些 id 存在」的探针。</li>
 * </ol>
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=PaymentSandboxOffTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qiqi.payment.sandbox-endpoints-enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentSandboxOffTest {

    /** admin 口令：默认种子值；改过口令（scripts/set-admin-password.sh）时用环境变量 ADMIN_PASSWORD
     *  覆盖 —— 与 e2e 侧（e2e/tests/support/ui.js: process.env.ADMIN_PASSWORD || '123456'）同一口径 */
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "123456");

    private static final String SN_PREFIX = "PAYCBOFF";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminJwt;
    private String orderSn;
    private Long payId;

    @BeforeAll
    void prepare() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", ADMIN_PASSWORD);
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login", body, JsonNode.class);
        adminJwt = resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(adminJwt).as("admin 登录失败：%s", resp.getBody()).isNotBlank();

        // payment_record 与 `order` **之间没有外键**（列注释写明 order_id 允许悬空），
        // 所以这里可以只造一条流水、不造订单 —— 顺带把这条口径在测试里固定下来。
        orderSn = SN_PREFIX + System.currentTimeMillis();
        jdbc.update("INSERT INTO payment_record (order_sn, amount, type, channel, trade_no, idem_key) "
                + "VALUES (?, 10.00, 1, 1, ?, ?)", orderSn, "OFF-" + orderSn, orderSn + ":PAY");
        payId = jdbc.queryForObject("SELECT id FROM payment_record WHERE order_sn = ?", Long.class, orderSn);
    }

    @AfterAll
    void cleanup() {
        int rows = jdbc.update("DELETE FROM payment_record WHERE order_sn LIKE ?", SN_PREFIX + "%");
        System.out.printf("[清理] 关闸用例自造的流水 %d 行已删除%n", rows);
    }

    @Test
    @DisplayName("开关关 → 重放被拒（code≠0 且文案说明只在非生产开放），库里一行都没动；不存在的 id 同样被同一句话挡住")
    void sandboxOff_replayRejectedBeforeAnyWrite() {
        int before = countRows();

        JsonNode onExisting = post("/admin/payments/" + payId + "/replay-channel-callback");
        JsonNode onMissing = post("/admin/payments/99999999/replay-channel-callback");
        System.out.printf("关闸：已存在的 id → code=%d message=%s；不存在的 id → code=%d message=%s%n",
                onExisting.path("code").asInt(), onExisting.path("message").asText(),
                onMissing.path("code").asInt(), onMissing.path("message").asText());

        for (JsonNode body : new JsonNode[]{onExisting, onMissing}) {
            assertThat(body.path("code").asInt()).as("关闸时必须是可读的业务失败（不是 code=0）").isNotZero();
            assertThat(body.path("message").asText())
                    .as("文案要说清「为什么调不通」：只在非生产环境开放")
                    .contains("非生产环境开放")
                    .contains("sandbox-endpoints-enabled=false");
        }
        assertThat(onExisting.path("message").asText())
                .as("关闸判断必须发生在查流水之前：否则这个开关就成了「哪些 id 存在」的探针")
                .isEqualTo(onMissing.path("message").asText());

        assertThat(countRows()).as("关闸时不许有任何写库动作（含新增与改写）").isEqualTo(before);
        assertThat(countRows()).isEqualTo(1);
    }

    private int countRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);
        return n == null ? 0 : n;
    }

    private JsonNode post(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        ResponseEntity<JsonNode> resp = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers), JsonNode.class);
        assertThat(resp.getBody()).as("端点必须有响应体（不是 404 白页）").isNotNull();
        return resp.getBody();
    }
}
