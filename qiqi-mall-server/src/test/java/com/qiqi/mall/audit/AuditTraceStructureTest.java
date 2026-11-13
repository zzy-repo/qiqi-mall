package com.qiqi.mall.audit;

import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.ConditionTrace;
import com.qiqi.mall.audit.engine.PolicyMiss;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判定留痕的<b>结构</b>单测（不连库）：把 {@code miss_reason_json} / {@code matched_condition_json} /
 * {@code excluded_by_json} 的「策略组 → 条件四元组」形状钉死。
 *
 * <h3>为什么必须断言 {@code evaluated=false}</h3>
 * 前端「差在哪」抽屉要区分三态：满足（绿）/ 不满足（红）/ <b>未求值</b>（灰）。给不出
 * {@code evaluated} 时它只能按 AND 短路规则自己猜 —— 一旦猜法变了（或引擎改成不短路求值），
 * 页面就会把「引擎没比过」写成「比过且不满足」，这是**编造证据**。
 * 所以这里两条都断言：
 * <ul>
 *   <li>首个不满足的条件 {@code evaluated=true}（它才是结论依据）；</li>
 *   <li>它后面的条件 {@code evaluated=false}（AND 链已断，现实值仍照实给出）。</li>
 * </ul>
 *
 * <p>跑：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditTraceStructureTest}
 */
class AuditTraceStructureTest {

    private final AuditEngine engine = AuditScenario.engine();

    private AuditVerdict judge(AuditContext ctx) {
        return engine.evaluate(ctx, AuditScenario.policies(), AuditScenario.strict());
    }

    /** QT-1006：淘宝-标准单「渠道 ✓ / 应收 ✓ / 种类数 ✗」—— 三条都求值过，没有未求值项 */
    @Test
    @DisplayName("QT-1006 的 miss 是逐条件四元组：三条 MATCH 全 evaluated，只有种类数不满足")
    void missTraceShape() {
        AuditVerdict v = judge(AuditScenario.qt1006());
        assertThat(v.result()).isEqualTo(AuditResult.NO_MATCH);

        PolicyMiss taobao = v.missReasons().stream()
                .filter(m -> AuditScenario.POLICY_AUTO_TAOBAO.equals(m.policyName()))
                .findFirst().orElseThrow();

        assertThat(taobao.conditions()).extracting(ConditionTrace::fieldCode)
                .containsExactly("CHANNEL", "RECEIVABLE_AMOUNT", "ITEM_KIND_COUNT");
        // seq 取自 audit_policy_condition.seq；单测构造的条件行 seq 都是 0（真库里是 1/2/3，
        // 「seq 一路带到留痕里」由 AuditRunIntegrationTest 对真库断言），这里只钉「原样透传」
        assertThat(taobao.conditions()).extracting(ConditionTrace::seq).containsExactly(0, 0, 0);
        assertThat(taobao.conditions()).extracting(ConditionTrace::satisfied)
                .containsExactly(true, true, false);
        assertThat(taobao.conditions()).as("三条都比过：结论依据与「顺带求值」不同，但都算求值到")
                .extracting(ConditionTrace::evaluated).containsExactly(true, true, true);
        assertThat(taobao.conditions().get(2).expected()).isEqualTo("5");
        assertThat(taobao.conditions().get(2).actual()).isEqualTo("6");
        assertThat(taobao.conditions().get(2).text()).isEqualTo("商品种类数 6 ≤ 5");
        // reasons 仍是人话摘要（只装失败项），keep 旧消费方可用
        assertThat(taobao.reasons()).containsExactly("商品种类数 6 ≤ 5");
    }

    /**
     * QT-1003（抖音 1580 元 8 件）跑的是 AUTO 链：淘宝-标准单第一条「渠道 = 淘宝」就不成立，
     * 它后面的应收/种类数不再决定结论 → {@code evaluated=false}（值照实给：1580.00 / 8）。
     */
    @Test
    @DisplayName("AND 短路：第一条不满足后，后续条件 evaluated=false，但 expected/actual 照实给出")
    void andChainMarksLaterConditionsUnevaluated() {
        AuditVerdict v = judge(AuditScenario.qt1003());
        assertThat(v.result()).isEqualTo(AuditResult.NO_MATCH);

        PolicyMiss taobao = v.missReasons().stream()
                .filter(m -> AuditScenario.POLICY_AUTO_TAOBAO.equals(m.policyName()))
                .findFirst().orElseThrow();

        List<ConditionTrace> c = taobao.conditions();
        assertThat(c).hasSize(3);
        assertThat(c.get(0).fieldCode()).isEqualTo("CHANNEL");
        assertThat(c.get(0).satisfied()).as("抖音 ≠ 淘宝：这就是「差在哪」").isFalse();
        assertThat(c.get(0).evaluated()).as("首个不满足项仍在结论链上").isTrue();
        assertThat(c.get(1).fieldCode()).isEqualTo("RECEIVABLE_AMOUNT");
        assertThat(c.get(1).evaluated()).as("前面的条件已不满足 → 不再决定结论").isFalse();
        assertThat(c.get(1).actual()).as("值照样给：不是「不知道」，是「不影响结论」").isEqualTo("1580.00");
        assertThat(c.get(2).fieldCode()).isEqualTo("ITEM_KIND_COUNT");
        assertThat(c.get(2).evaluated()).isFalse();
        assertThat(c.get(2).actual()).as("种类数只有 2（不是 8：件数才是 8）").isEqualTo("2");
    }

