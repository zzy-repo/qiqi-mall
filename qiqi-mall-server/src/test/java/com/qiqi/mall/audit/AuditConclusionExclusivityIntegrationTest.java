package com.qiqi.mall.audit;

import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.entity.OrderAuditTag;
import com.qiqi.mall.audit.service.AuditExecutor;
import com.qiqi.mall.audit.service.AuditPolicyService;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
import com.qiqi.mall.audit.service.OrderAuditTagService;
import com.qiqi.mall.oms.controller.OrderController;
import com.qiqi.mall.shared.common.Result;
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
 * <b>期 1.6 的验收口径</b>：把「一单最多一条 active 结论标」从"以为成立"变成"真的成立"。
 *
 * <p>期 1.5 只在「引擎落新标」这一条路上做了互斥，实测漏了三条 —— 都是"代码走了但没被覆盖"：
 * <pre>
 * ① 人工冻结（{@code OrderAuditTagService#freeze}）从不 supersede
 *    ⇒「先判成 NO_MATCH 再人工冻结」的单并挂 NO_POLICY_MATCH + FROZEN（两条都是 category='结论'）；
 * ② {@code ConclusionTags#codeOf} 对 FROZEN 返回 null ⇒ 策略冻结同样不参与唯一性；
 * ③ 人工放行（7→8）/ 审核不通过（7→4）整条链零释放
 *    ⇒ 放行后还挂着 AUDIT_PENDING / BLOCKED（{@code blocking=1}）。③ 最危险：
 *      下游若按「结论标 + blocking」一刀切拦下发，会把**已经放行**的单锁死，且没有任何入口能解。
 * </pre>
 *
 * <p>本类就是这五条路径的可执行版本（每条都直查 {@code order_audit_tag}，断言"当前结论标集合"到底剩什么）：
 * <table border="1">
 *   <caption>五条路径 + 一条回归</caption>
 *   <tr><th>#</th><th>路径</th><th>active 结论标</th></tr>
 *   <tr><td>①</td><td>NO_MATCH → 人工冻结</td><td>{@code [FROZEN]}（不是两个）</td></tr>
 *   <tr><td>②</td><td>NO_MATCH → 策略冻结</td><td>{@code [FROZEN]}；配了细分码则 {@code [FROZEN_RETURN_APPLY]}</td></tr>
 *   <tr><td>③</td><td>MANUAL → 人工放行（7→8）</td><td>{@code []}（AUDIT_PENDING 已 superseded）</td></tr>
 *   <tr><td>④</td><td>BLOCKED → 人工放行（7→8）</td><td>{@code []}（BLOCKED 已 superseded）</td></tr>
 *   <tr><td>⑤</td><td>NO_MATCH → 审核不通过（7→4）</td><td>{@code []}（关单是终局，结论标不再成立）</td></tr>
 *   <tr><td>⑥</td><td>回归：NO_MATCH → MANUAL（期 1.5 的用例）</td><td>{@code [AUDIT_PENDING]}</td></tr>
 * </table>
 *
 * <h3>「结论标」的判据不是只 join 标目录</h3>
 * 细分冻结码 {@code FROZEN_RETURN_APPLY} 目前还没登记进 {@code audit_tag_catalog}（那是期 2 维护页的事），
 * 但它在语义上就是 FROZEN。所以本类的查询写作
 * {@code c.category='结论' OR t.tag_code LIKE 'FROZEN%'} —— 与 {@code OrderAuditTagService#isConclusionTag}
 * 同一口径。只按目录过滤会看不见那条标，等于把"两条结论标"的漏洞藏起来。
 *
 * <h3>造数（7 笔单，靠 channel 区分命中的策略；隔离库 {@code qiqi_mall_it}，用完即重建）</h3>
 * <pre>
 * QT-3001 抖音   1580 → NO_MATCH      （无策略命中 → ① 人工冻结）
 * QT-3002 快手   1580 → FROZEN        （EXCEPTION「快手需人工确认」，标码 FROZEN）
 * QT-3003 拼多多 268  → MANUAL        （AUTO 命中但被全局排除「备注含改」挡下 → ③ 人工放行）
 * QT-3004 京东   268  → BLOCKED       （BLOCK「京东拦截」→ ④ 人工放行）
 * QT-3005 小红书 268  → NO_MATCH      （→ ⑤ 审核不通过 7→4）
 * QT-3006 唯品会 268  → FROZEN        （EXCEPTION 配的是**细分码** FROZEN_RETURN_APPLY → ② 的另一半）
 * QT-3007 有赞   268  → NO_MATCH      （→ ⑥ 回归：改成 MANUAL，验期 1.5 的互斥没被本期改坏）
 * </pre>
 *
 * <p>运行：{@code ./scripts/run-it.sh -Dtest=AuditConclusionExclusivityIntegrationTest}
 */
@SpringBootTest(properties = {
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditConclusionExclusivityIntegrationTest {

    private static final String MEMBER = "audit_concl_member";
    private static final String PRODUCT = "结论互斥测试商品";
    private static final String WAREHOUSE = "结论互斥测试仓";
    /** 本类单号前缀（清理/残留断言都靠它定位，绝不与 QT-100x / QT-200x 混） */
    private static final String SN_LIKE = "QT-300%";

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
    private OrderAuditTagService tagService;
    /** 人工放行 / 审核不通过走真入口（不是直接改 status）：要证明的是整条链结算干净 */
    @Autowired
    private OrderController orderController;

    private Long memberId;
    private Long productId;

    // ================================================================== 准备

    @BeforeAll
    void seed() {
        ItDatabase.assertIsolatedAndReady(jdbc);
        cleanOurData();
        seedCatalogMemberProduct();
        seedWarehouse();
        seedOrders();
        seedPolicies();
        System.out.println("[准备] QT-3001..QT-3007 就绪（channel 各不相同，靠策略条件区分结论）");
    }

    @AfterAll
    void restore() {
        printTagTable();
        Map<String, Integer> deleted = new LinkedHashMap<>(ItCleanup.purgeOrders(jdbc, SN_LIKE));
        deleted.putAll(ItCleanup.purgeCatalog(jdbc, PRODUCT, MEMBER));
        // 放行路径会真的拆单 → 包裹状态流水（无外键、不随订单级联）与仓库必须显式清理
        deleted.put("delivery_status_ledger", jdbc.update(
                "DELETE FROM delivery_status_ledger WHERE order_id IN "
                        + "(SELECT id FROM `order` WHERE order_sn LIKE ?)", SN_LIKE));
        deleted.put("warehouse", jdbc.update("DELETE FROM warehouse WHERE name = ?", WAREHOUSE));
        deleted.put("audit_tag_catalog(custom)", jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0"));
        System.out.println("[清理] 删除行数：" + deleted);

        ItCleanup.residueOfOrders(jdbc, SN_LIKE)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE order_id NOT IN "
                        + "(SELECT id FROM `order`) AND delivery_sn IS NOT NULL", Integer.class))
                .as("包裹不许留下没爹的行（放行路径真拆过单）").isZero();
        ItCleanup.residueOfCatalog(jdbc, PRODUCT, MEMBER)
                .forEach((table, left) -> assertThat(left).as("清理后 `%s` 还有残留", table).isZero());
        System.out.println("[清理] 残留断言通过");
    }

    // ================================================================== ① 人工冻结

    /**
     * 路径①：先判成 NO_MATCH，再人工冻结。
     *
     * <p>期 1.5 的行为（实测）：两张标并列 —— NO_POLICY_MATCH + FROZEN，而且两行都是
     * {@code category='结论'}、FROZEN 还 {@code blocking=1}，队列口径无从判断"这单现在到底是什么"。
     *
     * <p>期 1.6 的判断（见 {@code OrderAuditTagService#freeze} 的注释）：<b>人工冻结该 supersede</b> ——
     * 人工冻结不篡改"上一轮判成什么"（决策行一个字不动、队列 result 仍是 NO_MATCH），
     * 但它确实是**当前结论的新状态**：这单现在就是"被冻结"，不是"无策略命中"。
     */
    @Test
    @Order(1)
    @DisplayName("①人工冻结：先 NO_MATCH 再人工冻结 ⇒ active 结论标只剩 [FROZEN]（不是两条）")
    void manualFreezeSupersedesConclusionTag() {
        // ---------------- 起点：这一轮判成 NO_MATCH，身上是 NO_POLICY_MATCH ----------------
        assertThat(decisionResult("QT-3001")).isEqualTo("NO_MATCH");
        assertThat(activeConclusionCodes("QT-3001"))
                .as("起点：结论标是 NO_POLICY_MATCH").containsExactly("NO_POLICY_MATCH");

        // ---------------- 人工冻结 ----------------
        String NOTE = "IT：先判不命中，再人工冻结";
        tagService.freeze(orderOf("QT-3001"), OrderAuditTagService.TAG_FROZEN, null, NOTE, "人工");

        // ① 只剩 FROZEN
        assertThat(activeConclusionCodes("QT-3001"))
                .as("人工冻结后 active 结论标必须只剩 FROZEN（NO_POLICY_MATCH 要让位）")
                .containsExactly(OrderAuditTagService.TAG_FROZEN);
        assertThat(conclusionTagCount("QT-3001")).as("结论标恰好 1 条（一单一个当前结论）").isEqualTo(1);

        // ② 旧标没被删，解除痕迹可查
        Map<String, Object> old = anyTagRow("QT-3001", "NO_POLICY_MATCH");
        assertThat(((Number) old.get("active")).intValue()).as("旧标应 active=0（不是删行）").isZero();
        assertThat(old.get("released_at")).as("解除时间必须记下").isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .as("解除人应说明「被新结论取代」").isEqualTo(OrderAuditTagService.RELEASED_BY_SUPERSEDED);
        assertThat(String.valueOf(old.get("reason"))).as("旧标的人话原因一个字没改").contains("无匹配");
        assertThat(tagRowCount("QT-3001", "NO_POLICY_MATCH")).as("行还在（只有一行）").isEqualTo(1);

        // ③ 决策行没被改写：冻结不改 result（队列徽标仍是"不命中"，这是对的 ——
        //    它回答的是"这一轮判了什么"，冻结由 frozen 那一列回答）
        assertThat(decisionResult("QT-3001")).as("人工冻结不产生决策行、也不改写上一轮结论")
                .isEqualTo("NO_MATCH");

        // ④ 冻结标本身：带人工填的备注 + 是冻结类标
        OrderAuditTag frozen = tagService.activeFrozen(orderIdOf("QT-3001"));
        assertThat(frozen).isNotNull();
        assertThat(frozen.getTagCode()).isEqualTo(OrderAuditTagService.TAG_FROZEN);
        assertThat(frozen.getReason()).as("reason = 人话快照（人工填的备注优先）").isEqualTo(NOTE);

        // ⑤ 幂等：再冻一次不产生第二条标、也不产生第二条审计
        int rowsBefore = tagRowCount("QT-3001", OrderAuditTagService.TAG_FROZEN);
        tagService.freeze(orderOf("QT-3001"), OrderAuditTagService.TAG_FROZEN, null, NOTE, "人工");
        assertThat(tagRowCount("QT-3001", OrderAuditTagService.TAG_FROZEN)).isEqualTo(rowsBefore);
        assertThat(activeConclusionCodes("QT-3001")).containsExactly(OrderAuditTagService.TAG_FROZEN);

        System.out.println("[①] QT-3001（NO_MATCH → 人工冻结）active 结论标 = "
                + activeConclusionCodes("QT-3001")
                + "；旧 NO_POLICY_MATCH：active=" + old.get("active")
                + " released_by=" + old.get("released_by") + " released_at=" + old.get("released_at"));
    }

    // ================================================================== ② 策略冻结

    /**
     * 路径②：策略冻结（{@code AuditExecutor} 的 FROZEN 分支）。
     *
     * <p>期 1.5 的行为：{@code ConclusionTags#codeOf(FROZEN)} 返回 {@code null} ⇒ 冻结不参与结论唯一性；
     * 冻结标能不能挤掉上一轮的结论标，全看 freeze 里有没有顺手做（答案：没有）。
     * 结果与 ① 一样 —— 一单两条结论标。
     *
     * <p>期 1.6：{@code codeOf} 返回**这轮冻结真正落的码**，互斥在 freeze 里做，
     * 与人工冻结是同一处代码。细分码（{@code FROZEN_RETURN_APPLY}）与 {@code FROZEN}
     * 是同一个结论的粗细两种说法 —— 互斥按"族"算一类，所以不会出现"细分码把通用 FROZEN 挤掉"
     * 或"通用 FROZEN 再补一条"的来回打架。
     */
    @Test
    @Order(2)
    @DisplayName("②策略冻结：⇒ active 结论标 == [FROZEN]；配细分码时 == [FROZEN_RETURN_APPLY] 且不额外补通用 FROZEN")
    void policyFreezeSupersedesConclusionTag() {
        // ---------------- QT-3002：策略配的标码就是 FROZEN ----------------
        assertThat(decisionResult("QT-3002")).as("异常策略命中 → FROZEN").isEqualTo("FROZEN");
        assertThat(activeConclusionCodes("QT-3002"))
                .as("策略冻结后只剩冻结标（上一轮的 NO_POLICY_MATCH 之类必须让位）")
                .containsExactly(OrderAuditTagService.TAG_FROZEN);
        assertThat(conclusionTagCount("QT-3002")).isEqualTo(1);
        OrderAuditTag frozen = tagService.activeFrozen(orderIdOf("QT-3002"));
        assertThat(frozen).isNotNull();
        assertThat(frozen.getReason()).as("reason = 策略里配的冻结原因").contains("快手");

        // ---------------- QT-3006：策略配的是细分码 FROZEN_RETURN_APPLY ----------------
        assertThat(decisionResult("QT-3006")).isEqualTo("FROZEN");
        assertThat(activeConclusionCodes("QT-3006"))
                .as("细分码就是这一轮冻结的结论标（不是 FROZEN + 细分码两条）")
                .containsExactly("FROZEN_RETURN_APPLY");
        assertThat(tagRowCount("QT-3006", OrderAuditTagService.TAG_FROZEN))
                .as("不许再补一条 reason_id 为空的通用 FROZEN（那会把一条结论说成两条）").isZero();
        assertThat(tagService.isFrozen(orderIdOf("QT-3006")))
                .as("FROZEN 前缀都算冻结（与 unfreeze 的判据同口径）").isTrue();

        // ---------------- 只有一条冻结标时，解冻正好放开一条 ----------------
        assertThat(tagService.unfreeze(orderOf("QT-3006"))).isEqualTo(1);
        assertThat(activeConclusionCodes("QT-3006"))
                .as("解冻后连一条结论标都不剩（冻结标是唯一那条）").isEmpty();

        System.out.println("[②] QT-3002（策略冻·FROZEN）active 结论标 = "
                + activeConclusionCodes("QT-3002") + "；QT-3006（策略冻·细分码）解冻前 = [FROZEN_RETURN_APPLY]，"
                + "解冻后 = " + activeConclusionCodes("QT-3006"));
    }

    // ================================================================== ③ 人工放行（MANUAL）

    /**
     * 路径③：MANUAL 单被人点「审核通过」（7→8）。
     *
     * <p>期 1.5 的行为（实测，最危险的一条）：整条链零释放 —— {@code auditPass} 只查 status==7、
     * 改 8、拆单。于是这单到下发环节还挂着 {@code AUDIT_PENDING}（{@code blocking=1}）。
     * 下游只要按「结论标 + blocking」一刀切，就把**已经放行**的单锁死，而队列里它已经不是待审核，
     * 没有任何入口能把那条标解掉 —— 第二道保险当场变成第二道死锁。
     */
    @Test
    @Order(3)
    @DisplayName("③人工放行（V9）：MANUAL → 审核通过 ⇒ active 结论标 == []（旧标已解除，不补落「通过」标）")
    void manualPassClearsConclusionTags() {
        Long id = orderIdOf("QT-3003");
        assertThat(decisionResult("QT-3003")).isEqualTo("MANUAL");
        assertThat(activeConclusionCodes("QT-3003")).as("起点：待人工审核").containsExactly("AUDIT_PENDING");
        assertThat(blockingConclusionCount("QT-3003")).as("AUDIT_PENDING 是 blocking=1").isEqualTo(1);

        Result<?> result = orderController.auditPass(id, null);
        assertThat(result.getCode()).as("放行应成功：%s", result.getMessage()).isZero();

        assertThat(statusOf("QT-3003")).as("7 待审核 → 8 待发货").isEqualTo(8);
        // ★ V9（2026-11-16）：结算回到「只解不落」—— 放行之后身上**没有**结论标。
        //   用户 16:38 拍板：「通过与否由状态表达，不由标表达」「绝不会有一个叫『已通过』的标」。
        assertThat(activeConclusionCodes("QT-3003"))
                .as("放行后 active 结论标 == []（「过了」由状态 8 表达）").isEmpty();
        assertThat(conclusionTagCount("QT-3003")).isZero();
        assertThat(blockingConclusionCount("QT-3003"))
                .as("已放行的单身上不许残留任何 blocking 的结论标（下发保险不会被自己锁死）").isZero();
        assertThat(activeTagCodes("QT-3003")).as("一个标都没有（本类没给它打特征标，也不落「通过」标）").isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_audit_tag "
                        + "WHERE order_id = ? AND tag_code = 'AUDIT_PASSED'", Integer.class, id))
                .as("不是「写了又被解掉」：AUDIT_PASSED 这条落标路径整段不存在").isZero();

        Map<String, Object> old = anyTagRow("QT-3003", "AUDIT_PENDING");
        assertThat(((Number) old.get("active")).intValue()).as("旧标必须真的被解除（不是留着两条）").isZero();
        assertThat(old.get("released_at")).as("解除时间必须记下").isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .as("解除人要说清是「放行结算」而不是「被新结论取代」")
                .isEqualTo(OrderAuditTagService.RELEASED_BY_AUDIT_PASS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE order_id = ?", Integer.class, id))
                .as("放行确实走完了拆单（证明不是绕开入口改的 status）").isGreaterThanOrEqualTo(1);

        System.out.println("[③] QT-3003（MANUAL → 人工通过）status=" + statusOf("QT-3003")
                + "，active 结论标 = " + activeConclusionCodes("QT-3003")
                + "；AUDIT_PENDING released_by=" + old.get("released_by"));
    }

    // ================================================================== ④ 被 BLOCK 过再放行

    /** 路径④：先被策略拦过（BLOCKED，{@code blocking=1}），再人工放行 —— 同样必须结算干净。 */
    @Test
    @Order(4)
    @DisplayName("④被 BLOCK 过再人工放行 ⇒ active 结论标 == []（BLOCKED 已解除，不补落「通过」标）")
    void blockedThenPassClearsConclusionTags() {
        assertThat(decisionResult("QT-3004")).isEqualTo("BLOCKED");
        assertThat(activeConclusionCodes("QT-3004")).as("起点：被拦").containsExactly("BLOCKED");

        Result<?> result = orderController.auditPass(orderIdOf("QT-3004"), null);
        assertThat(result.getCode()).as("放行应成功：%s", result.getMessage()).isZero();

        assertThat(statusOf("QT-3004")).isEqualTo(8);
        assertThat(activeConclusionCodes("QT-3004"))
                .as("放行后不该再挂着 BLOCKED（它就是那条会把已放行单锁死的标），且不补落任何「通过」标")
                .isEmpty();
        assertThat(conclusionTagCount("QT-3004")).isZero();
        Map<String, Object> old = anyTagRow("QT-3004", "BLOCKED");
        assertThat(((Number) old.get("active")).intValue()).isZero();
        assertThat(old.get("released_at")).as("解除时间必须记下").isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .isEqualTo(OrderAuditTagService.RELEASED_BY_AUDIT_PASS);

        System.out.println("[④] QT-3004（BLOCKED → 人工通过）status=" + statusOf("QT-3004")
                + "，active 结论标 = " + activeConclusionCodes("QT-3004")
                + "；BLOCKED released_by=" + old.get("released_by"));
    }

    // ================================================================== ⑤ 审核不通过

    /**
     * 路径⑤：审核不通过（7→4 已关闭）。
     *
     * <p>判断（第 4 条的延伸）：<b>同样要结算</b>。4 是终局，这单永远不会再被审核、也不会下发；
     * 挂着 {@code NO_POLICY_MATCH}（blocking=0，但仍是"当前结论"）会让「这单现在是什么」自相矛盾 ——
     * 状态说"已关闭"，标说"系统还不知道拿它怎么办"。所以按同一条路结算，
     * 只是 {@code released_by=SYSTEM:AUDIT_REJECT}，事后查库能分清"被放行"还是"被关闭"。
     */
    @Test
    @Order(5)
    @DisplayName("⑤审核不通过（7→4，V9）⇒ active 结论标 == []（「没过」由状态 4 表达，不落「不通过」标）")
    void auditRejectClearsConclusionTags() {
        assertThat(decisionResult("QT-3005")).isEqualTo("NO_MATCH");
        assertThat(activeConclusionCodes("QT-3005")).containsExactly("NO_POLICY_MATCH");

        Result<?> result = orderController.auditReject(orderIdOf("QT-3005"), null);
        assertThat(result.getCode()).as("审核不通过应成功：%s", result.getMessage()).isZero();

        assertThat(statusOf("QT-3005")).as("7 待审核 → 4 已关闭").isEqualTo(4);
        // ★ V9：关单之后身上**没有**结论标 —— 「没过」由状态 4（已关闭）表达。
        assertThat(activeConclusionCodes("QT-3005"))
                .as("关单后 active 结论标 == []").isEmpty();
        assertThat(conclusionTagCount("QT-3005")).isZero();
        Map<String, Object> old = anyTagRow("QT-3005", "NO_POLICY_MATCH");
        assertThat(((Number) old.get("active")).intValue()).isZero();
        assertThat(old.get("released_at")).isNotNull();
        assertThat(String.valueOf(old.get("released_by")))
                .isEqualTo(OrderAuditTagService.RELEASED_BY_AUDIT_REJECT);

        System.out.println("[⑤] QT-3005（NO_MATCH → 审核不通过）status=" + statusOf("QT-3005")
                + "，active 结论标 = " + activeConclusionCodes("QT-3005")
                + "；NO_POLICY_MATCH released_by=" + old.get("released_by"));
    }

    // ================================================================== ⑥ 回归（期 1.5）

    /**
     * 回归：期 1.5 的那条互斥用例（NO_MATCH → MANUAL 只剩 AUDIT_PENDING）在本期改动之后必须仍然是绿的。
     *
     * <p>为什么单独立一条：本期把 {@code supersedeOtherConclusionTags} 拆成了两条分支
     * （冻结族一族内互相保留 / 其它逐条让位用一条 UPDATE），改的是**所有结论标**的让位路径。
     * 若不在这里再钉一次，"修好了冻结、弄坏了普通结论标"这种回归就要等到期 2 才炸。
     */
    @Test
    @Order(6)
    @DisplayName("⑥回归：NO_MATCH → MANUAL ⇒ 只剩 [AUDIT_PENDING]，旧标 released_by=SYSTEM:SUPERSEDED")
    void noMatchToManualStillExclusive() {
        assertThat(activeConclusionCodes("QT-3007")).as("起点：不命中").containsExactly("NO_POLICY_MATCH");

        // 造一条 AUTO：有赞 全部命中，但「备注含改」被全局排除 → 结论从 NO_MATCH 变 MANUAL
        jdbc.update("UPDATE `order` SET cs_remark = '客户改地址：朝阳区zz路9号' WHERE order_sn = 'QT-3007'");
        policyService.create(new PolicyRequest("AUTO", 90, "IT-AUTO-有赞", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "有赞", null)),
                List.of(new ConditionRequest("EXCLUDE", "ALL_AUTO", "CS_REMARK", "CONTAINS", "TEXT",
                        null, "改", null))));

        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.manual()).as("QT-3007 现在该是 MANUAL（被全局排除）").isGreaterThanOrEqualTo(1);
        assertThat(decisionResult("QT-3007")).isEqualTo("MANUAL");

        assertThat(activeConclusionCodes("QT-3007"))
                .as("期 1.5 的互斥仍然成立：只剩 AUDIT_PENDING").containsExactly("AUDIT_PENDING");
        Map<String, Object> old = anyTagRow("QT-3007", "NO_POLICY_MATCH");
        assertThat(((Number) old.get("active")).intValue()).isZero();
        assertThat(String.valueOf(old.get("released_by")))
                .isEqualTo(OrderAuditTagService.RELEASED_BY_SUPERSEDED);

        System.out.println("[⑥] QT-3007（NO_MATCH → MANUAL）active 结论标 = " + activeConclusionCodes("QT-3007")
                + "；旧 NO_POLICY_MATCH released_by=" + old.get("released_by"));
    }

    // ================================================================== 造数

    private void cleanOurData() {
        for (Long id : jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE ?", Long.class, SN_LIKE)) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
            jdbc.update("DELETE FROM delivery_status_ledger WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM delivery_item WHERE delivery_id IN "
                + "(SELECT id FROM delivery_order WHERE order_id NOT IN (SELECT id FROM `order`))");
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE ?", SN_LIKE);
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
        jdbc.update("DELETE FROM member WHERE username = ?", MEMBER);
        jdbc.update("DELETE FROM product WHERE name = ?", PRODUCT);
        jdbc.update("DELETE FROM warehouse WHERE name = ?", WAREHOUSE);
        jdbc.update("DELETE FROM audit_tag_catalog WHERE builtin = 0");
    }

    private void seedCatalogMemberProduct() {
        jdbc.update("INSERT IGNORE INTO category (id, parent_id, name) VALUES (1, 0, '结论互斥测试分类')");
        jdbc.update("INSERT IGNORE INTO product_type (id, name) VALUES (1, '结论互斥测试类型')");
        memberId = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES (?, '', '结论互斥测试会员', 1, NOW(), 0, '普通')", MEMBER);
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES (?, '结论互斥集成测试用', ?, ?, 1, 0)", PRODUCT, categoryId, typeId);
    }

    /** 放行路径真会拆单：拆单必须有「启用的仓」可兜底（该 SKU 没有分仓库存记录 → 默认仓） */
    private void seedWarehouse() {
        insertAndReturnKey("INSERT INTO warehouse (name, status) VALUES (?, 1)", WAREHOUSE);
    }

    private void seedOrders() {
        Long sku = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, 268.00, 1000, 0, 200.00)",
                productId);
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, 1000)", sku);
        createOrder("QT-3001", "抖音", "1580.00", null, sku, 8);
        createOrder("QT-3002", "快手", "1580.00", null, sku, 8);
        createOrder("QT-3003", "拼多多", "268.00", "客户改地址：海淀区aa路1号", sku, 2);
        createOrder("QT-3004", "京东", "268.00", null, sku, 2);
        createOrder("QT-3005", "小红书", "268.00", null, sku, 2);
        createOrder("QT-3006", "唯品会", "268.00", null, sku, 2);
        createOrder("QT-3007", "有赞", "268.00", null, sku, 2);
    }

    private void createOrder(String sn, String channel, String amount, String csRemark, Long skuId, int qty) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, 7, 1, NOW(), NOW(), '张三', '13800000000', '北京市朝阳区xx路1号', 0, 0, 0, "
                        + "?, '官方旗舰店', 60.00, ?, 0)",
                sn, memberId, new BigDecimal(amount), channel, csRemark);
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                        + "product_price, product_quantity, cost_price) VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, productId, skuId, PRODUCT, new BigDecimal("134.00"), qty, new BigDecimal("100.00"));
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) VALUES (?, NULL, 7, 'ORDER_CREATE', "
                        + "'结论互斥集成测试造数', 'SYSTEM', 'system', ?)", orderId, orderId + ":0");
    }

    /** 三条策略 + 一条全局排除：按 channel 把它们派给不同的单（BLOCK 先于 EXCEPTION 先于 AUTO） */
    private void seedPolicies() {
        policyService.create(new PolicyRequest("BLOCK", 10, "IT-BLOCK-京东", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "京东", null)),
                null));
        policyService.create(new PolicyRequest("EXCEPTION", 10, "IT-EXCEPTION-快手", true, "FROZEN",
                "快手渠道需人工确认",
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "快手", null)),
                null));
        policyService.create(new PolicyRequest("EXCEPTION", 20, "IT-EXCEPTION-唯品会", true,
                "FROZEN_RETURN_APPLY", "唯品会渠道：冻结（细分码）",
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "唯品会", null)),
                null));
        // AUTO 只认拼多多，且被全局排除「备注含改」挡下 → QT-3003 留人工
        policyService.create(new PolicyRequest("AUTO", 100, "IT-AUTO-拼多多", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "拼多多", null)),
                List.of(new ConditionRequest("EXCLUDE", "ALL_AUTO", "CS_REMARK", "CONTAINS", "TEXT",
                        null, "改", null))));
    }

    /** 第一轮：七笔单各得一个结论（这一步把每条路径的"上一轮结论标"摆好） */
    @Test
    @Order(0)
    @DisplayName("⓪起点：跑一轮，七笔单各自落一条结论标（后面五条路径都从这里出发）")
    void firstRound() {
        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.total()).as("候选 = 七笔 status=7 的单").isEqualTo(7);
        assertThat(decisionResult("QT-3001")).isEqualTo("NO_MATCH");
        assertThat(decisionResult("QT-3002")).isEqualTo("FROZEN");
        assertThat(decisionResult("QT-3003")).isEqualTo("MANUAL");
        assertThat(decisionResult("QT-3004")).isEqualTo("BLOCKED");
        assertThat(decisionResult("QT-3005")).isEqualTo("NO_MATCH");
        assertThat(decisionResult("QT-3006")).isEqualTo("FROZEN");
        assertThat(decisionResult("QT-3007")).isEqualTo("NO_MATCH");

        System.out.println("[⓪] 第一轮结论标：3001=" + activeConclusionCodes("QT-3001")
                + " 3002=" + activeConclusionCodes("QT-3002")
                + " 3003=" + activeConclusionCodes("QT-3003")
                + " 3004=" + activeConclusionCodes("QT-3004")
                + " 3005=" + activeConclusionCodes("QT-3005")
                + " 3006=" + activeConclusionCodes("QT-3006")
                + " 3007=" + activeConclusionCodes("QT-3007"));
    }

    // ================================================================== 查库工具

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

    private int statusOf(String sn) {
        Integer s = jdbc.queryForObject("SELECT status FROM `order` WHERE order_sn = ?", Integer.class, sn);
        return s == null ? -1 : s;
    }

    private String decisionResult(String sn) {
        return jdbc.queryForObject(
                "SELECT d.result FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn = ? ORDER BY d.round_no DESC, d.id DESC LIMIT 1", String.class, sn);
    }

    private List<String> activeTagCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.active = 1 ORDER BY t.id", String.class, sn);
    }

    /**
     * 这单身上「结论」档的 active 标码。
     *
     * <p>判据 = 标目录 {@code category='结论'} <b>或</b> {@code FROZEN%} 前缀：
     * 细分冻结码还没登记进目录，只按目录过滤会看不见它（见类注释）。
     */
    private List<String> activeConclusionCodes(String sn) {
        return jdbc.queryForList(
                "SELECT t.tag_code FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 "
                        + "  AND (c.category = '结论' OR t.tag_code LIKE 'FROZEN%') ORDER BY t.id",
                String.class, sn);
    }

    private int conclusionTagCount(String sn) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 "
                        + "  AND (c.category = '结论' OR t.tag_code LIKE 'FROZEN%')", Integer.class, sn);
        return n == null ? -1 : n;
    }

    /** 这单身上「会拦住下单往下走」的 active 结论标数（期 2 下发保险的口径） */
    private int blockingConclusionCount(String sn) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn = ? AND t.active = 1 AND c.category = '结论' AND c.blocking = 1",
                Integer.class, sn);
        return n == null ? -1 : n;
    }

    private int tagRowCount(String sn, String code) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.tag_code = ?", Integer.class, sn, code);
        return n == null ? -1 : n;
    }

    /** 取该单该码的标行（**不看 active**：互斥/结算之后旧标是 active=0，仍要能查到解除痕迹） */
    private Map<String, Object> anyTagRow(String sn, String code) {
        return jdbc.queryForMap(
                "SELECT t.* FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.tag_code = ? ORDER BY t.id DESC LIMIT 1", sn, code);
    }

    private void printTagTable() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.order_sn, t.tag_code, t.active, IFNULL(c.name,'<未登记>') AS name, "
                        + "IFNULL(c.category,'-') AS category, t.released_by, LEFT(IFNULL(t.reason,''), 30) AS reason "
                        + "FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "LEFT JOIN audit_tag_catalog c ON c.code = t.tag_code "
                        + "WHERE o.order_sn LIKE ? ORDER BY o.order_sn, t.id", SN_LIKE);
        System.out.println("┌─ order_audit_tag（隔离库 " + ItDatabase.dbName() + "）");
        for (Map<String, Object> r : rows) {
            System.out.println("│ " + r.get("order_sn") + " | " + r.get("tag_code") + " | active=" + r.get("active")
                    + " | " + r.get("name") + " | " + r.get("category")
                    + " | released_by=" + r.get("released_by") + " | " + r.get("reason"));
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
