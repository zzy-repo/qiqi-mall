package com.qiqi.mall.audit;

import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
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

/**
 * <b>期 3 · 步骤 1+2 的验收口径</b>：让「标驱动结论」在<b>接口上先存在</b>，而页面一个字都不变。
 *
 * <p>这个类是可执行版本的，四条硬要求逐条钉：
 * <ol>
 *   <li><b>AUTO_PASS 落 AUTO_PASSED，但队列行不显示它</b>（visible=0 的语义落实在渲染层）：
 *       库里有 {@code ['AUTO_PASSED']}，队列行的 {@code tags/tagCodes} 里<b>没有</b>它，
 *       {@code allTagCodes}（数据层）里有它；{@code ?tag=AUTO_PASSED} 又能筛到这张单；</li>
 *   <li><b>影子字段</b>：一张 NO_MATCH 单，{@code conclusionFromTag='NO_MATCH'}、{@code conclusionSource='TAG'}；</li>
 *   <li><b>「从未判定」的历史单不许被 NO_MATCH 冒充</b>：{@code conclusionSource='NONE'}、
 *       {@code conclusionFromTag=null}（没有决策行 → 标里也没有结论 → 就是没有结论）；</li>
 *   <li><b>一致性对账端点真的能列出不一致的单</b>（造一张 result 与标冲突的单来验）。</li>
 * </ol>
 *
 * <h3>为什么必须有一条「多条结论标怎么取」的用例</h3>
 * 期 1.5/1.6 的互斥规则说"一单只有一个 active 结论标"，但<b>历史遗留</b>（互斥上线前的库）里
 * 确实存在两条并挂的行。期 3 的"从标读结论"必须对这种数据给出**确定**答案
 * （阻断型优先 → id 最大），而不是撞 LIMIT 1 随机取一条，更不能抛异常把整张队列打成空白。
 * 同时"一单有两条"这个事实本身不许被压掉 —— {@code conclusionTagMultiple} 要如实报 true。
 *
 * <h3>造数（隔离库 qiqi_mall_it，用完即重建）</h3>
 * <pre>
 * QT-4002  淘宝 268 元 2 件        → AUTO_PASS（命中「淘宝-标准单」）→ 落 AUTO_PASSED（visible=0）
 * QT-4001  抖音 1580 元 8 件       → NO_MATCH（唯一那条 AUTO 策略只认淘宝 + 应收 &lt; 500）→ NO_POLICY_MATCH
 * QT-4003  淘宝 268 元，status=8，**一条决策行都没有** → 从未判定（结论来源 NONE）
 * </pre>
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditConclusionShadowIntegrationTest}
 */
