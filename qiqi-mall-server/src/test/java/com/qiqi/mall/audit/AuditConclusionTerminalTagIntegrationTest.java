package com.qiqi.mall.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.audit.service.AuditQueryService;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
import com.qiqi.mall.audit.service.ConclusionTags;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.support.ItCleanup;
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
 * <b>期 3 · 步骤 2.5 的验收口径</b>：结算不再等于「清空」，而是「落一条终局结论标」。
 *
 * <h3>这个类要钉死的一句话</h3>
 * 订单离开待审核之后，它的<b>当前结论仍然存在</b>，只是变成了「它最后怎么了」：
 * <pre>
 *   7 → 8 人工放行      ⇒ active 结论标 == [AUDIT_PASSED]   （visible=0：过没过由状态 8 表达）
 *   7 → 8 自动引擎过审  ⇒ active 结论标 == [AUTO_PASSED]    （visible=0）
 *   7 → 4 审核不通过    ⇒ active 结论标 == [AUDIT_REJECTED] （visible=0："没过"由状态 4 表达）
 *   7 → 5 登记退货申请  ⇒ active 结论标 == [RETURN_APPLIED] （visible=1：队列里看不到订单状态）
 *   7 → 2/3/9 聚合推导  ⇒ **不落**（那是包裹事实，不是审核结论）—— 由 {@link ConclusionTags#terminalCodeOf} 断言
 * </pre>
 * 每条路径同时验三件事：① 落的是哪一枚；② <b>旧标（AUDIT_PENDING / NO_POLICY_MATCH…）active=0
 * 且 released_at / released_by 有值</b>（不是删行、也不是并挂两条）；③ 一单仍然只有<b>一条</b>
 * active 结论标（互斥没被破坏）。
 *
 * <h3>为什么造数走真实入口</h3>
 * 建单 {@code POST /admin/orders}（status=7）→ 跑一轮 {@code POST /admin/order-audit/run}
 * （落 AUDIT_PENDING / NO_POLICY_MATCH）→ 人工放行 / 不通过 / 申请退货都走各自的 HTTP 入口。
 * 直插的标证明不了任何入口会结算它 —— 那正是本类要钉的东西。只有商品/规格/桶/退货原因字典
 * 这些<b>夹具</b>是直插的（与其它 IT 同口径，它们不是被测对象）。
 *
 * <p>单号前缀 {@code QT-410x}（自建自清，不与 QT-100x/200x/300x/400x 混）。
 * 运行：{@code ./scripts/run-it.sh -Dtest=AuditConclusionTerminalTagIntegrationTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditConclusionTerminalTagIntegrationTest {

    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "123456");
    private static final String SN_LIKE = "QT-410%";

    /** 留人工的那一路：AUTO 命中但被**本策略自己的排除条件**挡下 ⇒ MANUAL ⇒ AUDIT_PENDING */
    private static final String CH_MANUAL = "终局标测试渠道-留人工";
    private static final String POLICY_MANUAL = "IT-AUTO-终局标-留人工";

    /** 自动过审的那一路：只认这个渠道，条件全满足 ⇒ AUTO_PASS ⇒ AUTO_PASSED */
    private static final String CH_AUTO = "终局标测试渠道-自动过";

    /** 放行路径真会拆单：拆单必须有「启用的仓」可兜底（该 SKU 没有分仓库存记录 → 默认仓） */
    private static final String WAREHOUSE = "终局标测试仓";
    private static final String POLICY_AUTO = "IT-AUTO-终局标-自动过";

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
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditTagCatalogService tagCatalog;
    @Autowired
    private AuditQueryService queryService;

    private String adminJwt;
    private Long memberId;
    private Long productId;
    private Long skuId;
    private Long reasonId;

    private Long idPassed;
    private Long idRejected;
    private Long idReturned;
    private Long idAutoPassed;

    // ================================================================== 准备 / 清理

    @BeforeAll
    void prepare() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedFixtures();
        adminJwt = login();

        // V8 必须已经把三条终局标种进目录，且 visible/blocking 与设计一致
        // —— 这条断言同时证明「迁移真的跑过」，而不是测试自己插的目录项
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_PASSED))
                .as("V8 必须已把 AUDIT_PASSED 种进标目录").isNotNull();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_REJECTED)).isNotNull();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_RETURN_APPLIED)).isNotNull();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_PASSED).getVisible())
                .as("AUDIT_PASSED visible=0：过没过由状态表达，标是事实不显示").isZero();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_REJECTED).getVisible()).isZero();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_RETURN_APPLIED).getVisible())
                .as("RETURN_APPLIED visible=1：队列里看不到订单状态，只有这枚标能说明它在退货流程里").isEqualTo(1);
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_RETURN_APPLIED).getBlocking())
                .as("RETURN_APPLIED blocking=0：结论标从不参与下发保险，blocking=1 是让字典承诺一件系统不执行的事")
                .isZero();
    }

    @AfterAll
    void cleanup() {
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, SN_LIKE));
        deleted.put("audit_policy_version", jdbc.update("DELETE FROM audit_policy_version"));
        deleted.put("audit_policy_condition", jdbc.update("DELETE FROM audit_policy_condition"));
        deleted.put("audit_policy", jdbc.update("DELETE FROM audit_policy"));
        deleted.put("audit_run", jdbc.update("DELETE FROM audit_run"));
        deleted.put("sku_bucket", jdbc.update("DELETE FROM sku_bucket WHERE sku_id = ?", skuId));
        deleted.put("sku", jdbc.update("DELETE FROM sku WHERE id = ?", skuId));
        deleted.put("product", jdbc.update("DELETE FROM product WHERE id = ?", productId));
        deleted.put("warehouse", jdbc.update("DELETE FROM warehouse WHERE name = ?", WAREHOUSE));
        deleted.put("return_reason", jdbc.update("DELETE FROM return_reason WHERE id = ?", reasonId));
        deleted.put("member", jdbc.update("DELETE FROM member WHERE id = ?", memberId));
        deleted.put("admin", jdbc.update("DELETE FROM admin WHERE username = 'admin'"));
        System.out.println("[清理] 删除行数：" + deleted);

        ItCleanup.residueOfOrders(jdbc, SN_LIKE)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE order_sn LIKE ?",
                Integer.class, SN_LIKE)).as("本类的单不许留给下一个测试类（它们会数本轮候选）").isZero();
    }

    // ================================================================== 主用例：五条路径

    @Test
    @Order(1)
    @DisplayName("① 起点：四笔单跑一轮 ⇒ AUDIT_PENDING ×3 + NO_POLICY_MATCH ×1（终局标还没落）")
    void roundOneLandsVerdictTags() {
        idPassed = createPendingOrder("QT-4101", CH_MANUAL, "客户改地址：朝阳区tt路1号");
        idRejected = createPendingOrder("QT-4102", CH_MANUAL, "客户改地址：朝阳区tt路2号");
        idReturned = createPendingOrder("QT-4103", CH_MANUAL, "客户改地址：朝阳区tt路3号");
        idAutoPassed = createPendingOrder("QT-4104", CH_AUTO, null);

        // 第一轮：只有"留人工"那条策略（QT-4104 没有策略认它 ⇒ NO_MATCH）
        createManualPolicy();
        JsonNode run = ok(post("/admin/order-audit/run", "{}"));
        assertThat(run.path("manual").asInt()).as("三笔转人工：%s", run).isGreaterThanOrEqualTo(3);
        assertThat(run.path("noMatch").asInt()).as("一笔不命中（QT-4104）：%s", run).isGreaterThanOrEqualTo(1);

        assertThat(activeConclusionCodes(idPassed)).containsExactly("AUDIT_PENDING");
        assertThat(activeConclusionCodes(idRejected)).containsExactly("AUDIT_PENDING");
        assertThat(activeConclusionCodes(idReturned)).containsExactly("AUDIT_PENDING");
        assertThat(activeConclusionCodes(idAutoPassed)).containsExactly("NO_POLICY_MATCH");
        System.out.printf("[①] 起点：QT-4101/02/03 = AUDIT_PENDING，QT-4104 = NO_POLICY_MATCH%n");
    }

    @Test
    @Order(2)
    @DisplayName("② 7→8 自动过审：active 结论标 == [AUTO_PASSED]，NO_POLICY_MATCH 已解除（released_at/by 有值）")
    void autoPassLandsAutoPassed() {
        // 第二轮：给 QT-4104 的渠道补一条 AUTO 策略 ⇒ 自动过审（7→8），
        // 这正是「上一轮不命中 → 这一轮自动过审」那条路径（历史上就是它漏过 BLOCKED/NO_POLICY_MATCH 的结算）
        createAutoPolicy();
        JsonNode run = ok(post("/admin/order-audit/run", "{}"));
        assertThat(run.path("autoPass").asInt()).as("QT-4104 该被自动过审：%s", run).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(idAutoPassed)).as("7 待审核 → 8 待发货").isEqualTo(8);

        assertThat(activeConclusionCodes(idAutoPassed))
                .as("自动过审的当前结论就是 AUTO_PASSED（不是空）")
                .containsExactly(AuditTagCatalogService.CODE_AUTO_PASSED);
        assertThat(conclusionTagCount(idAutoPassed)).as("一单只有一条 active 结论标").isEqualTo(1);

        Map<String, Object> old = anyTagRow(idAutoPassed, "NO_POLICY_MATCH");
        assertThat(((Number) old.get("active")).intValue()).as("旧结论标必须被解除").isZero();
        assertThat(old.get("released_at")).as("解除时间必须记下").isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .as("自动过审是「引擎给出了新结论」⇒ 旧结论被取代").isEqualTo("SYSTEM:SUPERSEDED");

        Map<String, Object> landed = anyTagRow(idAutoPassed, AuditTagCatalogService.CODE_AUTO_PASSED);
        assertThat(landed.get("released_at")).as("终局标是 active 的").isNull();
        assertThat(String.valueOf(landed.get("reason")))
                .as("标的 reason 用引擎的判定原话（不是一句等于没说的话）").isNotBlank();

        System.out.println("[②] QT-4104（NO_MATCH → 自动过审）active 结论标 = "
                + activeConclusionCodes(idAutoPassed) + "；NO_POLICY_MATCH released_by=" + old.get("released_by"));
    }

    @Test
    @Order(3)
    @DisplayName("③ 7→8 人工放行：active 结论标 == [AUDIT_PASSED]（旧 AUDIT_PENDING 已解除）")
    void manualPassLandsAuditPassed() {
        assertThat(activeConclusionCodes(idPassed)).as("起点：待人工审核").containsExactly("AUDIT_PENDING");

        ok(post("/admin/orders/" + idPassed + "/audit-pass", null));
        assertThat(statusOf(idPassed)).as("7 待审核 → 8 待发货").isEqualTo(8);

        assertThat(activeConclusionCodes(idPassed))
                .as("放行后「当前结论」仍然存在：人工放行通过（不是空）")
                .containsExactly(AuditTagCatalogService.CODE_AUDIT_PASSED);
        assertThat(conclusionTagCount(idPassed)).as("一单只有一条 active 结论标（互斥）").isEqualTo(1);

        Map<String, Object> old = anyTagRow(idPassed, "AUDIT_PENDING");
        assertThat(((Number) old.get("active")).intValue()).isZero();
        assertThat(old.get("released_at")).isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .as("解除人要说清是「放行结算」").isEqualTo("SYSTEM:AUDIT_PASS");
        // 队列/显示层口径：AUDIT_PASSED 是 visible=0，所以它不会跑到队列的标区里
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_PASSED).getVisible())
                .as("visible=0 ⇒ 队列行不显示它（数据上有事实，页面上没有噪声）").isZero();
        Map<String, Object> row = queueRowByTag("AUDIT_PASSED", "QT-4101");
        assertThat(strings(row.get("tagCodes")))
                .as("渲染输入（visible=1 的标）里没有它：队列不显示「人工放行通过」这枚标").isEmpty();
        assertThat(strings(row.get("allTagCodes")))
                .as("数据层（全部 active 标）里必须有它 —— 判断只能读 allTagCodes").containsExactly("AUDIT_PASSED");
        assertThat(row.get("conclusionTerminalTag")).as("终局轴答得出「它最后怎么了」").isEqualTo("AUDIT_PASSED");
        assertThat(row.get("conclusionSource")).as("结论来源是终局标（不是 DECISION 退回）").isEqualTo("TERMINAL");
        assertThat(row.get("primaryTagCode")).as("主标只在可见标里挑 ⇒ 只剩一枚 visible=0 的标时主标为 null").isNull();

        System.out.println("[③] QT-4101（AUDIT_PENDING → 人工放行）active 结论标 = "
                + activeConclusionCodes(idPassed) + "；AUDIT_PENDING released_by=" + old.get("released_by"));
    }

    @Test
    @Order(4)
    @DisplayName("④ 7→4 审核不通过：active 结论标 == [AUDIT_REJECTED]（旧 AUDIT_PENDING 已解除）")
    void auditRejectLandsAuditRejected() {
        assertThat(activeConclusionCodes(idRejected)).containsExactly("AUDIT_PENDING");

        ok(post("/admin/orders/" + idRejected + "/audit-reject", null));
        assertThat(statusOf(idRejected)).as("7 待审核 → 4 已关闭").isEqualTo(4);

        assertThat(activeConclusionCodes(idRejected))
                .as("关单后「当前结论」= 审核不通过（状态 4 只说关了，说不出是人工关的）")
                .containsExactly(AuditTagCatalogService.CODE_AUDIT_REJECTED);
        assertThat(conclusionTagCount(idRejected)).isEqualTo(1);

        Map<String, Object> old = anyTagRow(idRejected, "AUDIT_PENDING");
        assertThat(((Number) old.get("active")).intValue()).isZero();
        assertThat(old.get("released_at")).isNotNull();
        assertThat(String.valueOf(old.get("released_by"))).isEqualTo("SYSTEM:AUDIT_REJECT");

        System.out.println("[④] QT-4102（AUDIT_PENDING → 审核不通过）active 结论标 = "
                + activeConclusionCodes(idRejected) + "；AUDIT_PENDING released_by=" + old.get("released_by"));
    }

    @Test
    @Order(5)
    @DisplayName("⑤ 7→5 申请退货：active 结论标 == [RETURN_APPLIED]（旧 AUDIT_PENDING 已解除）")
    void returnApplyLandsReturnApplied() {
        assertThat(activeConclusionCodes(idReturned)).containsExactly("AUDIT_PENDING");

        JsonNode apply = ok(post("/admin/return-applies",
                "{\"orderId\":" + idReturned + ",\"memberId\":" + memberId + ",\"reasonId\":" + reasonId
                        + ",\"returnReason\":\"终局标用例：申请退货\"}"));
        assertThat(apply.path("id").asLong()).as("退货申请应真的落库").isPositive();
        assertThat(statusOf(idReturned)).as("7 待审核 → 5 退款中").isEqualTo(5);

        assertThat(activeConclusionCodes(idReturned))
                .as("「当前结论」= 已申请退货（visible=1：队列里看不到订单状态，只有它能说明这单在退货流程里）")
                .containsExactly(AuditTagCatalogService.CODE_RETURN_APPLIED);
        assertThat(conclusionTagCount(idReturned)).isEqualTo(1);

        Map<String, Object> old = anyTagRow(idReturned, "AUDIT_PENDING");
        assertThat(((Number) old.get("active")).intValue())
                .as("AUDIT_PENDING（blocking=1）必须解除，否则下发保险会误拦").isZero();
        assertThat(old.get("released_at")).isNotNull();
        assertThat(String.valueOf(old.get("released_by"))).isEqualTo("SYSTEM:RETURN_APPLY");

        Map<String, Object> row = queueRowByTag("RETURN_APPLIED", "QT-4103");
        assertThat(strings(row.get("tagCodes")))
                .as("visible=1 ⇒ 它**出现在队列的渲染输入里**（队列看不到订单状态，这是唯一能说明\"在退货流程里\"的地方）")
                .containsExactly("RETURN_APPLIED");
        assertThat(row.get("conclusionTerminalTag")).isEqualTo("RETURN_APPLIED");
        assertThat(row.get("conclusionSource")).isEqualTo("TERMINAL");

        System.out.println("[⑤] QT-4103（AUDIT_PENDING → 申请退货）active 结论标 = "
                + activeConclusionCodes(idReturned) + "；AUDIT_PENDING released_by=" + old.get("released_by"));
    }

    // ================================================================== ⑥ 聚合推导那一支：只解不落

    /**
     * 7→2/3/9 是<b>包裹事实推导</b>，不是审核结论 —— 所以「只解不落」是设计，不是漏。
     *
     * <p>为什么这条用纯函数断言而不是造一条聚合流转：{@code DeliveryFlowService#recompute} 要把
     * 7 推成 2/3/9 需要一整套包裹/出库事件，而这里要钉的只是<b>那张映射表的答案</b>
     * （{@code OrderStatusService} 只是照它执行）。造一整套包裹事件来验"落不落标"属于拿大炮打蚊子，
     * 真库那一侧的行为由 {@code StatusEntryGuardTest} ③ 用 mock 断言（release 调了、ensureActive 没调）。
     */
    @Test
    @Order(6)
    @DisplayName("⑥ 映射表：7→2/3/9（聚合推导）与 7→8(聚合) 都不落终局标；四条结算路各落各的")
    void terminalCodeMappingTable() {
        assertThat(ConclusionTags.terminalCodeOf(8, ReasonCodes.AUDIT_PASS))
                .isEqualTo(AuditTagCatalogService.CODE_AUDIT_PASSED);
        assertThat(ConclusionTags.terminalCodeOf(8, ReasonCodes.AUDIT_AUTO_PASS))
                .isEqualTo(AuditTagCatalogService.CODE_AUTO_PASSED);
        assertThat(ConclusionTags.terminalCodeOf(4, ReasonCodes.AUDIT_REJECT))
                .isEqualTo(AuditTagCatalogService.CODE_AUDIT_REJECTED);
        assertThat(ConclusionTags.terminalCodeOf(5, ReasonCodes.RETURN_APPLY))
                .isEqualTo(AuditTagCatalogService.CODE_RETURN_APPLIED);

        // 聚合推导：包裹事实，不是审核结论 ⇒ 不落（只由 change 里的 releaseAll 收尾）
        assertThat(ConclusionTags.terminalCodeOf(2, ReasonCodes.DELIVERY_AGGREGATE)).isNull();
        assertThat(ConclusionTags.terminalCodeOf(3, ReasonCodes.DELIVERY_AGGREGATE)).isNull();
        assertThat(ConclusionTags.terminalCodeOf(9, ReasonCodes.DELIVERY_AGGREGATE)).isNull();
        assertThat(ConclusionTags.terminalCodeOf(8, ReasonCodes.DELIVERY_AGGREGATE))
                .as("7→8 也可能是聚合推导出来的 ⇒ 判据必须带 reason，不能只看目标状态").isNull();

        // 三枚终局标必须被"终局轴"认出来（否则对账会拿它们去跟 result 比，制造假冲突）
        assertThat(ConclusionTags.terminalCodesFromSettlement())
                .containsExactlyInAnyOrder(AuditTagCatalogService.CODE_AUDIT_PASSED,
                        AuditTagCatalogService.CODE_AUDIT_REJECTED, AuditTagCatalogService.CODE_RETURN_APPLIED);
        assertThat(ConclusionTags.terminalCodesFromSettlement())
                .allSatisfy(code -> assertThat(ConclusionTags.isTerminalConclusion(code)).isTrue());
        // 判定轴的码不许被当成终局码（它们与 result 必须对得上，参与 valueMismatch 检查）
        assertThat(ConclusionTags.isTerminalConclusion("AUDIT_PENDING")).isFalse();
        assertThat(ConclusionTags.isTerminalConclusion("BLOCKED")).isFalse();
        assertThat(ConclusionTags.isTerminalConclusion("NO_POLICY_MATCH")).isFalse();

        System.out.println("[⑥] 映射表：7→8(人工)=AUDIT_PASSED / 7→8(自动)=AUTO_PASSED / 7→4=AUDIT_REJECTED / "
                + "7→5=RETURN_APPLIED；7→2/3/9 与 7→8(聚合) 均为 null（只解不落）");
    }

    // ================================================================== 真实入口

    private Long createPendingOrder(String orderSn, String channel, String csRemark) {
        String remark = csRemark == null ? "" : ",\"csRemark\":\"" + csRemark + "\"";
        String json = "{\"order\":{\"orderSn\":\"" + orderSn + "\",\"memberId\":" + memberId
                + ",\"status\":7,\"channel\":\"" + channel + "\"" + remark
                + ",\"receiverName\":\"终局标用例收货人\",\"receiverPhone\":\"13700000001\""
                + ",\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"终局标用例-明细\",\"productPrice\":10.00,\"productQuantity\":1}]}";
        JsonNode data = ok(post("/admin/orders", json));
        Long id = data.path("id").asLong();
        assertThat(id).as("建单应返回 id：%s", data).isPositive();
        return id;
    }

    /** AUTO 命中 + **本策略的**排除条件挡下 ⇒ MANUAL（AUDIT_PENDING 的来路） */
    private void createManualPolicy() {
        String json = "{\"type\":\"AUTO\",\"priority\":50,\"name\":\"" + POLICY_MANUAL + "\",\"enabled\":true,"
                + "\"conditions\":["
                + "{\"kind\":\"MATCH\",\"scope\":\"POLICY\",\"fieldCode\":\"CHANNEL\",\"opCode\":\"EQ\","
                + "\"valueType\":\"TEXT\",\"valueText\":\"" + CH_MANUAL + "\"},"
                + "{\"kind\":\"EXCLUDE\",\"scope\":\"POLICY\",\"fieldCode\":\"CS_REMARK\",\"opCode\":\"CONTAINS\","
                + "\"valueType\":\"TEXT\",\"valueText\":\"改\"}]}";
        ok(post("/admin/audit-policies", json));
    }

    /** 只认自动过审那个渠道、条件全满足 ⇒ AUTO_PASS */
    private void createAutoPolicy() {
        String json = "{\"type\":\"AUTO\",\"priority\":40,\"name\":\"" + POLICY_AUTO + "\",\"enabled\":true,"
                + "\"conditions\":[{\"kind\":\"MATCH\",\"scope\":\"POLICY\",\"fieldCode\":\"CHANNEL\",\"opCode\":\"EQ\","
                + "\"valueType\":\"TEXT\",\"valueText\":\"" + CH_AUTO + "\"}]}";
        ok(post("/admin/audit-policies", json));
    }

    // ================================================================== 夹具

    private void seedFixtures() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '终局标测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '终局标测试类型')");
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES ('终局标测试商品', '期3步骤2.5用', ?, ?, 1, 0)", categoryId, typeId);
        skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, 10.00, 1000, 0, 8.00)",
                productId);
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, 1000)", skuId);
        // audit-pass 会真的拆单（生成发货单），拆单必须有启用仓可兜底
        insertAndReturnKey("INSERT INTO warehouse (name, status) VALUES (?, 1)", WAREHOUSE);
        reasonId = insertAndReturnKey("INSERT INTO return_reason (name) VALUES ('终局标用例原因')");
        insertAndReturnKey("INSERT INTO admin (username, password, nickname, status) VALUES (?, ?, '终局标用例管理员', 1)",
                "admin", passwordEncoder.encode(ADMIN_PASSWORD));
        // 买家：申请退货要 memberId
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '终局标用例会员', 1, NOW(), 0, '普通')",
                "terminal-tag-" + System.currentTimeMillis());
    }

    private String login() {
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login",
                Map.of("username", "admin", "password", ADMIN_PASSWORD), JsonNode.class);
        String token = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(token).as("admin 登录失败：%s", resp.getBody()).isNotBlank();
        return token;
    }

    // ================================================================== 查库工具

    private int statusOf(Long orderId) {
        Integer s = jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        return s == null ? -1 : s;
    }

    /** 这单身上「结论」档的 active 标码（判据 = 目录 category='结论' 或 FROZEN% 前缀，与生产同口径） */
    private List<String> activeConclusionCodes(Long orderId) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE t.order_id = ? AND t.active = 1 "
                        + "  AND (c.category = '结论' OR t.tag_code LIKE 'FROZEN%') ORDER BY t.id",
                String.class, orderId);
    }

    private int conclusionTagCount(Long orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE t.order_id = ? AND t.active = 1 "
                        + "  AND (c.category = '结论' OR t.tag_code LIKE 'FROZEN%')", Integer.class, orderId);
        return n == null ? -1 : n;
    }

    private Map<String, Object> anyTagRow(Long orderId, String tagCode) {
        return jdbc.queryForMap("SELECT * FROM order_audit_tag WHERE order_id = ? AND tag_code = ? "
                + "ORDER BY id DESC LIMIT 1", orderId, tagCode);
    }

    /** 按标码筛队列行，再按单号取那一行（接口级证据：visible 决定它进不进渲染输入） */
    private Map<String, Object> queueRowByTag(String tagCode, String orderSn) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>)
                queryService.queue(null, tagCode, 1, 100).get("rows");
        return rows.stream()
                .filter(r -> orderSn.equals(String.valueOf(r.get("order_sn"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("?tag=" + tagCode + " 的队列行里没有 " + orderSn));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        return raw == null ? List.of() : (List<String>) raw;
    }

    private void cleanOurData() {
        ItCleanup.purgeOrders(jdbc, SN_LIKE);
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
        jdbc.update("DELETE FROM warehouse WHERE name = ?", WAREHOUSE);
    }

    // ================================================================== HTTP 辅助

    private ResponseEntity<JsonNode> post(String path, String json) {
        return exchange(HttpMethod.POST, path, json);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (adminJwt != null) headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    private Long insertAndReturnKey(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("自增主键应取到：%s", sql).isNotNull();
        return key.longValue();
    }
}
