package com.qiqi.mall.audit;

import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditSetting;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.ConditionEvaluator;
import com.qiqi.mall.audit.engine.ConditionTrace;
import com.qiqi.mall.audit.engine.ConditionView;
import com.qiqi.mall.audit.engine.FieldRegistry;
import com.qiqi.mall.audit.engine.PolicyType;
import com.qiqi.mall.audit.engine.PolicyView;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
import com.qiqi.mall.audit.service.AuditContextLoader;
import com.qiqi.mall.audit.service.AuditPolicyService;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>期 2 步骤 1 的验收口径</b>：引擎的条件能「读标」。
 *
 * <p>用户原话：<i>标是策略之间的传送带：策略的条件要能引用标。第一个检测点命中 → 打标；
 * 第二个检测点读标 → 决定动作。<b>没有这一条，管线不成立，标永远只是装饰。</b></i>
 *
 * <p>这个类就是「读标」六条硬要求的可执行版本，外加一条本步骤最容易忽略的影响评估
 * （改了 {@code AuditContextLoader} 之后冻结短路还成不成立）：
 * <ol>
 *   <li>{@code HAS} 命中一个存在的特征标 → true；</li>
 *   <li>{@code NOT_HAS} 在没有该标时 → true；</li>
 *   <li><b>子串陷阱</b>：标码 {@code FROZEN_RETURN_APPLY} 存在时，{@code HAS 'FROZEN'} 必须 false
 *       —— 这条专门钉住「不许退化成字符串包含」；</li>
 *   <li><b>空集合</b>：{@code HAS} 任何码 → false；{@code NOT_HAS} → true（空集合不算"有值"）；</li>
 *   <li><b>字段缺失 ≠ 空集合</b>：没装配（{@code activeTags == null}）是不可评估（抛异常），
 *       空集合是可评估的"一标没有"（判 false，不抛）；</li>
 *   <li><b>结论标被保存校验拒掉</b>：策略配 {@code ACTIVE_TAGS HAS NO_POLICY_MATCH} → 保存失败 + 中文原因；</li>
 *   <li>（附加）{@code AuditContextLoader} 从 {@code selectActiveFrozen} 改成 {@code selectActive} 之后，
 *       判定链第 2 步的<b>冻结短路</b>仍然成立（同时挂着 FROZEN 与特征标的单仍是 SKIPPED）。</li>
 * </ol>
 *
 * <h3>为什么要有第 ③ 条（子串陷阱）</h3>
 * 标码是「前缀 + 细分」命名的（{@code FROZEN} / {@code FROZEN_RETURN_APPLY}）。如果用
 * {@code CONTAINS} 判逗号串，{@code HAS 'FROZEN'} 会把所有细分冻结标都算命中 ——
 * 一条"有没有普通冻结标"的条件悄悄变成"有没有任何冻结标"，不报错、不留痕。所以多值字段的求值
 * 必须是**元素相等**（见 {@code ConditionEvaluator#evaluateList}），这条用例就是它的反面证据。
 *
 * <h3>造数</h3>
 * 引擎侧用例（①②③④⑤）是纯内存的：上下文里直接给标集合，不碰库。
 * 保存校验（⑥）与冻结短路（⑦）要走真服务 + 真库，所以整个类跑在隔离库 {@code qiqi_mall_it} 上
 * （{@link ItDatabase} 每次 JVM 重建，用完即弃）。
 *
 * <p>运行：{@code ./scripts/run-it.sh -Dtest=AuditTagConditionTest}
 */
@SpringBootTest(properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditTagConditionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 18, 0);

    /** 本类自建的特征标（存在目录里，category='特征'） */
    private static final String TAG_VIP = "VIP_CUSTOMER";
    private static final String TAG_COMPLAINT = "COMPLAINT_CUSTOMER";
    /** 不存在的标码：目录里没有，也没打到任何单上 */
    private static final String TAG_ABSENT = "NEVER_TAGGED_CODE";
    /** 策略名：本类造的策略（清理与"保存失败没落库"断言都靠它定位） */
    private static final String POLICY_OK = "读标-特征标可引用";
    private static final String POLICY_BAD = "读标-结论标禁用";

    @DynamicPropertySource
    static void itDatasource(DynamicPropertyRegistry registry) {
        ItDatabase.prepare();
        registry.add("spring.datasource.url", ItDatabase::url);
        registry.add("spring.datasource.username", ItDatabase::username);
        registry.add("spring.datasource.password", ItDatabase::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuditPolicyService policyService;
    @Autowired
    private AuditTagCatalogService tagCatalog;
    @Autowired
    private AuditContextLoader contextLoader;

    @BeforeAll
    void prepareCatalog() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        // 两个特征标：一个给 ①（命中）、一个给 ②（没挂上，NOT_HAS 成立）
        tagCatalog.createCustom(TAG_VIP, "客诉客户", "客户有过客诉记录（特征标，与过没过无关）", "特征", 0);
        tagCatalog.createCustom(TAG_COMPLAINT, "待回复客诉", "有客诉工单还没回复（特征标）", "特征", 0);
        tagCatalog.reload();
        assertThat(tagCatalog.byCode(TAG_VIP).getCategory())
                .as("V4 的目录必须能装特征标（本步骤只读标，不落特征标）")
                .isEqualTo(AuditTagCatalogService.CATEGORY_FEATURE);
        System.out.println("[准备] 特征标：" + TAG_VIP + " / " + TAG_COMPLAINT
                + "；结论标 NO_POLICY_MATCH 在目录里（" + tagCatalog.nameOf("NO_POLICY_MATCH") + "）");
    }

    @AfterAll
    void cleanUp() {
        // 策略随条件级联删除（DDL ON DELETE CASCADE）；自定义标目录项只在 builtin=0 上删
        int policies = jdbc.update("DELETE FROM audit_policy WHERE name IN (?, ?)", POLICY_OK, POLICY_BAD);
        int tags = jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0");
        System.out.println("[清理] 策略 " + policies + " 条 / 自定义目录项 " + tags + " 条");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_policy WHERE name IN (?, ?)",
                Integer.class, POLICY_OK, POLICY_BAD)).as("清理后不许有残留策略").isZero();
    }

    // ================================================================== ① HAS 命中存在的特征标

    @Test
    @Order(1)
    @DisplayName("① HAS 命中一个存在的特征标 → true")
    void hasHitsExistingFeatureTag() {
        AuditContext ctx = contextWithTags(List.of(TAG_VIP, TAG_COMPLAINT));
        ConditionTrace trace = ConditionEvaluator.evaluate(ctx, has(TAG_VIP));

        assertThat(trace.satisfied()).as("这单挂着 VIP_CUSTOMER，HAS 必须成立").isTrue();
        assertThat(trace.fieldCode()).isEqualTo(FieldRegistry.ACTIVE_TAGS);
        assertThat(trace.actual()).as("实际值把人能看的标码列出来").contains(TAG_VIP);
        assertThat(trace.text()).as("文案要点明这是「有标」，不能与「文本含」混同")
                .isEqualTo("当前标记 有标「" + TAG_VIP + "」（实际 " + TAG_VIP + "、" + TAG_COMPLAINT + "）");

        // 同一批事实走一遍策略保存：**特征**标必须能存进去（守门不能把合法的也拒了）
        PolicyRequest req = new PolicyRequest("AUTO", 500, POLICY_OK, true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", FieldRegistry.ACTIVE_TAGS, "HAS", "LIST",
                        null, TAG_VIP, null)), null);
        assertThat(policyService.create(req).conditions()).as("特征标可被 ACTIVE_TAGS 引用并落库")
                .hasSize(1);

        System.out.println("[①] HAS " + TAG_VIP + " → satisfied=" + trace.satisfied()
                + "；文案「" + trace.text() + "」；策略保存成功");
    }

    // ================================================================== ② NOT_HAS 在没有该标时成立

    @Test
    @Order(2)
    @DisplayName("② NOT_HAS 在没有该标时 → true（有该标时 → false）")
    void notHasHoldsWhenTagAbsent() {
        AuditContext ctx = contextWithTags(List.of(TAG_VIP));

        ConditionTrace absent = ConditionEvaluator.evaluate(ctx, notHas(TAG_ABSENT));
        assertThat(absent.satisfied()).as("这单没有 NEVER_TAGGED_CODE，NOT_HAS 成立").isTrue();
        assertThat(absent.text()).isEqualTo("当前标记 无标「" + TAG_ABSENT + "」（实际 " + TAG_VIP + "）");

        ConditionTrace present = ConditionEvaluator.evaluate(ctx, notHas(TAG_VIP));
        assertThat(present.satisfied()).as("这单挂着 VIP_CUSTOMER，NOT_HAS 不成立").isFalse();

        System.out.println("[②] NOT_HAS " + TAG_ABSENT + " → " + absent.satisfied()
                + "；NOT_HAS " + TAG_VIP + " → " + present.satisfied());
    }

    // ================================================================== ③ 子串陷阱（本步骤的核心）

    @Test
    @Order(3)
    @DisplayName("③ 子串陷阱：标码 FROZEN_RETURN_APPLY 存在时，HAS FROZEN 必须 false")
    void substringTrap() {
        // 这单只挂着「细分」冻结标，没有普通 FROZEN
        AuditContext ctx = contextWithTags(List.of("FROZEN_RETURN_APPLY", TAG_VIP));

        ConditionTrace trap = ConditionEvaluator.evaluate(ctx, has("FROZEN"));
        assertThat(trap.satisfied())
                .as("『FROZEN_RETURN_APPLY』里包含子串 FROZEN —— 若用字符串包含判就会命中；"
                        + "集合语义必须判 false（这正是多值字段不用 CONTAINS 的原因）")
                .isFalse();
        assertThat(trap.actual()).as("实际值里必须**看得见**那条近似标码，排障时才看得出为什么没命中")
                .contains("FROZEN_RETURN_APPLY");

        // 对照：判它自己（完整的细分码）当然成立 —— 说明"不命中"不是整条都坏了
        assertThat(ConditionEvaluator.evaluate(ctx, has("FROZEN_RETURN_APPLY")).satisfied())
                .as("精确判细分码应当命中").isTrue();
        // 对照：NOT_HAS 'FROZEN' 在子串陷阱下必须为 true（与 HAS 相反，不是"都 false"）
        assertThat(ConditionEvaluator.evaluate(ctx, notHas("FROZEN")).satisfied())
                .as("没有普通 FROZEN 这个元素 → NOT_HAS 成立").isTrue();

        System.out.println("[③] 标码=[FROZEN_RETURN_APPLY," + TAG_VIP + "]：HAS FROZEN → " + trap.satisfied()
                + "（子串陷阱已被集合语义挡掉）；HAS FROZEN_RETURN_APPLY → true");
    }

    // ================================================================== ④ 空集合

    @Test
    @Order(4)
    @DisplayName("④ 空集合：HAS 任何码 → false；NOT_HAS → true；EXISTS → false")
    void emptySetIsNotPresent() {
        AuditContext ctx = contextWithTags(List.of());

        for (String code : List.of(TAG_VIP, TAG_ABSENT, "FROZEN")) {
            assertThat(ConditionEvaluator.evaluate(ctx, has(code)).satisfied())
                    .as("空集合上 HAS %s 必须 false", code).isFalse();
            assertThat(ConditionEvaluator.evaluate(ctx, notHas(code)).satisfied())
                    .as("空集合上 NOT_HAS %s 必须 true（「没有这个标」在空集合上确实成立）", code).isTrue();
        }
        ConditionTrace exists = ConditionEvaluator.evaluate(ctx,
                new ConditionView(null, 0, com.qiqi.mall.audit.engine.ConditionKind.MATCH,
                        com.qiqi.mall.audit.engine.ConditionScope.POLICY,
                        FieldRegistry.ACTIVE_TAGS, "EXISTS",
                        com.qiqi.mall.audit.engine.ValueType.LIST, null, null, null));
        assertThat(exists.satisfied()).as("空集合不算「有值」").isFalse();
        assertThat(exists.actual()).as("文案要写成「空集合」，不能与「无值/未装配」混同").isEqualTo("（空集合）");

        System.out.println("[④] 空集合：HAS VIP_CUSTOMER=false / NOT_HAS VIP_CUSTOMER=true / EXISTS=false（实际 "
                + exists.actual() + "）");
    }

    // ================================================================== ⑤ 字段缺失 vs 空集合

    @Test
    @Order(5)
    @DisplayName("⑤ 字段缺失（没装配）≠ 空集合：前者不可评估（抛），后者判 false")
    void missingFieldIsNotAnEmptySet() {
        // (a) 没装配：activeTags = null（装配点漏传这个组件时就是这个样子）
        AuditContext unassembled = unassembledContext();
        assertThatThrownBy(() -> ConditionEvaluator.evaluate(unassembled, has(TAG_VIP)))
                .as("未装配必须**抛**，不许静默判 false —— 那会让一条配好的读标策略永远不命中且毫无痕迹")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未装配")
                .hasMessageContaining("未装配 ≠ 空集合");

        // (b) 装配了、但这单确实一个标都没有：可评估 → HAS 判 false（不抛）
        AuditContext empty = contextWithTags(List.of());
        ConditionTrace trace = ConditionEvaluator.evaluate(empty, has(TAG_VIP));
        assertThat(trace.satisfied()).isFalse();
        assertThat(trace.actual()).isEqualTo("（空集合）");

        // (c) 两者在文案上必须不同：一个「未装配」、一个「（空集合）」
        assertThat(fmtValue(unassembled)).as("未装配的文案").isEqualTo("未装配");
        assertThat(fmtValue(empty)).as("空集合的文案").isEqualTo("（空集合）");

        System.out.println("[⑤] 未装配 → 抛「未装配 ≠ 空集合」；空集合 → satisfied=false、实际值=「（空集合）」"
                + "（两者文案不同，排障时不会混）");
    }

    // ================================================================== ⑥ 结论标被保存校验拒掉

    @Test
    @Order(6)
    @DisplayName("⑥ 策略配 ACTIVE_TAGS HAS NO_POLICY_MATCH（结论标）→ 保存失败 + 中文原因")
    void conclusionTagRejectedOnSave() {
        PolicyRequest req = new PolicyRequest("AUTO", 500, POLICY_BAD, true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", FieldRegistry.ACTIVE_TAGS, "HAS", "LIST",
                        null, "NO_POLICY_MATCH", null)), null);

        assertThatThrownBy(() -> policyService.create(req))
                .as("结论标（NO_POLICY_MATCH）必须被拒：标是判定**之后**才落库的，"
                        + "引用结论标 = 拿上一轮的照片做这一轮的决定")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能引用「特征」类的标")
                .hasMessageContaining("NO_POLICY_MATCH")
                .hasMessageContaining("结论标")
                .hasMessageContaining("无策略命中");   // 中文名（来自标目录，不是硬编码）

        // 拒在事务里 → 策略一行都不许留下（否则策略列表里会出现一条永远存不成条件的空策略）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_policy WHERE name = ?",
                Integer.class, POLICY_BAD)).as("保存失败必须整体回滚，不留半截策略").isZero();

        // 冻结类标同口径（FROZEN 前缀按结论档处理：它是判定链第 2 步的产物）
        assertThatThrownBy(() -> policyService.create(new PolicyRequest("AUTO", 500, POLICY_BAD, true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", FieldRegistry.ACTIVE_TAGS, "HAS", "LIST",
                        null, "FROZEN_RETURN_APPLY", null)), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冻结标");

        System.out.println("[⑥] ACTIVE_TAGS HAS NO_POLICY_MATCH → 保存被拒（中文原因含中文名「无策略命中」），"
                + "策略行 0 条；FROZEN_RETURN_APPLY 同口径被拒");
    }

    // ================================================================== ⑦（附加）冻结短路没被读标改动破坏

    /**
     * <b>影响评估</b>（本步骤最容易忽略的一条）：{@code AuditContextLoader} 从
     * {@code selectActiveFrozen}（一条 FROZEN 标）改成 {@code selectActive}（全部 active 标）之后，
     * 判定链第 2 步的冻结短路还成立吗？
     *
     * <p>成立 —— 但**必须有证据**，不能靠"看着没问题"。这里用真实装配路径证明：
     * 一单同时挂着 {@code FROZEN_RETURN_APPLY} 和一个特征标，装配出来的上下文仍然
     * {@code frozen=true}、{@code frozenTag=FROZEN_RETURN_APPLY}，引擎仍然 SKIPPED；
     * 同时 {@code activeTags} 两个码都在（读标看得到全部，短路只看 FROZEN 类那一条）。
     */
    @Test
    @Order(7)
    @DisplayName("⑦（附加）selectActive 之后冻结短路仍成立：FROZEN 类的单依旧 SKIPPED")
    void frozenShortCircuitSurvivesSelectActive() {
        long orderId = 900_001L;   // order_audit_tag 没有指向 order 的外键，装配只按 order_id 查标
        try {
            jdbc.update("INSERT INTO order_audit_tag (order_id, tag_code, active, reason) VALUES (?,?,1,?)",
                    orderId, TAG_VIP, "测试：先打一个特征标");
            jdbc.update("INSERT INTO order_audit_tag (order_id, tag_code, active, reason) VALUES (?,?,1,?)",
                    orderId, "FROZEN_RETURN_APPLY", "测试：细分冻结标");

            com.qiqi.mall.order.entity.Order order = new com.qiqi.mall.order.entity.Order();
            order.setId(orderId);
            order.setOrderSn("QT-2101");
            order.setStatus(AuditContext.STATUS_PENDING_AUDIT);
            order.setChannel("淘宝");

            AuditContext ctx = contextLoader.load(order, NOW);

            // ① 冻结短路的口径与改动前逐字一致（selectActiveFrozen: FROZEN 前缀 + ORDER BY id 第一条）
            assertThat(ctx.frozen()).as("挂着 FROZEN 类标的单必须仍是 frozen=true").isTrue();
            assertThat(ctx.frozenTag()).as("取的是那条 FROZEN 类标本身（不是随便一条）")
                    .isEqualTo("FROZEN_RETURN_APPLY");
            // ② 读标能看见全部标（两者同源、同一次查询，不会互相打脸）
            assertThat(ctx.activeTags()).as("activeTags = 该单全部 active 标码")
                    .containsExactly(TAG_VIP, "FROZEN_RETURN_APPLY");
            // ③ 引擎仍然在第 2 步就 SKIPPED（不因为多了读标能力而改变链序）
            AuditVerdict verdict = new AuditEngine().evaluate(ctx,
                    List.of(PolicyView.of(1L, PolicyType.AUTO, 100, "随便一条自动策略", 1,
                            List.of(ConditionView.matchList(FieldRegistry.ACTIVE_TAGS, "HAS", TAG_VIP)))),
                    AuditSetting.defaults());
            assertThat(verdict.result()).as("冻结的单不参与本轮判定").isEqualTo(AuditResult.SKIPPED);
            assertThat(verdict.reasonText()).contains("已被冻结");
            assertThat(verdict.matchedConditions()).as("SKIPPED 不求值任何条件").isEmpty();

            System.out.println("[⑦] 同一单挂 " + TAG_VIP + " + FROZEN_RETURN_APPLY → frozen=" + ctx.frozen()
                    + "、frozenTag=" + ctx.frozenTag() + "、activeTags=" + ctx.activeTags()
                    + "、引擎=" + verdict.result() + "（冻结短路未受影响）");

            // ④ 大小写不敏感的等价性：老 SQL 是 LIKE 'FROZEN%'（utf8mb4_0900_ai_ci），
            //    一条手工写进去的小写 frozen_xxx 在改动前**会**被当成冻结，改动后必须一样
            long lowerId = 900_002L;
            try {
                jdbc.update("INSERT INTO order_audit_tag (order_id, tag_code, active, reason) VALUES (?,?,1,?)",
                        lowerId, "frozen_lower", "测试：大小写不规范的脏数据");
                com.qiqi.mall.order.entity.Order lower = new com.qiqi.mall.order.entity.Order();
                lower.setId(lowerId);
                lower.setOrderSn("QT-2102");
                lower.setStatus(AuditContext.STATUS_PENDING_AUDIT);
                AuditContext lowerCtx = contextLoader.load(lower, NOW);
                assertThat(lowerCtx.frozen()).as("LIKE 'FROZEN%' 在 ci 排序规则下不区分大小写 —— 等价性要保住").isTrue();
                assertThat(lowerCtx.frozenTag()).isEqualTo("frozen_lower");
                System.out.println("[⑦] 附加：小写 frozen_lower 仍然 frozen=true（与老 SQL 的 LIKE 行为一致）");
            } finally {
                jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", lowerId);
            }
        } finally {
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", orderId);
        }
    }

    // ================================================================== 工具

    /** 一条 {@code ACTIVE_TAGS HAS <code>} */
    private static ConditionView has(String tagCode) {
        return ConditionView.matchList(FieldRegistry.ACTIVE_TAGS, "HAS", tagCode);
    }

    /** 一条 {@code ACTIVE_TAGS NOT_HAS <code>} */
    private static ConditionView notHas(String tagCode) {
        return ConditionView.matchList(FieldRegistry.ACTIVE_TAGS, "NOT_HAS", tagCode);
    }

    /** 装配好的上下文：这些标确实挂在这张单上（空集合 = 一个标都没有，不是"没装配"） */
    private static AuditContext contextWithTags(List<String> tags) {
        return AuditScenario.builder(1101L, "QT-1101", AuditContext.STATUS_PENDING_AUDIT, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(2).itemKinds(1).stock(100, 100).costMissing(false)
                .tags(tags.toArray(new String[0]))
                .build();
    }

    /**
     * 「没装配」的上下文：{@code activeTags == null}。
     *
     * <p>刻意走**完整构造器**而不是便捷构造：这张单除了标以外其它事实都齐，
     * 唯一缺的就是那个组件 —— 这正是装配点漏传时真实的样子。
     */
    private static AuditContext unassembledContext() {
        return new AuditContext(1102L, "QT-1102", AuditContext.STATUS_PENDING_AUDIT, "淘宝", null,
                new BigDecimal("268.00"), new BigDecimal("62.00"), null, null, null, null,
                false, false, 100, 100, 1, 2, false, null, "北京市", "北京市朝阳区xx路1号",
                false, null, null, NOW);
    }

    /** 只看 Fmt 的文案（LIST 的空集合 / 未装配必须给出两句不同的话） */
    private static String fmtValue(AuditContext ctx) {
        return com.qiqi.mall.audit.engine.Fmt.value(ctx.activeTags(),
                FieldRegistry.require(FieldRegistry.ACTIVE_TAGS), ctx);
    }
}
