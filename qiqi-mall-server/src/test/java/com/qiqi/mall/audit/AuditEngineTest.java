package com.qiqi.mall.audit;

import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.PolicyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditEngine 纯函数单测：六笔单（QT-1001..1006，<b>用户原表</b>）逐笔断言 + 链上各步补充场景。
 *
 * <h3>为什么逐笔断言「原因原文里的具体数值」</h3>
 * 只断言 {@code result == BLOCKED} 测不出问题：把门槛 0 写成 10、把「≤」写成「&lt;」、
 * 把 −12.00 格式化成 −12，result 一样，但运营看到的解释全变了。这个模块的价值就在
 * 「原因能不能让人直接行动」，所以断言的是**文案里的数值与符号**（含 NO_MATCH 的差额）。
 *
 * <p>跑：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditEngineTest}（不连库，毫秒级）
 */
class AuditEngineTest {

    private final AuditEngine engine = AuditScenario.engine();

    private AuditVerdict judge(AuditContext ctx) {
        return engine.evaluate(ctx, AuditScenario.policies(), AuditScenario.strict());
    }

    // ------------------------------------------------------------------ 六笔单

    @Test
    @DisplayName("QT-1001 淘宝 268 元 2 件 毛利 62 → AUTO_PASS（淘宝-标准单），原因含金额与种类数")
    void qt1001_autoPass() {
        AuditVerdict v = judge(AuditScenario.qt1001());

        assertThat(v.result()).isEqualTo(AuditResult.AUTO_PASS);
        assertThat(v.matchedPolicy().name()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
        assertThat(v.downgradeReason()).isNull();
        assertThat(v.reasonText())
                .contains("自动过审")
                .contains("淘宝-标准单")
                .contains("渠道 = 淘宝（实际 淘宝）")
                .contains("应收金额 268.00 < 500.00")
                .contains("商品种类数 1 ≤ 5");
        assertThat(v.advancesStatus()).isTrue();
    }

    @Test
    @DisplayName("QT-1002 淘宝 268 元但客服备注「改地址」→ MANUAL：淘宝-标准单满足却被全局排除")
    void qt1002_manualByGlobalExclusion() {
        AuditVerdict v = judge(AuditScenario.qt1002());

        assertThat(v.result()).isEqualTo(AuditResult.MANUAL);
        assertThat(v.matchedPolicy().name()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
        assertThat(v.excludedBy()).hasSize(1);
        assertThat(v.excludedBy().get(0).text()).contains("客服备注 含「改」");
        assertThat(v.reasonText()).contains("被排除").contains("客服备注 含「改」");
        assertThat(v.advancesStatus()).isFalse();
        // 被排除 ≠ 没有策略命中：命中策略那一条的 miss 原因以「被排除：」开头
        assertThat(v.missReasons()).anySatisfy(m -> {
            assertThat(m.policyName()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
            assertThat(m.reasons().get(0)).startsWith("被排除：");
        });
    }

    @Test
    @DisplayName("QT-1003 抖音 1580 元 8 件 → NO_MATCH，miss 里写明「应收金额 1580.00 < 500.00」")
    void qt1003_noMatch() {
        AuditVerdict v = judge(AuditScenario.qt1003());

        assertThat(v.result()).isEqualTo(AuditResult.NO_MATCH);
        assertThat(v.matchedPolicy()).isNull();
        assertThat(v.reasonText()).contains("没有任何 AUTO 策略命中");
        assertThat(v.missReasons()).hasSize(3);      // 直营-小单 / 淘宝-标准单 / 抖音-标准单
        assertThat(v.missReasons()).anySatisfy(m -> {
            assertThat(m.policyName()).isEqualTo(AuditScenario.POLICY_AUTO_DOUYIN);
            assertThat(m.reasons()).anyMatch(r -> r.contains("应收金额 1580.00 < 500.00"));
        });
        // 金额是 1580（远超 500），种类数 2 反而满足 —— 要能一眼看出差在金额
        assertThat(v.missReasons()).anySatisfy(m -> {
            assertThat(m.policyName()).isEqualTo(AuditScenario.POLICY_AUTO_DOUYIN);
            assertThat(m.reasons()).noneMatch(r -> r.contains("商品种类数"));
        });
    }

    @Test
    @DisplayName("QT-1004 直营门店 199 元 毛利 −12 → BLOCKED（毛利不为正），would_be=直营-小单")
    void qt1004_blockedWithWouldBe() {
        AuditVerdict v = judge(AuditScenario.qt1004());

        assertThat(v.result()).isEqualTo(AuditResult.BLOCKED);
        assertThat(v.matchedPolicy().name()).isEqualTo(AuditScenario.POLICY_BLOCK_PROFIT);
        assertThat(v.reasonText()).contains("拦截").contains("毛利 −12.00 ≤ 0");
        assertThat(v.wouldBePolicy()).isNotNull();
        assertThat(v.wouldBePolicy().name())
                .as("被拦截的单必须能回答「本来会不会自动过」")
                .isEqualTo("直营-小单");
        assertThat(v.wouldBeConditions()).allMatch(t -> t.satisfied());
        assertThat(v.advancesStatus()).as("would-be 只回填，绝不推进状态").isFalse();
    }

    @Test
    @DisplayName("QT-1005 两轮：有退款申请 → FROZEN（would_be=淘宝-标准单）；处理后解冻 → AUTO_PASS")
    void qt1005_frozenThenAutoPass() {
        AuditVerdict round1 = judge(AuditScenario.qt1005Round1());
        assertThat(round1.result()).isEqualTo(AuditResult.FROZEN);
        assertThat(round1.matchedPolicy().name()).isEqualTo(AuditScenario.POLICY_EXCEPTION_RETURN);
        assertThat(round1.reasonText()).contains("冻结").contains("有退款申请 = 是");
        assertThat(round1.wouldBePolicy().name()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
        assertThat(round1.evidence()).containsKey("action");
        assertThat(round1.advancesStatus()).isFalse();

        AuditVerdict round2 = judge(AuditScenario.qt1005Round2());
        assertThat(round2.result()).isEqualTo(AuditResult.AUTO_PASS);
        assertThat(round2.matchedPolicy().name()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
    }

    @Test
    @DisplayName("QT-1006 淘宝 420 元 6 件 6 个样 → NO_MATCH：差在「商品种类数 6 ≤ 5」")
    void qt1006_noMatchOnKindCount() {
        AuditVerdict v = judge(AuditScenario.qt1006());

        assertThat(v.result()).isEqualTo(AuditResult.NO_MATCH);
        assertThat(v.matchedPolicy()).isNull();
        assertThat(v.downgradeReason())
                .as("QT-1006 的结论是 NO_MATCH，不是库存降级（库存要留到人工审核时才发现）")
                .isNull();
        assertThat(v.missReasons()).anySatisfy(m -> {
            assertThat(m.policyName()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
            assertThat(m.reasons()).anyMatch(r -> r.contains("商品种类数 6 ≤ 5"));
            // 金额 420 < 500 是满足的 —— 差的是种类数，不能把两条都列成差额
            assertThat(m.reasons()).noneMatch(r -> r.contains("应收金额"));
        });
    }

    // ------------------------------------------------------------------ 链上各步（补充）

    @Test
    @DisplayName("状态 8 → SKIPPED（判定链第 1 步：不是 7 就不审）")
    void skipWhenNotPendingAudit() {
        AuditVerdict v = judge(AuditScenario.qt1001Shipped());
        assertThat(v.result()).isEqualTo(AuditResult.SKIPPED);
        assertThat(v.reasonText()).contains("≠ 7");
        assertThat(v.matchedPolicy()).isNull();
    }

    @Test
    @DisplayName("已冻结 → SKIPPED（判定链第 2 步）")
    void skipWhenFrozen() {
        AuditVerdict v = judge(AuditScenario.qt1005Frozen());
        assertThat(v.result()).isEqualTo(AuditResult.SKIPPED);
        assertThat(v.reasonText()).contains("冻结");
    }

    @Test
    @DisplayName("毛利没算过 + 成本价缺失 → MANUAL + COST_MISSING（风险③不许沉默放行）")
    void costMissing() {
        AuditVerdict v = judge(AuditScenario.qtCostMissing());
        assertThat(v.result()).isEqualTo(AuditResult.MANUAL);
        assertThat(v.downgradeReason()).isEqualTo(AuditVerdict.DOWNGRADE_COST_MISSING);
        assertThat(v.reasonText()).contains("成本价缺失").contains("毛利");
    }

    @Test
    @DisplayName("引擎库存预检保留：AUTO 条件满足但桶 5 < 需求 8 → STRICT 降级 STOCK_SHORT")
    void stockPrecheckStillWorks() {
        AuditVerdict v = judge(AuditScenario.qtStockShort());

        assertThat(v.result()).isEqualTo(AuditResult.MANUAL);
        assertThat(v.downgradeReason()).isEqualTo(AuditVerdict.DOWNGRADE_STOCK_SHORT);
        assertThat(v.matchedPolicy().name()).isEqualTo(AuditScenario.POLICY_AUTO_TAOBAO);
        assertThat(v.reasonText())
                .contains("可用 5").contains("需求 8")
                .contains("SUM(sku_bucket.stock)").contains("999");
        @SuppressWarnings("unchecked")
        Map<String, Object> stock = (Map<String, Object>) v.evidence().get("stock");
        assertThat(stock).containsEntry("bucket_sum", 5).containsEntry("sku_stock_cache", 999)
                .containsEntry("demand", 8).containsEntry("guard", "STRICT");
    }

    @Test
    @DisplayName("LENIENT 档：库存不足不阻断，但证据里留告警")
    void lenientKeepsAutoPassWithWarning() {
        AuditVerdict v = engine.evaluate(AuditScenario.qtStockShort(), AuditScenario.policies(), AuditScenario.lenient());

        assertThat(v.result()).isEqualTo(AuditResult.AUTO_PASS);
        assertThat(v.downgradeReason()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> stock = (Map<String, Object>) v.evidence().get("stock");
        assertThat(String.valueOf(stock.get("warning"))).contains("LENIENT").contains("可用 5");
    }

    @Test
    @DisplayName("双地址（BLOCK-2）：命中即 BLOCKED，且不落进 AUTO")
    void addressMultiBlocks() {
        AuditContext ctx = AuditScenario.builder(1201L, "QT-1201", 7, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(2).itemKinds(1).addressMulti(true).stock(100, 100).build();
        AuditVerdict v = judge(ctx);
        assertThat(v.result()).isEqualTo(AuditResult.BLOCKED);
        assertThat(v.matchedPolicy().name()).isEqualTo(AuditScenario.POLICY_BLOCK_ADDRESS);
        assertThat(v.reasonText()).contains("多地址 = 是");
    }

    @Test
    @DisplayName("纯函数：同一输入跑两次结果完全一致（不读时钟、不落状态）")
    void engineIsPure() {
        AuditVerdict a = judge(AuditScenario.qt1001());
        AuditVerdict b = judge(AuditScenario.qt1001());
        assertThat(a).isEqualTo(b);
        assertThat(a.evidence()).isEqualTo(b.evidence());
    }

    @Test
    @DisplayName("停用的策略不参与判定（enabled=false）")
    void disabledPoliciesIgnored() {
        List<PolicyView> disabled = AuditScenario.policies().stream()
                .map(p -> new PolicyView(p.id(), p.type(), p.priority(), p.name(), p.versionNo(), false,
                        p.action(), p.conditions()))
                .toList();
        AuditVerdict v = engine.evaluate(AuditScenario.qt1001(), disabled, AuditScenario.strict());
        assertThat(v.result()).isEqualTo(AuditResult.NO_MATCH);
    }
}