@SpringBootTest(properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditConclusionShadowIntegrationTest {

    private static final String MEMBER = "audit_shadow_member";
    private static final String PRODUCT = "期3影子字段测试商品";
    private static final String AUTO_POLICY = "影子-淘宝标准单";

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
        seedThreeOrders();
        seedAutoPolicy();

        // V7 必须已经把 AUTO_PASSED 种进目录，且 **visible=0 / blocking=0 / category='结论' / builtin=1**
        AuditTagCatalog autoPassed = tagCatalog.byCode(AuditTagCatalogService.CODE_AUTO_PASSED);
        assertThat(autoPassed).as("V7 必须已把 AUTO_PASSED 种进标目录（步骤 1 的迁移真的跑过）").isNotNull();
        assertThat(autoPassed.getVisible()).as("AUTO_PASSED 必须 visible=0（有事实、不显示）").isZero();
        assertThat(autoPassed.getBlocking()).as("AUTO_PASSED 不许拦任何东西（单子已经过审了）").isZero();
        assertThat(autoPassed.getCategory()).isEqualTo(AuditTagCatalogService.CATEGORY_CONCLUSION);
        assertThat(autoPassed.getBuiltin()).isEqualTo(1);
        System.out.println("[准备] QT-4002（该 AUTO_PASS）/ QT-4001（该 NO_MATCH）/ QT-4003（从未判定）就绪；"
                + "标目录 " + tagCatalog.all().size() + " 条");
    }

    @AfterAll
    void restore() {
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, "QT-400%"));
        deleted.putAll(ItCleanup.purgeCatalog(jdbc, PRODUCT, MEMBER));
        System.out.println("[清理] 删除行数：" + deleted);
        ItCleanup.residueOfOrders(jdbc, "QT-400%")
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        ItCleanup.residueOfCatalog(jdbc, PRODUCT, MEMBER)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        System.out.println("[清理] 残留断言通过：订单侧 / 商品侧 全部为 0");
    }

    // ============================================================ ① AUTO_PASS：落标但不显示

    @Test
    @Order(1)
    @DisplayName("①AUTO_PASS 单：active 结论标 == [AUTO_PASSED]，但队列行不显示它（visible=0），?tag= 仍筛得到")
    void autoPassLandsInvisibleTag() {
        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.total()).as("候选 = 两笔 status=7 的单（QT-4003 是 status=8，不进候选）").isEqualTo(2);
        assertThat(run.autoPass()).as("QT-4002").isEqualTo(1);
        assertThat(run.noMatch()).as("QT-4001").isEqualTo(1);

        // ---------------- 数据层：结论标真的落了，且就是 AUTO_PASSED ----------------
        assertThat(activeConclusionCodes("QT-4002"))
                .as("AUTO_PASS 的结论必须有标（否则「结论从标读」在这批最常见的单上给不出答案）")
                .containsExactly(AuditTagCatalogService.CODE_AUTO_PASSED);

        // ---------------- 渲染层：队列行不显示它 ----------------
        Map<String, Object> row = queueRowOf("QT-4002");
        assertThat(row.get("result")).as("result 一个字不许改（页签/筛选/e2e 全依赖它）").isEqualTo("AUTO_PASS");
        assertThat(strings(row.get("tagCodes")))
                .as("tags/tagCodes 是**可展示的标**（visible=1）⇒ AUTO_PASSED 不该出现在队列行的渲染输入里")
                .isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) row.get("tags");
        assertThat(tags).as("同理 tags 数组里也不该有它（页面 otherwise 会多出一枚「自动过审通过」）").isEmpty();
        assertThat(strings(row.get("allTagCodes")))
                .as("数据层必须看得到：allTagCodes（全部 active 标）里有 AUTO_PASSED —— 判断只能读这个")
                .containsExactly(AuditTagCatalogService.CODE_AUTO_PASSED);
        assertThat(row.get("primaryTagCode"))
                .as("主标只在**可见**标里挑 ⇒ 只有一枚 visible=0 的标时主标是 null（页面上没有第一枚标）").isNull();

        // ---------------- 影子字段：这里恰好"标与决策同源" ----------------
        assertThat(row.get("conclusionFromTag"))
                .as("AUTO_PASSED 反推回结论值 AUTO_PASS（取值域与 result 完全相同）").isEqualTo("AUTO_PASS");
        assertThat(row.get("conclusionSource")).isEqualTo("TAG");

        // ---------------- 筛选入口照样通：?tag=AUTO_PASSED ----------------
        Map<String, Object> byTag = queryService.queue(null, AuditTagCatalogService.CODE_AUTO_PASSED, 1, 50);
        assertThat(orderSns(byTag))
                .as("visible=0 只影响显示，不影响可筛：?tag=AUTO_PASSED 必须能筛出这单")
                .containsExactly("QT-4002");
        assertThat(byTag.get("total")).isEqualTo(1L);

        System.out.println("[①] QT-4002：库内结论标=" + activeConclusionCodes("QT-4002")
                + "，队列 tagCodes=" + row.get("tagCodes")
                + "，allTagCodes=" + row.get("allTagCodes")
                + "，?tag=AUTO_PASSED → " + orderSns(byTag));
    }

    // ============================================================ ② 影子字段（NO_MATCH → TAG）

    @Test
    @Order(2)
    @DisplayName("②影子字段：NO_MATCH 单 → conclusionFromTag='NO_MATCH'、conclusionSource='TAG'")
    void shadowOfNoMatchOrder() {
        Map<String, Object> row = queueRowOf("QT-4001");
        assertThat(row.get("result")).isEqualTo("NO_MATCH");
        assertThat(row.get("conclusionFromTag"))
                .as("从 NO_POLICY_MATCH 标反推出来的结论必须与 result 同值域同值").isEqualTo("NO_MATCH");
        assertThat(row.get("conclusionSource")).as("有 active 结论标 ⇒ 来源是 TAG").isEqualTo("TAG");
        assertThat(strings(row.get("conclusionTagCodes")))
                .as("结论标码列表（数据层，按 id 升序）").containsExactly("NO_POLICY_MATCH");
        assertThat(row.get("conclusionTagMultiple"))
                .as("互斥生效时不会出现多条结论标").isEqualTo(false);
        // 可见标里只有 NO_POLICY_MATCH（blocking=0）⇒ 它就是页面上的第一枚标
        assertThat(row.get("primaryTagCode")).isEqualTo("NO_POLICY_MATCH");
        assertThat(row.get("primaryTagName")).as("主标带中文名（页面显示的就是它）").isEqualTo("无策略命中");

        System.out.println("[②] QT-4001：result=" + row.get("result")
                + "，conclusionFromTag=" + row.get("conclusionFromTag")
                + "，conclusionSource=" + row.get("conclusionSource")
                + "，primary=" + row.get("primaryTagCode"));
    }

    // ============================================================ ③ 从未判定 → NONE（不冒充）

    @Test
    @Order(3)
    @DisplayName("③从未判定的单：conclusionSource='NONE'、conclusionFromTag=null —— 不拿 NO_MATCH 冒充")
    void neverJudgedOrderHasNoConclusion() {
        Long orderId = orderIdOf("QT-4003");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_decision WHERE order_id = ?", Integer.class, orderId))
                .as("造数前提：这张单一条决策行都没有（队列里也永远看不到它）").isZero();

        Map<String, Object> shadow = queryService.conclusionShadowOf(orderId);
        assertThat(shadow.get("conclusionSource"))
                .as("没有标、也没有决策行 ⇒ 结论来源就是 NONE（不是 DECISION、更不是 TAG）")
                .isEqualTo("NONE");
        assertThat(shadow.get("conclusionFromTag"))
                .as("**必须为 null**：拿 NO_MATCH 兜底等于伪造一条结论，对账会因此把缺口算成一致")
                .isNull();
        assertThat(shadow.get("hasDecision")).isEqualTo(false);
        assertThat(strings(shadow.get("conclusionTagCodes"))).isEmpty();
        assertThat(strings(shadow.get("allTagCodes"))).isEmpty();
        assertThat(shadow.get("primaryTagCode")).isNull();

        System.out.println("[③] QT-4003（从未判定）：conclusionSource=" + shadow.get("conclusionSource")
                + "，conclusionFromTag=" + shadow.get("conclusionFromTag") + "（不是 NO_MATCH）");
    }

    // ============================================================ ④ 多条结论标怎么取

    @Test
    @Order(4)
    @DisplayName("④一单多条 active 结论标：按「阻断型优先 → id 最大」取一条，并把「有多条」如实标出来")
    void multipleConclusionTagsTieBreak() {
        Long orderId = orderIdOf("QT-4002");
        // 模拟"互斥上线前的历史遗留"：在 AUTO_PASSED 之外再加一条阻断型结论标。
        // 用的是引擎同一个写入口（ensureActive），不是直插 SQL —— 造出来的状态是真实可能存在的。
        tagService.ensureActive(orderId, AuditTagCatalogService.CODE_BLOCKED, "历史遗留：互斥上线前并挂的行");

        Map<String, Object> row = queueRowOf("QT-4002");
        assertThat(strings(row.get("conclusionTagCodes")))
                .as("两条都在（数据层不许把「一单有两条」这个事实压掉）")
                .containsExactlyInAnyOrder(AuditTagCatalogService.CODE_AUTO_PASSED,
                        AuditTagCatalogService.CODE_BLOCKED);
        assertThat(row.get("conclusionTagMultiple"))
                .as("必须显式报「有多条」——理论不该有，真出现就说明互斥漏了某条路径").isEqualTo(true);
        assertThat(row.get("conclusionFromTag"))
                .as("取一条的规则：阻断型优先（BLOCKED blocking=1 压过 AUTO_PASSED blocking=0），而不是随机/最早那条")
                .isEqualTo("BLOCKED");
        assertThat(row.get("primaryTagCode"))
                .as("显示层的主标用同一条规则（阻断型优先），且只在可见标里挑").isEqualTo("BLOCKED");
        assertThat(strings(row.get("tagCodes")))
                .as("AUTO_PASSED 依旧不显示；BLOCKED 是可见标，必须显示")
                .containsExactly("BLOCKED");

        // 清理：把那条历史遗留的 BLOCKED 解掉，恢复"一单一条结论标"
        tagService.release(orderId, AuditTagCatalogService.CODE_BLOCKED, "集成测试清理");
        assertThat(activeConclusionCodes("QT-4002"))
                .containsExactly(AuditTagCatalogService.CODE_AUTO_PASSED);

        System.out.println("[④] 并挂两条时：conclusionFromTag=" + row.get("conclusionFromTag")
                + "，multiple=" + row.get("conclusionTagMultiple")
                + "，conclusionTagCodes=" + row.get("conclusionTagCodes"));
    }

    // ============================================================ ⑤ 一致性对账端点

    @Test
    @Order(5)
    @DisplayName("⑤一致性对账：造一张 result 与标不一致的单，端点必须把它数出来并列出明细")
    void parityReportListsInconsistentOrders() {
        // ---------------- 起点：跑完那轮之后，两笔单都是"标与决策一致" ----------------
        Map<String, Object> clean = queryService.conclusionParity(20);
        assertThat(number(clean.get("inconsistent")))
                .as("刚跑完的一轮不该有任何不一致（标就是这一轮落的）").isZero();
        assertThat(number(clean.get("scanned"))).as("扫的是队列里的单（每单一行 ≥ 本类这两笔）")
                .isGreaterThanOrEqualTo(2);
        assertThat(clean.get("consistencyRate")).isEqualTo(1.0);

        // ---------------- 造不一致：把 NO_MATCH 单上的标换掉 ----------------
        // 这是**真实可能存在的历史状态**：决策行说 NO_MATCH，但标表里挂着 AUDIT_PENDING
        // （互斥规则上线前，两条结论标可以并挂；或者人工在旧版本里改过标）。
        // 不用直插 SQL —— 走的是引擎同一个写入口，写出来的是真数据。
        Long orderId = orderIdOf("QT-4001");
        tagService.release(orderId, AuditTagCatalogService.CODE_NO_POLICY_MATCH, "集成测试：造不一致");
        tagService.ensureActive(orderId, AuditTagCatalogService.CODE_AUDIT_PENDING, "集成测试：造不一致");

        Map<String, Object> parity = queryService.conclusionParity(20);
        assertThat(number(parity.get("valueMismatch")))
                .as("两边都给了结论但不一样 ⇒ valueMismatch（最危险的一类）").isGreaterThanOrEqualTo(1);
        assertThat(number(parity.get("inconsistent"))).isGreaterThanOrEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) parity.get("samples");
        assertThat(samples).as("端点必须把不一致的单列出来（否则只有数字，没人能查）").isNotEmpty();
        Map<String, Object> s0 = samples.stream()
                .filter(x -> "QT-4001".equals(String.valueOf(x.get("orderSn"))))
                .findFirst().orElseThrow(() -> new AssertionError("对账明细里没有 QT-4001：" + samples));
        assertThat(s0.get("result")).isEqualTo("NO_MATCH");
        assertThat(s0.get("conclusionFromTag")).isEqualTo("MANUAL");
        assertThat(s0.get("conclusionSource")).isEqualTo("TAG");
        assertThat(strings(s0.get("activeConclusionTags"))).containsExactly("AUDIT_PENDING");
        assertThat(s0.get("conclusionTagMultiple")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> byPair = (Map<String, Object>) parity.get("byPair");
        assertThat(((Number) byPair.getOrDefault("AUTO_PASS → AUTO_PASS", 0)).intValue())
                .as("配对分布能一眼看出「哪些结论在被标改写」：AUTO_PASS ↔ AUTO_PASSED 这一对必须是一致的")
                .isGreaterThanOrEqualTo(1);
        assertThat(((Number) byPair.getOrDefault("NO_MATCH → MANUAL", 0)).intValue())
                .as("不一致的那一对也要出现在分布里（NO_MATCH 的决策 + AUDIT_PENDING 的标）")
                .isGreaterThanOrEqualTo(1);

        // 只读自证：对账跑完，标一行都没被改（没有"顺手修数据"这种能力）
        Map<String, Object> after = queueRowOf("QT-4001");
        assertThat(strings(after.get("conclusionTagCodes")))
                .as("对账端点**纯只读**：跑完标还是那一条 AUDIT_PENDING，不许顺手把数据修成一致")
                .containsExactly(AuditTagCatalogService.CODE_AUDIT_PENDING);

        System.out.println("[⑤] 对账：scanned=" + parity.get("scanned")
                + "，inconsistent=" + parity.get("inconsistent")
                + "（valueMismatch=" + parity.get("valueMismatch")
                + "，noConclusionTag=" + parity.get("noConclusionTag")
                + "），neverJudgedOrders=" + parity.get("neverJudgedOrders")
                + "，byPair=" + parity.get("byPair"));
    }

    // ================================================================== 造数 / 工具

    private void cleanOurData() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE 'QT-400%'", Long.class);
        for (Long id : ids) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE 'QT-400%'");
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
        jdbc.update("DELETE FROM member WHERE username = ?", MEMBER);
        jdbc.update("DELETE FROM product WHERE name = ?", PRODUCT);
    }

    private void seedCatalogMemberProduct() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '期3影子字段测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '期3影子字段测试类型')");
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '期3影子字段测试会员', 1, NOW(), 0, '普通')", MEMBER);
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES (?, '期3影子字段用', ?, ?, 1, 0)", PRODUCT, categoryId, typeId);
    }

    private void seedThreeOrders() {
        Long sku = newSku("268.00", "200.00");
        // QT-4002：淘宝 268 → AUTO_PASS
        createOrder("QT-4002", "淘宝", "268.00", "62.00", 7, sku, 2);
        // QT-4001：抖音 1580 → NO_MATCH
        Long skuDy = newSku("150.00", "100.00");
        createOrder("QT-4001", "抖音", "1580.00", "300.00", 7, skuDy, 8);
        // QT-4003：status=8（已在待发货）、**一条决策行都不造** → "从未判定过的历史单"
        createOrder("QT-4003", "淘宝", "268.00", "62.00", 8, sku, 2);
    }

    private Long newSku(String price, String cost) {
        Long skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, ?, ?, 0, ?)",
                productId, new BigDecimal(price), 1000, new BigDecimal(cost));
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, ?)", skuId, 1000);
        return skuId;
    }

    private void createOrder(String sn, String channel, String amount, String grossProfit,
                             int status, Long skuId, int qty) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, ?, 1, NOW(), NOW(), '李四', '13900000000', '北京市海淀区yy路2号', 0, 0, 0, "
                        + "?, '期3影子字段旗舰店', ?, NULL, 0)",
                sn, memberId, new BigDecimal(amount), status, channel, new BigDecimal(grossProfit));
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                        + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, productId, skuId, PRODUCT,
                new BigDecimal(amount).divide(new BigDecimal(qty), 2, java.math.RoundingMode.HALF_UP),
                qty, new BigDecimal("200.00"));
        // 出生台账（真实建单路径会写；造数补上，让"这张单存在过"也是完整的证据）
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) VALUES (?, NULL, ?, 'ORDER_CREATE', "
                        + "'期3影子字段集成测试造数', 'SYSTEM', 'system', ?)",
                orderId, status, orderId + ":0");
    }

    private void seedAutoPolicy() {
        policyService.create(new PolicyRequest("AUTO", 100, AUTO_POLICY, true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("500"), null, null)), null));
    }

    // ------------------------------------------------------------------ 工具

    private Map<String, Object> queueRowOf(String orderSn) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) queryService.queue(null, 1, 50).get("rows");
        return rows.stream()
                .filter(r -> orderSn.equals(String.valueOf(r.get("order_sn"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("队列里没有订单 " + orderSn + "（它可能没有决策行）"));
    }

    private List<String> orderSns(Map<String, Object> page) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        return rows.stream().map(r -> String.valueOf(r.get("order_sn"))).toList();
    }

    /** 直查库：这一单现在挂着哪些「结论」档标（与 e2e 的 activeConclusionTagsOf 同口径） */
    private List<String> activeConclusionCodes(String orderSn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 "
                        + "AND (t.tag_code LIKE 'FROZEN%' OR c.category = '结论') ORDER BY t.id",
                String.class, orderSn);
    }

    private Long orderIdOf(String orderSn) {
        return jdbc.queryForObject("SELECT id FROM `order` WHERE order_sn = ?", Long.class, orderSn);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        return raw == null ? List.of() : (List<String>) raw;
    }

    private static long number(Object raw) {
        return raw == null ? -1L : ((Number) raw).longValue();
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