    /** MANUAL（被全局排除）：匹配条件与被排除条件都要留痕，否则「被谁挡的」仍然说不清 */
    @Test
    @DisplayName("QT-1002 的 miss 组里既有满足的 MATCH，也有成立的全局排除，且全部 evaluated")
    void excludedMissTraceContainsMatchAndExclusion() {
        AuditVerdict v = judge(AuditScenario.qt1002());
        assertThat(v.result()).isEqualTo(AuditResult.MANUAL);

        PolicyMiss taobao = v.missReasons().stream()
                .filter(m -> AuditScenario.POLICY_AUTO_TAOBAO.equals(m.policyName()))
                .findFirst().orElseThrow();

        assertThat(taobao.conditions()).extracting(ConditionTrace::fieldCode)
                .containsExactly("CHANNEL", "RECEIVABLE_AMOUNT", "ITEM_KIND_COUNT", "CS_REMARK");
        assertThat(taobao.conditions()).allSatisfy(t -> {
            assertThat(t.satisfied()).isTrue();
            assertThat(t.evaluated()).isTrue();
        });
        assertThat(taobao.reasons()).containsExactly("被排除：客服备注 含「改」（实际 客户改地址：朝阳区xx路2号）");
    }

    /** 落库形状：策略组包着条件数组，键名与前端 fromBackendMiss 逐字对齐 */
    @Test
    @DisplayName("miss_reason_json 的结构：policyId/policyName/reasons/conditions，条件是四元组")
    void missJsonShape() {
        List<Map<String, Object>> json = judge(AuditScenario.qt1003()).missReasonMaps();
        assertThat(json).isNotEmpty();
        assertThat(json).allSatisfy(g -> {
            assertThat(g).containsKeys("policyId", "policyName", "reasons", "conditions");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) g.get("conditions");
            assertThat(conditions).isNotEmpty();
            assertThat(conditions).allSatisfy(c -> assertThat(c).containsKeys(
                    "seq", "fieldCode", "fieldLabel", "opCode", "expected", "actual",
                    "satisfied", "evaluated", "reason"));
        });
    }

    /** matched_condition_json / excluded_by_json 与 miss 同形状（同一个 group 包法），前端一套渲染通吃 */
    @Test
    @DisplayName("matched_condition_json 与 excluded_by_json 对齐同一形状（策略组 → 条件数组）")
    void matchedAndExcludedUseSameShape() {
        AuditVerdict autoPass = judge(AuditScenario.qt1001());
        assertThat(autoPass.result()).isEqualTo(AuditResult.AUTO_PASS);
        assertThat(autoPass.matchedConditionMaps()).hasSize(1);
        Map<String, Object> autoGroup = autoPass.matchedConditionMaps().get(0);
        assertThat(autoGroup).containsEntry("policyName", AuditScenario.POLICY_AUTO_TAOBAO);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> autoConds = (List<Map<String, Object>>) autoGroup.get("conditions");
        assertThat(autoConds).extracting(c -> c.get("fieldCode"))
                .containsExactly("CHANNEL", "RECEIVABLE_AMOUNT", "ITEM_KIND_COUNT");
        assertThat(autoConds).allSatisfy(c -> {
            assertThat(c.get("satisfied")).isEqualTo(true);
            assertThat(c.get("evaluated")).isEqualTo(true);
        });

        AuditVerdict manual = judge(AuditScenario.qt1002());
        assertThat(manual.excludedByMaps()).hasSize(1);
        Map<String, Object> excludedGroup = manual.excludedByMaps().get(0);
        assertThat(excludedGroup).containsEntry("policyName", AuditScenario.POLICY_AUTO_TAOBAO);
        assertThat(excludedGroup.get("reasons")).asList().anySatisfy(r -> assertThat(String.valueOf(r)).contains("被排除"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> excludedConds = (List<Map<String, Object>>) excludedGroup.get("conditions");
        assertThat(excludedConds).extracting(c -> c.get("fieldCode")).containsExactly("CS_REMARK");
    }
}
