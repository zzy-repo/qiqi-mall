package com.qiqi.mall.audit.explain;

import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.ConditionTrace;
import com.qiqi.mall.audit.engine.PolicyMiss;
import com.qiqi.mall.audit.engine.PolicyType;
import com.qiqi.mall.audit.engine.PolicyView;
import com.qiqi.mall.audit.explain.AuditExplainLayers.ExplainLayers;
import com.qiqi.mall.audit.explain.AuditExplainLayers.PolicyScan;
import com.qiqi.mall.audit.explain.AuditExplainLayers.ScanCondition;
import com.qiqi.mall.audit.explain.AuditExplainLayers.ScanOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PolicyScanAssembler} 的纯单测：<b>不连库、不启 Spring、不读时钟</b>。
 *
 * <p>它钉的是「{@code AuditVerdict} → {@link PolicyScan}」这一段的四个口径，每一个都曾经是
 * 界面会说错话的地方：
 * <ul>
 *   <li><b>A1</b> 结构识别三态：命中 / 被排除 / 不匹配 —— 且<b>不读文案</b>
 *       （同样的输入把 reasons 换成别的措辞，结果必须不变）；</li>
 *   <li><b>A2</b> 「值是否缺失」来自引擎透传的布尔，缺失的渠道条件<b>不</b>把策略算成「不适用」；</li>
 *   <li><b>A3</b> 历史行（没有 {@code valueMissing} 键）退回 {@code Fmt} 常量兜底，且不误判有值的行；</li>
 *   <li><b>A4</b> 组装器 + {@link AuditExplainLayers#build} 串起来后：渠道不符的策略进第二层
 *       {@code byField}，<b>不出现在第一层任何字段里</b>（这就是用户那句「三十条策略看不完」的止损点）。</li>
 * </ul>
 */
class PolicyScanAssemblerTest {

    // ------------------------------------------------------------------ 造数小工具

    private static ConditionTrace ok(int seq, String field, String op, String actual, String expected, String text) {
        return ConditionTrace.evaluated(seq, field, field, op, expected, actual, true, text);
    }

    private static ConditionTrace fail(int seq, String field, String op, String actual, String expected, String text) {
        return ConditionTrace.evaluated(seq, field, field, op, expected, actual, false, text);
    }

    /** 值缺失的不满足条件（判不了 ≠ 不符） */
    private static ConditionTrace missingValue(int seq, String field, String op, String expected) {
        String actual = com.qiqi.mall.audit.engine.Fmt.NO_VALUE_TEXT;
        String text = field + " 无值，无法比较 " + (op.equals("EQ") ? "=" : op) + " " + expected;
        return ConditionTrace.evaluated(seq, field, field, op, expected, actual, false, text, true);
    }

    private static PolicyView policy(long id, String name, int priority) {
        return PolicyView.of(id, PolicyType.AUTO, priority, name, 1, List.of());
    }

    private static PolicyMiss miss(long id, String name, List<String> reasons, ConditionTrace... conditions) {
        return new PolicyMiss(id, name, reasons, List.of(conditions));
    }

    private static AuditVerdict verdict(AuditResult result, PolicyView matched, List<ConditionTrace> matchedConds,
                                       List<ConditionTrace> excluded, List<PolicyMiss> misses) {
        return new AuditVerdict(result, matched, matchedConds, excluded, misses, null, List.of(), null, "", Map.of());
    }

    private static Map<Long, Integer> priorities(Object... idThenPriority) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < idThenPriority.length; i += 2) {
            out.put(((Number) idThenPriority[i]).longValue(), ((Number) idThenPriority[i + 1]).intValue());
        }
        return out;
    }

    // ------------------------------------------------------------------ A1 三态识别

    @Test
    @DisplayName("A1 结构识别三态：命中=条件全满足且没被排除；全满足仍在差在哪=被排除；有条不满足=不匹配")
    void a1_threeOutcomes() {
        PolicyView hitPolicy = policy(2L, "淘宝-标准单", 100);
        AuditVerdict v = verdict(AuditResult.AUTO_PASS, hitPolicy,
                List.of(ok(0, "CHANNEL", "EQ", "淘宝", "淘宝", "渠道 = 淘宝（实际 淘宝）"),
                        ok(1, "RECEIVABLE_AMOUNT", "LT", "268.00", "500.00", "应收金额 268.00 < 500.00")),
                List.of(),
                List.of(
                        // 直营-小单：客户标签不符（真实不匹配）
                        miss(1L, "直营-小单", List.of("客户标签 = 直营门店（实际 普通）"),
                                fail(0, "CUSTOMER_TAG", "EQ", "普通", "直营门店", "客户标签 = 直营门店（实际 普通）")),
                        // 抖音-标准单：条件全满足，只是被全局排除挡掉 → 只能是「被排除」
                        miss(3L, "抖音-标准单", List.of("被排除：客服备注 含「改」（实际 客户改地址）"),
                                ok(0, "CHANNEL", "EQ", "淘宝", "淘宝", "渠道 = 淘宝（实际 淘宝）"),
                                ok(1, "CS_REMARK", "CONTAINS", "客户改地址", "改", "客服备注 含「改」（实际 客户改地址）"))));

        List<PolicyScan> scans = PolicyScanAssembler.assemble(v, priorities(1L, 100, 2L, 200, 3L, 300));

        assertThat(scans).hasSize(3);
        assertThat(scans.get(0).outcome()).as("命中那条排第一").isEqualTo(ScanOutcome.HIT);
        assertThat(scans.get(0).policyName()).isEqualTo("淘宝-标准单");
        assertThat(scans.get(0).missCount()).isZero();
        assertThat(scans.get(0).satisfiedCount()).isEqualTo(2);
        assertThat(scans.get(0).hitCondition()).as("命中时带第一条满足条件的人话").contains("渠道 = 淘宝");

        PolicyScan direct = scans.stream().filter(s -> "直营-小单".equals(s.policyName())).findFirst().orElseThrow();
        assertThat(direct.outcome()).isEqualTo(ScanOutcome.MISS);
        assertThat(direct.missCount()).isEqualTo(1);
        assertThat(direct.missingConditions()).extracting(ScanCondition::fieldCode).containsExactly("CUSTOMER_TAG");

        PolicyScan douyin = scans.stream().filter(s -> "抖音-标准单".equals(s.policyName())).findFirst().orElseThrow();
        assertThat(douyin.outcome()).as("一条都不差 ⇒ 只能是被排除（结构性推理，不看文案）")
                .isEqualTo(ScanOutcome.EXCLUDED);
        assertThat(douyin.excludedBy()).as("摘要前缀「被排除：」去掉，免得界面重复说一次")
                .isEqualTo("客服备注 含「改」（实际 客户改地址）");
    }

    @Test
    @DisplayName("A1b 改文案不影响三态：把 reasons 全换成无意义字符串，outcome 一个都不变")
    void a1b_textFree() {
        List<PolicyMiss> misses = List.of(
                new PolicyMiss(3L, "抖音-标准单", List.of("zzz"), List.of(
                        ok(0, "CHANNEL", "EQ", "淘宝", "淘宝", "x"),
                        ok(1, "CS_REMARK", "CONTAINS", "客户改地址", "改", "y"))));
        AuditVerdict v = verdict(AuditResult.MANUAL, policy(3L, "抖音-标准单", 300), List.of(),
                List.of(ok(1, "CS_REMARK", "CONTAINS", "客户改地址", "改", "y")), misses);

        List<PolicyScan> scans = PolicyScanAssembler.assemble(v);
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).outcome()).isEqualTo(ScanOutcome.EXCLUDED);
    }

    // ------------------------------------------------------------------ A2 值缺失

    @Test
    @DisplayName("A2 引擎透传的 valueMissing=true ⇒ 渠道条件算「判不了」，不把这条策略算成不适用")
    void a2_valueMissingNotMismatch() {
        AuditVerdict v = verdict(AuditResult.NO_MATCH, null, List.of(), List.of(),
                List.of(miss(1L, "淘宝-标准单", List.of("渠道 无值，无法比较 = 淘宝"),
                        missingValue(0, "CHANNEL", "EQ", "淘宝"),
                        fail(1, "RECEIVABLE_AMOUNT", "LT", "1580.00", "500.00", "应收金额 1580.00 < 500.00"))));

        List<PolicyScan> scans = PolicyScanAssembler.assemble(v);
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).missingConditions())
                .as("valueMissing 必须原样带出来（下游据此把「判不了」排除在「不适用」之外）")
                .extracting(ScanCondition::valueMissing).containsExactly(true, false);

        ExplainLayers layers = AuditExplainLayers.build(scans);
        assertThat(layers.second().notApplicableTotal()).as("没填渠道 ≠ 渠道不符").isZero();
        assertThat(layers.first().conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_CLOSEST);
    }

    // ------------------------------------------------------------------ A3 历史行兜底

    @Test
    @DisplayName("A3 历史行（没有 valueMissing 键）退回 Fmt 常量兜底：写「无值」的算缺失，写实际值的算有值")
    void a3_legacyRowFallback() {
        Map<String, Object> missingCond = new LinkedHashMap<>();
        missingCond.put("seq", 0);
        missingCond.put("fieldCode", "CHANNEL");
        missingCond.put("opCode", "EQ");
        missingCond.put("expected", "淘宝");
        missingCond.put("actual", com.qiqi.mall.audit.engine.Fmt.NO_VALUE_TEXT);
        missingCond.put("satisfied", false);
        missingCond.put("text", "渠道 无值，无法比较 = 淘宝");

        Map<String, Object> valuedCond = new LinkedHashMap<>();
        valuedCond.put("seq", 0);
        valuedCond.put("fieldCode", "CHANNEL");
        valuedCond.put("opCode", "EQ");
        valuedCond.put("expected", "淘宝");
        valuedCond.put("actual", "抖音");
        valuedCond.put("satisfied", false);
        valuedCond.put("text", "渠道 = 淘宝（实际 抖音）");

        assertThat(PolicyScanAssembler.valueMissingOf(missingCond))
                .as("老行没有布尔，只能读 Fmt 导出的常量（文案只有一份，改了这里跟着改）").isTrue();
        assertThat(PolicyScanAssembler.valueMissingOf(valuedCond)).isFalse();

        // 新行有布尔时一律以布尔为准（哪怕 actual 恰好写成「无值」）
        valuedCond.put("valueMissing", false);
        assertThat(PolicyScanAssembler.valueMissingOf(valuedCond))
                .as("布尔优先：actual 是展示文案，不能反过来覆盖引擎的判定").isFalse();
    }

    // ------------------------------------------------------------------ A4 端到端（组装器 + 三层）

    @Test
    @DisplayName("A4 渠道不符的策略只进第二层 byField，第一层的任何字段里都没有它")
    void a4_notApplicableOnlyInSecondLayer() {
        // 一张抖音单（1580 / 8 件）：直营-小单差客户标签、淘宝-标准单差渠道、抖音-标准单差应收
        AuditVerdict v = verdict(AuditResult.NO_MATCH, null, List.of(), List.of(), List.of(
                miss(1L, "直营-小单", List.of("客户标签 = 直营门店（实际 普通）"),
                        fail(0, "CUSTOMER_TAG", "EQ", "普通", "直营门店", "客户标签 = 直营门店（实际 普通）"),
                        fail(1, "RECEIVABLE_AMOUNT", "LT", "1580.00", "300.00", "应收金额 1580.00 < 300.00")),
                miss(2L, "淘宝-标准单", List.of("渠道 = 淘宝（实际 抖音）"),
                        fail(0, "CHANNEL", "EQ", "抖音", "淘宝", "渠道 = 淘宝（实际 抖音）"),
                        fail(1, "RECEIVABLE_AMOUNT", "LT", "1580.00", "500.00", "应收金额 1580.00 < 500.00")),
                miss(3L, "抖音-标准单", List.of("应收金额 1580.00 < 500.00"),
                        ok(0, "CHANNEL", "EQ", "抖音", "抖音", "渠道 = 抖音（实际 抖音）"),
                        fail(1, "RECEIVABLE_AMOUNT", "LT", "1580.00", "500.00", "应收金额 1580.00 < 500.00"),
                        ok(2, "ITEM_KIND_COUNT", "LTE", "1", "5", "商品种类数 1 ≤ 5"))));

        ExplainLayers layers = PolicyScanAssembler.layers(v, priorities(1L, 100, 2L, 200, 3L, 300));

        assertThat(layers.first().conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_CLOSEST);
        assertThat(layers.first().closest().policyName()).isEqualTo("抖音-标准单");
        assertThat(layers.first().closest().missingConditions())
                .as("第一层只留最接近那条的差在哪").containsExactly("应收金额 1580.00 < 500.00");
        assertThat(layers.first().notApplicableNote()).isEqualTo("另有 2 条不适用（渠道不符 1、客户标签不符 1）");

        // ⚠️ 核心断言：第一层的任何字段里都不许出现「淘宝-标准单」（渠道不符 = 不适用 = 噪音）
        assertThat(String.valueOf(layers.first().toMap()))
                .as("第一层不许再出现一条「渠道不符」的策略名").doesNotContain("淘宝-标准单");
        assertThat(layers.first().oneLiner()).doesNotContain("淘宝-标准单");

        // 第二层：两个数字自洽
        assertThat(layers.second().notApplicableTotal()).isEqualTo(2);
        assertThat(layers.second().byField()).extracting(f -> f.field() + "=" + f.count())
                .containsExactlyInAnyOrder("渠道=1", "客户标签=1");

        // 第三层：一条不删（完整矩阵），且标出不适用
        assertThat(layers.third().policies()).hasSize(3);
        assertThat(layers.third().policies()).filteredOn(r -> "淘宝-标准单".equals(r.policyName()))
                .allSatisfy(r -> {
                    assertThat(r.notApplicable()).isTrue();
                    assertThat(r.notApplicableField()).isEqualTo("渠道");
                });
    }

    // ------------------------------------------------------------------ A5 退化输入

    @Test
    @DisplayName("A5 无命中、无留痕（老行 / 脏 JSON）⇒ 空列表，交给三层给「无可用策略」，不抛")
    void a5_emptyIsSafe() {
        assertThat(PolicyScanAssembler.assemble((AuditVerdict) null)).isEmpty();
        assertThat(PolicyScanAssembler.assemble(new PolicyScanAssembler.DecisionSnapshot(
                "NO_MATCH", null, null, List.of(), List.of(), List.of()), Map.of())).isEmpty();
        ExplainLayers layers = PolicyScanAssembler.layersOfQueueRow(Map.of("result", "SKIPPED"), Map.of());
        assertThat(layers.first().conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_NO_POLICY);
    }

    @Test
    @DisplayName("A6 队列行 → 快照：snake_case 键也认，且优先级缺省时排序仍确定（不依赖输入顺序）")
    void a6_queueRowSnapshot() {
        List<Map<String, Object>> misses = new ArrayList<>();
        misses.add(missGroup(2L, "淘宝-标准单", condMap(0, "CHANNEL", "EQ", "抖音", "淘宝", false)));
        misses.add(missGroup(3L, "抖音-标准单", condMap(1, "RECEIVABLE_AMOUNT", "LT", "1580.00", "500.00", false)));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("result", "NO_MATCH");
        row.put("matched_policy_id", null);
        row.put("matched_policy_name", null);
        row.put("miss_reasons", misses);

        List<PolicyScan> scans = PolicyScanAssembler.assemble(
                PolicyScanAssembler.DecisionSnapshot.fromQueueRow(row), Map.of());
        assertThat(scans).extracting(PolicyScan::policyName).containsExactlyInAnyOrder("淘宝-标准单", "抖音-标准单");
        assertThat(scans).allSatisfy(s -> assertThat(s.priority())
                .isEqualTo(PolicyScanAssembler.DEFAULT_PRIORITY));
    }

    // ------------------------------------------------------------------ A7 第二层明细（策略名 + 一句为什么）

    @Test
    @DisplayName("A7 第二层 items：只给策略名 + 一句为什么（无完整条件表），顺序同 byField，且与输入顺序无关")
    void a7_notApplicableItems() {
        List<PolicyMiss> misses = new ArrayList<>(List.of(
                miss(1L, "直营-小单", List.of("客户标签 = 直营门店（实际 普通）"),
                        fail(0, "CUSTOMER_TAG", "EQ", "普通", "直营门店", "客户标签 = 直营门店（实际 普通）")),
                miss(2L, "淘宝-标准单", List.of("渠道 = 淘宝（实际 抖音）"),
                        fail(0, "CHANNEL", "EQ", "抖音", "淘宝", "渠道 = 淘宝（实际 抖音）")),
                miss(3L, "抖音-标准单", List.of("应收金额 1580.00 < 500.00"),
                        fail(1, "RECEIVABLE_AMOUNT", "LT", "1580.00", "500.00", "应收金额 1580.00 < 500.00"))));
        // 再补两条「渠道不符」：让 byField 里「渠道」这一撮（3 条）明确多于「客户标签」（1 条）
        misses.add(miss(4L, "渠道-甲", List.of("渠道 = 甲（实际 抖音）"),
                fail(0, "CHANNEL", "EQ", "抖音", "甲", "渠道 = 甲（实际 抖音）")));
        misses.add(miss(5L, "渠道-乙", List.of("渠道 = 乙（实际 抖音）"),
                fail(0, "CHANNEL", "EQ", "抖音", "乙", "渠道 = 乙（实际 抖音）")));

        ExplainLayers layers = PolicyScanAssembler.layers(
                verdict(AuditResult.NO_MATCH, null, List.of(), List.of(), misses), Map.of());

        // ---- ① items 只有四个键：策略名 + 一句为什么，**没有**完整条件表（第一屏/第二屏都不许塞矩阵） ----
        Map<String, Object> json = PolicyScanAssembler.layersJson(layers);
        assertThat(json.keySet()).containsExactly("layer1", "layer2", "layer3");
        Map<String, Object> layer2 = asMap(json.get("layer2"));
        assertThat(layer2.keySet()).as("layer2 原有形状不动，只增 items")
                .containsExactly("notApplicableTotal", "byField", "items");

        List<Map<String, Object>> items = asListOfMap(layer2.get("items"));
        assertThat(items).hasSize(4);
        assertThat(items).allSatisfy(i -> assertThat(i.keySet())
                .as("第二层一行只承载「谁 + 为什么」，条件表属于第三层")
                .containsExactly("policyId", "policyName", "field", "why"));
        assertThat(items.stream().map(i -> String.valueOf(i.get("policyName"))).toList())
                .as("条数最多的那撮（渠道 3 条）先列，同组按 policyId 升序 —— 与第一层灰字同一口径")
                .containsExactly("淘宝-标准单", "渠道-甲", "渠道-乙", "直营-小单");
        assertThat(items.get(0).get("field")).isEqualTo("渠道");
        assertThat(items.get(0).get("why")).as("为什么优先取引擎原文（带实际值），不是「渠道不符」四个字")
                .isEqualTo("渠道 = 淘宝（实际 抖音）");
        assertThat(items.get(3).get("why")).isEqualTo("客户标签 = 直营门店（实际 普通）");

        // ---- ② items 的行数必须与 notApplicableTotal 对得平（界面上两个数字自洽） ----
        assertThat(((Number) layer2.get("notApplicableTotal")).intValue()).isEqualTo(items.size());
        // ---- ③ 第一层仍然一个字都不提它们 ----
        assertThat(String.valueOf(json.get("layer1"))).doesNotContain("淘宝-标准单");

        // ---- ④ 确定性：打乱输入，输出逐字节相同（含 items） ----
        List<PolicyMiss> shuffled = new ArrayList<>(misses);
        java.util.Collections.reverse(shuffled);
        Map<String, Object> again = PolicyScanAssembler.layersJson(PolicyScanAssembler.layers(
                verdict(AuditResult.NO_MATCH, null, List.of(), List.of(), shuffled), Map.of()));
        assertThat(String.valueOf(again)).isEqualTo(String.valueOf(json));
    }

    @Test
    @DisplayName("A7b 「为什么」按字段码取，不在文案里找子串：把条件文案换成别的措辞，取到的仍是那条的人话")
    void a7b_whyByFieldCodeNotText() {
        // 文案故意不再包含「渠道」两个字 —— 拿文案 includes('渠道') 的写法在这里会退化成「渠道不符」，
        // 甚至错配到别的字段。按字段码取就不会。
        ExplainLayers layers = PolicyScanAssembler.layers(verdict(AuditResult.NO_MATCH, null, List.of(), List.of(),
                List.of(miss(9L, "某渠道策略", List.of("平台来源 = 淘宝（实际 抖音）"),
                        fail(0, "CHANNEL", "EQ", "抖音", "淘宝", "平台来源 = 淘宝（实际 抖音）")))), Map.of());

        List<PolicyScanAssembler.NotApplicableItem> items = PolicyScanAssembler.notApplicableItems(layers);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).field()).isEqualTo("渠道");
        assertThat(items.get(0).why()).as("取的是那条条件的人话，不是拼一句「渠道不符」")
                .isEqualTo("平台来源 = 淘宝（实际 抖音）");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMap(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> missGroup(long id, String name, Map<String, Object> condition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policyId", id);
        m.put("policyName", name);
        m.put("reasons", List.of("x"));
        m.put("conditions", List.of(condition));
        return m;
    }

    private static Map<String, Object> condMap(int seq, String field, String op, String actual, String expected,
                                               boolean satisfied) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("seq", seq);
        c.put("fieldCode", field);
        c.put("opCode", op);
        c.put("actual", actual);
        c.put("expected", expected);
        c.put("satisfied", satisfied);
        c.put("text", field + " " + op + " " + expected);
        return c;
    }
}
