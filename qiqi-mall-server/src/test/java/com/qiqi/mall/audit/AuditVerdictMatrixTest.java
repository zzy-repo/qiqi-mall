package com.qiqi.mall.audit;

import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditSetting;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.PolicyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「五判定口径」的判定矩阵</b>：一行 = 一个「规则集叠加态」→ 期望判定。
 *
 * <h3>为什么要有这张表（而不是再加几条单点断言）</h3>
 * {@link AuditEngineTest} 与 {@link AuditPendingBehaviorTest} 都是「一笔单一条断言」，它们能证明
 * 每条规定<b>单独</b>成立，但证不了当<b>多条规则同时适用</b>时谁先谁后：
 * 「已冻结 × 需库存预检」「阻止命中 × 自动策略也满足」「自动策略满足 × 排除命中 × 需库存预检」
 * 这些组合里，任意两条规则给出的结论都不同，最后落哪个结论完全是<b>优先级</b>问题。
 * 而「口径自洽」（五类计数之和 == 决策行数）恰恰是规则集之间的优先级与叠加态属性 ——
 * 单点断言测不到，只有把组合摆进一张表里逐行钉死才测得到。
 *
 * <h3>表里每一行断言什么（五个字段，缺一不可）</h3>
 * <ol>
 *   <li>{@code result} —— 落哪一类（五类之一，或 SKIPPED 这个「没参与本轮」）；</li>
 *   <li>{@code matched_policy_name} —— 决策行会快照的策略名（哪条规则把它定下来的）；</li>
 *   <li>{@code downgrade_reason} —— MANUAL 三支来路（被排除 / STOCK_SHORT / COST_MISSING）靠它区分；</li>
 *   <li>{@code advancesStatus} —— 状态是否推进（只有 AUTO_PASS 推；这是「结论」与「动作」的分界）；</li>
 *   <li>{@code would_be_auto_policy_name} —— 被拦/冻结时回填「本来会不会自动过」。</li>
 * </ol>
 * 另外每行还要过三条<b>不变量</b>（写在用例体里，不逐行抄进表，免得表变成噪音）：
 * <pre>
 *  · 非 SKIPPED 的证据里一定有 stock 段，且 stock.short 永远如实反映「桶里够不够」——
 *    与结论无关（这正是修正①的全部意义：事实写进证据，结论不许被它改写）；
 *  · stock.hint 只在 NO_MATCH 上出现（判定链 [7b]：只写证据、不改结论的那条支路）；
 *  · stock.warning 只在「LENIENT 档 + 真的缺货 + 仍然 AUTO_PASS」时出现。
 * </pre>
 *
 * <h3>期望值怎么来的：先读实现，再如实写</h3>
 * 这张表<b>不是</b>「我认为应该怎样」，而是「实现现在怎样」。凡是出现「谁优先」的行，期望值都是从
 * {@code AuditEngine} 的判定链顺序读出来的（[1] 状态 → [2] 冻结 → [3] BLOCK → [4] EXCEPTION →
 * [5] AUTO → [6] 排除/不命中 → [7a][7b] 预检）。表跑绿的那一刻，它就从「实现事实」变成
 * 「被锁死的契约」—— 以后谁调了顺序，对应那行会红。
 *
 * <p>纯函数、不连库、毫秒级：{@code cd qiqi-mall-server && mvn -o test -Dtest=AuditVerdictMatrixTest}
 */
class AuditVerdictMatrixTest {

    private static final AuditEngine ENGINE = AuditScenario.engine();

    // ================================================================== 表

    /**
     * 判定矩阵：<b>一行 = 一个输入组合</b>。
     *
     * <p>行序按「判定链」排：先五类基线 → 再各道闸门之间的叠加态 → 再库存档位 → 最后同类内
     * priority 竞争与停用 → 三种以上的叠加态收尾。读表的人和改链的人看到的是同一个顺序。
     */
    static Stream<Arguments> matrix() {
        return rows().stream().map(Arguments::of);
    }

