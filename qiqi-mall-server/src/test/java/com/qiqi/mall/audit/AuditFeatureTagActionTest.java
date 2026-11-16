package com.qiqi.mall.audit;

import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
import com.qiqi.mall.audit.service.AuditExecutor;
import com.qiqi.mall.audit.service.AuditPolicyService;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>期 2 步骤 2 的验收口径</b>：策略动作能「打标」。
 *
 * <p>用户原话：<i>标是策略之间的传送带：第一个检测点命中 → 打标；第二个检测点读标 → 决定动作。
 * 没有这一条，管线不成立。</i>步骤 1 只做了「读标」，这个类是另一半：
 * <b>策略命中时能把「特征」标打到单上</b>，于是「策略 A 打标 → 下一轮策略 B 读标」才闭环。
 *
 * <h3>六条硬要求（跑绿 = 做完）</h3>
 * <ol>
 *   <li>EXCEPTION 策略配 {@code featureTags} → 命中后该单<b>多了这些特征标</b>，
 *       而且<b>结论动作（FROZEN）照常生效</b>（两种动作是并行关系，不是二选一）；</li>
 *   <li>AUTO 策略配 {@code featureTags} → 能配上、命中后打标，<b>结论不受影响</b>
 *       （照旧 7→8 自动过审，且结论标仍为 0）；</li>
 *   <li>BLOCK 策略配 {@code featureTags} → 同上（照旧 BLOCKED，并挂上 BLOCKED 结论标）；</li>
 *   <li>配一个<b>结论标</b>码当特征标（{@code NO_POLICY_MATCH}）→ 保存被拒 + 中文原因（带目录中文名）；</li>
 *   <li>配一个<b>目录里不存在</b>的码 → 保存被拒（第 ④ 条的决定：要求先在字典里建，<b>不</b>自动登记）；</li>
 *   <li><b>幂等</b>：同一策略跑两轮，特征标不产生第二行、也不重复写第二条 {@code AUDIT_TAG_ADD}。</li>
 * </ol>
 *
 * <h3>特征标与结论标的三处差别（这些断言就是它们的证据）</h3>
 * <ul>
 *   <li>{@code reason_id} / {@code reason_code} <b>必须为空</b>：那是 {@code audit_freeze_reason}
 *       的外键，特征是"标"不是"冻结原因"（稳定键已经是 {@code tag_code}）；</li>
 *   <li>特征标<b>不参与结论标互斥</b>：冻结的单身上既有 FROZEN（结论）也有特征标，两者并存；</li>
 *   <li>{@code reason} 是"为什么打这个标"：策略配了 note 就用它，没配就拼
 *       「策略「X」命中：&lt;第一条条件人话&gt;」（AUTO / BLOCK 的动作里没有 note）。</li>
 * </ul>
 *
 * <h3>造数（隔离库 {@code qiqi_mall_it}，用完即重建）</h3>
 * <pre>
 * QT-3001  淘宝 268 元，客服备注「客户拒收：地址不详」  → 命中 EXCEPTION（备注含 拒收）→ FROZEN + 2 个特征标
 * QT-3002  淘宝 268 元，干净                            → 命中 AUTO（淘宝 + 应收 &lt; 500）→ AUTO_PASS + 1 个特征标
 * QT-3003  抖音 1580 元，毛利 −12                       → 命中 BLOCK（毛利 ≤ 0）→ BLOCKED + 1 个特征标
 * </pre>
 * 三条策略分别在第 1/2/3 条用例里创建（顺序创建、顺序跑），这样每条用例只测一种动作、
 * 且后面的轮次不会被前面创建的策略串味（QT-3001 冻结后第二轮是 SKIPPED、QT-3002 过审后不再是候选）。
 *
 * <p>运行：{@code ./scripts/run-it.sh -Dtest=AuditFeatureTagActionTest}
 */
