package com.qiqi.mall.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditVerdict;
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
 * <b>V9（2026-11-16）的验收口径：结算回到「只解不落」</b> —— 本类原名
 * {@code AuditConclusionTerminalTagIntegrationTest}，钉的是期 3 步骤 2.5 那套"落终局结论标"，
 * 那套设计已被 V9 收回（助手自己拍板、推翻用户 16:38 的拍板）。本类保留全部真实入口造数，
 * 但断言整体翻面，钉的是<b>停用之后会发生什么</b>：
 * <pre>
 *   7 → 8 人工放行      ⇒ active 结论标 == []                （"过了"由状态 8 表达）
 *   7 → 8 自动引擎过审  ⇒ active 结论标 == []                （V7 曾落 AUTO_PASSED，已收回）
 *   7 → 4 审核不通过    ⇒ active 结论标 == []                （"没过"由状态 4 表达）
 *   7 → 5 登记退货申请  ⇒ active 结论标 == [RETURN_APPLIED]   （退货是**事实**，visible=1）
 *   7 → 2/3/9 聚合推导  ⇒ active 结论标 == []                （包裹事实，本就不落）
 * </pre>
 * 每条路径同时验三件事：① 结算**只解不落**（不再补一枚"通过/不通过"标）；
 * ② <b>旧标（AUDIT_PENDING / NO_POLICY_MATCH…）active=0 且 released_at / released_by 有值</b>
 * （不是删行、也不是并挂两条）；③ 那三枚码<b>一行都没有被写过</b>（不是"写了又被解掉"）。
 *
 * <h3>为什么造数走真实入口</h3>
 * 建单 {@code POST /admin/orders}（status=7）→ 跑一轮 {@code POST /admin/order-audit/run}
 * （落 AUDIT_PENDING / NO_POLICY_MATCH）→ 人工放行 / 不通过 / 申请退货都走各自的 HTTP 入口。
 * 直插的标证明不了任何入口会结算它 —— 那正是本类要钉的东西。只有商品/规格/桶/退货原因字典
 * 这些<b>夹具</b>是直插的（与其它 IT 同口径，它们不是被测对象）。
 *
 * <p>单号前缀 {@code QT-410x}（自建自清，不与 QT-100x/200x/300x/400x 混）。
 * 运行：{@code ./scripts/run-it.sh -Dtest=AuditSettlementLeavesNoPassTagTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditSettlementLeavesNoPassTagTest {

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

        // V9 必须已经把三枚「通过 / 不通过」标停用（enabled=0，**行不删**）
        // —— 这条断言同时证明「迁移真的跑过」，而不是测试自己插的目录项
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUTO_PASSED))
                .as("V9 停用但**不删行**：历史单身上的标码要有中文名可查").isNotNull();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_PASSED)).isNotNull();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_REJECTED)).isNotNull();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUTO_PASSED).getEnabled())
                .as("V9：AUTO_PASSED 停用（用户 16:38：绝不会有一个叫『已通过』的标）").isZero();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_PASSED).getEnabled()).isZero();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_REJECTED).getEnabled()).isZero();
        // 唯一保留的结算事实标：退货申请（不是"过没过"，是事实）
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_RETURN_APPLIED)).isNotNull();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_RETURN_APPLIED).getEnabled()).isEqualTo(1);
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
    @DisplayName("② 7→8 自动过审：active 结论标 == []，NO_POLICY_MATCH 已解除（released_at/by 有值），AUTO_PASSED 一行都没写过")
    void autoPassLeavesNoConclusionTag() {
        // 第二轮：给 QT-4104 的渠道补一条 AUTO 策略 ⇒ 自动过审（7→8），
        // 这正是「上一轮不命中 → 这一轮自动过审」那条路径（历史上就是它漏过 BLOCKED/NO_POLICY_MATCH 的结算）
        createAutoPolicy();
        JsonNode run = ok(post("/admin/order-audit/run", "{}"));
        assertThat(run.path("autoPass").asInt()).as("QT-4104 该被自动过审：%s", run).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(idAutoPassed)).as("7 待审核 → 8 待发货").isEqualTo(8);

        assertThat(activeConclusionCodes(idAutoPassed))
                .as("V9：自动过审**不落标** —— 「它过了」由状态 8 表达（用户 16:38：通过与否由状态表达，不由标表达）")
                .isEmpty();
        assertThat(conclusionTagCount(idAutoPassed)).as("离开待审核后身上没有结论标，这是设计").isZero();
        assertThat(tagRowCount(idAutoPassed, AuditTagCatalogService.CODE_AUTO_PASSED))
                .as("不是「写了又被解掉」：这条落标路径已经整段不存在，一行都没有").isZero();

        Map<String, Object> old = anyTagRow(idAutoPassed, "NO_POLICY_MATCH");
        assertThat(((Number) old.get("active")).intValue()).as("旧结论标必须被解除").isZero();
        assertThat(old.get("released_at")).as("解除时间必须记下").isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .as("自动过审是「引擎给出了新结论」⇒ 旧结论被取代").isEqualTo("SYSTEM:SUPERSEDED");

        System.out.println("[②] QT-4104（NO_MATCH → 自动过审 7→8）active 结论标 = "
                + activeConclusionCodes(idAutoPassed) + "；NO_POLICY_MATCH released_by=" + old.get("released_by")
                + "，released_at=" + old.get("released_at") + "；AUTO_PASSED 行数=0");
    }

    @Test
    @Order(3)
    @DisplayName("③ 7→8 人工放行：active 结论标 == []（旧 AUDIT_PENDING 已解除；AUDIT_PASSED 一行都没写过）")
    void manualPassLeavesNoConclusionTag() {
        assertThat(activeConclusionCodes(idPassed)).as("起点：待人工审核").containsExactly("AUDIT_PENDING");

        ok(post("/admin/orders/" + idPassed + "/audit-pass", null));
        assertThat(statusOf(idPassed)).as("7 待审核 → 8 待发货").isEqualTo(8);

        assertThat(activeConclusionCodes(idPassed))
                .as("V9：放行后身上**没有**结论标 —— 「过了」由状态 8 表达（用户 16:38：绝不会有一个叫『已通过』的标）")
                .isEmpty();
        assertThat(conclusionTagCount(idPassed)).isZero();
        assertThat(tagRowCount(idPassed, AuditTagCatalogService.CODE_AUDIT_PASSED))
                .as("不是「写了又被解掉」：这张单上从来没有过 AUDIT_PASSED").isZero();

        Map<String, Object> old = anyTagRow(idPassed, "AUDIT_PENDING");
        assertThat(((Number) old.get("active")).intValue()).isZero();
        assertThat(old.get("released_at")).isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .as("解除人要说清是「放行结算」").isEqualTo("SYSTEM:AUDIT_PASS");
        // 队列/显示层口径：队列行上一个标都没有，结论退回决策行（这是如实回答，不是缺口）
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_PASSED).getEnabled())
                .as("目录项停用 ⇒ 下拉/维护页不再提供它").isZero();
        Map<String, Object> row = queueRowOf("QT-4101");
        assertThat(strings(row.get("tagCodes"))).as("渲染输入里一个标都没有").isEmpty();
        assertThat(strings(row.get("allTagCodes"))).as("数据层也没有（不是 visible=0 藏起来，是真的没有）").isEmpty();
        assertThat(row.get("conclusionSettlementFact")).as("没有结算事实标").isNull();
        assertThat(row.get("conclusionFromTag")).as("判定结论读不出（离开待审核后这是设计）").isNull();
        assertThat(row.get("conclusionSource")).as("结论来源退回决策行").isEqualTo("DECISION");
        assertThat(row.get("primaryTagCode")).as("没有标 ⇒ 没有主标").isNull();

        System.out.println("[③] QT-4101（AUDIT_PENDING → 人工放行 7→8）active 结论标 = "
                + activeConclusionCodes(idPassed) + "；AUDIT_PENDING released_by=" + old.get("released_by")
                + "，released_at=" + old.get("released_at") + "；conclusionSource=" + row.get("conclusionSource"));
    }

    @Test
    @Order(4)
    @DisplayName("④ 7→4 审核不通过：active 结论标 == []（旧 AUDIT_PENDING 已解除；AUDIT_REJECTED 一行都没写过）")
    void auditRejectLeavesNoConclusionTag() {
        assertThat(activeConclusionCodes(idRejected)).containsExactly("AUDIT_PENDING");

        ok(post("/admin/orders/" + idRejected + "/audit-reject", null));
        assertThat(statusOf(idRejected)).as("7 待审核 → 4 已关闭").isEqualTo(4);

        assertThat(activeConclusionCodes(idRejected))
                .as("V9：关单后身上**没有**结论标 —— 「没过」由状态 4 表达")
                .isEmpty();
        assertThat(conclusionTagCount(idRejected)).isZero();
        assertThat(tagRowCount(idRejected, AuditTagCatalogService.CODE_AUDIT_REJECTED))
                .as("不是「写了又被解掉」：这张单上从来没有过 AUDIT_REJECTED").isZero();

        Map<String, Object> old = anyTagRow(idRejected, "AUDIT_PENDING");
        assertThat(((Number) old.get("active")).intValue()).isZero();
        assertThat(old.get("released_at")).isNotNull();
        assertThat(String.valueOf(old.get("released_by"))).isEqualTo("SYSTEM:AUDIT_REJECT");

        System.out.println("[④] QT-4102（AUDIT_PENDING → 审核不通过 7→4）active 结论标 = "
                + activeConclusionCodes(idRejected) + "；AUDIT_PENDING released_by=" + old.get("released_by")
                + "，released_at=" + old.get("released_at"));
    }

    @Test
    @Order(5)
    @DisplayName("⑤ 7→5 申请退货：active 结论标 == [RETURN_APPLIED]（唯一保留的结算事实标；旧 AUDIT_PENDING 已解除）")
    void returnApplyLeavesOnlyReturnApplied() {
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
        assertThat(row.get("conclusionSettlementFact")).isEqualTo("RETURN_APPLIED");
        assertThat(row.get("conclusionSource"))
                .as("来源是结算事实标（不与 result 比，对账不拿它当冲突）").isEqualTo("SETTLEMENT_FACT");
        assertThat(row.get("conclusionFromTag")).as("它不是\"这一轮判成了什么\"，判定结论读不出来").isNull();

        System.out.println("[⑤] QT-4103（AUDIT_PENDING → 申请退货 7→5）active 结论标 = "
                + activeConclusionCodes(idReturned) + "；AUDIT_PENDING released_by=" + old.get("released_by")
                + "，released_at=" + old.get("released_at"));
    }

    // ================================================== ⑥ V9 的"停用"到底发生了什么（映射与目录）

    /**
     * 三件事一起钉：目录停用、代码不再产出、唯一保留的事实标仍在。
     *
     * <p>为什么用纯函数/目录断言补这一条：上面的 ②③④⑤ 证明的是「真实入口不再落那三枚标」，
     * 但"为什么不再落"可能有两种原因（目录停用 vs 代码不写）。这条把两头都钉死 ——
     * 只有目录停用而代码还写，标会以"目录里查不到"的形态重新出现；只有代码不写而目录还启用，
     * 下拉里就会留下一个永远筛不到东西的选项。
     */
    @Test
    @Order(6)
    @DisplayName("⑥ 三枚「通过/不通过」标：目录 enabled=0 + 代码不再产出（codeOf(AUTO_PASS)==null）；RETURN_APPLIED 保留")
    void passFailTagsDisabledEverywhere() {
        // 目录：停用，但行还在（只增不删）
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUTO_PASSED).getEnabled()).isZero();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_PASSED).getEnabled()).isZero();
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_AUDIT_REJECTED).getEnabled()).isZero();
        // 目录：只剩"事实"那一枚还启用
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_RETURN_APPLIED).getEnabled()).isEqualTo(1);

        // 代码：AUTO_PASS 不再产标（V7 那条口径已收回）
        AuditVerdict autoPass = new AuditVerdict(AuditResult.AUTO_PASS, null, List.of(), List.of(), List.of(),
                null, List.of(), null, "自动过审（测试构造）", Map.of());
        assertThat(ConclusionTags.codeOf(autoPass)).as("AUTO_PASS 不落标").isNull();
        assertThat(ConclusionTags.producesTag(AuditResult.AUTO_PASS))
                .as("AUTO_PASS 不在\"会落标\"的结论里").isFalse();

        // 代码：那两枚"过没过"码不再被任何映射产出/认识
        assertThat(ConclusionTags.conclusionCodesWrittenHere())
                .as("这张清单里不该再有那三枚码")
                .doesNotContain(AuditTagCatalogService.CODE_AUTO_PASSED,
                        AuditTagCatalogService.CODE_AUDIT_PASSED,
                        AuditTagCatalogService.CODE_AUDIT_REJECTED);
        assertThat(ConclusionTags.resultOf(AuditTagCatalogService.CODE_AUDIT_PASSED))
                .as("读不出结论值（它已不是结论标；拿它冒充 AUTO_PASS 就是伪造结论）").isEmpty();

        // 代码：唯一保留的事实标认得出来，且只有它
        assertThat(ConclusionTags.isSettlementFact(AuditTagCatalogService.CODE_RETURN_APPLIED)).isTrue();
        assertThat(ConclusionTags.isSettlementFact(AuditTagCatalogService.CODE_AUDIT_PASSED)).isFalse();
        assertThat(ConclusionTags.isSettlementFact("AUDIT_PENDING")).isFalse();
        assertThat(ConclusionTags.isSettlementFact("BLOCKED")).isFalse();

        System.out.println("[⑥] 目录：AUTO_PASSED/AUDIT_PASSED/AUDIT_REJECTED enabled=0（行仍在）；"
                + "RETURN_APPLIED enabled=1；codeOf(AUTO_PASS)=null；conclusionCodesWrittenHere="
                + ConclusionTags.conclusionCodesWrittenHere());
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

    /** 这单上某个标码的**全部行数**（含已解除的）—— 用来证明"不是写了又被解掉" */
    private int tagRowCount(Long orderId, String tagCode) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag WHERE order_id = ? AND tag_code = ?",
                Integer.class, orderId, tagCode);
        return n == null ? -1 : n;
    }

    /** 队列里按单号取那一行（不按标筛：V9 之后终局单身上已经没有标可筛了） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> queueRowOf(String orderSn) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) queryService.queue(null, null, 1, 100).get("rows");
        return rows.stream()
                .filter(r -> orderSn.equals(String.valueOf(r.get("order_sn"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("队列里没有 " + orderSn));
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
