package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
import com.qiqi.mall.oms.wms.MockWmsService;
import com.qiqi.mall.support.ItDatabase;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>期 2 · 步骤 3 的验收口径：下发之前加第二个检测点（读标决定放不放行）。</b>
 *
 * <p>用户原话：<i>检测点比"全量预计算"重要：不在流程开头一次算全（事实有时效），而是在该问的环节问。
 * 先补一个检测点：下发 WMS 之前。</i>
 *
 * <h3>这是第二道保险，不是第一道</h3>
 * 正常冻结在<b>审核阶段</b>就是 {@code SKIPPED}（根本到不了待下发），所以这一道真正生效的场景是：
 * 订单<b>离开待审核之后</b>才被冻结 / 被打了阻断标（人工冻结不看 status，见
 * {@code OrderAuditController#freeze}），或者历史遗留单。
 *
 * <h3>八个场景（每条都真跑：真起后端 + 真打 HTTP + 真连库）</h3>
 * <pre>
 * ① 正常单（无标）→ 下发成功（最重要的一条：不许把正常发货挡住）
 * ② 冻结单（真实 freeze 接口冻的）→ code=1、包裹状态仍 0、WMS 台账 0 行、
 *    MockWmsService#dispatchCount 不增、审计 DISPATCH_BLOCKED +1（这条同时验 REQUIRES_NEW）
 * ③ 特征标 blocking=1 → 拦住
 * ④ 特征标 blocking=0 → 不拦
 * ⑤ 残留结论标（AUDIT_PENDING / BLOCKED 手工造，都是 category='结论' + blocking=1）→ 不拦（硬约束的回归防线）
 * ⑥ ?dryRun=true 预演同样报错，且一行都不落库（连审计都不写）
 * ⑦ （额外）冻结 → 人工审核通过 → 冻结标被结算 → 下发放行：证明"已放行的单不会被误拦"
 * ⑧ （回归防线）目录缓存陈旧（新标只进了库、没进缓存）→ 下发仍被拦：
 *    证明安全判据直查库、不读 per-instance 内存副本（2026-11-16 真站漏拦事故的防线）
 * </pre>
 *
 * <h3>为什么 ⑦ 必须存在（它把"第二道保险到底拦得到什么"钉在证据上）</h3>
 * 期 1.6 + 结算统一化之后，{@code OrderStatusService#change} 在「离开待审核」时会结算全部结论标
 * （<b>含 FROZEN 族</b>，{@code released_by=SYSTEM:AUDIT_PASS}）。所以「先冻结、再审核通过」的单
 * 到下发时<b>身上已经没有冻结标了</b>，第二道保险对它不生效 —— 这是<b>正确</b>的（它已经被放行了），
 * 而不是漏拦。⑦ 用真实路径（freeze → audit-pass → dispatch）把这条口径固化成断言，
 * 防止将来有人"为了让保险看起来有用"把结算改掉。
 *
 * <h3>为什么审计必须独立事务（② 的最后一个断言）</h3>
 * {@code dispatch} 靠<b>抛异常回滚</b>拦下，{@code AdminAuditWriter#record} 与业务同事务 ⇒ 一起回滚。
 * 所以被拦这条审计走 {@code recordIndependently}（{@code REQUIRES_NEW}）。
 * 证据：这次请求整体失败、包裹状态一个字段没变、台账一行没写，而 {@code DISPATCH_BLOCKED} 审计
 * <b>确实多了一条</b>（且 {@code operator_type=ADMIN} 说得出是谁点的）。
 *
 * <h3>造数（隔离库 {@code qiqi_mall_it}，用完即重建）</h3>
 * 每张单自带一张待下发包裹：{@code QT-4001..QT-4007}，跑完全删并断言无残留。
 *
 * <p>运行：{@code ./scripts/run-it.sh -Dtest=DispatchTagGateIntegrationTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DispatchTagGateIntegrationTest {

    /** admin 口令：与其它 IT / e2e 同一口径（种子默认 123456） */
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "123456");
    private static final String MEMBER = "dispatch_gate_member";
    private static final String PRODUCT = "下发检测点测试商品";
    private static final String SN_PREFIX = "QT-400";

    /** 本类自建的两个「特征」标：一个 blocking=1（该拦），一个 blocking=0（只是提示，该放行） */
    private static final String FEATURE_BLOCKING = "GATE_BLOCKING_TAG";
    private static final String FEATURE_INFO = "GATE_INFO_TAG";

    /** 文案里必须出现的那句（运维最容易误解的地方：以为 WMS 已经收到了） */
    private static final String NO_WMS_REQUEST = "本次没有向 WMS 发出任何请求";

    @DynamicPropertySource
    static void itDatasource(DynamicPropertyRegistry registry) {
        ItDatabase.prepare();
        registry.add("spring.datasource.url", ItDatabase::url);
        registry.add("spring.datasource.username", ItDatabase::username);
        registry.add("spring.datasource.password", ItDatabase::password);
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockWmsService mockWms;
    @Autowired
    private AuditTagCatalogService tagCatalog;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminJwt;
    private Long memberId;
    private Long productId;
    private Long skuId;
    private Long warehouseId;

    // ================================================================== 准备 / 收尾

    @BeforeAll
    void seed() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedAdmin();
        seedCatalogMemberProductWarehouse();
        // 两个特征标先进目录：检测点判 category='特征' + blocking 是**从目录读**的
        tagCatalog.createCustom(FEATURE_BLOCKING, "客诉客户", "该客户有过投诉，客服已介入，先别发货", "特征", 1);
        tagCatalog.createCustom(FEATURE_INFO, "绿旗急发", "运营标记：优先处理（提示性质，不拦流程）", "特征", 0);
        adminJwt = login();
        System.out.printf("[准备] 库=%s admin JWT 就绪；仓=%d sku=%d；特征标 %s(blocking=1) / %s(blocking=0)%n",
                ItDatabase.dbName(), warehouseId, skuId, FEATURE_BLOCKING, FEATURE_INFO);
    }

    @AfterAll
    void cleanup() {
        Map<String, Integer> deleted = new LinkedHashMap<>(cleanOurData());
        deleted.putAll(purgeEverything());
        System.out.println("[清理] 删除行数：" + deleted);
        assertThat(count("SELECT COUNT(*) FROM `order` WHERE order_sn LIKE ?", SN_PREFIX + "%"))
                .as("清理后订单残留").isZero();
        assertThat(count("SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                + "WHERE o.order_sn LIKE ?", SN_PREFIX + "%")).as("清理后标残留").isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE target_sn LIKE ?", "DL40%"))
                .as("清理后审计残留").isZero();
    }

    // ================================================================== ① 正常单不被挡

    @Test
    @Order(1)
    @DisplayName("① 正常单（无任何标）→ 下发成功（不能把正常发货挡住）")
    void normalOrderDispatches() {
        Fixture f = newOrderWithParcel("QT-4001", 8);
        assertThat(activeTags(f.orderId())).as("这张单身上本来就没有标").isEmpty();
        int wmsBefore = mockWms.dispatchCount();
        int auditBefore = count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DISPATCH_BLOCKED'");

        JsonNode body = ok(post("/admin/deliveries/" + f.deliveryId() + "/dispatch", null));
        System.out.printf("① 正常单下发 → status=%d，WMS 外呼增量=%d，DISPATCH_BLOCKED 审计增量=%d%n",
                deliveryStatus(f.deliveryId()), mockWms.dispatchCount() - wmsBefore,
                count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DISPATCH_BLOCKED'") - auditBefore);

        assertThat(body.path("code").asInt()).as("正常单必须成功：%s", body).isZero();
        assertThat(deliveryStatus(f.deliveryId())).as("包裹必须真的变成已下发").isEqualTo(1);
        assertThat(mockWms.dispatchCount() - wmsBefore).as("成功下发应当有 1 次 WMS 外呼").isEqualTo(1);
        assertThat(wmsLedgerRows(f.deliveryId())).as("成功下发应有 REQ + ACK 两行台账").isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DISPATCH_BLOCKED'"))
                .as("**正常单一次都不许被拦**").isEqualTo(auditBefore);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DELIVERY_DISPATCH' "
                + "AND target_id = ?", f.deliveryId())).as("下发成功的审计照旧写一条").isEqualTo(1);
    }

    // ================================================================== ② 冻结 → 拦下（核心）

    @Test
    @Order(2)
    @DisplayName("② 冻结单下发 → code=1、包裹仍 0、WMS 台账 0 行、外呼不增、DISPATCH_BLOCKED 审计 +1")
    void frozenOrderIsBlocked() {
        Fixture f = newOrderWithParcel("QT-4002", 8);
        // 真实路径冻结（不看 status：这张单已经在待发货 8，审核阶段那道保险对它已经不起作用）
        ok(post("/admin/order-audit/orders/" + f.orderId() + "/freeze",
                Map.of("note", "客户要求暂缓发货")));
        assertThat(activeTags(f.orderId())).as("冻结应当落一条 active 的 FROZEN 标").containsExactly("FROZEN");

        int wmsBefore = mockWms.dispatchCount();

        ResponseEntity<JsonNode> resp = post("/admin/deliveries/" + f.deliveryId() + "/dispatch", null);
        JsonNode body = resp.getBody();
        assertThat(resp.getStatusCode().value())
                .as("**不许**是 409/400：那套码的语义是「别人改过，刷新重试」，而这里刷一万次也还是被拦着").isEqualTo(200);
        assertThat(body).isNotNull();
        assertThat(body.path("code").asInt()).as("拦下必须是 code=1（前端拦截器原样 toast）：%s", body).isEqualTo(1);
        String message = body.path("message").asText();
        System.out.printf("② 冻结单下发 → HTTP %d，code=%d%n    message=%s%n",
                resp.getStatusCode().value(), body.path("code").asInt(), message);

        assertThat(message).as("文案必须说清「被拦下」").contains("已拦下");
        assertThat(message).as("必须点名冻结标").contains("标=FROZEN");
        assertThat(message).as("必须带上运营填的那句人话").contains("客户要求暂缓发货");
        assertThat(message).as("**必须写清没有外呼 WMS**（否则运维会去 WMS 侧手工撤销一张根本不存在单）")
                .contains(NO_WMS_REQUEST);
        assertThat(message).as("必须告诉人下一步做什么").contains("请先解冻");

        // 业务侧：一个字段都没动，一次外呼都没有，一行 WMS 台账都没有
        assertThat(deliveryStatus(f.deliveryId())).as("包裹状态必须还是待下发").isZero();
        assertThat(mockWms.dispatchCount() - wmsBefore).as("**一次都不许外呼 WMS**").isZero();
        assertThat(wmsLedgerRows(f.deliveryId())).as("**WMS 台账必须 0 行**（拦在 requestSent 之前）").isZero();
        assertThat(deliveryLedgerRows(f.deliveryId())).as("包裹状态台账也必须 0 行（业务整体回滚/未开始）").isZero();

        // 审计：独立事务写进去的，业务回滚不影响它
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT action, operator_type, operator_name, summary, after_json FROM admin_audit_log "
                        + "WHERE action = 'DISPATCH_BLOCKED' AND target_id = ? ORDER BY id", f.deliveryId());
        assertThat(audits).as("**被拦这件事必须留证：且业务回滚没把它带走（REQUIRES_NEW）**").hasSize(1);
        Map<String, Object> audit = audits.get(0);
        System.out.printf("② 审计 +1：action=%s operator=%s/%s%n    summary=%s%n    after=%s%n",
                audit.get("action"), audit.get("operator_type"), audit.get("operator_name"),
                audit.get("summary"), audit.get("after_json"));
        assertThat(String.valueOf(audit.get("operator_type"))).as("说得出是谁点的下发").isEqualTo("ADMIN");
        assertThat(String.valueOf(audit.get("summary")))
                .as("摘要顺序沿用冻结那套：包裹号 → 标码 → 中文名 → 人话")
                .contains("包裹 " + deliverySn(f.deliveryId()))
                .contains("标=FROZEN")
                .contains("中文名=被冻结")
                .contains("人话=客户要求暂缓发货");
        assertThat(String.valueOf(audit.get("after_json")))
                .as("结构化落点：wmsCalled=false 是这条审计最要紧的一列").contains("\"wmsCalled\": false");
    }

    // ================================================================== ③ 特征标 blocking=1 → 拦

    @Test
    @Order(3)
    @DisplayName("③ 特征标 blocking=1 → 拦住（blocking 列第一次真正被消费）")
    void blockingFeatureTagBlocks() {
        Fixture f = newOrderWithParcel("QT-4003", 8);
        addTag(f.orderId(), FEATURE_BLOCKING, "客服工单 #8891：客户投诉包装破损");
        int wmsBefore = mockWms.dispatchCount();

        JsonNode body = post("/admin/deliveries/" + f.deliveryId() + "/dispatch", null).getBody();
        assertThat(body).isNotNull();
        System.out.printf("③ 特征标 blocking=1 下发 → code=%d message=%s%n",
                body.path("code").asInt(), body.path("message").asText());
        assertThat(body.path("code").asInt()).isEqualTo(1);
        String message = body.path("message").asText();
        assertThat(message).as("必须点名是中哪个标（中文名 + 标码）")
                .contains("客诉客户").contains(FEATURE_BLOCKING);
        assertThat(message).as("这不是冻结，文案不能说成冻结").doesNotContain("被冻结");
        assertThat(message).as("必须写清没有外呼 WMS").contains(NO_WMS_REQUEST);
        assertThat(message).as("必须说下一步：解除标记").contains("请先解除标记");

        assertThat(deliveryStatus(f.deliveryId())).isZero();
        assertThat(mockWms.dispatchCount() - wmsBefore).isZero();
        assertThat(wmsLedgerRows(f.deliveryId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DISPATCH_BLOCKED' "
                + "AND target_id = ?", f.deliveryId())).as("拦截审计 +1").isEqualTo(1);
    }

    // ================================================================== ④ 特征标 blocking=0 → 放过

    @Test
    @Order(4)
    @DisplayName("④ 特征标 blocking=0 → 不拦（只是提示，不该挡住流程）")
    void infoFeatureTagDoesNotBlock() {
        Fixture f = newOrderWithParcel("QT-4004", 8);
        addTag(f.orderId(), FEATURE_INFO, "运营：这单优先处理");
        int wmsBefore = mockWms.dispatchCount();

        JsonNode body = ok(post("/admin/deliveries/" + f.deliveryId() + "/dispatch", null));
        System.out.printf("④ 特征标 blocking=0 下发 → code=%d status=%d%n",
                body.path("code").asInt(), deliveryStatus(f.deliveryId()));
        assertThat(body.path("code").asInt()).isZero();
        assertThat(deliveryStatus(f.deliveryId())).as("blocking=0 的特征标不许挡住发货").isEqualTo(1);
        assertThat(mockWms.dispatchCount() - wmsBefore).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DISPATCH_BLOCKED' "
                + "AND target_id = ?", f.deliveryId())).isZero();
    }

    // ================================================================== ⑤ 残留结论标 → 不拦（硬约束）

    @Test
    @Order(5)
    @DisplayName("⑤ 残留结论标（AUDIT_PENDING / BLOCKED，都是 blocking=1）→ 不拦（硬约束的回归防线）")
    void leftoverConclusionTagsDoNotBlock() {
        Fixture f = newOrderWithParcel("QT-4005", 8);
        // 手工造「历史遗留单」：结算上线之前离开 7 的单，身上还挂着两条 blocking=1 的结论标。
        // 这两条在标目录里 category='结论'、blocking=1 —— 若按「结论标 + blocking」一刀切就会拦住，
        // 而队列里它已经不是待审核，**没有任何入口能解掉这两条标**（自愈无门 = 死锁）。
        addTag(f.orderId(), "AUDIT_PENDING", "历史遗留：结算上线前离开待审核");
        addTag(f.orderId(), "BLOCKED", "历史遗留：被策略拦截过");
        assertThat(activeTags(f.orderId())).containsExactlyInAnyOrder("AUDIT_PENDING", "BLOCKED");
        // 先确认这两条标确实是「目录里 category=结论 且 blocking=1」—— 否则这条回归防线是空的
        assertThat(conclusionBlockingCatalogCodes())
                .as("AUDIT_PENDING / BLOCKED 必须真的是结论标 + blocking=1，⑤ 才有意义")
                .contains("AUDIT_PENDING", "BLOCKED");
        int wmsBefore = mockWms.dispatchCount();

        JsonNode body = ok(post("/admin/deliveries/" + f.deliveryId() + "/dispatch", null));
        System.out.printf("⑤ 残留结论标下发 → code=%d status=%d WMS 外呼增量=%d%n",
                body.path("code").asInt(), deliveryStatus(f.deliveryId()), mockWms.dispatchCount() - wmsBefore);
        assertThat(body.path("code").asInt()).as("结论标不许拦（否则历史遗留单永远发不出去）").isZero();
        assertThat(deliveryStatus(f.deliveryId())).isEqualTo(1);
        assertThat(mockWms.dispatchCount() - wmsBefore).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DISPATCH_BLOCKED' "
                + "AND target_id = ?", f.deliveryId())).isZero();
    }

    // ================================================================== ⑥ 预演同样报错、零落库

    @Test
    @Order(6)
    @DisplayName("⑥ ?dryRun=true 预演：同样报错（code=1 + 同一句文案），且一行都不落库（连审计都不写）")
    void previewAlsoBlocksAndWritesNothing() {
        Fixture f = newOrderWithParcel("QT-4006", 8);
        ok(post("/admin/order-audit/orders/" + f.orderId() + "/freeze", Map.of("note", "风控复审中")));

        int auditTotalBefore = count("SELECT COUNT(*) FROM admin_audit_log");
        int deliveryAuditBefore = count("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ?", f.deliveryId());
        int wmsBefore = mockWms.dispatchCount();

        JsonNode body = post("/admin/deliveries/" + f.deliveryId() + "/dispatch?dryRun=true", null).getBody();
        assertThat(body).isNotNull();
        System.out.printf("⑥ 预演冻结单 → code=%d message=%s%n",
                body.path("code").asInt(), body.path("message").asText());
        assertThat(body.path("code").asInt()).as("预演必须和真实下发同一个结论").isEqualTo(1);
        assertThat(body.path("message").asText())
                .as("预演文案与真实下发同源（同一处判定 + 同一句文案）")
                .contains("已拦下").contains(NO_WMS_REQUEST);
        assertThat(body.path("data").isNull()).as("拦下了就不该给 willDo（别让人以为点一下就能发）").isTrue();

        // 预演零落库：连「被拦」的审计都不许写（预演不是尝试，不留下一次尝试的痕迹）
        assertThat(deliveryStatus(f.deliveryId())).as("包裹状态不许变").isZero();
        assertThat(wmsLedgerRows(f.deliveryId())).isZero();
        assertThat(deliveryLedgerRows(f.deliveryId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log")).as("**预演一行审计都不许写**")
                .isEqualTo(auditTotalBefore);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ?", f.deliveryId()))
                .as("这张包裹名下审计条数一字不变").isEqualTo(deliveryAuditBefore);
        assertThat(mockWms.dispatchCount() - wmsBefore).isZero();

        // 正常单的预演也该照常放行（预演不是「一律报错」）
        Fixture ok2 = newOrderWithParcel("QT-4007", 8);
        JsonNode okBody = ok(post("/admin/deliveries/" + ok2.deliveryId() + "/dispatch?dryRun=true", null));
        assertThat(okBody.path("data").path("willDo").asText()).as("正常单预演必须给出「将要发生什么」")
                .contains("将把包裹").contains("待下发→已下发");
        assertThat(deliveryStatus(ok2.deliveryId())).as("预演之后仍然是待下发").isZero();
    }

    // ================================================================== ⑦ 冻结 → 审核通过 → 不误拦

    @Test
    @Order(7)
    @DisplayName("⑦ 冻结 → 人工审核通过 → 冻结标被结算 → 下发放行（已放行的单绝不许被误拦）")
    void freezeThenAuditPassThenDispatchIsAllowed() {
        // 真实全链路：7 待审核 + 有明细 + 冻结 → audit-pass（7→8，拆单生成包裹）→ 下发
        Long orderId = newAuditPendingOrderWithItem("QT-4008");
        ok(post("/admin/order-audit/orders/" + orderId + "/freeze", Map.of("note", "客服先按住")));
        assertThat(activeTags(orderId)).containsExactly("FROZEN");

        ok(post("/admin/orders/" + orderId + "/audit-pass", null));
        Map<String, Object> frozenRow = jdbc.queryForMap(
                "SELECT active, released_by FROM order_audit_tag WHERE order_id = ? AND tag_code = 'FROZEN' ORDER BY id DESC LIMIT 1",
                orderId);
        System.out.printf("⑦ 冻结 → audit-pass 之后：订单状态=%d，FROZEN 标 active=%s released_by=%s%n",
                orderStatus(orderId), frozenRow.get("active"), frozenRow.get("released_by"));
        assertThat(orderStatus(orderId)).isEqualTo(8);
        assertThat(((Number) frozenRow.get("active")).intValue())
                .as("离开待审核就结算：冻结标必须是已解除状态").isZero();
        assertThat(String.valueOf(frozenRow.get("released_by"))).isEqualTo("SYSTEM:AUDIT_PASS");

        List<Long> deliveries = jdbc.queryForList(
                "SELECT id FROM delivery_order WHERE order_id = ? ORDER BY id", Long.class, orderId);
        assertThat(deliveries).as("审核通过应拆出包裹").isNotEmpty();
        int wmsBefore = mockWms.dispatchCount();
        JsonNode body = ok(post("/admin/deliveries/" + deliveries.get(0) + "/dispatch", null));
        System.out.printf("⑦ 已放行的单下发 → code=%d status=%d WMS 外呼增量=%d%n",
                body.path("code").asInt(), deliveryStatus(deliveries.get(0)), mockWms.dispatchCount() - wmsBefore);
        assertThat(body.path("code").asInt())
                .as("**已经人工放行的单不许被第二道保险误拦**（拦了它也没有任何动作能解）").isZero();
        assertThat(deliveryStatus(deliveries.get(0))).isEqualTo(1);
        assertThat(mockWms.dispatchCount() - wmsBefore).isEqualTo(1);
    }

    // ================================================================== ⑧ 目录缓存陈旧 → 保险仍然生效

    /**
     * <b>回归防线（2026-11-16 真站事故）</b>：目录缓存陈旧时，下发保险必须<b>照样拦住</b>。
     *
     * <h3>事故原貌</h3>
     * 保险的「特征标」判据原来读 {@code AuditTagCatalogService} 的 per-instance 内存缓存
     * （启动装载；只有处理 {@code createCustom} 的那个实例会 reload）。
     * 双实例部署下，另一个实例的副本一直陈旧 ⇒ 新加的阻断特征标在它眼里"不存在" ⇒
     * <b>挂着阻断标的包裹被下发给 WMS 并拿到 ACK</b>（包裹 DL20261116202343872321 / 订单 3）。
     * 同一段代码里「FROZEN」那条腿是纯 {@code tag_code LIKE 'FROZEN%'}、不查目录，所以它一直是对的。
     *
     * <h3>这个用例怎么真正把"缓存陈旧"造出来</h3>
     * 关键在<b>顺序</b>，不在"等一会儿"：
     * <ol>
     *   <li>{@code tagCatalog.reload()} —— 把缓存刷到"当前库里没有这个标"，并重置 TTL 计时；</li>
     *   <li>直接用 SQL 插一条 {@code category='特征' AND blocking=1} 的目录项（<b>不走</b> createCustom，
     *       所以没有任何代码会去 reload 缓存）；</li>
     *   <li><b>先断言缓存确实是陈旧的</b>（{@code byCode} 拿不到它）—— 这一步不能省：
     *       少了它，用例只是在验证"缓存刚好是新鲜的"，根本证明不了判据绕开了缓存；</li>
     *   <li>再下发 → 必须被拦。</li>
     * </ol>
     * TTL 是 5s，而 2→4 之间只有毫秒级，所以"陈旧"是构造出来的事实，不是运气。
     */
    @Test
    @Order(8)
    @DisplayName("⑧ 目录缓存陈旧（新标只进了库、没进缓存）→ 下发仍被拦（安全判据直查库）")
    void staleCatalogCacheStillBlocks() {
        final String staleCode = "GATE_STALE_ONLY_TAG";
        Fixture f = newOrderWithParcel("QT-4009", 8);

        // 1) 先把缓存刷到"库里还没有这个标"的状态
        tagCatalog.reload();
        // 2) 只进库、不进缓存（刻意不用 createCustom —— 它就是那个"只 reload 自己实例"的入口）
        jdbc.update("INSERT INTO audit_tag_catalog (code, name, description, category, blocking, visible, "
                + "enabled, builtin, sort_no) VALUES (?, '缓存陈旧验证标', '只进库不进缓存', '特征', 1, 1, 1, 0, 900)",
                staleCode);
        addTag(f.orderId(), staleCode, "人工打的阻断标（目录项刚刚才补登记）");

        // 3) 先证明"缓存确实陈旧"：拿不到 ⇒ 若判据还读缓存，就一定会放行
        assertThat(tagCatalog.byCode(staleCode))
                .as("前置条件：这个标此刻还不在内存缓存里（否则本用例证明不了任何事）").isNull();

        int wmsBefore = mockWms.dispatchCount();
        JsonNode body = post("/admin/deliveries/" + f.deliveryId() + "/dispatch", null).getBody();
        assertThat(body).isNotNull();
        System.out.printf("⑧ 缓存陈旧（%s 不在缓存里）下发 → code=%d message=%s%n",
                staleCode, body.path("code").asInt(), body.path("message").asText());

        assertThat(body.path("code").asInt())
                .as("★ 缓存陈旧也必须拦住：安全判据不许读 per-instance 内存副本").isEqualTo(1);
        assertThat(body.path("message").asText())
                .as("要点名这个标（中文名来自直查库，不是缓存）")
                .contains("缓存陈旧验证标").contains(staleCode)
                .contains(NO_WMS_REQUEST);
        assertThat(deliveryStatus(f.deliveryId())).isZero();
        assertThat(mockWms.dispatchCount() - wmsBefore).isZero();
        assertThat(wmsLedgerRows(f.deliveryId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'DISPATCH_BLOCKED' "
                + "AND target_id = ?", f.deliveryId())).isEqualTo(1);
    }

    // ================================================================== 造数

    /** 一张订单 + 一张待下发包裹（明细一条） */
    private record Fixture(Long orderId, Long deliveryId) {
    }

    private Fixture newOrderWithParcel(String sn, int orderStatus) {
        Long orderId = newOrder(sn, orderStatus);
        Long orderItemId = insertAndReturnKey(
                "INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, product_price, "
                        + "product_quantity, cost_price) VALUES (?, ?, ?, ?, 100.00, 2, 80.00)",
                orderId, productId, skuId, PRODUCT);
        Long deliveryId = insertAndReturnKey(
                "INSERT INTO delivery_order (delivery_sn, order_id, warehouse_id, status, receiver_name, "
                        + "receiver_phone, receiver_address, source) VALUES (?, ?, ?, 0, '张三', '13800000000', "
                        + "'北京市朝阳区xx路1号', 0)",
                "DL" + sn.replace("QT-", "") + "-" + System.nanoTime() % 100000, orderId, warehouseId);
        jdbc.update("INSERT INTO delivery_item (delivery_id, order_item_id, product_sku_id, quantity) "
                + "VALUES (?, ?, ?, 2)", deliveryId, orderItemId, skuId);
        return new Fixture(orderId, deliveryId);
    }

    /** 待审核(7) 的订单 + 一条明细（给 ⑦ 走真实 audit-pass → 拆单用） */
    private Long newAuditPendingOrderWithItem(String sn) {
        Long orderId = newOrder(sn, 7);
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, product_price, "
                + "product_quantity, cost_price) VALUES (?, ?, ?, ?, 100.00, 2, 80.00)",
                orderId, productId, skuId, PRODUCT);
        return orderId;
    }

    private Long newOrder(String sn, int status) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name) VALUES (?, ?, 100.00, ?, 1, NOW(), NOW(), '张三', '13800000000', "
                        + "'北京市朝阳区xx路1号', ?, 0, 0, '淘宝', '官方旗舰店')",
                sn, memberId, status, status == 8 ? 1 : 0);
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                + "operator_type, operator_name, idem_key) VALUES (?, NULL, ?, 'ORDER_CREATE', '下发检测点造数', "
                + "'SYSTEM', 'system', ?)", orderId, status, orderId + ":0");
        return orderId;
    }

    /** 手工打标（造「历史遗留单」/ 特征标用；生产路径见 freeze / applyManualTag） */
    private void addTag(Long orderId, String code, String reason) {
        jdbc.update("INSERT INTO order_audit_tag (order_id, tag_code, active, reason) VALUES (?, ?, 1, ?)",
                orderId, code, reason);
    }

    private void seedAdmin() {
        jdbc.update("INSERT INTO admin (username, password, nickname, status) VALUES (?, ?, '下发检测点测试管理员', 1)",
                "admin", passwordEncoder.encode(ADMIN_PASSWORD));
    }

    private void seedCatalogMemberProductWarehouse() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '下发检测点分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '下发检测点类型')");
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '下发检测点会员', 1, NOW(), 0, '普通')", MEMBER);
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES (?, '下发前检测点集成测试用', ?, ?, 1, 0)", PRODUCT, categoryId, typeId);
        skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, 100.00, 1000, 0, 80.00)",
                productId);
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, 1000)", skuId);
        warehouseId = insertAndReturnKey("INSERT INTO warehouse (name, status) VALUES ('下发检测点仓', 1)");
    }

    private Map<String, Integer> cleanOurData() {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        List<Long> orderIds = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE ?", Long.class,
                SN_PREFIX + "%");
        List<Long> deliveryIds = jdbc.queryForList("SELECT id FROM delivery_order WHERE order_id IN "
                + "(SELECT id FROM `order` WHERE order_sn LIKE ?)", Long.class, SN_PREFIX + "%");
        for (Long deliveryId : deliveryIds) {
            jdbc.update("DELETE FROM delivery_logistics WHERE delivery_id = ?", deliveryId);
            jdbc.update("DELETE FROM wms_exchange_ledger WHERE delivery_id = ?", deliveryId);
            jdbc.update("DELETE FROM delivery_status_ledger WHERE delivery_id = ?", deliveryId);
        }
        deleted.put("wms_exchange_ledger", deliveryIds.size());
        for (Long orderId : orderIds) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", orderId);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE ?", SN_PREFIX + "%");
        return deleted;
    }

    /** 收尾：审计 / 标目录 / 商品 / 会员 / 管理员 / 仓库（本类独占隔离库，整表清即可） */
    private Map<String, Integer> purgeEverything() {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("admin_audit_log(dl40)", jdbc.update("DELETE FROM admin_audit_log WHERE target_sn LIKE ?", "DL40%"));
        deleted.put("audit_tag_catalog(custom)", jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0"));
        deleted.put("audit_policy", jdbc.update("DELETE FROM audit_policy"));
        deleted.put("audit_run", jdbc.update("DELETE FROM audit_run"));
        deleted.put("product", jdbc.update("DELETE FROM product WHERE name = ?", PRODUCT));
        deleted.put("sku_bucket", jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId));
        deleted.put("delivery_order(left)", jdbc.update("DELETE FROM delivery_order"));
        deleted.put("member", jdbc.update("DELETE FROM member WHERE username = ?", MEMBER));
        deleted.put("admin", jdbc.update("DELETE FROM admin WHERE username = ?", "admin"));
        deleted.put("warehouse", jdbc.update("DELETE FROM warehouse"));
        return deleted;
    }

    // ================================================================== 工具

    private String login() {
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login",
                Map.of("username", "admin", "password", ADMIN_PASSWORD), JsonNode.class);
        String token = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(token).as("admin 登录失败：%s", resp.getBody()).isNotBlank();
        return token;
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminJwt);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** 断言 code=0 并返回 data（HTTP 200 + code=0 是本项目所有成功响应的口径） */
    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        assertThat(resp.getStatusCode().value()).as("HTTP 状态：%s", resp.getBody()).isEqualTo(200);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asInt()).as("期望成功，实际：%s", body).isZero();
        return body;
    }

    private int deliveryStatus(Long deliveryId) {
        Integer s = jdbc.queryForObject("SELECT status FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
        return s == null ? -1 : s;
    }

    private int orderStatus(Long orderId) {
        Integer s = jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        return s == null ? -1 : s;
    }

    private String deliverySn(Long deliveryId) {
        return jdbc.queryForObject("SELECT delivery_sn FROM delivery_order WHERE id = ?", String.class, deliveryId);
    }

    private List<String> activeTags(Long orderId) {
        return jdbc.queryForList("SELECT tag_code FROM order_audit_tag WHERE order_id = ? AND active = 1 "
                + "ORDER BY id", String.class, orderId);
    }

    /** 标目录里「category=结论 且 blocking=1」的码（⑤ 用它证明回归防线不是空跑） */
    private List<String> conclusionBlockingCatalogCodes() {
        return jdbc.queryForList("SELECT code FROM audit_tag_catalog WHERE category = '结论' AND blocking = 1 "
                + "ORDER BY sort_no", String.class);
    }

    private int wmsLedgerRows(Long deliveryId) {
        return count("SELECT COUNT(*) FROM wms_exchange_ledger WHERE delivery_id = ?", deliveryId);
    }

    private int deliveryLedgerRows(Long deliveryId) {
        return count("SELECT COUNT(*) FROM delivery_status_ledger WHERE delivery_id = ?", deliveryId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    private Long insertAndReturnKey(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("插入未返回主键：" + sql);
        return key.longValue();
    }
}
