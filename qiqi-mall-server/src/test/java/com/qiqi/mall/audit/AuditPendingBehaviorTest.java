package com.qiqi.mall.audit;

import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.service.AuditExecutor;
import com.qiqi.mall.audit.service.AuditPolicyService;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>审核引擎三处修正的行为契约</b>（已实现，三条断言全绿；它们就是这三处修正的唯一验收口径）。
 *
 * <p>存在意义：这三处是「设计缝」，只在对话里描述过一次。写成可执行的断言之后，实现时不用回读
 * 聊天记录、也不用靠记忆判断「这样做算不算做完了」—— 把三条跑到绿，就是做完。
 *
 * <h3>① 不命中的单也要跑一次库存预检（只写证据，不改结论）</h3>
 * <pre>
 * 修前：库存预检（{@code AuditEngine#autoPassOrDowngrade}）只在「已经是过审候选」之后跑，
 *       NO_MATCH 的单压根不查库存。于是 QT-1006（淘宝 420 元 6 件、桶里合计 5 件）判 NO_MATCH
 *       后一直躺在待审核里，直到人工审核点「通过」才因缺货失败。
 * 现在：判定链 [7b]（{@code AuditEngine#noMatchEvidence}）对 NO_MATCH 也跑一次库存预检，
 *       <b>只写进 evidence 作提示，不改变判定结果</b>（result 仍是 NO_MATCH、downgrade_reason
 *       仍是 NULL，但 evidence_json 里出现 stock 段 + 一条「若人工审核将因缺货失败」的提示）。
 *       绝不复用 [7a]「库存不足 → 降级 MANUAL + STOCK_SHORT」那条分支（那是 AUTO_PASS 专用的）。
 * </pre>
 *
 * <h3>② 五种判定对外口径统一（STOCK_SHORT 只是 MANUAL 的 downgrade 子类）</h3>
 * <pre>
 * 修前：MANUAL 有三支来路 —— 被排除条件挡掉 / 库存不足降级(STOCK_SHORT) / 成本缺失降级，
 *       对外混着数，用户「留人工 + 不命中」对不平。
 * 现在：口径统一为「自动过审 / 转人工 / 被阻止 / 被冻结 / 不命中」五类，
 *       「被阻止 + 被冻结」合称「被拦」；STOCK_SHORT 不单列（result 仍是 MANUAL，
 *       靠 downgrade_reason 区分，**不新增枚举值**）。
 *       即：五类计数之和 == 决策行数，且 total == 五类之和 + skipped（skipped 不写决策行，见 ③）。
 * </pre>
 *
 * <h3>③ SKIPPED 不写决策行（冻结单不该每轮多一行）</h3>
 * <pre>
 * 修前：候选范围含「有 active FROZEN 标的单」，所以冻结单每跑一轮就写一行 SKIPPED 决策，
 *       冻结久了 round_no 一直涨、证据表被无意义的行撑大。
 * 现在：SKIPPED <b>不写决策行</b>，但 run 的计数里仍如实体现「本轮跳过了 N 笔」
 *       （{@link RunResultDto#skipped()}，不占决策行）—— 冻结单连跑 3 轮，决策行数不增长且为 0。
 * </pre>
 *
 * <h3>与 {@link AuditRunIntegrationTest} 共用同一批单号（QT-100x）</h3>
 * 本类自建自清全套数据（会员 / 商品 / SKU / 桶 / 7 笔单 / 7 条策略），因此<b>不要与
 * {@code AuditRunIntegrationTest} 同时跑</b>（两者都拥有 QT-100x 家族）。
 * 造数在<b>各用例体内按需触发</b>（{@link #seedIfNeeded()}），不在 {@code @BeforeAll} 里 ——
 * {@code @BeforeAll} 在「三个用例全 {@code @Disabled}」时照样执行，会造数 + 清表，
 * 等于一个不跑断言的破坏性副作用。放进用例体后，全 disabled 时一行 SQL 都不跑。
 * 每个用例开头再调 {@link #resetBaseline()}，把 7 笔单还原成「status=7、无决策行、无标」的干净基线，
 * 并把库里既有的其它待审核单临时挪到 8（{@link #parkForeignPendingOrders()}，用例结束还原）——
 * 候选范围是全库的，不挪开它们 {@code total} 就会被历史数据顶多出两笔。
 * 这样三个用例彼此独立、可单独跑、也可连跑。
 *
 * <p>跑：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditPendingBehaviorTest} → 期望 <b>3 passed</b>。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/qiqi_mall_test"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=root",
        "spring.datasource.password=qiqi123456",
        // sku.stock 缓存回写会改写断言依据（桶才是权威值），关掉
        "qiqi.stock.cache-refresh-ms=3600000",
        // 关掉本上下文的定时调度：后台线程若在断言之间跑一轮，计数与轮次都会漂
        "qiqi.audit.schedule-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditPendingBehaviorTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuditExecutor executor;
    @Autowired
    private AuditPolicyService policyService;

    /** ① 的引擎层用例用它：纯函数，不连库也能证明「结论不变、证据多了 stock 段」 */
    private final AuditEngine engine = AuditScenario.engine();

    private Long memberUser;
    private Long memberStore;
    private Long productId;
    private Long skuMain;
    private Long skuDouyin;
    private Long skuDirect;
    private Long skuScarce;                 // QT-1007：桶 5 < 需求 8 → STOCK_SHORT
    private final List<Long> skuSix = new ArrayList<>();   // QT-1006 的 6 个样，桶合计 5
    private Long returnReasonId;

    /** 库里既有的其它待审核单（如 QQ2026…）：候选范围 = 「status=7 或已冻结」，它们会混进五类计数 */
    private final Map<Long, Integer> parkedOrders = new LinkedHashMap<>();

    // ================================================================== 修正 ①

    @Test
    @Order(1)
    @DisplayName("①QT-1006 判 NO_MATCH 也要查库存：结果仍 NO_MATCH，但 evidence 里有 stock 段 + 缺货提示，状态仍 7、台账 0 行")
    void noMatchStillRunsStockPrecheckForEvidenceOnly() {
        seedIfNeeded();
        resetBaseline();

        // ---------------- 引擎层（纯函数）：结论不许被库存信息改动 ----------------
        // 淘宝 420 元 6 件 6 个样、桶里合计只有 5 件 —— 两个事实同时成立：
        //   「种类数 6 ≤ 5」不成立 → NO_MATCH；「SUM(sku_bucket.stock)=5 < 需求 6」→ 缺货
        AuditContext ctx = AuditScenario.builder(1006L, "QT-1006", 7, "淘宝", "420.00")
                .grossProfit("100.00").itemQty(6).itemKinds(6)
                .stock(5, 999).costMissing(false)
                .build();
        AuditVerdict verdict = engine.evaluate(ctx, AuditScenario.policies(), AuditScenario.strict());

        // 期望行为（明天）：result 不变、不是降级、不推进状态
        assertThat(verdict.result()).isEqualTo(AuditResult.NO_MATCH);
        assertThat(verdict.matchedPolicy()).isNull();
        assertThat(verdict.downgradeReason())
                .as("库存不足在这里只是提示，绝不能把 NO_MATCH 改写成 STOCK_SHORT 降级")
                .isNull();
        assertThat(verdict.advancesStatus()).isFalse();
        // miss 原因不许被库存信息污染：仍然是「商品种类数 6 ≤ 5」
        assertThat(verdict.missReasons()).anySatisfy(m -> {
            assertThat(m.policyName()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
            assertThat(m.reasons()).anyMatch(r -> r.contains("商品种类数 6 ≤ 5"));
        });

        // 期望行为（明天）：evidence 里出现 stock 段，值与 AUTO_PASS 那条链同口径
        assertThat(verdict.evidence()).containsKey("stock");
        @SuppressWarnings("unchecked")
        Map<String, Object> stock = (Map<String, Object>) verdict.evidence().get("stock");
        assertThat(stock).containsEntry("bucket_sum", 5).containsEntry("demand", 6);
        // 段里还要有一条「若人工审核将因缺货失败」类提示（具体挂在 stock.hint / stock.note / evidence.reason
        // 都行 —— 这里按「整条证据文本里同时出现这两个词」断言，避免锁死字段名）
        assertThat(String.valueOf(verdict.evidence()))
                .as("证据里必须有让人能直接行动的那句话：缺货 + 人工审核")
                .contains("缺货").contains("人工审核");

        // ---------------- 执行层（真库）：跑一轮，看真落库的 evidence_json ----------------
        RunResultDto run = executor.run("MANUAL", null);
        assertThat(run.noMatch()).as("QT-1003 + QT-1006").isEqualTo(2);

        Map<String, Object> d6 = decision("QT-1006");
        assertThat(d6.get("result")).isEqualTo("NO_MATCH");
        assertThat(d6.get("downgrade_reason")).as("结论没变，downgrade_reason 必须仍为 NULL").isNull();
        assertThat(jdbc.queryForObject(
                "SELECT JSON_EXTRACT(evidence_json, '$.stock') FROM order_audit_decision WHERE id = ?",
                Object.class, ((Number) d6.get("id")).longValue()))
                .as("evidence_json 里要有 stock 段")
                .isNotNull();
        String stockJson = String.valueOf(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(evidence_json, '$.stock')) FROM order_audit_decision WHERE id = ?",
                String.class, ((Number) d6.get("id")).longValue()));
        assertThat(stockJson).contains("bucket_sum").contains("demand");
        assertThat(String.valueOf(d6.get("evidence_json"))).contains("缺货").contains("人工审核");

        // 只是提示：状态不动（仍待审核 7）、库存不足不产生任何挂起标、不写状态台账
        assertThat(statusOf("QT-1006")).isEqualTo(7);
        assertThat(countTags("QT-1006")).as("NO_MATCH 不打任何挂起标（含 STOCK_SHORT）").isZero();
        assertThat(countLedger("QT-1006", "AUDIT%")).as("台账 0 行：NO_MATCH 不推进状态").isZero();

        printRun("①", run);
        printDecisions("①");
    }

    // ================================================================== 修正 ②

    @Test
    @Order(2)
    @DisplayName("②一轮 run 的五类计数之和 == total；QT-1007 库存降级行 result==MANUAL 且 downgrade_reason==STOCK_SHORT")
    void fiveVerdictBucketsAddUpAndStockShortIsManualDowngrade() {
        seedIfNeeded();
        resetBaseline();

        // 数据：用户原表六笔单 + 一笔「AUTO 条件全满足但桶不够」的单
        //   QT-1001 → 自动过审 1        （淘宝-标准单）
        //   QT-1002 → 转人工（被全局排除）1
        //   QT-1004 → 被阻止 1          （毛利不为正）
        //   QT-1005 → 被冻结 1          （有退款申请）
        //   QT-1003 / QT-1006 → 不命中 2
        //   QT-1007 → 库存不足降级 → 也是 MANUAL（第二笔转人工），不另立第六类
        RunResultDto run = executor.run("MANUAL", null);

        // ------------- 口径一：五类之和 == total（这是「用户数不平」的那条缝） -------------
        int autoPass = run.autoPass();
        int manual = run.manual();
        int blocked = run.blocked();
        int frozen = run.frozen();
        int noMatch = run.noMatch();
        assertThat(run.total()).as("六笔原表单 + QT-1007").isEqualTo(7);
        assertThat(autoPass + manual + blocked + frozen + noMatch)
                .as("五类计数之和必须等于 total —— 不许有单落在五类之外")
                .isEqualTo(run.total());
        assertThat(run.skipped()).as("本轮没有 SKIPPED（SKIPPED 不占五类，见 ③）").isZero();
        assertThat(run.decisions()).hasSize(run.total());

        // 逐类钉死，防止「把某一类挪进另一类」也能凑出 sum == total
        assertThat(autoPass).as("QT-1001").isEqualTo(1);
        assertThat(manual).as("QT-1002 被排除 + QT-1007 库存降级").isEqualTo(2);
        assertThat(blocked).as("QT-1004 毛利不为正").isEqualTo(1);
        assertThat(frozen).as("QT-1005 有退款申请").isEqualTo(1);
        assertThat(noMatch).as("QT-1003 金额超限 + QT-1006 种类数超限").isEqualTo(2);

        // ------------- 口径二：被拦 = 被阻止 + 被冻结（对外合称，不单列） -------------
        assertThat(blocked + frozen).as("对外说「被拦 2 笔」= 阻止 1 + 冻结 1").isEqualTo(2);

        // ------------- 口径三：STOCK_SHORT 是 MANUAL 的 downgrade 子类，不是第六种判定 -------------
        Map<String, Object> d7 = decision("QT-1007");
        assertThat(d7.get("result"))
                .as("库存不足降级在 result 列上就是 MANUAL")
                .isEqualTo("MANUAL");
        assertThat(d7.get("downgrade_reason"))
                .as("「哪一支转人工」由 downgrade_reason 表达，不新增 result 枚举值")
                .isEqualTo("STOCK_SHORT");
        assertThat(d7.get("matched_policy_name")).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
        assertThat(String.valueOf(d7.get("reason"))).contains("可用 5").contains("需求 8");

        // 对照：被排除转人工的 downgrade_reason 是 NULL（两支来路不能混）
        Map<String, Object> d2 = decision("QT-1002");
        assertThat(d2.get("result")).isEqualTo("MANUAL");
        assertThat(d2.get("downgrade_reason")).as("被排除转人工没有 downgrade 子类").isNull();
        assertThat(String.valueOf(d2.get("reason"))).contains("被排除");

        // 两条 MANUAL 的来路在 decision 行上可区分 —— 这是「口径统一」要保住的信息量
        assertThat(run.decisions().stream().filter(r -> "MANUAL".equals(r.result())).count()).isEqualTo(2);

        printRun("②", run);
        printDecisions("②");
    }

    // ================================================================== 修正 ③

    @Test
    @Order(3)
    @DisplayName("③已冻结单连跑 3 轮：该单决策行数不增长；每轮 run.skipped()==1")
    void skippedDoesNotAppendDecisionRow() {
        seedIfNeeded();
        resetBaseline();

        // 只留一笔已冻结的单在候选范围里：status=7 + active FROZEN 标（其余 6 笔挪到 8）
        Long order5 = orderIdOf("QT-1005");
        jdbc.update("UPDATE `order` SET status = 8 WHERE order_sn LIKE 'QT-100%' AND order_sn <> 'QT-1005'");
        jdbc.update("INSERT INTO order_audit_tag (order_id, tag_code, active, reason) "
                + "VALUES (?, 'FROZEN', 1, '已冻结：等待处理退款申请')", order5);
        assertThat(countTags("QT-1005")).as("FROZEN 标是它进入候选范围的唯一理由").isEqualTo(1);

        RunResultDto run1 = executor.run("MANUAL", null);
        int rowsAfter1 = decisionCount(order5);

        RunResultDto run2 = executor.run("MANUAL", null);
        RunResultDto run3 = executor.run("MANUAL", null);
        int rowsAfter3 = decisionCount(order5);

        // 期望行为（明天）：SKIPPED 不写决策行 → 行数不随轮次增长
        assertThat(rowsAfter3)
                .as("同一笔已冻结单连跑 3 轮，决策行数不许增长（冻结久了 round_no 会一直涨）")
                .isEqualTo(rowsAfter1);
        assertThat(rowsAfter3).as("SKIPPED 一行都不该写").isZero();

        // 期望行为（明天）：跳过的事实仍要在 run 上看得见 —— 用 skipped 计数，不占决策行
        assertThat(run1.skipped()).as("第 1 轮：本轮跳过 1 笔").isEqualTo(1);
        assertThat(run2.skipped()).isEqualTo(1);
        assertThat(run3.skipped()).isEqualTo(1);

        // 冻结单本身不受影响：状态仍 7、标仍生效、不写状态台账
        assertThat(statusOf("QT-1005")).isEqualTo(7);
        assertThat(countTags("QT-1005")).isEqualTo(1);
        assertThat(countLedger("QT-1005", "AUDIT%")).as("冻结/SKIPPED 都不写状态台账").isZero();

        printRun("③run1", run1);
        printRun("③run2", run2);
        printRun("③run3", run3);
        System.out.println("[③] QT-1005 决策行数 after1=" + rowsAfter1 + " after3=" + rowsAfter3);
        printDecisions("③");

        // 注：run.total() 的口径本轮<b>故意不断言</b>，只锁死「skipped 计数 + 决策行数不增长」这两条
        //     用户明确要的行为。口径已定为 total = 五类之和 + skipped（= 本轮候选范围单数，
        //     见 RunResultDto 的 Javadoc）：本轮 1 笔单全被跳过，所以 total=1、五类之和=0、
        //     skipped=1、决策行 0 行 —— 四个数互相咬合，但断言仍不锁它（口径可变，行为不可变）。
    }

    // ================================================================== 造数 / 清理 / 工具

    /** 造数只跑一次（三个用例共用同一批 QT-100x；PER_CLASS 生命周期下字段可复用） */
    private boolean seeded;

    /**
     * 按需造数：每个用例开头调一次，第一个真正执行的用例造数，其余复用。
     *
     * <p>为什么不是 {@code @BeforeAll}：JUnit 在「本类所有用例都 {@code @Disabled}」时
     * <b>照样执行</b> {@code @BeforeAll} —— 那会清表 + 造数，一个断言都不跑却改了库，
     * 是纯粹的破坏性副作用（而且它与 {@code AuditRunIntegrationTest} 共用 QT-100x 单号，
     * 谁先跑谁把对方的数清掉）。放进用例体后：全 disabled = 零 SQL。
     */
    private void seedIfNeeded() {
        if (seeded) return;
        cleanOurData();
        seedCatalogAndMembers();
        seedSevenOrders();
        seedPolicies();
        seeded = true;
        System.out.println("[准备] QT-1001..QT-1007 已就绪（7 条策略 / 7 笔单）");
    }

    /**
     * 用例结束还原被临时挪开的既有待审核单（{@code @AfterEach} 只在用例真的执行时才跑：
     * 全 {@code @Disabled} 时一个 SQL 都不会执行，与本类「全 disabled 零副作用」的要求一致）。
     */
    @AfterEach
    void restoreParkedOrders() {
        parkedOrders.forEach((id, status) ->
                jdbc.update("UPDATE `order` SET status = ? WHERE id = ?", status, id));
        if (!parkedOrders.isEmpty()) {
            System.out.println("[恢复] 既有待审核单状态已还原：" + parkedOrders.keySet());
        }
        parkedOrders.clear();
    }

    /**
     * 把库里的「其它待审核单」临时挪到 8（只改 status 一列，用例结束还原）。
     *
     * <p>为什么必须这么做：候选范围是**全库**「status=7 或已冻结」，不是只有 QT-100x。
     * 测试库里本来就有别的待审核单（QQ2026…），不挪开它们，{@code run.noMatch() / run.total()}
     * 就会被它们顶多出两笔 —— 断言失败的原因跑到「历史数据」上，读报告的人得先猜一遍才明白
     * 不是代码错了。与 {@code AuditRunIntegrationTest} 同一手法。
     */
    private void parkForeignPendingOrders() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM `order` WHERE status = 7 AND order_sn NOT LIKE 'QT-100%'");
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            parkedOrders.putIfAbsent(id, ((Number) row.get("status")).intValue());
            jdbc.update("UPDATE `order` SET status = 8 WHERE id = ?", id);
        }
    }

    /** 把 7 笔单还原成「status=7、无决策行、无标、无台账」的干净基线，用例彼此独立 */
    private void resetBaseline() {
        parkForeignPendingOrders();
        List<Long> ids = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE 'QT-100%'", Long.class);
        for (Long id : ids) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
            jdbc.update("UPDATE `order` SET status = 7 WHERE id = ?", id);
        }
        jdbc.update("UPDATE return_apply SET status = 0 "
                + "WHERE order_id IN (SELECT id FROM `order` WHERE order_sn LIKE 'QT-100%')");
        jdbc.update("DELETE FROM audit_run");
    }

    private void cleanOurData() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE 'QT-100%'", Long.class);
        for (Long id : ids) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_decision WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_audit_tag WHERE order_id = ?", id);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM admin_audit_log WHERE order_id = ?", id);
            jdbc.update("DELETE FROM return_apply WHERE order_id = ?", id);
        }
        jdbc.update("DELETE FROM `order` WHERE order_sn LIKE 'QT-100%'");
        jdbc.update("DELETE FROM audit_run");
        jdbc.update("DELETE FROM audit_policy_version");
        jdbc.update("DELETE FROM audit_policy_condition");
        jdbc.update("DELETE FROM audit_policy");
        jdbc.update("DELETE FROM member WHERE username IN ('audit_pending_member', 'audit_pending_store')");
        jdbc.update("DELETE FROM product WHERE name = '待审核行为契约测试商品'");
    }

    private void seedCatalogAndMembers() {
        memberUser = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES ('audit_pending_member', '', '待审核契约-普通', 1, NOW(), 0, '普通')");
        memberStore = insertAndReturnKey(
                "INSERT INTO member (username, password, nickname, status, create_time, version, customer_tag) "
                        + "VALUES ('audit_pending_store', '', '待审核契约-直营门店', 1, NOW(), 0, '直营门店')");
        Long categoryId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM category", Long.class);
        Long typeId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM product_type", Long.class);
        productId = insertAndReturnKey(
                "INSERT INTO product (name, description, category_id, type_id, publish_status, version) "
                        + "VALUES ('待审核行为契约测试商品', '三处引擎修正的断言用', ?, ?, 1, 0)", categoryId, typeId);

        skuMain = newSku("268.00", "200.00", 1000);      // QT-1001/1002/1005
        skuDouyin = newSku("150.00", "100.00", 1000);    // QT-1003
        skuDirect = newSku("199.00", "150.00", 1000);    // QT-1004
        skuScarce = newSku("268.00", "200.00", 5);       // QT-1007：桶 5 < 需求 8
        for (int i = 0; i < 6; i++) {
            // QT-1006 的 6 个样，桶库存合计 5（5 个各 1 件 + 1 个 0）→ 需求 6 件必然缺货
            skuSix.add(newSku("70.00", "50.00", i == 5 ? 0 : 1));
        }
        returnReasonId = jdbc.queryForObject("SELECT COALESCE(MIN(id), 1) FROM return_reason", Long.class);
    }

    private void seedSevenOrders() {
        // 用户原表六笔 + 一笔库存降级单（QT-1007）
        createOrder("QT-1001", "淘宝", "268.00", "62.00", null, memberUser, skuMain, 2);
        createOrder("QT-1002", "淘宝", "268.00", "62.00", "客户改地址：海淀区yy路2号", memberUser, skuMain, 2);
        createOrder("QT-1003", "抖音", "1580.00", "300.00", null, memberUser, skuDouyin, 8);
        createOrder("QT-1004", "直营", "199.00", "-12.00", null, memberStore, skuDirect, 1);
        createOrder("QT-1005", "淘宝", "320.00", "80.00", null, memberUser, skuMain, 2);
        Long order6 = createOrder("QT-1006", "淘宝", "420.00", "100.00", null, memberUser, null, 0);
        for (Long skuId : skuSix) {
            jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                            + "product_price, product_quantity, cost_price) "
                            + "VALUES (?, ?, ?, '待审核行为契约测试商品', 70.00, 1, 50.00)",
                    order6, productId, skuId);
        }
        createOrder("QT-1007", "淘宝", "268.00", "62.00", null, memberUser, skuScarce, 8);

        Long order5 = orderIdOf("QT-1005");
        jdbc.update("INSERT INTO return_apply (order_id, member_id, return_reason, description, status, reason_id, status_seq) "
                        + "VALUES (?, ?, '不想要了', '契约测试：待处理退款申请', 0, ?, 0)",
                order5, memberUser, returnReasonId);
    }

    private Long newSku(String price, String cost, int stock) {
        Long skuId = insertAndReturnKey(
                "INSERT INTO sku (product_id, price, stock, version, cost_price) VALUES (?, ?, ?, 0, ?)",
                productId, new BigDecimal(price), stock, new BigDecimal(cost));
        jdbc.update("INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES (?, 0, ?)", skuId, stock);
        return skuId;
    }

    private Long createOrder(String sn, String channel, String amount, String grossProfit, String csRemark,
                             Long memberId, Long skuId, int qty) {
        Long orderId = insertAndReturnKey(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, payment_time, created_at, "
                        + "receiver_name, receiver_phone, receiver_address, split_status, version, status_seq, "
                        + "channel, shop_name, gross_profit, cs_remark, audit_addr_multi) "
                        + "VALUES (?, ?, ?, 7, 1, NOW(), NOW(), '张三', '13800000000', '北京市朝阳区xx路1号', 0, 0, 0, "
                        + "?, '官方旗舰店', ?, ?, 0)",
                sn, memberId, new BigDecimal(amount), channel, new BigDecimal(grossProfit), csRemark);
        if (skuId != null) {
            BigDecimal unitPrice = new BigDecimal(amount).divide(new BigDecimal(qty), 2, RoundingMode.HALF_UP);
            BigDecimal cost = skuId.equals(skuDouyin) ? new BigDecimal("100.00")
                    : skuId.equals(skuDirect) ? new BigDecimal("150.00")
                    : new BigDecimal("200.00");
            jdbc.update("INSERT INTO order_item (order_id, product_id, product_sku_id, product_name, "
                            + "product_price, product_quantity, cost_price) "
                            + "VALUES (?, ?, ?, '待审核行为契约测试商品', ?, ?, ?)",
                    orderId, productId, skuId, unitPrice, qty, cost);
        }
        jdbc.update("INSERT INTO order_status_ledger (order_id, from_status, to_status, reason, reason_detail, "
                        + "operator_type, operator_name, idem_key) "
                        + "VALUES (?, NULL, 7, 'ORDER_CREATE', '契约测试造数', 'SYSTEM', 'system', ?)",
                orderId, orderId + ":0");
        return orderId;
    }

    private void seedPolicies() {
        policyService.create(new PolicyRequest("AUTO", 100, "直营-小单", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CUSTOMER_TAG", "EQ", "TEXT", null, "直营门店", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("300"), null, null)), null));
        policyService.create(new PolicyRequest("AUTO", 200, "淘宝-标准单", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "淘宝", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("500"), null, null),
                        new ConditionRequest("MATCH", "POLICY", "ITEM_KIND_COUNT", "LTE", "NUM",
                                new BigDecimal("5"), null, null)),
                List.of(new ConditionRequest("EXCLUDE", "ALL_AUTO", "CS_REMARK", "CONTAINS", "TEXT",
                        null, "改", null))));
        policyService.create(new PolicyRequest("AUTO", 300, "抖音-标准单", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "CHANNEL", "EQ", "TEXT", null, "抖音", null),
                        new ConditionRequest("MATCH", "POLICY", "RECEIVABLE_AMOUNT", "LT", "NUM",
                                new BigDecimal("500"), null, null),
                        new ConditionRequest("MATCH", "POLICY", "ITEM_KIND_COUNT", "LTE", "NUM",
                                new BigDecimal("5"), null, null)), null));
        policyService.create(new PolicyRequest("BLOCK", 10, "毛利不为正", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "GROSS_PROFIT", "LTE", "NUM",
                        BigDecimal.ZERO, null, null)), null));
        policyService.create(new PolicyRequest("BLOCK", 20, "双地址", true, null, null,
                List.of(new ConditionRequest("MATCH", "POLICY", "ADDRESS_MULTI", "EQ", "BOOL",
                        BigDecimal.ONE, null, null)), null));
        policyService.create(new PolicyRequest("EXCEPTION", 10, "退款申请冻结", true, "FROZEN", "存在待处理退款申请",
                List.of(new ConditionRequest("MATCH", "POLICY", "HAS_RETURN_APPLY", "EQ", "BOOL",
                        BigDecimal.ONE, null, null)), null));
        policyService.create(new PolicyRequest("EXCEPTION", 20, "平台备注冻结", true, "FROZEN", "平台备注 24 小时内被修改过",
                List.of(new ConditionRequest("MATCH", "POLICY", "PLATFORM_REMARK_UPDATED", "LTE", "NUM",
                        new BigDecimal("24"), null, null)), null));
    }

    // ------------------------------------------------------------------ 工具

    /** 报告用：把一轮 run 的五类计数 + skipped 打出来（真库验证的原始输出） */
    private void printRun(String tag, RunResultDto run) {
        System.out.println("[run] " + tag + " runId=" + run.runId() + " total=" + run.total()
                + " autoPass=" + run.autoPass() + " manual=" + run.manual() + " blocked=" + run.blocked()
                + " frozen=" + run.frozen() + " noMatch=" + run.noMatch() + " skipped=" + run.skipped()
                + " 五类之和=" + run.verdictSum() + " decisions=" + run.decisions().size());
    }

    /** 报告用：把当前 QT-100x 的决策行打出来（与报告里贴的 SQL 结果一致） */
    private void printDecisions(String tag) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.order_sn, d.round_no, d.result, d.matched_policy_name, d.downgrade_reason "
                        + "FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn LIKE 'QT-100%' ORDER BY o.order_sn, d.round_no");
        System.out.println("[决策行 " + tag + "] 共 " + rows.size() + " 行：" + rows);
    }

    private Map<String, Object> decision(String orderSn) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.*, JSON_UNQUOTE(JSON_EXTRACT(d.evidence_json, '$.reason')) AS reason, "
                        + "CAST(d.evidence_json AS CHAR) AS evidence_json "
                        + "FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
                        + "WHERE o.order_sn = ? ORDER BY d.round_no DESC LIMIT 1", orderSn);
        assertThat(rows).as("订单 %s 没有任何决策行", orderSn).isNotEmpty();
        return rows.get(0);
    }

    private int statusOf(String orderSn) {
        Integer status = jdbc.queryForObject("SELECT status FROM `order` WHERE order_sn = ?", Integer.class, orderSn);
        return status == null ? -1 : status;
    }

    private Long orderIdOf(String orderSn) {
        return jdbc.queryForObject("SELECT id FROM `order` WHERE order_sn = ?", Long.class, orderSn);
    }

    private int decisionCount(Long orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_decision WHERE order_id = ?", Integer.class, orderId);
        return n == null ? -1 : n;
    }

    private int countTags(String orderSn) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_audit_tag t JOIN `order` o ON o.id = t.order_id "
                        + "WHERE o.order_sn = ? AND t.active = 1", Integer.class, orderSn);
        return n == null ? -1 : n;
    }

    private int countLedger(String orderSn, String reasonPattern) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_status_ledger l JOIN `order` o ON o.id = l.order_id "
                        + "WHERE o.order_sn = ? AND l.reason LIKE ?", Integer.class, orderSn, reasonPattern);
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