    static List<Row> rows() {
        return List.of(
                // ---------------- ① 五判定各一个基线 ----------------
                row("M01", "AUTO_PASS 基线：淘宝 268 元 2 件，淘宝-标准单条件全满足", "无干扰项 → 自动过审（唯一会推进状态的结论）",
                        AuditScenario.qt1001(),
                        AuditResult.AUTO_PASS, AuditScenario.POLICY_AUTO_TAOBAO, null, true, null),

                row("M02", "MANUAL 基线：同 M01，但客服备注含「改」→ 命中全局排除", "条件全满足却被 ALL_AUTO 排除 → 留人工（不是不命中）",
                        AuditScenario.qt1002(),
                        AuditResult.MANUAL, AuditScenario.POLICY_AUTO_TAOBAO, null, false, null),

                row("M03", "BLOCKED 基线：直营 199 元、毛利 −12", "BLOCK 命中即结论；AUTO 只回填不执行",
                        AuditScenario.qt1004(),
                        AuditResult.BLOCKED, AuditScenario.POLICY_BLOCK_PROFIT, null, false, AuditScenario.POLICY_AUTO_DIRECT),

                row("M04", "FROZEN 基线：淘宝 320 元、有待处理退款申请", "EXCEPTION 命中 → 冻结（打标，不动状态）",
                        AuditScenario.qt1005Round1(),
                        AuditResult.FROZEN, AuditScenario.POLICY_EXCEPTION_RETURN, null, false, AuditScenario.POLICY_AUTO_TAOBAO),

                row("M05", "NO_MATCH 基线：抖音 1580 元（三条 AUTO 都不命中），桶里够", "全都不中 → 不命中；库存够时 hint 说「人工审核不会因缺货失败」",
                        AuditScenario.qt1003(),
                        AuditResult.NO_MATCH, null, null, false, null),

                // ---------------- ② 闸门叠加：谁先谁后 ----------------
                row("M06", "已冻结 × 需库存预检（桶 5 < 需求 8）× AUTO 条件全满足",
                        "【冻结优先于预检、也优先于 AUTO】[2] 在 [5][7a][7b] 之前 → SKIPPED，连 would_be 都不算",
                        frozenShortStock(1201L, "QT-1201"),
                        AuditResult.SKIPPED, null, null, false, null),

                row("M07", "状态 8（已发货）× 策略全满足 × 需库存预检",
                        "【状态优先于一切】[1] 最前 → SKIPPED",
                        shippedShortStock(1202L, "QT-1202"),
                        AuditResult.SKIPPED, null, null, false, null),

                row("M08", "阻止命中（毛利 0 ≤ 0）× 自动策略也满足（淘宝 268）",
                        "【BLOCK 优先于 AUTO】结论是 BLOCKED，但被丢弃的 AUTO 仍回填 would_be",
                        builder(1203L, "QT-1203", 7, "淘宝", "268.00")
                                .grossProfit("0.00").itemQty(2).itemKinds(1).stock(100, 100).build(),
                        AuditResult.BLOCKED, AuditScenario.POLICY_BLOCK_PROFIT, null, false, AuditScenario.POLICY_AUTO_TAOBAO),

                row("M09", "异常命中（有退款申请）× 阻止也命中（毛利 −5）",
                        "【实现事实：BLOCK 在 EXCEPTION 之前】[3] 先于 [4] → BLOCKED（不是 FROZEN）",
                        builder(1204L, "QT-1204", 7, "淘宝", "268.00")
                                .grossProfit("-5.00").itemQty(2).itemKinds(1).hasReturnApply(true).stock(100, 100).build(),
                        AuditResult.BLOCKED, AuditScenario.POLICY_BLOCK_PROFIT, null, false, AuditScenario.POLICY_AUTO_TAOBAO),

                row("M10", "异常命中（有退款申请）× 需库存预检（桶 5 < 需求 8）",
                        "【冻结优先于预检】[4] 在 [7a][7b] 之前 → FROZEN；证据里仍如实写 short=true（事实与结论分离）",
                        builder(1205L, "QT-1205", 7, "淘宝", "320.00")
                                .grossProfit("80.00").itemQty(8).itemKinds(1).hasReturnApply(true).stock(5, 999).build(),
                        AuditResult.FROZEN, AuditScenario.POLICY_EXCEPTION_RETURN, null, false, AuditScenario.POLICY_AUTO_TAOBAO),

                row("M11", "不命中（种类数 6 > 5）× 需库存预检（桶 5 < 需求 6）",
                        "【结论不变、只写证据】[7b] 只写 stock 段 + hint → 仍是 NO_MATCH，绝不改成 MANUAL/STOCK_SHORT",
                        builder(1206L, "QT-1206", 7, "淘宝", "420.00")
                                .grossProfit("100.00").itemQty(6).itemKinds(6).stock(5, 999).build(),
                        AuditResult.NO_MATCH, null, null, false, null),

                row("M12", "自动策略满足 × 排除命中（客服备注「改」）× 需库存预检",
                        "【排除优先于预检】[5b] 在 [7a] 之前 → MANUAL（被排除，无 downgrade），不 STOCK_SHORT",
                        builder(1207L, "QT-1207", 7, "淘宝", "268.00")
                                .grossProfit("62.00").itemQty(8).itemKinds(1)
                                .csRemark("客户改地址：朝阳区xx路2号").stock(5, 999).build(),
                        AuditResult.MANUAL, AuditScenario.POLICY_AUTO_TAOBAO, null, false, null),

                row("M13", "自动策略满足 × 库存不足（STRICT）",
                        "[7a] STRICT：绝不带着缺口自动过审 → MANUAL + STOCK_SHORT（MANUAL 的子类，不新增枚举）",
                        AuditScenario.qtStockShort(),
                        AuditResult.MANUAL, AuditScenario.POLICY_AUTO_TAOBAO, AuditVerdict.DOWNGRADE_STOCK_SHORT, false, null),

                // ---------------- ③ 库存档位：同一个单，两个档位两个结论 ----------------
                row("M14", "自动策略满足 × 库存不足（LENIENT）—— 与 M13 同一个单，只换档位",
                        "[7a] LENIENT：仍放行 → AUTO_PASS（状态会推进），缺口只落 evidence.stock.warning",
                        AuditScenario.qtStockShort(), AuditScenario.lenient(),
                        AuditResult.AUTO_PASS, AuditScenario.POLICY_AUTO_TAOBAO, null, true, null),

                // ---------------- ④ 同类内 priority 竞争与停用 ----------------
                row("M15", "多 AUTO 同时满足（直营-小单 prio 100 ∩ 淘宝-标准单 prio 200）",
                        "【同类内 priority 升序】取优先级最小那条 → 直营-小单",
                        bothAutoMatch(1208L, "QT-1208"), AuditScenario.policies(), AuditScenario.strict(),
                        AuditResult.AUTO_PASS, AuditScenario.POLICY_AUTO_DIRECT, null, true, null),

                row("M16", "同 M15，但「直营-小单」被停用（enabled=false）",
                        "【停用策略不参与】次优的那条接上 → 淘宝-标准单",
                        bothAutoMatch(1208L, "QT-1208"), disable(AuditScenario.POLICY_AUTO_DIRECT), AuditScenario.strict(),
                        AuditResult.AUTO_PASS, AuditScenario.POLICY_AUTO_TAOBAO, null, true, null),

                row("M17", "两条 EXCEPTION 同时命中（退款申请 prio 10 ∩ 平台备注 2 小时内被改 prio 20）",
                        "【同类内 priority 升序】被哪条冻的必须一眼看出 → 退款申请冻结",
                        builder(1211L, "QT-1211", 7, "淘宝", "320.00")
                                .grossProfit("80.00").itemQty(2).itemKinds(1).hasReturnApply(true)
                                .platformRemark("改成朝阳区xx路2号", AuditScenario.NOW.minusHours(2))
                                .stock(100, 100).build(),
                        AuditResult.FROZEN, AuditScenario.POLICY_EXCEPTION_RETURN, null, false, AuditScenario.POLICY_AUTO_TAOBAO),

                row("M18", "两条 BLOCK 同时命中（毛利不为正 prio 10 ∩ 双地址 prio 20）",
                        "【同类内 priority 升序】命中「毛利不为正」—— 原因文案才带具体数值差",
                        builder(1212L, "QT-1212", 7, "直营", "199.00")
                                .customerTag("直营门店").grossProfit("-12.00").itemQty(1).itemKinds(1)
                                .addressMulti(true).stock(100, 100).build(),
                        AuditResult.BLOCKED, AuditScenario.POLICY_BLOCK_PROFIT, null, false, AuditScenario.POLICY_AUTO_DIRECT),

                // ---------------- ⑤ 三种以上的叠加态 ----------------
                row("M19", "三者叠加：已冻结 × 阻止命中 × 不命中（抖音 1580 元、毛利 −12、已有 FROZEN 标）",
                        "【冻结最先】[2] 在 [3][5] 之前 → SKIPPED（既不 BLOCKED 也不 NO_MATCH）",
                        builder(1209L, "QT-1209", 7, "抖音", "1580.00")
                                .grossProfit("-12.00").itemQty(8).itemKinds(2)
                                .frozen(true, "FROZEN").stock(5, 999).build(),
                        AuditResult.SKIPPED, null, null, false, null),

                row("M20", "阻止命中 × 不命中（抖音 1580 元、毛利 −12，无冻结）",
                        "【BLOCK 与「AUTO 不命中」不冲突】拦住就够，would_be 为空（本来也不会自动过）",
                        builder(1210L, "QT-1210", 7, "抖音", "1580.00")
                                .grossProfit("-12.00").itemQty(8).itemKinds(2).stock(100, 100).build(),
                        AuditResult.BLOCKED, AuditScenario.POLICY_BLOCK_PROFIT, null, false, null),

                // M09 那行两条策略 priority 相同（都是 10），区分不出「类型顺序」与「全局 priority」。
                // 这一行让 EXCEPTION 的 priority 数字更小（10 < 20）：若按全局 priority 排 → FROZEN；
                // 实得 BLOCKED，说明跨类型比的是**类型顺序**，priority 只在同类内有效。
                row("M21", "阻止命中（双地址 prio 20）× 异常命中（退款申请 prio 10）—— 异常那条的数字更小",
                        "【跨类型只认类型顺序，不认 priority 数字】[3] 整段在 [4] 之前 → BLOCKED（不是 FROZEN）",
                        builder(1213L, "QT-1213", 7, "淘宝", "268.00")
                                .grossProfit("62.00").itemQty(2).itemKinds(1)
                                .addressMulti(true).hasReturnApply(true).stock(100, 100).build(),
                        AuditResult.BLOCKED, AuditScenario.POLICY_BLOCK_ADDRESS, null, false, AuditScenario.POLICY_AUTO_TAOBAO)
        );
    }

