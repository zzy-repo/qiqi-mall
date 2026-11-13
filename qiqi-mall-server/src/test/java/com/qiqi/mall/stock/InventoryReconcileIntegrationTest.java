package com.qiqi.mall.stock;

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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存对账接口的集成测试：{@code GET /admin/inventory/reconcile} 与 {@code .../reconcile/drift}。
 *
 * <h3>要固化的三件事</h3>
 * <ol>
 *   <li><b>三个数的口径</b>：{@code bucketSum}（权威）、{@code skuStock}（缓存）、
 *       {@code ledgerNet}（全生命周期进出和）。前两个之差才是 {@code drift}；
 *       {@code ledgerNet} <b>不得</b>被后端拿去和 {@code bucketSum} 对平 —— 用例里故意造出
 *       「桶合计 500、台账净额 −3」，断言接口<b>如实</b>返回这两个不相等、互不影响的数；</li>
 *   <li><b>字段齐全</b>：{@code skuCode}/{@code skuName} 必须由后端拼好（{@code sku} 表没有这两列，
 *       前端缺了就显示「（无编码）」「（无名称）」）；</li>
 *   <li><b>drift 分页 + threshold 过滤</b>：{@code |bucketSum − skuStock| > threshold} 才进列表，
 *       分页参数 {@code page/size} 与其它列表同口径（{@code size} 夹到 50）。</li>
 * </ol>
 *
 * <h3>数据自建自清</h3>
 * 自己造一个 SKU（挂到库里已有的商品上）+ 8 个桶 + 2 条库存流水，
 * {@code sku.stock} 故意留 0 制造偏差；跑完全删（流水 → 桶 → SKU）。
 * 只断言自己造的行。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=InventoryReconcileIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryReconcileIntegrationTest {

    /** admin 口令：默认种子值；改过口令（scripts/set-admin-password.sh）时用环境变量 ADMIN_PASSWORD
     *  覆盖 —— 与 e2e 侧（e2e/tests/support/ui.js: process.env.ADMIN_PASSWORD || '123456'）同一口径 */
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "123456");

    /** 桶合计（8 桶：6×63 + 2×61 = 500），与 sku.stock 故意不一致 */
    private static final int BUCKET_TOTAL = 500;
    /** 台账净额：−5（下单扣减）+ 2（退货回补）= −3 */
    private static final int LEDGER_NET = -3;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminJwt;
    private Long skuId;
    private Long productId;
    private String productName;

    // ------------------------------------------------------------------ 准备

    @BeforeAll
    void prepareData() {
        adminJwt = loginAsAdmin();

        Map<String, Object> product = jdbc.queryForMap(
                "SELECT id, name FROM product WHERE name IS NOT NULL ORDER BY id LIMIT 1");
        productId = ((Number) product.get("id")).longValue();
        productName = String.valueOf(product.get("name"));

        // 用 GeneratedKeyHolder 拿自增 id：JdbcTemplate 每次调用可能换连接，
        // 不能靠「INSERT 之后另查一次 LAST_INSERT_ID()」（那是连接级的会话变量）
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sku (product_id, price, stock, version) VALUES (?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, productId);
            ps.setBigDecimal(2, new BigDecimal("9.90"));
            ps.setInt(3, 0);
            ps.setInt(4, 0);
            return ps;
        }, keys);
        skuId = keys.getKey().longValue();

        // 8 个桶合计 500，sku.stock 仍是 0 ⇒ drift = +500（「缓存少记」）
        int[] perBucket = {63, 63, 63, 63, 63, 63, 61, 61};
        for (int i = 0; i < perBucket.length; i++) {
            jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?,?,?)", skuId, i, perBucket[i]);
        }
        insertLedger(-5, "ORDER_CREATE", null, null);
        insertLedger(2, "RETURN_REFUND", "RETURN_APPLY", 9_900_001L);

        System.out.println("[准备] 自建偏差 SKU：skuId=" + skuId + "（桶合计 " + BUCKET_TOTAL
                + "，sku.stock=0，台账净额 " + LEDGER_NET + "）");
    }

    @AfterAll
    void cleanUp() {
        try {
            jdbc.update("DELETE FROM stock_ledger WHERE sku_id = ?", skuId);
            jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId);
            jdbc.update("DELETE FROM sku WHERE id = ?", skuId);
        } catch (RuntimeException e) {
            System.out.println("[清理] 自建偏差 SKU 清理失败（请手工核对 sku " + skuId + "）：" + e.getMessage());
        }
        System.out.println("[清理] 自建偏差 SKU 已删除");
    }

    // ------------------------------------------------------------------ ① 单 SKU 对账

    @Test
    @DisplayName("① 对账返回三个数（bucketSum / skuStock / ledgerNet）且互不强制相等；字段齐全含流水")
    void reconcile_returnsThreeNumbersAndAllFields() {
        JsonNode body = get("/admin/inventory/reconcile?skuId=" + skuId);
        JsonNode data = body.path("data");
        System.out.println("① /admin/inventory/reconcile?skuId=" + skuId + " → bucketSum=" + data.path("bucketSum").asInt()
                + " skuStock=" + data.path("skuStock").asInt() + " drift=" + data.path("drift").asInt()
                + " ledgerNet=" + data.path("ledgerNet").asInt() + " bucketCount=" + data.path("bucketCount").asInt());

        assertThat(body.path("code").asInt()).isZero();

        // 契约字段一个不能少
        for (String key : List.of("skuId", "skuCode", "skuName", "bucketSum", "skuStock", "drift",
                "lastRefreshAt", "bucketCount", "ledgerNet", "ledgerRows")) {
            assertThat(data.has(key)).as("对账结果必须带字段 %s：%s", key, data).isTrue();
        }

        assertThat(data.path("bucketSum").asInt()).as("权威值 = SUM(桶)").isEqualTo(BUCKET_TOTAL);
        assertThat(data.path("skuStock").asInt()).as("展示缓存 = sku.stock").isZero();
        assertThat(data.path("drift").asInt()).as("偏差 = 权威 − 缓存").isEqualTo(BUCKET_TOTAL);
        assertThat(data.path("ledgerNet").asInt()).as("台账净额 = SUM(delta)，与当前库存无关")
                .isEqualTo(LEDGER_NET);
        assertThat(data.path("bucketCount").asInt()).isEqualTo(8);
        assertThat(data.path("expectedBucketCount").asInt()).as("配置里的桶数（qiqi.stock.bucket-count）").isEqualTo(8);
        assertThat(data.path("bucketCountMatch").asBoolean()).isTrue();

        // skuCode / skuName 由后端拼（sku 表没有这两列）
        assertThat(data.path("skuCode").asText()).isEqualTo("SKU-" + skuId);
        assertThat(data.path("skuName").asText()).as("名称取 product.name").startsWith(productName);
        assertThat(data.path("lastRefreshAt").asText()).isNotEmpty();

        // 最近 20 条流水：字段齐全、最近的在最前
        JsonNode rows = data.path("ledgerRows");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).as("造了 2 条流水").isEqualTo(2);
        for (JsonNode row : rows) {
            for (String key : List.of("id", "reason", "delta", "bucketNo", "refType", "refId", "createdAt", "idemKey")) {
                assertThat(row.has(key)).as("流水行必须带字段 %s：%s", key, row).isTrue();
            }
        }
        assertThat(rows.get(0).path("reason").asText()).as("按 id 倒序：最后写的 RETURN_REFUND 在最前")
                .isEqualTo("RETURN_REFUND");
        assertThat(rows.get(0).path("refType").asText()).isEqualTo("RETURN_APPLY");

        // 口径红线：接口不许把 ledgerNet 修正成与 bucketSum 相等
        assertThat(data.path("ledgerNet").asInt()).isNotEqualTo(data.path("bucketSum").asInt());
    }

    @Test
    @DisplayName("② SKU 不存在 → 明确中文原因（不给一张「三个数都是 0」的卡片）")
    void reconcile_missingSku_failsWithReason() {
        JsonNode body = get("/admin/inventory/reconcile?skuId=999999999");
        System.out.println("② skuId=999999999 → code=" + body.path("code").asInt()
                + " message=" + body.path("message").asText());
        assertThat(body.path("code").asInt()).isNotZero();
        assertThat(body.path("message").asText()).contains("SKU 不存在");
    }

    // ------------------------------------------------------------------ ③ 全库偏差

    @Test
    @DisplayName("③ threshold 过滤生效（100 命中、1000000 空）；size 夹到 50；行内字段齐全")
    void drift_paginationAndThreshold() {
        JsonNode hit = get("/admin/inventory/reconcile/drift?page=1&size=50&threshold=100").path("data");
        System.out.println("③ threshold=100 → total=" + hit.path("total").asLong()
                + " 首行=" + (hit.path("records").size() > 0 ? hit.path("records").get(0) : "（空）"));
        assertThat(hit.path("total").asLong()).as("自建的偏差 500 > 100，必须出现在列表里").isPositive();

        boolean found = false;
        for (JsonNode row : hit.path("records")) {
            for (String key : List.of("skuId", "skuCode", "bucketSum", "skuStock", "drift")) {
                assertThat(row.has(key)).as("偏差行必须带字段 %s：%s", key, row).isTrue();
            }
            if (row.path("skuId").asLong() == skuId) {
                found = true;
                assertThat(row.path("drift").asInt()).isEqualTo(BUCKET_TOTAL);
                assertThat(row.path("bucketSum").asInt()).isEqualTo(BUCKET_TOTAL);
                assertThat(row.path("skuStock").asInt()).isZero();
                assertThat(row.path("skuCode").asText()).isEqualTo("SKU-" + skuId);
            }
        }
        assertThat(found).as("阈值 100 时应该能翻到自建的那个 SKU").isTrue();

        JsonNode miss = get("/admin/inventory/reconcile/drift?page=1&size=50&threshold=1000000").path("data");
        System.out.println("③ threshold=1000000 → total=" + miss.path("total").asLong());
        assertThat(miss.path("total").asLong()).as("阈值高于所有偏差时必须是空的").isZero();

        JsonNode clamp = get("/admin/inventory/reconcile/drift?page=1&size=999&threshold=0").path("data");
        assertThat(clamp.path("size").asLong()).as("size 上限 50").isEqualTo(50);
        assertThat(clamp.path("records").size()).isLessThanOrEqualTo(50);
    }

    // ------------------------------------------------------------------ 小工具

    private String loginAsAdmin() {
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login",
                Map.of("username", "admin", "password", ADMIN_PASSWORD), JsonNode.class);
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        String token = node.path("data").path("accessToken").asText(null);
        assertThat(token).as("admin 登录失败（种子账号 admin/123456）：%s", node).isNotBlank();
        return token;
    }

    private JsonNode get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), JsonNode.class).getBody();
    }

    /** uk_idem 全局唯一：测试行用随机键，别跟真实台账撞 */
    private void insertLedger(int delta, String reason, String refType, Long refId) {
        jdbc.update("INSERT INTO stock_ledger (order_id, sku_id, delta, reason, ref_type, ref_id, bucket_no, "
                        + "idem_key, created_at) VALUES (?,?,?,?,?,?,?,?, NOW())",
                9_900_000L, skuId, delta, reason, refType, refId, 0,
                "T" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** 只是为了让 IDE/评审看到清单字段（真正的断言在用例里逐条做） */
    @SuppressWarnings("unused")
    private static final List<String> CONTRACT_KEYS = new ArrayList<>(List.of(
            "skuId", "skuCode", "skuName", "bucketSum", "skuStock", "drift",
            "lastRefreshAt", "bucketCount", "ledgerNet", "ledgerRows"));
}