@SpringBootTest(properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditFeatureTagActionTest {

    private static final String MEMBER = "audit_feature_tag_member";
    private static final String PRODUCT = "特征标动作测试商品";

    /** 本类自建的四个「特征」标（category='特征'，builtin=0） */
    private static final String FEATURE_COMPLAINT = "COMPLAINT_CUSTOMER";
    private static final String FEATURE_VIP = "VIP_CUSTOMER";
    private static final String FEATURE_RISK_ADDR = "HIGH_RISK_ADDRESS";
    private static final String FEATURE_WATCH = "MANUAL_WATCH";
    /** 只用来撞「最多 5 个」上限的两个（校验按**去重后**的数量算，所以必须凑满 6 个不同的码） */
    private static final String FEATURE_EXTRA_A = "PRICE_DISPUTE";
    private static final String FEATURE_EXTRA_B = "DUPLICATE_ORDER";

    private static final String POLICY_EXCEPTION = "异常-拒收";
    private static final String POLICY_AUTO = "自动-淘宝标准单";
    private static final String POLICY_BLOCK = "拦截-毛利不为正";
    /** 保存应当被拒的两条（名字用来断言"没落库"） */
    private static final String POLICY_BAD_CONCLUSION = "非法-拿结论标当特征标";
    private static final String POLICY_BAD_UNKNOWN = "非法-目录里没有的码";

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
    private AuditExecutor executor;
    @Autowired
    private AuditPolicyService policyService;
    @Autowired
    private AuditTagCatalogService tagCatalog;

    private Long memberId;
    /** 开跑前的内置目录项数（清理后必须回到同一个数；不写死数字 —— 加标不该改测试） */
    private int builtinCatalogSize;
    private Long productId;

    // ================================================================== 准备 / 收尾

    @BeforeAll
    void seed() {
        builtinCatalogSize = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_tag_catalog WHERE builtin = 1", Integer.class);
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedCatalogMemberProduct();
        seedThreeOrders();

        // 四个特征标先进目录：② 的校验要求「码必须在目录里且 category='特征'」
        for (String[] t : new String[][]{
                {FEATURE_COMPLAINT, "客诉客户", "该客户有过投诉，客服已介入"},
                {FEATURE_VIP, "VIP客户", "高价值会员，优先处理"},
                {FEATURE_RISK_ADDR, "高风险地址", "收货地址命中风险库"},
                {FEATURE_WATCH, "人工关注", "运营标记：这单要看一眼"},
                {FEATURE_EXTRA_A, "价格争议", "客户对价格有异议"},
                {FEATURE_EXTRA_B, "重复下单", "同一客户短时间重复下单"}}) {
            AuditTagCatalog created = tagCatalog.createCustom(t[0], t[1], t[2], "特征", 0);
            assertThat(created.getCategory()).as("自建项只能是「特征」档").isEqualTo(AuditTagCatalogService.CATEGORY_FEATURE);
            assertThat(created.getEnabled()).as("新加的特征标必须可直接配进策略").isEqualTo(1);
        }
        System.out.println("[准备] 特征标就绪：" + FEATURE_COMPLAINT + " / " + FEATURE_VIP + " / "
                + FEATURE_RISK_ADDR + " / " + FEATURE_WATCH
                + "；QT-3001（备注拒收 → 该冻结）/ QT-3002（淘宝 268 → 该过审）/ QT-3003（毛利 −12 → 该拦）");
    }

    @AfterAll
    void restore() {
        printTagTable();
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, "QT-300%"));
        deleted.putAll(ItCleanup.purgeCatalog(jdbc, PRODUCT, MEMBER));
        deleted.put("audit_tag_catalog(custom)", jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0"));
        System.out.println("[清理] 删除行数：" + deleted);
        ItCleanup.residueOfOrders(jdbc, "QT-300%")
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_tag_catalog WHERE builtin = 1", Integer.class))
                .as("内置标目录项不许被碰（数量应与开跑前一致，不写死条数：V7 起加了 AUTO_PASSED）")
                .isEqualTo(builtinCatalogSize);
    }

    // ================================================================== ① EXCEPTION：冻结 + 打标

    @Test
    @Order(1)
    @DisplayName("①EXCEPTION 配 featureTags：命中后结论动作（FROZEN）照常生效，且该单多了这些特征标")
    void exceptionPolicyFreezesAndTags() {
        policyService.create(new PolicyRequest("EXCEPTION", 10, POLICY_EXCEPTION, true,
                "FROZEN", "客户拒收", null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CS_REMARK", "CONTAINS", "TEXT",
                        null, "拒收", null)),
                null,
                // 冻结 + 打标：两种动作并行（一次命中既冻结又打标）
                List.of(FEATURE_COMPLAINT, FEATURE_VIP)));

        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.frozen()).as("QT-3001 该被冻结").isEqualTo(1);
        assertThat(decisionResult("QT-3001")).isEqualTo("FROZEN");

        // ---------------- 结论动作照常生效 ----------------
        assertThat(activeConclusionCodes("QT-3001"))
                .as("FROZEN 结论标必须落上（并行动作不许把结论动作挤掉）").containsExactly("FROZEN");
        Map<String, Object> frozen = activeTagRow("QT-3001", "FROZEN");
        assertThat(frozen.get("reason_id")).as("冻结标照旧带原因字典 id").isNotNull();

        // ---------------- 并行动作：两个特征标都打上了 ----------------
        assertThat(activeFeatureCodes("QT-3001"))
                .as("配了几个特征标就打几个").containsExactlyInAnyOrder(FEATURE_COMPLAINT, FEATURE_VIP);
        assertThat(activeTagCodes("QT-3001"))
                .as("最终：1 个结论标 + 2 个特征标（并存，不是二选一）")
                .containsExactlyInAnyOrder("FROZEN", FEATURE_COMPLAINT, FEATURE_VIP);

        // ---------------- 特征标的三个落法（与冻结那套刻意不同） ----------------
        Map<String, Object> row = activeTagRow("QT-3001", FEATURE_COMPLAINT);
        assertThat(row.get("reason_id")).as("特征标不许写 reason_id（FK 指向 audit_freeze_reason，特征是标不是冻结原因）")
                .isNull();
        assertThat(row.get("reason_code")).as("特征标不许写 reason_code（稳定键已经是 tag_code 本身）").isNull();
        assertThat(String.valueOf(row.get("reason")))
                .as("reason = 策略配的 note（策略说的是「客户拒收」）").contains("客户拒收");

        // ---------------- 审计：AUDIT_TAG_ADD 一条一个标，摘要「码 → 中文名 → 备注」 ----------------
        List<Map<String, Object>> audits = auditsOf(QT_3001_ID);
        assertThat(audits).as("两个特征标 = 两条打标审计").hasSize(2);
        String first = String.valueOf(audits.get(0).get("summary"));
        assertThat(first).as("摘要顺序是契约：码 → 中文名（备注最后）")
                .contains(FEATURE_COMPLAINT).contains("客诉客户");
        assertThat(first.indexOf(FEATURE_COMPLAINT))
                .as("码必须排在中文名之前（AdminAuditWriter 从尾部裁 255，先被裁的只能是人写的备注）")
                .isLessThan(first.indexOf("客诉客户"));
        assertThat(first).as("审计要能看出是哪条策略打的标").contains(POLICY_EXCEPTION);

        System.out.println("[①] QT-3001 active 标=" + activeTagCodes("QT-3001")
                + "；结论标=" + activeConclusionCodes("QT-3001")
                + "；特征标行 reason_id=" + row.get("reason_id") + " reason_code=" + row.get("reason_code")
                + " reason=" + row.get("reason"));
    }

    // ================================================================== ② AUTO：过审 + 打标

    @Test
    @Order(2)
    @DisplayName("②AUTO 配 featureTags：能配上且命中后打标，结论不受影响（照旧 7→8，结论标仍为 0）")
    void autoPolicyPassesAndTags() {
        policyService.create(new PolicyRequest("AUTO", 100, POLICY_AUTO, true, null, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("500"), null, null)),
                null,
                List.of(FEATURE_RISK_ADDR)));

        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.autoPass()).as("QT-3002 该自动过审").isEqualTo(1);
        assertThat(run.skipped()).as("QT-3001 已冻结 → 本轮跳过（不写决策行）").isEqualTo(1);

        assertThat(decisionResult("QT-3002")).isEqualTo("AUTO_PASS");
        assertThat(orderStatus("QT-3002")).as("AUTO 的结论一个字没变：状态照旧 7 → 8").isEqualTo(8);

        // ---------------- 打标成功，且它对"过没过"零影响 ----------------
        assertThat(activeFeatureCodes("QT-3002")).as("AUTO 策略也能打特征标").containsExactly(FEATURE_RISK_ADDR);
        // ⚠️ 口径变更（期 3 步骤 1，V7）：AUTO_PASS 现在**必须**落一条 AUTO_PASSED（visible=0），
        //    否则"结论从标读"在最常见的那个结论上根本没有答案。特征标仍然不算结论标。
        assertThat(conclusionTagCount("QT-3002"))
                .as("AUTO_PASS 的结论标恰好一条（AUTO_PASSED）；特征标不算结论标").isEqualTo(1);
        assertThat(activeTagCodes("QT-3002"))
                .as("这单身上是「一条结论标 + 那一个特征标」，两者并列、互不影响")
                .containsExactlyInAnyOrder(FEATURE_RISK_ADDR, "AUTO_PASSED");

        // AUTO 的动作里没有 note（note 属于结论动作），所以 reason 走「策略「X」命中：<第一条条件人话>」
        Map<String, Object> row = activeTagRow("QT-3002", FEATURE_RISK_ADDR);
        String reason = String.valueOf(row.get("reason"));
        assertThat(reason).as("没配 note 时必须说清是哪条策略、第一条条件是什么，不能写成「自动审核」四个字")
                .contains("策略「" + POLICY_AUTO + "」命中").contains("渠道").contains("淘宝");
        assertThat(row.get("reason_id")).as("特征标不带冻结原因 id").isNull();
        assertThat(row.get("reason_code")).as("特征标不带原因码").isNull();

        System.out.println("[②] QT-3002 状态=" + orderStatus("QT-3002") + "；active 标=" + activeTagCodes("QT-3002")
                + "；结论标数=" + conclusionTagCount("QT-3002") + "；reason=" + reason);
    }

    // ================================================================== ③ BLOCK：拦截 + 打标

    @Test
    @Order(3)
    @DisplayName("③BLOCK 配 featureTags：能配上且命中后打标，结论不受影响（照旧 BLOCKED + BLOCKED 结论标）")
    void blockPolicyBlocksAndTags() {
        policyService.create(new PolicyRequest("BLOCK", 10, POLICY_BLOCK, true, null, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "GROSS_PROFIT", "LTE", "NUM",
                        BigDecimal.ZERO, null, null)),
                null,
                List.of(FEATURE_WATCH)));

        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.blocked()).as("QT-3003 该被拦").isEqualTo(1);
        assertThat(decisionResult("QT-3003")).isEqualTo("BLOCKED");
        assertThat(orderStatus("QT-3003")).as("拦是结论不是动作：状态不动，仍是 7").isEqualTo(7);

        assertThat(activeConclusionCodes("QT-3003")).as("结论标照旧是 BLOCKED").containsExactly("BLOCKED");
        assertThat(activeFeatureCodes("QT-3003")).as("BLOCK 策略也能打特征标").containsExactly(FEATURE_WATCH);
        assertThat(activeTagCodes("QT-3003")).as("1 结论标 + 1 特征标 并存")
                .containsExactlyInAnyOrder("BLOCKED", FEATURE_WATCH);

        System.out.println("[③] QT-3003 status=" + orderStatus("QT-3003")
                + "；active 标=" + activeTagCodes("QT-3003"));
    }

    // ================================================================== ④ 结论标不许当特征标

    @Test
    @Order(4)
    @DisplayName("④配结论标（NO_POLICY_MATCH）当 featureTag → 保存被拒 + 中文原因（带目录中文名）")
    void conclusionTagRejectedAsFeatureTag() {
        assertThatThrownBy(() -> policyService.create(new PolicyRequest("AUTO", 900, POLICY_BAD_CONCLUSION, true,
                null, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null)),
                null,
                List.of(AuditTagCatalogService.CODE_NO_POLICY_MATCH))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AuditTagCatalogService.CODE_NO_POLICY_MATCH)
                .hasMessageContaining("无策略命中")          // 目录中文名（参照冻结原因那套）
                .hasMessageContaining("结论");

        // 结论动作也仍然只有 EXCEPTION 能配（这一条没被本步骤放开）
        assertThatThrownBy(() -> policyService.create(new PolicyRequest("AUTO", 901, "非法-AUTO带冻结动作", true,
                "FROZEN", "顺手冻一下", null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null)),
                null, List.of(FEATURE_VIP))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有 EXCEPTION 会冻结订单");

        assertThat(policyCount(POLICY_BAD_CONCLUSION, "非法-AUTO带冻结动作"))
                .as("被拒的策略一行都不许落库").isZero();
        System.out.println("[④] 结论标当特征标 / AUTO 带冻结动作 → 都在保存时被拒（中文原因已带目录名）");
    }

    // ================================================================== ⑤ 目录里没有的码

    @Test
    @Order(5)
    @DisplayName("⑤配目录里不存在的码 → 保存被拒（要求先在字典里建，不自动登记）；超过 5 个也拒")
    void unknownFeatureTagRejected() {
        assertThatThrownBy(() -> policyService.create(new PolicyRequest("AUTO", 902, POLICY_BAD_UNKNOWN, true,
                null, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null)),
                null,
                List.of("NO_SUCH_FEATURE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_SUCH_FEATURE")
                .hasMessageContaining("不在标目录里");
        assertThat(policyCount(POLICY_BAD_UNKNOWN)).as("拼错的码不许默默变成一条新目录项，也不许落库策略").isZero();
        assertThat(tagCatalog.byCode("NO_SUCH_FEATURE"))
                .as("**不自动登记**：目录是标码的闭集，拼错一个字就必须当场报错").isNull();

        // 数量上限（≤ 5）
        assertThatThrownBy(() -> policyService.create(new PolicyRequest("AUTO", 903, "非法-特征标过多", true,
                null, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null)),
                null,
                List.of(FEATURE_COMPLAINT, FEATURE_VIP, FEATURE_RISK_ADDR, FEATURE_WATCH,
                        FEATURE_EXTRA_A, FEATURE_EXTRA_B))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多打 5 个");
        assertThat(policyCount("非法-特征标过多")).as("超限的也不落库").isZero();
        System.out.println("[⑤] 未知码 / 超 5 个 → 保存被拒；目录里没有被偷偷新增一行");
    }

    // ================================================================== ⑥ 幂等

    @Test
    @Order(6)
    @DisplayName("⑥同一策略跑两轮：特征标不产生第二行，AUDIT_TAG_ADD 也不重复写")
    void featureTagsAreIdempotent() {
        assertThat(tagRowCount("QT-3003", FEATURE_WATCH)).as("起点：上一轮打了一条").isEqualTo(1);
        int auditBefore = auditsOf(QT_3003_ID).size();

        RunResultDto again = executor.run("MANUAL", null);
        assertThat(again.blocked()).as("QT-3003 仍是 status=7，第二轮又被同一条 BLOCK 命中").isEqualTo(1);
        assertThat(decisionResult("QT-3003")).isEqualTo("BLOCKED");

        assertThat(tagRowCount("QT-3003", FEATURE_WATCH))
                .as("同一单同一码永远只有一行（幂等靠 uk_active，不靠调用方自觉）").isEqualTo(1);
        assertThat(activeFeatureCodes("QT-3003")).as("active 的特征标也还是那一个").containsExactly(FEATURE_WATCH);
        assertThat(auditsOf(QT_3003_ID))
                .as("第二轮什么都没发生 → 不许再写一条「打标」审计（那是假证据）").hasSize(auditBefore);

        // 单跑 ensureActive 也幂等（并发路径靠 DuplicateKeyException 吞掉）
        assertThat(tagRowCount("QT-3003", FEATURE_WATCH)).isEqualTo(1);
        System.out.println("[⑥] QT-3003 特征标行数=" + tagRowCount("QT-3003", FEATURE_WATCH)
                + "（跑完两轮仍是 1）；审计条数=" + auditsOf(QT_3003_ID).size() + "（没变）");
    }

    // ================================================================== ⑦ 清空打标配置必须真的落库

    /**
     * <b>回归防线（2026-11-16 真站验收发现）</b>：把「命中时打标」全部删掉再保存，必须真的清空。
     *
     * <h3>原来为什么清不掉</h3>
     * {@code AuditPolicyService#update} 用 {@code policyMapper.updateById(update)} 落库，
     * 而 MyBatis-Plus 的 {@code updateById} 默认<b>忽略 null 字段</b>（字段级 NOT_NULL 策略）：
     * 动作全删 ⇒ {@code actionJson(req, type)} 返回 null ⇒ 那句 {@code setActionJson(null)}
     * <b>根本不出现在 SQL 里</b> ⇒ 接口返回 200、版本号 +1、配置却原封不动。
     * 表现就是"运营点了清空、刷新一看又回来了"，而且没有任何报错。
     *
     * <h3>这条用例钉的是什么</h3>
     * 不钉"能保存"（那早就绿了），钉的是<b>"清空 = 真的没了"</b>：
     * 直接看 {@code audit_policy.action_json} 这一列，而不是看接口回显。
     */
    @Test
    @Order(7)
    @DisplayName("⑦清空 featureTags 再保存 → action_json 真的变 NULL（不是静默忽略 null）")
    void clearingFeatureTagsReallyClearsTheColumn() {
        Long id = insertAndReturnKey(
                "INSERT INTO audit_policy (type, priority, name, enabled, action_json, version_no) "
                        + "VALUES ('BLOCK', 900, ?, 1, ?, 1)",
                "清空验证策略", "{\"feature_tags\": [\"" + FEATURE_WATCH + "\"]}");
        assertThat(jdbc.queryForObject("SELECT action_json FROM audit_policy WHERE id = ?", String.class, id))
                .as("起点：这一列上有打标配置").isNotNull().contains(FEATURE_WATCH);

        policyService.update(id, new PolicyRequest("BLOCK", 900, "清空验证策略", true, null, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "GROSS_PROFIT", "LTE", "NUM",
                        new BigDecimal("-9999"), null, null)),
                null, List.of()));

        String after = jdbc.queryForObject("SELECT action_json FROM audit_policy WHERE id = ?", String.class, id);
        System.out.printf("⑦ 清空 featureTags 后 action_json=%s%n", after);
        assertThat(after)
                .as("★ 传空列表 = 真的清空。若这一列还是带 feature_tags 的 JSON，说明 null 被 updateById 静默丢弃了")
                .isNull();
        assertThat(policyService.list().policies().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow().featureTags()).as("回显也必须是空").isEmpty();

        jdbc.update("DELETE FROM audit_policy_condition WHERE policy_id = ?", id);
        jdbc.update("DELETE FROM audit_policy WHERE id = ?", id);
    }

    // ================================================================== 造数 & 工具

    /** 三个单的 id 在 seed 时记下来：审计查询按 order_id 走索引，比 join 单号更直接 */
    private static Long QT_3001_ID;
    private static Long QT_3002_ID;
    private static Long QT_3003_ID;

    private void cleanOurData() {
        for (Long id : jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE 'QT-300%'", Long.class)) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE 'QT-300%'");
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
        jdbc.update("DELETE FROM member WHERE username = ?", MEMBER);
        jdbc.update("DELETE FROM product WHERE name = ?", PRODUCT);
        jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0");
    }

    private void seedCatalogMemberProduct() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '特征标测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '特征标测试类型')");
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '特征标测试会员', 1, NOW(), 0, '普通')", MEMBER);
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES (?, '特征标动作集成测试用', ?, ?, 1, 0)", PRODUCT, categoryId, typeId);
    }

    private void seedThreeOrders() {
        Long sku = newSku("134.00", "103.00", 1000);      // 268 / 2 件，成本 206 → 毛利 62
        Long skuDouyin = newSku("197.50", "150.00", 1000);
        QT_3001_ID = createOrder("QT-3001", "淘宝", "268.00", "62.00", sku, 2, "客户拒收：地址不详");
        QT_3002_ID = createOrder("QT-3002", "淘宝", "268.00", "62.00", sku, 2, null);
        QT_3003_ID = createOrder("QT-3003", "抖音", "1580.00", "-12.00", skuDouyin, 8, null);
    }

    private Long newSku(String price, String cost, int stock) {
        Long skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, ?, ?, 0, ?)",
                productId, new BigDecimal(price), stock, new BigDecimal(cost));
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, ?)", skuId, stock);
        return skuId;
    }

    private Long createOrder(String sn, String channel, String amount, String grossProfit, Long skuId, int qty,
                             String csRemark) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, 7, 1, NOW(), NOW(), '张三', '13800000000', '北京市朝阳区xx路1号', 0, 0, 0, "
                        + "?, '官方旗舰店', ?, ?, 0)",
                sn, memberId, new BigDecimal(amount), channel, new BigDecimal(grossProfit), csRemark);
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                        + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, productId, skuId, PRODUCT,
                new BigDecimal(amount).divide(new BigDecimal(qty), 2, java.math.RoundingMode.HALF_UP),
                qty, new BigDecimal("150.00"));
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) VALUES (?, NULL, 7, 'ORDER_CREATE', "
                        + "'特征标动作集成测试造数', 'SYSTEM', 'system', ?)", orderId, orderId + ":0");
        return orderId;
    }

    private String decisionResult(String sn) {
        return jdbc.queryForObject(
                "SELECT d.result FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn = ? ORDER BY d.round_no DESC, d.id DESC LIMIT 1", String.class, sn);
    }

    private int orderStatus(String sn) {
        Integer s = jdbc.queryForObject("SELECT status FROM `order` WHERE order_sn = ?", Integer.class, sn);
        return s == null ? -1 : s;
    }

    private List<String> activeTagCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.active = 1 ORDER BY t.id", String.class, sn);
    }

    private List<String> activeConclusionCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 AND c.category = ? ORDER BY t.id",
                String.class, sn, AuditTagCatalogService.CATEGORY_CONCLUSION);
    }

    private List<String> activeFeatureCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 AND c.category = ? ORDER BY t.id",
                String.class, sn, AuditTagCatalogService.CATEGORY_FEATURE);
    }

    private int conclusionTagCount(String sn) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 AND c.category = ?",
                Integer.class, sn, AuditTagCatalogService.CATEGORY_CONCLUSION);
        return n == null ? -1 : n;
    }

    private int tagRowCount(String sn, String code) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.tag_code = ?", Integer.class, sn, code);
        return n == null ? -1 : n;
    }

    private Map<String, Object> activeTagRow(String sn, String code) {
        return jdbc.queryForMap(
                "SELECT t.* FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.tag_code = ? AND t.active = 1", sn, code);
    }

    /** 这单的打标审计（按时间升序）；只认 AUDIT_TAG_ADD —— 冻结那条是 AUDIT_FREEZE，别混进来 */
    private List<Map<String, Object>> auditsOf(Long orderId) {
        return jdbc.queryForList(
                "SELECT action, summary, after_json FROM admin_audit_log "
                        + "WHERE action = 'AUDIT_TAG_ADD' AND target_id = ? ORDER BY id", orderId);
    }

    private int policyCount(String... names) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_policy WHERE name IN ("
                        + String.join(",", java.util.Collections.nCopies(names.length, "?")) + ")",
                Integer.class, (Object[]) names);
        return n == null ? -1 : n;
    }

    private void printTagTable() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.order_sn, t.tag_code, t.active, IFNULL(c.name,'<未登记>') AS name, "
                        + "IFNULL(c.category,'-') AS category, t.reason_id, t.reason_code, "
                        + "LEFT(IFNULL(t.reason,''), 50) AS reason "
                        + "FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn LIKE 'QT-300%' ORDER BY o.order_sn, t.id");
        System.out.println("┌─ order_audit_tag（隔离库 " + ItDatabase.dbName() + "）");
        for (Map<String, Object> r : rows) {
            System.out.println("│ " + r.get("order_sn") + " | " + r.get("tag_code") + " | active=" + r.get("active")
                    + " | " + r.get("name") + " | " + r.get("category")
                    + " | reason_id=" + r.get("reason_id") + " reason_code=" + r.get("reason_code")
                    + " | " + r.get("reason"));
        }
        System.out.println("└─ 共 " + rows.size() + " 行");
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