    // ================================================================== 用例

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("matrix")
    @DisplayName("判定矩阵：每行把 result / matched_policy_name / downgrade_reason / advancesStatus / would_be 一次钉死")
    void verdictMatrix(Row row) {
        AuditVerdict v = ENGINE.evaluate(row.ctx(), row.policies(), row.setting());

        // ---------------- 五个字段（表的正题） ----------------
        assertThat(v.result()).as("[%s] %s → result", row.id(), row.combo()).isEqualTo(row.result());
        assertThat(nameOf(v.matchedPolicy())).as("[%s] %s → matched_policy_name", row.id(), row.combo())
                .isEqualTo(row.matchedPolicyName());
        assertThat(v.downgradeReason()).as("[%s] %s → downgrade_reason（MANUAL 三支来路靠它区分）",
                row.id(), row.combo()).isEqualTo(row.downgradeReason());
        assertThat(v.advancesStatus()).as("[%s] %s → advancesStatus（只有 AUTO_PASS 推进状态）",
                row.id(), row.combo()).isEqualTo(row.advancesStatus());
        assertThat(nameOf(v.wouldBePolicy())).as("[%s] %s → would_be_auto_policy_name",
                row.id(), row.combo()).isEqualTo(row.wouldBePolicyName());

        // ---------------- 不变量一：证据里的 stock 段与结论无关 ----------------
        boolean shortByFacts = row.ctx().stockAvailable() < row.ctx().itemQtyTotal();
        if (row.result() == AuditResult.SKIPPED) {
            assertThat(v.evidence()).as("[%s] SKIPPED 没参与判定 → 不跑库存预检，证据里没有 stock 段", row.id())
                    .doesNotContainKeys("stock", "cost", "would_be");
            assertThat(v.evidence()).as("[%s] SKIPPED 的证据只回答「为什么没审」", row.id())
                    .containsKeys("reason", "order_status", "frozen");
            assertThat(v.reasonText()).as("[%s] SKIPPED 必须说清为什么跳过", row.id()).isNotBlank();
        } else {
            Map<String, Object> stock = stockOf(v, row);
            assertThat(stock).as("[%s] 非 SKIPPED 的证据里一定有 stock 段（含不命中的单 —— 修正①的口径）", row.id())
                    .containsKeys("bucket_sum", "sku_stock_cache", "demand", "guard", "short");
            assertThat(stock.get("bucket_sum")).as("[%s] 预检判据是 SUM(sku_bucket.stock)，不是 sku.stock 缓存", row.id())
                    .isEqualTo(row.ctx().stockAvailable());
            assertThat(stock.get("demand")).as("[%s] 需求量 = 商品总件数", row.id())
                    .isEqualTo(row.ctx().itemQtyTotal());
            assertThat(stock.get("short"))
                    .as("[%s] stock.short 永远如实反映「桶里够不够」—— 与结论无关（只写事实，不改判定）", row.id())
                    .isEqualTo(shortByFacts);

            // 不变量二：hint 只属于 [7b]（NO_MATCH 那条只写证据的支路）
            assertThat(stock.containsKey("hint"))
                    .as("[%s] stock.hint 只在 NO_MATCH 上出现（[7b]；[7a] 是会改结论的另一条支路）", row.id())
                    .isEqualTo(row.result() == AuditResult.NO_MATCH);

            // 不变量三：warning 只属于「LENIENT + 真缺货 + 仍放行」
            boolean lenientWarning = row.setting().stockGuard() == AuditSetting.StockGuard.LENIENT
                    && shortByFacts && row.result() == AuditResult.AUTO_PASS;
            assertThat(stock.containsKey("warning"))
                    .as("[%s] stock.warning 只在 LENIENT 档 + 真的缺货 + 仍然 AUTO_PASS 时出现", row.id())
                    .isEqualTo(lenientWarning);
        }

        // ---------------- 不变量四：MANUAL 三支来路可区分（「五类口径」能数平的前提） ----------------
        if (row.result() == AuditResult.MANUAL) {
            if (row.downgradeReason() == null) {
                assertThat(v.excludedBy()).as("[%s] MANUAL 且无 downgrade → 唯一来路是「被排除」", row.id())
                        .isNotEmpty();
                assertThat(v.reasonText()).as("[%s] 被排除的原因文案要说明「被排除」", row.id()).contains("被排除");
            } else {
                assertThat(v.excludedBy()).as("[%s] 降级来的 MANUAL 不是被排除挡下来的", row.id()).isEmpty();
            }
            if (AuditVerdict.DOWNGRADE_STOCK_SHORT.equals(row.downgradeReason())) {
                assertThat(v.reasonText()).as("[%s] 库存降级必须给出「可用 / 需求」两个数", row.id())
                        .contains("可用 " + row.ctx().stockAvailable()).contains("需求 " + row.ctx().itemQtyTotal());
            }
        }

        // ---------------- 报告用原始行（跑完直接贴，不用手抄） ----------------
        System.out.println("[矩阵] " + row.id() + " " + row.combo()
                + " || 期望 " + row.result() + "/" + nz(row.matchedPolicyName()) + "/" + nz(row.downgradeReason())
                + "/推进=" + (row.advancesStatus() ? "是" : "否") + "/would_be=" + nz(row.wouldBePolicyName())
                + " ⇒ 实得 " + v.result() + "/" + nz(nameOf(v.matchedPolicy())) + "/" + nz(v.downgradeReason())
                + "/推进=" + (v.advancesStatus() ? "是" : "否") + "/would_be=" + nz(nameOf(v.wouldBePolicy())));
    }

