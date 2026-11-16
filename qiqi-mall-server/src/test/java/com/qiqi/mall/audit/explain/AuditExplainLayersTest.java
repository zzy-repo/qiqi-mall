package com.qiqi.mall.audit.explain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.audit.engine.FieldRegistry;
import com.qiqi.mall.audit.explain.AuditExplainLayers.Closest;
import com.qiqi.mall.audit.explain.AuditExplainLayers.ExplainLayers;
import com.qiqi.mall.audit.explain.AuditExplainLayers.FirstLayer;
import com.qiqi.mall.audit.explain.AuditExplainLayers.MatrixRow;
import com.qiqi.mall.audit.explain.AuditExplainLayers.PolicyScan;
import com.qiqi.mall.audit.explain.AuditExplainLayers.ScanCondition;
import com.qiqi.mall.audit.explain.AuditExplainLayers.ScanOutcome;
import com.qiqi.mall.audit.explain.AuditExplainLayers.SecondLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link AuditExplainLayers} 的纯单测：<b>不连库、不启 Spring、不读时钟</b>。
 *
 * <p>这 8 条（+3 条补充）就是「肉眼看不看得过来」的可执行契约：
 * <ul>
 *   <li>T1 渠道不符的策略进「不适用」，第一屏只剩一条；</li>
 *   <li>T2 outcome 权重最高（命中 &gt; 差 0 条的不命中）；</li>
 *   <li>T3 并列时按 priority 再 policyId 定序；</li>
 *   <li>T4 <b>真的</b>打乱输入 20 次，输出逐字节一致（不是注释里承诺）；</li>
 *   <li>T5 全部不适用也要有一句人话；</li>
 *   <li>T6 空策略列表不抛、给可读结论；</li>
 *   <li>T7 四种模板的 oneLiner 都 ≤80 字；</li>
 *   <li>T8 值缺失的条件<b>不</b>算「不适用」。</li>
 * </ul>
 * 补充三条锁住「渠道 3→30 第一屏不变」这条承诺（T9）、第二层两个数字自洽（T10）、
 * 第一层的类型里没有完整矩阵（T11）。
 */
class AuditExplainLayersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ 造数小工具

    private static ScanCondition cond(int seq, String field, String op, String text) {
        return ScanCondition.of(seq, field, op, text);
    }

    /** 值缺失的不满足条件（判不了 ≠ 不符） */
    private static ScanCondition missingCond(int seq, String field, String op, String text) {
        return ScanCondition.ofMissingValue(seq, field, op, text);
    }

    private static PolicyScan miss(long id, String name, int priority, int satisfied, List<ScanCondition> conds) {
        return new PolicyScan(id, name, priority, ScanOutcome.MISS, satisfied, conds.size(), conds, "", "");
    }

    private static PolicyScan hit(long id, String name, int priority, int satisfied, String hitCondition) {
        return new PolicyScan(id, name, priority, ScanOutcome.HIT, satisfied, 0, List.of(), "", hitCondition);
    }

    private static PolicyScan excluded(long id, String name, int priority, int satisfied, String reason) {
        return new PolicyScan(id, name, priority, ScanOutcome.EXCLUDED, satisfied, 0, List.of(), reason, "");
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一轮「直营店铺单」的扫描结果：3 条淘宝/抖音策略 + 2 条直营店策略 */
    private static List<PolicyScan> directShopScans() {
        return List.of(
                // 直营店：只差量级（毛利），是真正的「最接近」
                miss(11L, "直营店-标准单", 10, 2,
                        List.of(cond(3, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −2.00 < 0"))),
                // 直营店：差量级两条
                miss(12L, "直营店-大额单", 20, 2,
                        List.of(cond(2, FieldRegistry.RECEIVABLE_AMOUNT, "GT", "应收 88.00 > 500.00"))),
                // 淘宝/抖音：渠道就不是这一类 —— 不是「接近」，是「不适用」
                miss(21L, "淘宝-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝"))),
                miss(22L, "抖音-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 抖音"))),
                miss(23L, "淘宝-大额单", 20, 2,
                        List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝"),
                                cond(2, FieldRegistry.RECEIVABLE_AMOUNT, "GT", "应收 88.00 > 500.00")))
        );
    }

    // ------------------------------------------------------------------ T1

    @Test
    @DisplayName("T1 直营店铺单 + 3 条淘宝/抖音策略 ⇒ 那 3 条进「不适用(渠道)」，第一层只 1 条")
    void t1_channelMismatchPoliciesAreAggregatedAwayFromFirstScreen() {
        ExplainLayers layers = AuditExplainLayers.build(directShopScans());
        FirstLayer first = layers.first();
        SecondLayer second = layers.second();

        // 第一层：只有一条「最接近」，而且是最接近的那条直营店策略，不是淘宝
        assertThat(first.conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_CLOSEST);
        assertThat(first.closest()).isNotNull();
        assertThat(first.closest().policyName()).isEqualTo("直营店-标准单");
        assertThat(first.oneLiner()).isEqualTo("最接近「直营店-标准单」，还差 1 个条件：毛利 −2.00 < 0");

        // 第二层：3 条渠道不符聚合成一句话
        assertThat(second.notApplicableTotal()).isEqualTo(3);
        assertThat(second.byField()).hasSize(1);
        assertThat(second.byField().get(0).field()).isEqualTo("渠道");
        assertThat(second.byField().get(0).count()).isEqualTo(3);
        assertThat(first.notApplicableNote()).isEqualTo("另有 3 条不适用（渠道不符）");

        // 第三层：一条不删（5 条全在），不适用行被标出来但不占第一层
        assertThat(layers.third().policies()).hasSize(5);
        assertThat(layers.third().policies().stream().filter(MatrixRow::notApplicable).count()).isEqualTo(3);
        assertThat(layers.third().policies().stream()
                .filter(r -> r.policyId() == 21L)
                .findFirst().orElseThrow().notApplicableField()).isEqualTo("渠道");
    }

    // ------------------------------------------------------------------ T2

    @Test
    @DisplayName("T2 命中优先于「差 0 个条件的不命中」（outcome 权重最高，压过 priority/件数）")
    void t2_hitBeatsZeroMissNonHit() {
        PolicyScan hitScan = hit(5L, "直营店-命中", 99, 3, "渠道 = 直营店");
        // missCount 0 但 outcome=MISS：只可能来自「已求值但被排除口径改写」的退化输入，
        // 正是为了证明第 1 级排序键（outcome）比 missCount/priority 都硬
        PolicyScan zeroMiss = new PolicyScan(6L, "直营店-差零条", 1, ScanOutcome.MISS, 3, 0,
                List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 5.00 < 0")), "", "");

        ExplainLayers hitFirst = AuditExplainLayers.build(List.of(zeroMiss, hitScan));
        assertThat(hitFirst.first().conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_HIT);
        assertThat(hitFirst.first().closest().policyName()).isEqualTo("直营店-命中");
        assertThat(hitFirst.first().oneLiner()).isEqualTo("命中「直营店-命中」：渠道 = 直营店");

        // 反过来只放不命中的那条，才轮到它当「最接近」—— 证明上面赢的是 outcome 而不是别的键
        ExplainLayers missOnly = AuditExplainLayers.build(List.of(zeroMiss));
        assertThat(missOnly.first().conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_CLOSEST);
        assertThat(missOnly.first().closest().policyName()).isEqualTo("直营店-差零条");
    }

    // ------------------------------------------------------------------ T3

    @Test
    @DisplayName("T3 两个策略差同样多条件 ⇒ tie-break 按 priority 再 policyId，结果确定")
    void t3_tieBreakByPriorityThenPolicyId() {
        PolicyScan p5id7 = miss(7L, "策略-优先级5", 5, 2,
                List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −1.00 < 0")));
        PolicyScan p1id3 = miss(3L, "策略-优先级1-ID3", 1, 2,
                List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −2.00 < 0")));
        PolicyScan p1id9 = miss(9L, "策略-优先级1-ID9", 1, 2,
                List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −3.00 < 0")));

        // 第 4 级：priority 升序 —— 1 赢 5；两条在"差多少/满足多少"上并列，所以要如实说「另有 1 条同样接近」
        ExplainLayers two = AuditExplainLayers.build(List.of(p5id7, p1id3));
        assertThat(two.first().closest().policyName()).isEqualTo("策略-优先级1-ID3");
        assertThat(two.first().tieNote()).isEqualTo("另有 1 条同样接近");

        // 差的条件数不同（真正不并列）时，不许多出一句「同样接近」
        PolicyScan p1id8TwoMiss = miss(8L, "策略-差两条", 1, 1,
                List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −2.00 < 0"),
                        cond(2, FieldRegistry.RECEIVABLE_AMOUNT, "GT", "应收 88.00 > 500.00")));
        ExplainLayers noTie = AuditExplainLayers.build(List.of(p1id3, p1id8TwoMiss));
        assertThat(noTie.first().closest().policyName()).isEqualTo("策略-优先级1-ID3");
        assertThat(noTie.first().tieNote()).isEmpty();

        // 第 5 级：priority 相同时 policyId 升序 —— 3 赢 9；并且如实说「另有 2 条同样接近」
        ExplainLayers three = AuditExplainLayers.build(List.of(p5id7, p1id9, p1id3));
        assertThat(three.first().closest().policyName()).isEqualTo("策略-优先级1-ID3");
        assertThat(three.first().tieNote()).isEqualTo("另有 2 条同样接近");

        // 输入顺序颠倒后结论不变（确定性不是靠"碰巧排对"）
        List<PolicyScan> reversed = new ArrayList<>(List.of(p5id7, p1id9, p1id3));
        Collections.reverse(reversed);
        assertThat(json(AuditExplainLayers.build(reversed).toMap()))
                .isEqualTo(json(three.toMap()));
    }

    // ------------------------------------------------------------------ T4

    @Test
    @DisplayName("T4 打乱输入顺序 20 次（外层策略 + 内层条件都打乱）⇒ 输出逐字节一致")
    void t4_shufflingInputTwentyTimesKeepsOutputByteIdentical() {
        List<PolicyScan> template = new ArrayList<>(directShopScans());
        // 再补点"花样"：命中、被排除、并列、多分类字段，让打乱真的可能改变某些实现的结果
        template.add(hit(31L, "直营店-命中", 1, 3, "渠道 = 直营店"));
        template.add(excluded(32L, "改备注-排除", 2, 2, "客服备注 含 改单"));
        template.add(miss(33L, "策略-并列A", 3, 1,
                List.of(cond(2, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −5.00 < 0"),
                        cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −4.00 < 0"))));
        template.add(miss(34L, "策略-并列B", 9, 1,
                List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −6.00 < 0"))));
        template.add(miss(35L, "多字段不符", 4, 1,
                List.of(cond(1, FieldRegistry.SHOP_NAME, "EQ", "店铺 ≠ 其它店"),
                        cond(2, FieldRegistry.RECEIVER_CITY, "EQ", "收货城市 ≠ 杭州"))));

        Random rnd = new Random(20260116L);
        String baseline = null;
        int distinctOutputs = 0;
        List<String> outputs = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            List<PolicyScan> shuffled = shuffleDeep(template, rnd);
            String current = json(AuditExplainLayers.build(shuffled).toMap());
            outputs.add(current);
            if (baseline == null) baseline = current;
            else if (!baseline.equals(current)) distinctOutputs++;
        }
        // 20 次里 0 次出现与第一次不同的输出 = 逐字节一致
        assertThat(distinctOutputs).isZero();
        assertThat(outputs.stream().distinct().count()).isEqualTo(1);
        // 实跑留痕（不是注释里的承诺）：20 次输出同一串字节，哈希也同一串
        System.out.println("T4 打乱 20 次：不同输出数=" + outputs.stream().distinct().count()
                + "，基线字节数=" + baseline.length() + "，sha256=" + sha256(baseline));
        // 而且"打乱"真的发生了：每次喂进去的顺序都不一样（否则这条用例什么都没证明）
        List<String> inputOrders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            inputOrders.add(shuffleDeep(template, rnd).stream().map(PolicyScan::policyName).toList().toString());
        }
        assertThat(inputOrders.stream().distinct().count()).isGreaterThan(1);
    }

    /** 打乱策略顺序 + 每条策略内部的未满足条件顺序（内层顺序也是"输入顺序"，同样不许影响输出） */
    private static List<PolicyScan> shuffleDeep(List<PolicyScan> template, Random rnd) {
        List<PolicyScan> out = new ArrayList<>();
        for (PolicyScan s : template) {
            List<ScanCondition> conds = new ArrayList<>(s.missingConditions());
            Collections.shuffle(conds, rnd);
            out.add(new PolicyScan(s.policyId(), s.policyName(), s.priority(), s.outcome(),
                    s.satisfiedCount(), s.missCount(), conds, s.excludedBy(), s.hitCondition()));
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    // ------------------------------------------------------------------ T5

    @Test
    @DisplayName("T5 全部不适用 ⇒ 第一层给「全不适用」那句人话，不是空")
    void t5_allNotApplicableStillSpeaks() {
        ExplainLayers layers = AuditExplainLayers.build(List.of(
                miss(41L, "淘宝-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝"))),
                miss(42L, "其它店-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.SHOP_NAME, "EQ", "店铺 ≠ 其它店"))),
                miss(43L, "VIP-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.CUSTOMER_TAG, "EQ", "客户标签 ≠ VIP")))));

        FirstLayer first = layers.first();
        assertThat(first.conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_ALL_NOT_APPLICABLE);
        assertThat(first.oneLiner()).isEqualTo("本的渠道/店铺与全部策略都不匹配");
        assertThat(first.oneLiner()).isNotBlank();
        assertThat(first.closest()).isNull();
        assertThat(layers.second().notApplicableTotal()).isEqualTo(3);
        // 三个字段各 1 条：数量相同 ⇒ 按闭集声明顺序 渠道 → 店铺 → 客户标签
        assertThat(layers.second().byField()).extracting("field").containsExactly("渠道", "店铺", "客户标签");
        assertThat(first.notApplicableNote()).isEqualTo("共 3 条不适用（渠道不符 1、店铺不符 1、客户标签不符 1）");
    }

    // ------------------------------------------------------------------ T6

    @Test
    @DisplayName("T6 空策略列表 ⇒ 不抛异常，给可读结论")
    void t6_emptyPolicyListIsReadable() {
        assertThatCode(() -> AuditExplainLayers.build(List.of())).doesNotThrowAnyException();

        ExplainLayers empty = AuditExplainLayers.build(List.of());
        assertThat(empty).isNotNull();
        assertThat(empty.first().conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_NO_POLICY);
        assertThat(empty.first().oneLiner()).isEqualTo("没有可用的审核策略，请先配置策略");
        assertThat(empty.first().closest()).isNull();
        assertThat(empty.first().notApplicableNote()).isEmpty();
        assertThat(empty.second().notApplicableTotal()).isZero();
        assertThat(empty.second().byField()).isEmpty();
        assertThat(empty.third().policies()).isEmpty();

        // null 入参同样不许炸（调用方漏装配是现实，不是意外）
        assertThatCode(() -> AuditExplainLayers.build(null)).doesNotThrowAnyException();
        assertThat(AuditExplainLayers.build(null).first().oneLiner()).isEqualTo("没有可用的审核策略，请先配置策略");
    }

    // ------------------------------------------------------------------ T7

    @Test
    @DisplayName("T7 oneLiner ≤80 字符（覆盖四种模板 + 超长策略名截断）")
    void t7_oneLinerNeverExceeds80Chars() {
        // ① 命中
        String hitLine = AuditExplainLayers.build(List.of(hit(51L, "直营店-标准单", 1, 2, "渠道 = 直营店")))
                .first().oneLiner();
        assertThat(hitLine).isEqualTo("命中「直营店-标准单」：渠道 = 直营店");

        // ② 最接近（不匹配）
        String closestLine = AuditExplainLayers.build(List.of(miss(52L, "直营店-标准单", 1, 2,
                        List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −2.00 < 0")))))
                .first().oneLiner();
        assertThat(closestLine).isEqualTo("最接近「直营店-标准单」，还差 1 个条件：毛利 −2.00 < 0");

        // ③ 被排除
        String excludedLine = AuditExplainLayers.build(List.of(excluded(53L, "改备注-排除", 1, 2, "客服备注 含 改单")))
                .first().oneLiner();
        assertThat(excludedLine).isEqualTo("被排除策略「改备注-排除」挡掉：客服备注 含 改单");

        // ④ 全不适用
        String allNaLine = AuditExplainLayers.build(List.of(miss(54L, "淘宝-标准单", 1, 2,
                        List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝")))))
                .first().oneLiner();
        assertThat(allNaLine).isEqualTo("本的渠道/店铺与全部策略都不匹配");

        assertThat(List.of(hitLine, closestLine, excludedLine, allNaLine))
                .allSatisfy(line -> {
                    assertThat(line).isNotBlank();
                    assertThat(line.length()).isLessThanOrEqualTo(AuditExplainLayers.ONELINER_MAX);
                });

        // 超长策略名：截断到恰好 80 字（末尾 1 字是省略号），第一屏不许被撑破
        String longName = "超长策略名".repeat(20);
        String longLine = AuditExplainLayers.build(List.of(hit(55L, longName, 1, 2, "渠道 = 直营店")))
                .first().oneLiner();
        assertThat(longLine.length()).isEqualTo(AuditExplainLayers.ONELINER_MAX);
        assertThat(longLine).endsWith("…");
        // 类型自带的那道钳（构造 FirstLayer 时也会钳，双保险）
        assertThat(new FirstLayer("命中", longName, null, "", "").oneLiner().length())
                .isEqualTo(AuditExplainLayers.ONELINER_MAX);
    }

    // ------------------------------------------------------------------ T8

    @Test
    @DisplayName("T8 值缺失的条件不被算成「不适用」（缺失 ≠ 不符）")
    void t8_missingValueIsNotMismatch() {
        // 同一张条件：渠道 EQ 淘宝。只看"有值时"才是不适用
        PolicyScan cityUnknown = miss(61L, "淘宝-标准单(城市缺失)", 10, 1,
                List.of(missingCond(1, FieldRegistry.CHANNEL, "EQ", "渠道 无值，判不了"),
                        missingCond(2, FieldRegistry.RECEIVER_CITY, "EQ", "收货城市 无值，判不了")));
        // 对照：值有值 ⇒ 渠道确实不符 ⇒ 不适用
        PolicyScan channelKnown = miss(62L, "淘宝-标准单", 10, 1,
                List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝")));

        ExplainLayers missingOnly = AuditExplainLayers.build(List.of(cityUnknown));
        assertThat(missingOnly.second().notApplicableTotal()).isZero();
        assertThat(missingOnly.first().conclusion()).isEqualTo(AuditExplainLayers.CONCLUSION_CLOSEST);
        assertThat(missingOnly.first().closest().policyName()).isEqualTo("淘宝-标准单(城市缺失)");
        assertThat(missingOnly.third().policies().get(0).notApplicable()).isFalse();

        ExplainLayers both = AuditExplainLayers.build(List.of(cityUnknown, channelKnown));
        assertThat(both.second().notApplicableTotal()).isEqualTo(1);
        assertThat(both.second().byField()).extracting("field").containsExactly("渠道");
        // 缺失的那条留在候选里，有值不符的那条被聚合走
        assertThat(both.first().closest().policyName()).isEqualTo("淘宝-标准单(城市缺失)");
        assertThat(both.first().notApplicableNote()).isEqualTo("另有 1 条不适用（渠道不符）");

        // 非 EQ/NE 操作符（含 CONTAINS）不参与「不适用」判定
        PolicyScan contains = miss(63L, "备注-标准单", 10, 1,
                List.of(cond(1, FieldRegistry.CHANNEL, "CONTAINS", "渠道 含 淘")));
        assertThat(AuditExplainLayers.isNotApplicable(contains)).isFalse();
    }

    // ------------------------------------------------------------------ T9（补充：渠道 3→30）

    /**
     * 一个「渠道数可调」的样本：1 条直营店策略（真最接近）+ (n-1) 条别的渠道策略（全都不适用）。
     * 只改 n，别的都一样 —— 这正是「渠道从 3 涨到 30」在数据长什么样。
     */
    private static List<PolicyScan> channelSeries(int n) {
        String[] otherChannels = {"淘宝", "抖音", "快手", "拼多多", "京东", "小红书", "视频号", "1688"};
        List<PolicyScan> out = new ArrayList<>();
        out.add(miss(101L, "直营店-标准单", 10, 2,
                List.of(cond(3, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −2.00 < 0"))));
        for (int i = 0; i < n - 1; i++) {
            String channel = otherChannels[i % otherChannels.length] + (i / otherChannels.length);
            out.add(miss(200L + i, channel + "-标准单", 10 + i, 2,
                    List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ " + channel))));
        }
        return out;
    }

    @Test
    @DisplayName("T9 渠道 3→30：第一屏（第一层 JSON）只长 1 个字符（2 → 29 的那一位）")
    void t9_threeChannelsVersusThirtyChannelsKeepsFirstScreen() {
        ExplainLayers layersOf3 = AuditExplainLayers.build(channelSeries(3));
        ExplainLayers layersOf30 = AuditExplainLayers.build(channelSeries(30));
        FirstLayer firstOf3 = layersOf3.first();
        FirstLayer firstOf30 = layersOf30.first();

        // 那句话、那条最接近：完全不随渠道数变化（名字里 29 条淘宝/抖音策略一个都没进第一屏）
        assertThat(firstOf3.oneLiner()).isEqualTo(firstOf30.oneLiner());
        assertThat(firstOf3.closest()).isEqualTo(firstOf30.closest());
        assertThat(firstOf3.notApplicableNote()).isEqualTo("另有 2 条不适用（渠道不符）");
        assertThat(firstOf30.notApplicableNote()).isEqualTo("另有 29 条不适用（渠道不符）");

        // 第一层 JSON 只多 1 个字符（"2" → "29"）；第三层则如实从 3 行涨到 30 行（信息没丢，只是折叠）
        assertThat(json(firstOf30.toMap()).length() - json(firstOf3.toMap()).length()).isEqualTo(1);
        assertThat(layersOf3.third().policies()).hasSize(3);
        assertThat(layersOf30.third().policies()).hasSize(30);
        assertThat(layersOf30.second().notApplicableTotal()).isEqualTo(29);
    }

    // ------------------------------------------------------------------ T10（补充：第二层自洽）

    @Test
    @DisplayName("T10 第二层两个数字自洽：byField 合计 == notApplicableTotal（多字段不符也只记一次）")
    void t10_secondLayerCountsAddUp() {
        List<PolicyScan> scans = List.of(
                // 同时撞渠道 + 城市：只记到闭集声明顺序最靠前的「渠道」
                miss(71L, "淘宝-双不符", 10, 1,
                        List.of(cond(1, FieldRegistry.RECEIVER_CITY, "EQ", "收货城市 ≠ 杭州"),
                                cond(2, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝"))),
                miss(72L, "淘宝-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝"))),
                miss(73L, "VIP-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.CUSTOMER_TAG, "EQ", "客户标签 ≠ VIP"))),
                miss(74L, "直营店-标准单", 10, 2,
                        List.of(cond(1, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −2.00 < 0"))));

        SecondLayer second = AuditExplainLayers.build(scans).second();
        assertThat(second.notApplicableTotal()).isEqualTo(3);
        assertThat(second.byField()).extracting("field").containsExactly("渠道", "客户标签");
        int sum = second.byField().stream().mapToInt(fc -> fc.count()).sum();
        assertThat(sum).isEqualTo(second.notApplicableTotal());
        assertThat(second.byField().get(0).count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ T11（补充：第一层类型里没有矩阵）

    @Test
    @DisplayName("T11 第一层的数据形状里没有 conditions / 完整矩阵键（类型 + 落盘形状都堵死）")
    void t11_firstLayerHasNoConditionsKey() {
        ExplainLayers layers = AuditExplainLayers.build(directShopScans());
        FirstLayer first = layers.first();

        assertThat(first.toMap()).doesNotContainKeys("conditions", "missingConditions", "policies", "matrix");
        assertThat(first.toMap().keySet())
                .containsExactly("conclusion", "oneLiner", "closest", "notApplicableNote", "tieNote");
        assertThat(json(first.toMap())).doesNotContain("\"conditions\":[");
        // 反过来：完整矩阵确实还在第三层（"一条不删"不是靠删信息实现的）
        assertThat(json(layers.third().toMap())).contains("\"missingConditions\"");
        assertThat(layers.third().policies()).hasSize(5);
        // closest 只带人话，不带 expected/actual/satisfied 三元组
        Closest closest = first.closest();
        assertThat(closest.missingConditions()).containsExactly("毛利 −2.00 < 0");
    }

    // ------------------------------------------------------------------ T12（补充：ACTIVE_TAGS 进闭集）

    @Test
    @DisplayName("T12 读标策略对未打标单进「不适用」聚合、不占第一屏（ACTIVE_TAGS 用 HAS/NOT_HAS）")
    void t12_readTagPolicyTreatsMissingTagAsNotApplicable() {
        // 直营店单：只差毛利，是真正的「最接近」
        PolicyScan closest = miss(81L, "直营店-标准单", 10, 2,
                List.of(cond(3, FieldRegistry.GROSS_PROFIT, "LT", "毛利 −2.00 < 0")));
        // 读标策略：只对「被打过 FROZEN_RETURN_APPLY」的单生效 —— 这单没这个身份 ⇒ 不适用
        PolicyScan readTagHas = miss(82L, "退款单-加严", 10, 2,
                List.of(cond(1, FieldRegistry.ACTIVE_TAGS, "HAS", "当前标记 无 FROZEN_RETURN_APPLY")));
        // NOT_HAS 的未满足同样 = 身份不符：这单确实有 VIP，而策略只要「没有 VIP 的单」
        PolicyScan readTagNotHas = miss(83L, "非VIP-标准单", 10, 2,
                List.of(cond(1, FieldRegistry.ACTIVE_TAGS, "NOT_HAS", "当前标记 有 VIP_CUSTOMER")));
        // 渠道不符的老噪音，一起进聚合
        PolicyScan taobao = miss(84L, "淘宝-标准单", 10, 2,
                List.of(cond(1, FieldRegistry.CHANNEL, "EQ", "渠道 ≠ 淘宝")));

        ExplainLayers layers = AuditExplainLayers.build(List.of(closest, readTagHas, readTagNotHas, taobao));

        // 3 条不适用：HAS 未满足 / NOT_HAS 未满足 / 渠道不符，都不占第一屏
        assertThat(layers.second().notApplicableTotal()).isEqualTo(3);
        assertThat(layers.second().byField()).extracting("field").containsExactly("当前标记", "渠道");
        assertThat(layers.second().byField().get(0).count()).isEqualTo(2);
        assertThat(layers.first().closest().policyName()).isEqualTo("直营店-标准单");
        assertThat(layers.first().notApplicableNote()).isEqualTo("另有 3 条不适用（当前标记不符 2、渠道不符 1）");
        assertThat(layers.first().oneLiner()).isEqualTo("最接近「直营店-标准单」，还差 1 个条件：毛利 −2.00 < 0");

        // 判定本身
        assertThat(AuditExplainLayers.isNotApplicable(readTagHas)).isTrue();
        assertThat(AuditExplainLayers.isNotApplicable(readTagNotHas)).isTrue();
        assertThat(AuditExplainLayers.isClassificationField(FieldRegistry.ACTIVE_TAGS)).isTrue();
        assertThat(AuditExplainLayers.mismatchOperators(FieldRegistry.ACTIVE_TAGS)).containsExactly("HAS", "NOT_HAS");
        assertThat(AuditExplainLayers.mismatchOperators(FieldRegistry.CHANNEL)).containsExactly("EQ", "NE");
        assertThat(AuditExplainLayers.mismatchOperators(FieldRegistry.GROSS_PROFIT)).isEmpty();

        // ⚠️ 边界一：标的白名单里没有阈值 —— ACTIVE_TAGS 上写 GT 不参与「不适用」判定
        assertThat(AuditExplainLayers.isNotApplicable(miss(85L, "配错的读标", 10, 1,
                List.of(cond(1, FieldRegistry.ACTIVE_TAGS, "GT", "当前标记 > 0"))))).isFalse();
        // ⚠️ 边界二：标未装配（值缺失）= 判不了，不是不符
        assertThat(AuditExplainLayers.isNotApplicable(miss(86L, "标未装配", 10, 1,
                List.of(missingCond(1, FieldRegistry.ACTIVE_TAGS, "HAS", "当前标记 未装配，判不了"))))).isFalse();
        // ⚠️ 边界三：标对上了但「件数/金额不满足」是真实不匹配，不许说成不适用
        PolicyScan tagOkQtyBad = miss(87L, "大额退款单", 10, 2,
                List.of(cond(2, FieldRegistry.ITEM_QTY_TOTAL, "GTE", "商品总件数 3.00 ≥ 10.00")));
        assertThat(AuditExplainLayers.isNotApplicable(tagOkQtyBad)).isFalse();
        ExplainLayers qtyOnly = AuditExplainLayers.build(List.of(tagOkQtyBad));
        assertThat(qtyOnly.second().notApplicableTotal()).isZero();
        assertThat(qtyOnly.first().oneLiner()).isEqualTo("最接近「大额退款单」，还差 1 个条件：商品总件数 3.00 ≥ 10.00");

        // 闭集就是 5 个，顺序固定（新字段只许追加在末尾）
        assertThat(AuditExplainLayers.classificationFields()).containsExactly(
                FieldRegistry.CHANNEL, FieldRegistry.SHOP_NAME, FieldRegistry.CUSTOMER_TAG,
                FieldRegistry.RECEIVER_CITY, FieldRegistry.ACTIVE_TAGS);
    }
}
