package com.qiqi.mall.audit;

import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.dto.TagCatalogDto;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
import com.qiqi.mall.audit.entity.OrderAuditTag;
import com.qiqi.mall.audit.service.AuditExecutor;
import com.qiqi.mall.audit.service.AuditPolicyService;
import com.qiqi.mall.audit.service.AuditQueryService;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
import com.qiqi.mall.audit.service.OrderAuditTagService;
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
 * <b>期 1 的验收口径</b>：标从"引擎私有开关"升级为"业务接口层"。
 *
 * <p>这个类就是那四条硬要求的可执行版本（跑绿 = 做完），外加两条本轮口径修正的证明：
 * <ol>
 *   <li>一张 NO_MATCH 的单，跑一轮后<b>身上有 {@code NO_POLICY_MATCH} 标</b>
 *       （以前 18 条空白单一个标都没有，这就是缺口）；</li>
 *   <li>重复跑同一轮 → <b>不产生第二个同名标</b>（幂等靠 {@code uk_active}，不靠调用方自觉）；</li>
 *   <li>队列按 {@code ?tag=NO_POLICY_MATCH} <b>能筛出它</b>，且不碰 {@code result} 那六个页签的语义；</li>
 *   <li>一张 AUTO_PASS 的单，跑完一轮后<b>「结论」标为 0</b>
 *       —— ⚠️ 注意口径：断的是"结论标为 0"，<b>不是</b>"没有任何标"。
 *       AUTO_PASS 这个<b>结论</b>不产标，但自动过审的单完全可以带特征标
 *       （客诉客户 / 绿旗急发 / 双地址…，吉客云原话：这些单打了标照样自动过审）。
 *       断言按 {@code audit_tag_catalog.category='结论'} 过滤，将来加一个特征标不会误红；</li>
 *   <li>（修正 ③）标字典<b>从一开始就能新增</b>：人工新增一条自定义标 → 打到单上 → 留审计；</li>
 *   <li>（修正 ②）数据层<b>不设主标</b>：一单多个标就是并列多行，没有任何"主标"列可写。</li>
 * </ol>
 *
 * <h3>造数（隔离库 qiqi_mall_it，用完即重建）</h3>
 * <pre>
 * QT-2001  抖音 1580 元 8 件  → NO_MATCH（唯一那条 AUTO 策略只认淘宝 + 应收 &lt; 500）
 * QT-2002  淘宝 268 元 2 件   → AUTO_PASS（命中淘宝-标准单；库存充足、成本已维护）
 * </pre>
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditTagLifecycleIntegrationTest}
 */