    /**
     * 表自身的体检：行号唯一、每行都写了「为什么是这个结论」、五判定各自有基线、必备叠加态齐全。
     *
     * <p>没有这条，矩阵最容易的腐坏方式不是断言变红，而是<b>悄悄少了一行</b>：
     * 复制一行过去忘改单号 → 少一个组合，但整个类还是全绿。
     */
    @Test
    @DisplayName("矩阵自检：行号唯一、每行写清优先级、五判定各有基线、必备叠加态齐全")
    void matrixIsWellFormed() {
        List<Row> rows = rows();

        Set<String> ids = new HashSet<>();
        rows.forEach(r -> assertThat(ids.add(r.id())).as("行号 %s 重复（复制行忘改号 = 少测一个组合）", r.id()).isTrue());
        rows.forEach(r -> assertThat(r.why()).as("[%s] 必须写清「为什么是这个结论」（优先级说明）", r.id()).isNotBlank());

        assertThat(rows).as("矩阵至少 12 行，少了就说明组合没铺开").hasSizeGreaterThanOrEqualTo(12);

        for (AuditResult result : AuditResult.values()) {
            assertThat(rows).as("五判定口径里 %s 必须至少有一行", result).anyMatch(r -> r.result() == result);
        }

        // 必备叠加态：按「期望结论 + 关键输入事实」找行，缺任何一种就红
        assertThat(rows).as("必须有「已冻结 × 需库存预检 → SKIPPED」")
                .anyMatch(r -> r.result() == AuditResult.SKIPPED && r.ctx().frozen());
        assertThat(rows).as("必须有「状态非 7 × 策略全满足 → SKIPPED」")
                .anyMatch(r -> r.result() == AuditResult.SKIPPED && !r.ctx().frozen()
                        && r.ctx().status() != AuditContext.STATUS_PENDING_AUDIT);
        assertThat(rows).as("必须有「阻止命中 × 自动策略也满足 → BLOCKED 且回填 would_be」")
                .anyMatch(r -> r.result() == AuditResult.BLOCKED
                        && AuditScenario.POLICY_BLOCK_PROFIT.equals(r.matchedPolicyName())
                        && AuditScenario.POLICY_AUTO_TAOBAO.equals(r.wouldBePolicyName()));
        assertThat(rows).as("必须有「异常命中 × 阻止也命中」这一行（谁优先必须写在表里）")
                .anyMatch(r -> r.ctx().hasReturnApply() && r.ctx().grossProfit() != null
                        && r.ctx().grossProfit().signum() <= 0);
        assertThat(rows).as("必须有「异常策略的 priority 数字更小、结论仍是 BLOCKED」这一行"
                        + "（区分「类型顺序」与「全局 priority」两套口径）")
                .anyMatch(r -> r.result() == AuditResult.BLOCKED && r.ctx().hasReturnApply() && r.ctx().addressMulti());
        assertThat(rows).as("必须有「异常命中 × 需库存预检 → FROZEN」")
                .anyMatch(r -> r.result() == AuditResult.FROZEN && r.ctx().stockAvailable() < r.ctx().itemQtyTotal());
        assertThat(rows).as("必须有「不命中 × 需库存预检 → NO_MATCH」")
                .anyMatch(r -> r.result() == AuditResult.NO_MATCH && r.ctx().stockAvailable() < r.ctx().itemQtyTotal());
        assertThat(rows).as("必须有「自动策略满足 × 排除命中 × 需库存预检 → MANUAL」")
                .anyMatch(r -> r.result() == AuditResult.MANUAL && r.downgradeReason() == null
                        && r.ctx().csRemark() != null && r.ctx().stockAvailable() < r.ctx().itemQtyTotal());
        assertThat(rows).as("必须有「库存不足 STRICT → MANUAL + STOCK_SHORT」")
                .anyMatch(r -> r.result() == AuditResult.MANUAL
                        && AuditVerdict.DOWNGRADE_STOCK_SHORT.equals(r.downgradeReason())
                        && r.setting().stockGuard() == AuditSetting.StockGuard.STRICT);
        assertThat(rows).as("必须有「库存不足 LENIENT → AUTO_PASS + 告警」")
                .anyMatch(r -> r.result() == AuditResult.AUTO_PASS
                        && r.setting().stockGuard() == AuditSetting.StockGuard.LENIENT
                        && r.ctx().stockAvailable() < r.ctx().itemQtyTotal());
        assertThat(rows).as("必须有「多策略同时满足 → 取 priority 最小」")
                .anyMatch(r -> r.result() == AuditResult.AUTO_PASS
                        && AuditScenario.POLICY_AUTO_DIRECT.equals(r.matchedPolicyName()));
        assertThat(rows).as("必须有「停用策略不参与」")
                .anyMatch(r -> r.policies().stream().anyMatch(p -> !p.enabled()));
        assertThat(rows).as("必须有三种事实叠加的行（两个以上闸门同时适用）")
                .anyMatch(r -> r.ctx().frozen() && r.ctx().grossProfit() != null
                        && r.ctx().grossProfit().signum() <= 0 && r.result() == AuditResult.SKIPPED);
    }

    // ================================================================== 表的零件

    /**
     * 矩阵的一行。
     *
     * @param id                行号（M01…，唯一，报告里按它引用）
     * @param combo             输入组合（一句话说清哪几条规则同时适用）
     * @param why               为什么落这个结论（优先级说明 —— 这列是这张表真正的正文）
     * @param ctx               订单上下文
     * @param policies          策略集（停用 / 换档位靠它）
     * @param setting           设置快照（STRICT / LENIENT）
     * @param result            期望结论
     * @param matchedPolicyName 期望 matched_policy_name（null = 必须为空）
     * @param downgradeReason   期望 downgrade_reason（null = 必须为空）
     * @param advancesStatus    期望「状态是否推进」
     * @param wouldBePolicyName 期望 would_be_auto_policy_name（null = 必须为空）
     */
    record Row(String id, String combo, String why, AuditContext ctx, List<PolicyView> policies,
               AuditSetting setting, AuditResult result, String matchedPolicyName, String downgradeReason,
               boolean advancesStatus, String wouldBePolicyName) {

        @Override
        public String toString() {
            return id + " " + combo + " → " + result
                    + "（命中=" + nz(matchedPolicyName) + "，downgrade=" + nz(downgradeReason)
                    + "，would_be=" + nz(wouldBePolicyName) + "，推进状态=" + (advancesStatus ? "是" : "否") + "）";
        }
    }