@SpringBootTest(properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditTagLifecycleIntegrationTest {

    /** 本类造的会员/商品名：清理与残留断言都靠它们定位 */
    private static final String MEMBER = "audit_tag_member";
    private static final String PRODUCT = "标目录测试商品";

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
    private AuditQueryService queryService;
    @Autowired
    private OrderAuditTagService tagService;
    @Autowired
    private AuditTagCatalogService tagCatalog;

    private Long memberId;
    private Long productId;

    // ================================================================== 准备

    @BeforeAll
    void seed() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedCatalogMemberProduct();
        seedTwoOrders();
        seedOneAutoPolicy();
        System.out.println("[准备] QT-2001（抖音 1580 → 该 NO_MATCH）/ QT-2002（淘宝 268 → 该 AUTO_PASS）就绪");
        // 目录必须是 V4 种下的那 6 条内置结论标（迁移真的跑过，而不是测试自己插的）
        assertThat(tagCatalog.byCode(AuditTagCatalogService.CODE_NO_POLICY_MATCH))
                .as("V4 必须已把 NO_POLICY_MATCH 种进标目录").isNotNull();
        System.out.println("[准备] 标目录：" + tagCatalog.all().size() + " 条，结论 "
                + tagCatalog.enabledByCategory(AuditTagCatalogService.CATEGORY_CONCLUSION).size() + " 条 / 特征 "
                + tagCatalog.enabledByCategory(AuditTagCatalogService.CATEGORY_FEATURE).size() + " 条");
    }

    @AfterAll
    void restore() {
        printTagTable();
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, "QT-200%"));
        deleted.putAll(ItCleanup.purgeCatalog(jdbc, PRODUCT, MEMBER));
        // 自定义标目录项（CUSTOM_xxx）是本类造出来的，一并清掉；内置项（builtin=1）一个都不许删
        deleted.put("audit_tag_catalog(custom)", jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0"));
        System.out.println("[清理] 删除行数：" + deleted);

        ItCleanup.residueOfOrders(jdbc, "QT-200%")
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        ItCleanup.residueOfCatalog(jdbc, PRODUCT, MEMBER)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        assertThat((Integer) jdbc.queryForObject("SELECT COUNT(*) FROM audit_tag_catalog WHERE builtin = 1", Integer.class))
                .as("内置标目录项不许被清理掉（清理只删 builtin=0）").isEqualTo(6);
        System.out.println("[清理] 残留断言通过：订单侧 / 商品侧 全部为 0，内置目录项仍 6 条");
    }

    // ================================================================== ① NO_MATCH 落标

    @Test
    @Order(1)
    @DisplayName("①NO_MATCH 单跑一轮后身上有 NO_POLICY_MATCH 标（中文名/说明来自标目录）")
    void noMatchGetsTag() {
        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.total()).as("候选 = 两笔 status=7 的单").isEqualTo(2);
        assertThat(run.noMatch()).as("QT-2001").isEqualTo(1);
        assertThat(run.autoPass()).as("QT-2002").isEqualTo(1);

        // ---------------- 判定结论一个字没变（本期是"两个都写"，不是"标取代决策行"） ----------------
        assertThat(decisionResult("QT-2001")).isEqualTo("NO_MATCH");
        assertThat(decisionResult("QT-2002")).isEqualTo("AUTO_PASS");

        // ---------------- ① 标真的落上去了 ----------------
        List<String> codes = activeTagCodes("QT-2001");
        assertThat(codes).as("NO_MATCH 的单必须带上 NO_POLICY_MATCH 标（以前这里一片空白）")
                .containsExactly(AuditTagCatalogService.CODE_NO_POLICY_MATCH);

        // 目录里那一条必须能翻出中文名与说明（前端显示的就是它们，不是英文码）
        AuditTagCatalog def = tagCatalog.byCode(AuditTagCatalogService.CODE_NO_POLICY_MATCH);
        assertThat(def.getName()).isEqualTo("无策略命中");
        assertThat(def.getDescription()).contains("没有任何自动策略命中");
        assertThat(def.getCategory()).isEqualTo(AuditTagCatalogService.CATEGORY_CONCLUSION);
        assertThat(def.getBlocking()).as("NO_POLICY_MATCH 只是提示，不拦住下单往下走").isZero();

        // 标行本身：active=1、reason 是人话原因、没被塞进冻结原因字典（它不是冻结类标）
        Map<String, Object> tag = activeTagRow("QT-2001", AuditTagCatalogService.CODE_NO_POLICY_MATCH);
        assertThat(((Number) tag.get("active")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(tag.get("reason"))).as("reason = 判定那一刻的人话原因（含数值差）")
                .contains("无匹配").contains("淘宝-标准单").contains("1580.00");
        assertThat(tag.get("reason_id")).as("非冻结标不许有冻结原因 id（那是 FROZEN 专属）").isNull();

        System.out.println("[①] QT-2001 的 active 标 = " + codes + "；QT-2002 = " + activeTagCodes("QT-2002"));
    }

    // ================================================================== ② 幂等

    @Test
    @Order(2)
    @DisplayName("②重复跑同一轮 → 不产生第二个同名标（不报错、不多行）")
    void rerunIsIdempotent() {
        int rowsBefore = tagRowCount("QT-2001", AuditTagCatalogService.CODE_NO_POLICY_MATCH);
        assertThat(rowsBefore).isEqualTo(1);

        // 同一轮连跑三次（QT-2001 仍是 status=7，每轮都会被判 NO_MATCH）
        RunResultDto run2 = executor.run("MANUAL", null);
        RunResultDto run3 = executor.run("MANUAL", null);
        RunResultDto run4 = executor.run("MANUAL", null);
        assertThat(run2.noMatch()).isEqualTo(1);
        assertThat(run3.noMatch()).isEqualTo(1);
        assertThat(run4.noMatch()).isEqualTo(1);

        assertThat(tagRowCount("QT-2001", AuditTagCatalogService.CODE_NO_POLICY_MATCH))
                .as("同单同码的标**永远只有一行**（幂等不是靠调用方自觉，是 uk_active 兜的）")
                .isEqualTo(1);
        assertThat(activeTagCodes("QT-2001")).containsExactly(AuditTagCatalogService.CODE_NO_POLICY_MATCH);

        // 判定记录却在增加（标是状态、决策是流水 —— 两者不是同一件事，都照常写）
        assertThat(decisionCount("QT-2001")).as("第 1~4 轮各一行判定记录").isEqualTo(4);

        // 直接调 ensureActive 也幂等（并发路径靠 DuplicateKeyException 吞掉）
        OrderAuditTag again = tagService.ensureActive(orderIdOf("QT-2001"),
                AuditTagCatalogService.CODE_NO_POLICY_MATCH, "重复打标：应当什么都不做");
        assertThat(again).isNotNull();
        assertThat(tagRowCount("QT-2001", AuditTagCatalogService.CODE_NO_POLICY_MATCH)).isEqualTo(1);
        assertThat(String.valueOf(again.getReason())).as("已存在时**不覆盖**原有的 reason（历史不被改写）")
                .contains("无匹配").contains("1580.00");

        System.out.println("[②] QT-2001 的 NO_POLICY_MATCH 标行数 = "
                + tagRowCount("QT-2001", AuditTagCatalogService.CODE_NO_POLICY_MATCH) + "（跑完 4 轮仍是 1）");
    }

    // ================================================================== ③ 按标筛选

    @Test
    @Order(3)
    @DisplayName("③队列按 ?tag=NO_POLICY_MATCH 能筛出它；result 页签语义不变")
    void queueFiltersByTag() {
        Map<String, Object> byTag = queryService.queue(null, AuditTagCatalogService.CODE_NO_POLICY_MATCH, 1, 50);
        List<String> sns = orderSns(byTag);
        assertThat(sns).as("按标筛：命中的正是那张 NO_MATCH 的单").containsExactly("QT-2001");
        assertThat(byTag.get("total")).as("total 与行数同口径（用 EXISTS 而不是 JOIN，不会因多标复制行）")
                .isEqualTo(1L);

        // 行里带的是**中文名 + 说明 + 打标时间**（抽屉要逐条显示），且键名是驼峰
        Map<String, Object> row = firstRow(byTag);
        assertThat(strings(row.get("tagCodes"))).as("tagCodes 供列表层快速判断").containsExactly(
                AuditTagCatalogService.CODE_NO_POLICY_MATCH);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) row.get("tags");
        assertThat(tags).hasSize(1);
        Map<String, Object> t0 = tags.get(0);
        assertThat(t0.get("tagCode")).isEqualTo(AuditTagCatalogService.CODE_NO_POLICY_MATCH);
        assertThat(t0.get("name")).as("中文名（前端直接显示，永远不是英文码）").isEqualTo("无策略命中");
        assertThat(String.valueOf(t0.get("description"))).contains("没有任何自动策略命中");
        assertThat(t0.get("category")).isEqualTo(AuditTagCatalogService.CATEGORY_CONCLUSION);
        assertThat(t0.get("blocking")).isEqualTo(false);
        assertThat(String.valueOf(t0.get("at"))).as("打标时间（抽屉里要显示「什么时候打的」）")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

        // ---------------- 现有 result 页签语义**一个都没动**（期 3 才改） ----------------
        assertThat(orderSns(queryService.queue("NO_MATCH", null, 1, 50))).containsExactly("QT-2001");
        assertThat(orderSns(queryService.queue("AUTO_PASS", null, 1, 50))).containsExactly("QT-2002");
        assertThat(orderSns(queryService.queue(null, null, 1, 50)))
                .as("不带任何筛选 = 全部").containsExactlyInAnyOrder("QT-2001", "QT-2002");

        // 两个筛选条件正交、可并列：result=NO_MATCH 且 tag=NO_POLICY_MATCH → 仍是这一笔
        assertThat(orderSns(queryService.queue("NO_MATCH",
                AuditTagCatalogService.CODE_NO_POLICY_MATCH, 1, 50))).containsExactly("QT-2001");
        // 不存在的标 → 空集合，不是"全返回"
        assertThat(orderSns(queryService.queue(null, "NO_SUCH_TAG", 1, 50))).isEmpty();

        // P0 的三个冻结子查询原样保留（并列新增，不是替换）
        assertThat(row).containsKeys("frozen", "frozenReason", "frozenTagCode", "frozenReasonName");

        System.out.println("[③] ?tag=NO_POLICY_MATCH → " + sns
                + "；result=NO_MATCH → " + orderSns(queryService.queue("NO_MATCH", null, 1, 50)));
    }

    // ================================================================== ④ AUTO_PASS 不落结论标

    @Test
    @Order(4)
    @DisplayName("④AUTO_PASS 的单「结论」标为 0（不是「没有任何标」——特征标可以有）")
    void autoPassHasNoConclusionTag() {
        assertThat(decisionResult("QT-2002")).isEqualTo("AUTO_PASS");
        assertThat(conclusionTagCount("QT-2002"))
                .as("AUTO_PASS 这个**结论**不产标（按 catalog category='结论' 过滤）")
                .isZero();
        assertThat(activeTagCodes("QT-2002")).as("本场景里它确实一个标都没有").isEmpty();

        // ★ 口径修正 ①：AUTO_PASS 的单**可以**带特征标，且带了也不算"结论标"
        AuditTagCatalog custom = tagCatalog.createCustom(null, "客诉客户", "该客户有过投诉，客服已知会", "特征", 0);
        assertThat(custom.getCode()).as("code 自动生成（中文名不能当稳定键）").startsWith("CUSTOM_");
        assertThat(custom.getBuiltin()).as("自定义项 builtin=0（可停用、不可删除）").isZero();
        assertThat(custom.getCategory()).isEqualTo(AuditTagCatalogService.CATEGORY_FEATURE);

        tagService.applyManualTag(orderOf("QT-2002"), custom.getCode(), "客服已知会，单据仍按流程走");
        assertThat(activeTagCodes("QT-2002")).as("特征标打上去了").containsExactly(custom.getCode());
        assertThat(conclusionTagCount("QT-2002"))
                .as("打了特征标之后，**结论标仍然为 0** —— 标从头到尾没参与判断").isZero();

        // 幂等：同一个标再打一次，不产生第二行
        tagService.applyManualTag(orderOf("QT-2002"), custom.getCode(), "重复打标");
        assertThat(tagRowCount("QT-2002", custom.getCode())).isEqualTo(1);

        // 必须留审计：谁在什么时候给哪张单打了什么标
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT action, summary, after_json FROM admin_audit_log "
                        + "WHERE action = 'AUDIT_TAG_ADD' AND target_id = ? ORDER BY id", orderIdOf("QT-2002"));
        assertThat(audits).as("人工打标必须写审计（没有出处的标比没有标更糟）").hasSize(1);
        assertThat(String.valueOf(audits.get(0).get("summary")))
                .as("摘要顺序：标码 → 中文名 → 备注（机器要断言的靠前）")
                .contains(custom.getCode()).contains("客诉客户");

        // 人工**不许**打结论标（那等于伪造一条判定结论的证据）
        assertThatThrownBy(() -> tagService.applyManualTag(orderOf("QT-2002"),
                AuditTagCatalogService.CODE_BLOCKED, "我想手动拦一下"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结论");
        // 目录里没有的码也不许打（自由文本不能当 code）
        assertThatThrownBy(() -> tagService.applyManualTag(orderOf("QT-2002"), "随便写一句话", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标目录里没有标码");

        // 队列里这单现在带着特征标，且 result 仍是 AUTO_PASS（标不改写结论）
        Map<String, Object> row = firstRow(queryService.queue("AUTO_PASS", null, 1, 50));
        assertThat(row.get("result")).isEqualTo("AUTO_PASS");
        assertThat(strings(row.get("tagCodes"))).containsExactly(custom.getCode());

        System.out.println("[④] QT-2002：结论标=" + conclusionTagCount("QT-2002")
                + "，active 标=" + activeTagCodes("QT-2002") + "（特征标不影响 AUTO_PASS）");
    }

    // ================================================================== ⑤ 目录与数据层的形状

    @Test
    @Order(5)
    @DisplayName("⑤标目录：6 条内置结论标 + 没有「主标」列（数据层并列多标）")
    void catalogShape() {
        List<TagCatalogDto> conclusion = tagCatalog.enabledByCategory(AuditTagCatalogService.CATEGORY_CONCLUSION);
        assertThat(conclusion).extracting(TagCatalogDto::code).containsExactlyInAnyOrder(
                "BLOCKED", "FROZEN", "STOCK_SHORT", "COST_MISSING", "AUDIT_PENDING", "NO_POLICY_MATCH");
        assertThat(conclusion).extracting(TagCatalogDto::name).containsExactlyInAnyOrder(
                "被策略拦截", "被冻结", "库存不足", "毛利为负", "待人工审核", "无策略命中");
        assertThat(conclusion).allSatisfy(c ->
                assertThat(c.description()).as("每个标都要有一句话说明（给业务看）").isNotBlank());

        // 修正 ②：数据层**不许**有"主标"概念
        Integer primaryCols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'audit_tag_catalog' "
                        + "AND (COLUMN_NAME LIKE '%primary%' OR COLUMN_NAME LIKE '%priority%' "
                        + "     OR COLUMN_NAME LIKE '%is_main%')", Integer.class);
        assertThat(primaryCols).as("catalog 里不许有 is_primary / priority 之类的列（主标只在显示层算）").isZero();

        // FROZEN 只是**登记**，不是重建：目录里 1 行，order_audit_tag 里那 17 行一行都不归它管
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_tag_catalog WHERE code = 'FROZEN'", Integer.class))
                .as("FROZEN 在目录里只登记一条元数据").isEqualTo(1);

        System.out.println("[⑤] 结论标目录：" + conclusion.stream().map(TagCatalogDto::code).toList());
    }

    // ================================================================== ⑥ 结论标互斥（期 1.5）

    /**
     * 结论标互斥：一单从 NO_MATCH 变成 MANUAL 之后，身上只能有 <b>AUDIT_PENDING</b> 一个结论标，
     * 旧的 {@code NO_POLICY_MATCH} 必须让位（active=0 + released_at + released_by=SYSTEM:SUPERSEDED）。
     *
     * <p>为什么这条必须存在（期 1 的真问题）：不做互斥时两个结论标并列 active，
     * {@code ?tag=NO_POLICY_MATCH} 会把这单算进"无策略命中"，「这个月多少单无策略命中」当场被高估。
     *
     * <p>同时钉住两条边界：
     * <ul>
     *   <li><b>不是删历史</b>：旧标行还在，解除时间/解除人可查；</li>
     *   <li><b>特征标不受影响</b>：一单可以既"客诉客户"又"绿旗急发"，它们与"过没过"无关，
     *       结论怎么变都不许动它们（这正是"只增不删"管事实、不管当前状态标的落点）。</li>
     * </ul>
     */
    @Test
    @Order(6)
    @DisplayName("⑥结论标互斥：NO_MATCH→MANUAL 后只剩 AUDIT_PENDING，旧标 released_by=SYSTEM:SUPERSEDED，特征标不动")
    void conclusionTagsAreMutuallyExclusive() {
        // ---------------- 起点：QT-2001 身上是「1 结论标 + 2 特征标」 ----------------
        assertThat(activeConclusionCodes("QT-2001"))
                .as("起点：结论标是 NO_POLICY_MATCH").containsExactly("NO_POLICY_MATCH");

        AuditTagCatalog featureA = tagCatalog.createCustom(null, "客诉客户", "该客户有过投诉，客服已介入", "特征", 0);
        AuditTagCatalog featureB = tagCatalog.createCustom(null, "绿旗急发", "客户催得急，仓库优先处理", "特征", 0);
        tagService.applyManualTag(orderOf("QT-2001"), featureA.getCode(), "客服已介入");
        tagService.applyManualTag(orderOf("QT-2001"), featureB.getCode(), "仓库优先");
        assertThat(activeFeatureCodes("QT-2001")).containsExactlyInAnyOrder(featureA.getCode(), featureB.getCode());

        // ---------------- 让这张单从 NO_MATCH 变成 MANUAL（命中一条 AUTO，但被全局排除挡下） ----------------
        jdbc.update("UPDATE `order` SET cs_remark = '客户改地址：海淀区yy路2号' WHERE order_sn = 'QT-2001'");
        policyService.create(new PolicyRequest("AUTO", 50, "抖音-兜底", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "抖音", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("2000"), null, null)),
                List.of(new ConditionRequest("EXCLUDE", "ALL_AUTO", "CS_REMARK", "CONTAINS", "TEXT",
                        null, "改", null))));

        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.manual()).as("QT-2001 现在该是 MANUAL（被全局排除）").isEqualTo(1);
        assertThat(decisionResult("QT-2001")).isEqualTo("MANUAL");

        // ---------------- ① 只剩新结论标（不是两个） ----------------
        assertThat(activeConclusionCodes("QT-2001"))
                .as("结论标必须互斥：只剩 AUDIT_PENDING，NO_POLICY_MATCH 必须让位")
                .containsExactly("AUDIT_PENDING");

        // ---------------- ② 旧标没被删，解除痕迹可查 ----------------
        Map<String, Object> old = anyTagRow("QT-2001", "NO_POLICY_MATCH");
        assertThat(((Number) old.get("active")).intValue()).as("旧标应 active=0（不是删行）").isZero();
        assertThat(old.get("released_at")).as("解除时间必须记下（历史可查）").isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .as("解除人要能说清是「被新结论取代」").isEqualTo(OrderAuditTagService.RELEASED_BY_SUPERSEDED);
        assertThat(tagRowCount("QT-2001", "NO_POLICY_MATCH")).as("行还在（只有一行，没被复制）").isEqualTo(1);
        assertThat(String.valueOf(old.get("reason"))).as("旧标的人话原因一个字没改").contains("无匹配");

        // ---------------- ③ 按标统计恢复正确：旧标码不再筛出这张单 ----------------
        assertThat(orderSns(queryService.queue(null, "NO_POLICY_MATCH", 1, 50)))
                .as("?tag=NO_POLICY_MATCH 不该再筛出它（结论已不是「无策略命中」）")
                .doesNotContain("QT-2001");
        assertThat(orderSns(queryService.queue(null, "AUDIT_PENDING", 1, 50)))
                .as("?tag=AUDIT_PENDING 现在筛得出它").contains("QT-2001");

        // ---------------- ④ 特征标并存不受影响 ----------------
        assertThat(activeFeatureCodes("QT-2001"))
                .as("两个特征标都必须还 active（结论变化不许动特征标）")
                .containsExactlyInAnyOrder(featureA.getCode(), featureB.getCode());
        assertThat(activeTagCodes("QT-2001"))
                .as("最终：1 个结论标 + 2 个特征标").containsExactlyInAnyOrder(
                        "AUDIT_PENDING", featureA.getCode(), featureB.getCode());

        // 队列行：结论标与特征标都在（页面按 blocking/分类各自展示）
        Map<String, Object> row = firstRow(queryService.queue(null, "AUDIT_PENDING", 1, 50));
        assertThat(strings(row.get("tagCodes")))
                .containsExactlyInAnyOrder("AUDIT_PENDING", featureA.getCode(), featureB.getCode());

        System.out.println("[⑥] QT-2001 最终 active 标=" + activeTagCodes("QT-2001")
                + "；结论标=" + activeConclusionCodes("QT-2001")
                + "；旧 NO_POLICY_MATCH：active=" + old.get("active")
                + " released_by=" + old.get("released_by") + " released_at=" + old.get("released_at"));
    }

    // ================================================================== 造数 & 工具

    private void cleanOurData() {
        for (Long id : jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE 'QT-200%'", Long.class)) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE 'QT-200%'");
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
        jdbc.update("DELETE FROM member WHERE username = ?", MEMBER);
        jdbc.update("DELETE FROM product WHERE name = ?", PRODUCT);
        jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0");
    }

    private void seedCatalogMemberProduct() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '标目录测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '标目录测试类型')");
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '标目录测试会员', 1, NOW(), 0, '普通')", MEMBER);
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES (?, '标目录集成测试用', ?, ?, 1, 0)", PRODUCT, categoryId, typeId);
    }

    private void seedTwoOrders() {
        Long skuTaobao = newSku("134.00", "103.00", 1000);     // QT-2002：268 / 2 件，成本 206 → 毛利 62
        Long skuDouyin = newSku("197.50", "150.00", 1000);     // QT-2001：1580 / 8 件
        createOrder("QT-2001", "抖音", "1580.00", "380.00", skuDouyin, 8);
        createOrder("QT-2002", "淘宝", "268.00", "62.00", skuTaobao, 2);
    }

    private Long newSku(String price, String cost, int stock) {
        Long skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, ?, ?, 0, ?)",
                productId, new BigDecimal(price), stock, new BigDecimal(cost));
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, ?)", skuId, stock);
        return skuId;
    }

    private void createOrder(String sn, String channel, String amount, String grossProfit, Long skuId, int qty) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, 7, 1, NOW(), NOW(), '张三', '13800000000', '北京市朝阳区xx路1号', 0, 0, 0, "
                        + "?, '官方旗舰店', ?, NULL, 0)",
                sn, memberId, new BigDecimal(amount), channel, new BigDecimal(grossProfit));
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                        + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, productId, skuId, PRODUCT,
                new BigDecimal(amount).divide(new BigDecimal(qty), 2, java.math.RoundingMode.HALF_UP),
                qty, new BigDecimal("150.00"));
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) VALUES (?, NULL, 7, 'ORDER_CREATE', "
                        + "'标目录集成测试造数', 'SYSTEM', 'system', ?)", orderId, orderId + ":0");
    }

    /** 唯一那条 AUTO 策略：只认淘宝 + 应收 &lt; 500 + 种类数 ≤ 5 → QT-2002 过审、QT-2001 不命中 */
    private void seedOneAutoPolicy() {
        policyService.create(new PolicyRequest("AUTO", 100, "淘宝-标准单", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("500"), null, null),
                        new ConditionRequest("MATCH", "POLICY", "ITEM_KIND_COUNT", "LTE", "NUM",
                                new BigDecimal("5"), null, null)), null));
    }

    private Long orderIdOf(String sn) {
        return jdbc.queryForObject("SELECT id FROM `order` WHERE order_sn = ?", Long.class, sn);
    }

    private com.qiqi.mall.order.entity.Order orderOf(String sn) {
        return jdbc.queryForObject("SELECT id, order_sn, status FROM `order` WHERE order_sn = ?", (rs, i) -> {
            com.qiqi.mall.order.entity.Order o = new com.qiqi.mall.order.entity.Order();
            o.setId(rs.getLong("id"));
            o.setOrderSn(rs.getString("order_sn"));
            o.setStatus(rs.getInt("status"));
            return o;
        }, sn);
    }

    private String decisionResult(String sn) {
        return jdbc.queryForObject(
                "SELECT d.result FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn = ? ORDER BY d.round_no DESC, d.id DESC LIMIT 1", String.class, sn);
    }

    private int decisionCount(String sn) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn = ?", Integer.class, sn);
        return n == null ? -1 : n;
    }

    private List<String> activeTagCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.active = 1 ORDER BY t.id", String.class, sn);
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

    /** 这单身上「结论」档的 active 标码（按 catalog 分组过滤） */
    private List<String> activeConclusionCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 AND c.category = ? ORDER BY t.id",
                String.class, sn, AuditTagCatalogService.CATEGORY_CONCLUSION);
    }

    /** 这单身上「特征」档的 active 标码 */
    private List<String> activeFeatureCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 AND c.category = ? ORDER BY t.id",
                String.class, sn, AuditTagCatalogService.CATEGORY_FEATURE);
    }

    /** 取该单该码的标行（**不看 active**：互斥之后旧标是 active=0，仍要能查到它的解除痕迹） */
    private Map<String, Object> anyTagRow(String sn, String code) {
        return jdbc.queryForMap(
                "SELECT t.* FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.tag_code = ? ORDER BY t.id DESC LIMIT 1", sn, code);
    }

    /** 这单身上「结论」档的 active 标数（按 catalog 分组过滤，将来加特征标不会误红） */
    private int conclusionTagCount(String sn) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 AND c.category = ?",
                Integer.class, sn, AuditTagCatalogService.CATEGORY_CONCLUSION);
        return n == null ? -1 : n;
    }

    /** 把队列行里的 list 宽松收敛成 List&lt;String&gt;（Jackson/MyBatis 回来的元素类型不保证） */
    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> orderSns(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("rows")).stream()
                .map(r -> String.valueOf(r.get("order_sn"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("rows")).get(0);
    }

    private void printTagTable() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.order_sn, t.tag_code, t.active, IFNULL(c.name,'<未登记>') AS name, "
                        + "IFNULL(c.category,'-') AS category, t.created_at, LEFT(IFNULL(t.reason,''), 40) AS reason "
                        + "FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn LIKE 'QT-200%' ORDER BY o.order_sn, t.id");
        System.out.println("┌─ order_audit_tag（隔离库 " + ItDatabase.dbName() + "）");
        for (Map<String, Object> r : rows) {
            System.out.println("│ " + r.get("order_sn") + " | " + r.get("tag_code") + " | active=" + r.get("active")
                    + " | " + r.get("name") + " | " + r.get("category") + " | " + r.get("created_at")
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