    /** 行工厂（默认策略集 + STRICT 档：绝大多数行只关心上下文） */
    private static Row row(String id, String combo, String why, AuditContext ctx, AuditResult result,
                           String matchedPolicyName, String downgradeReason, boolean advancesStatus,
                           String wouldBePolicyName) {
        return new Row(id, combo, why, ctx, AuditScenario.policies(), AuditScenario.strict(), result,
                matchedPolicyName, downgradeReason, advancesStatus, wouldBePolicyName);
    }

    /** 行工厂（只换档位，策略集默认） */
    private static Row row(String id, String combo, String why, AuditContext ctx, AuditSetting setting,
                           AuditResult result, String matchedPolicyName, String downgradeReason,
                           boolean advancesStatus, String wouldBePolicyName) {
        return new Row(id, combo, why, ctx, AuditScenario.policies(), setting, result,
                matchedPolicyName, downgradeReason, advancesStatus, wouldBePolicyName);
    }

    /** 行工厂（策略集与档位都可换） */
    private static Row row(String id, String combo, String why, AuditContext ctx, List<PolicyView> policies,
                           AuditSetting setting, AuditResult result, String matchedPolicyName,
                           String downgradeReason, boolean advancesStatus, String wouldBePolicyName) {
        return new Row(id, combo, why, ctx, policies, setting, result, matchedPolicyName, downgradeReason,
                advancesStatus, wouldBePolicyName);
    }

    private static AuditScenario.Builder builder(Long id, String sn, int status, String channel, String amount) {
        return AuditScenario.builder(id, sn, status, channel, amount);
    }

    /** 已冻结 + 桶不够 + AUTO 条件全满足：三件事实同时成立，结论只能是 SKIPPED */
    private static AuditContext frozenShortStock(Long id, String sn) {
        return builder(id, sn, 7, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(8).itemKinds(1).stock(5, 999)
                .frozen(true, "FROZEN").build();
    }

    /** 状态 8 + 桶不够 + AUTO 条件全满足：状态闸门在一切之前 */
    private static AuditContext shippedShortStock(Long id, String sn) {
        return builder(id, sn, 8, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(8).itemKinds(1).stock(5, 999).build();
    }

    /** 直营-小单（prio 100）与淘宝-标准单（prio 200）同时满足：直营门店标签 + 渠道淘宝 + 268 元 */
    private static AuditContext bothAutoMatch(Long id, String sn) {
        return builder(id, sn, 7, "淘宝", "268.00")
                .customerTag("直营门店").grossProfit("62.00").itemQty(2).itemKinds(1).stock(100, 100).build();
    }

    /** 只把指定名字的策略置为停用（其余原样），用来证明「停用策略不参与判定」 */
    private static List<PolicyView> disable(String... names) {
        Set<String> off = Set.of(names);
        return AuditScenario.policies().stream()
                .map(p -> off.contains(p.name())
                        ? new PolicyView(p.id(), p.type(), p.priority(), p.name(), p.versionNo(), false,
                        p.action(), p.conditions())
                        : p)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stockOf(AuditVerdict v, Row row) {
        Object stock = v.evidence().get("stock");
        assertThat(stock).as("[%s] evidence.stock 应是对象（与 AUTO_PASS 那条链同口径）", row.id())
                .isInstanceOf(Map.class);
        return (Map<String, Object>) stock;
    }

    private static String nameOf(PolicyView policy) {
        return policy == null ? null : policy.name();
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }
}
