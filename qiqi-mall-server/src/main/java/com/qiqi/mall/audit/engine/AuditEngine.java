package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单自动审核引擎：<b>纯函数</b> —— 输入 (订单上下文, 策略列表, 设置)，输出判定。
 * 不碰库、不读时钟、不发 SQL、不写台账。执行层（{@code AuditExecutor}）负责把这些结论
 * 落成决策行 / 状态流转 / 打标 / 审计。
 *
 * <h3>判定链（顺序固定，不可插队）</h3>
 * <pre>
 * [1] status != 7                      → SKIPPED
 * [2] 已冻结（active tag=FROZEN 类）    → SKIPPED
 * [3] BLOCK 命中（同类内 priority 升序）→ BLOCKED（原因带具体数值差）
 * [4] EXCEPTION 命中                   → FROZEN（打标 + 冻结）
 *     [3][4] 命中后**仍继续跑 [5]，只看不执行**，回填 would_be_auto_policy
 * [5] AUTO（priority 升序）逐条：
 *     5a MATCH 条件没全满足 → 记 miss_reason（哪条、expected vs actual）
 *     5b 排除条件（本策略 POLICY 级 ∪ 全部 ALL_AUTO 级）任一成立 → 记 excluded_by
 *     5c 无排除 → AUTO_PASS
 * [6] 全都不中 → 若曾有策略「条件全满足、只被排除挡掉」→ MANUAL（被排除）；否则 NO_MATCH（逐条「差在哪」）
 * [7a] AUTO 候选的预检（**可改结论**，AUTO_PASS 专用分支）：
 *     库存 SUM(sku_bucket.stock) &gt;= 需求，不足 → MANUAL + STOCK_SHORT；
 *     成本：毛利 ≤ 0 而成本价缺失 → MANUAL + COST_MISSING（风险③：不许沉默放行）
 * [7b] NO_MATCH 的库存预检（**只写证据、不改结论**）：见 {@link #noMatchEvidence}
 * </pre>
 *
 * <h3>[7b] 为什么 NO_MATCH 也要查一次库存（但不许动结论）</h3>
 * NO_MATCH 的单最终也是「人来看」，而人看的第一眼最容易被误导：订单页显示 sku.stock=999、
 * 于是点「通过」，然后在扣减那一步才发现桶里只有 5 件。所以判定完 NO_MATCH 之后<b>再查一次库存</b>，
 * 把「缺货」和「若人工审核通过将因缺货失败」写进 {@code evidence_json}，让下一个人一眼看见。
 *
 * <p>但这一步<b>绝不能</b>复用 [7a] 那个「库存不足 → 降级 MANUAL + STOCK_SHORT」的分支：
 * 那条分支回答的是「本来该自动过审的单能不能放行」，命中它的单 result 会从 AUTO_PASS 变成 MANUAL、
 * 打 STOCK_SHORT 挂起标。NO_MATCH 的单根本没进自动过审，没有「放行」可降级 —— 把它的 result
 * 改写成 MANUAL/STOCK_SHORT 会让「不命中」这一类凭空少掉一笔（对外五类计数就此对不平），
 * 而且会给一张连策略都没命中的单打上「库存不足」的业务标。所以 [7b] 只往证据里写字。
 *
 * <h3>为什么 [3][4] 之后还要跑 AUTO</h3>
 * 被拦截/冻结的单，运营第一句话是「那它本来会不会自动过？」。这个答案只能靠**再跑一遍但要丢弃结论**
 * 得到；靠猜（比如「金额小所以应该会」）不算证据。因此回填的 would-be 与真实执行严格分离：
 * 命中方式完全一样，但结果只写进决策行的回填列，不推进任何状态。
 *
 * <h3>为什么排除要分两级</h3>
 * POLICY 级 = 这条策略自己的例外；ALL_AUTO 级 = 所有 AUTO 策略共用的全局例外（决策 C1：
 * 「客服备注含改」）。全局例外必须在评估**每一条** AUTO 策略时都并进来 —— 否则它就成了
 * 「只有配置它的那条策略才认」的策略级排除，与设计相反。
 */
public final class AuditEngine {

    /** 同类策略按 priority 升序；priority 相同按 id 升序（保证同配置两次跑结果一致） */
    private static final Comparator<PolicyView> BY_PRIORITY =
            Comparator.comparingInt(PolicyView::priority).thenComparing(p -> p.id() == null ? 0L : p.id());

    /**
     * 判定一次。
     *
     * @param ctx      订单上下文（执行层装配好；引擎只读）
     * @param policies 全部策略（含停用的；停用在这里被过滤）
     * @param setting  设置快照（null = 默认：开关开、库存严格档）
     */
    public AuditVerdict evaluate(AuditContext ctx, List<PolicyView> policies, AuditSetting setting) {
        AuditSetting s = setting == null ? AuditSetting.defaults() : setting;
        List<PolicyView> active = policies == null ? List.of()
                : policies.stream().filter(PolicyView::enabled).toList();

        // ---------------- [1] 状态闸门 ----------------
        if (ctx.status() == null || ctx.status() != AuditContext.STATUS_PENDING_AUDIT) {
            return skipped(ctx, "当前状态 " + ctx.status() + " ≠ 7（待审核），不参与本轮");
        }

        // ---------------- [2] 冻结闸门 ----------------
        if (ctx.frozen()) {
            String tag = ctx.frozenTag() == null ? ActionSpec.DEFAULT_TAG : ctx.frozenTag();
            return skipped(ctx, "订单已被冻结（active tag=" + tag + "），本轮跳过，需人工解冻后重新判定");
        }

        // 全局排除（ALL_AUTO）：与具体策略无关，评估每条 AUTO 时都要并进来
        List<ConditionView> globalExclusions = active.stream()
                .flatMap(p -> p.conditions().stream())
                .filter(c -> c.scope() == ConditionScope.ALL_AUTO && c.kind() == ConditionKind.EXCLUDE)
                // 装载时全局排除行会挂到**每条** AUTO 策略上（DB 里 policy_id 为 NULL，没有归属策略可挂），
                // 这里按「行的内容」去重：同一个全局排除只评估一次，excluded_by 里也不会重复两条。
                .distinct()
                .sorted(Comparator.comparingInt(c -> c.seq() == null ? 0 : c.seq()))
                .toList();

        // ---------------- [3] BLOCK 前置拦截 ----------------
        for (PolicyView policy : sorted(active, PolicyType.BLOCK)) {
            List<ConditionTrace> traces = evalMatches(ctx, policy);
            if (allSatisfied(traces)) {
                WouldBe wouldBe = wouldBe(ctx, active, globalExclusions);
                return new AuditVerdict(AuditResult.BLOCKED, policy, traces, List.of(), List.of(),
                        wouldBe.policy(), wouldBe.conditions(), null,
                        "拦截：策略「" + policy.name() + "」命中 —— " + join(traces),
                        evidence(ctx, s, "BLOCKED", wouldBe, null));
            }
        }

        // ---------------- [4] EXCEPTION 异常冻结 ----------------
        for (PolicyView policy : sorted(active, PolicyType.EXCEPTION)) {
            List<ConditionTrace> traces = evalMatches(ctx, policy);
            if (allSatisfied(traces)) {
                WouldBe wouldBe = wouldBe(ctx, active, globalExclusions);
                ActionSpec action = policy.action() == null ? ActionSpec.freeze("异常策略命中：" + policy.name()) : policy.action();
                return new AuditVerdict(AuditResult.FROZEN, policy, traces, List.of(), List.of(),
                        wouldBe.policy(), wouldBe.conditions(), null,
                        "冻结：策略「" + policy.name() + "」命中 —— " + join(traces),
                        evidence(ctx, s, "FROZEN", wouldBe, action));
            }
        }

        // ---------------- [5] AUTO 逐条 ----------------
        List<PolicyMiss> misses = new ArrayList<>();
        PolicyView firstExcluded = null;
        List<ConditionTrace> firstExcludedBy = List.of();

        for (PolicyView policy : sorted(active, PolicyType.AUTO)) {
            List<ConditionTrace> traces = evalMatches(ctx, policy);
            List<ConditionTrace> failed = traces.stream().filter(t -> !t.satisfied()).toList();

            // 5a 条件没全满足 → 记「差在哪」（逐条件四元组 + 人话摘要）
            if (!failed.isEmpty()) {
                misses.add(new PolicyMiss(policy.id(), policy.name(),
                        failed.stream().map(ConditionTrace::text).toList(),
                        andChain(traces)));
                continue;
            }

            // 5b 排除条件：本策略 POLICY 级 ∪ 全部 ALL_AUTO 级
            List<ConditionTrace> excluded = new ArrayList<>(satisfied(ctx, policy.policyExclusions()));
            excluded.addAll(satisfied(ctx, globalExclusions));
            if (!excluded.isEmpty()) {
                // 条件全满足、只是被例外挡掉：留痕要把「满足的 MATCH」和「成立的排除」都摆出来，
                // 否则运营只看到「被排除」四个字，仍然不知道是备注还是城市把它挡下来的。
                List<ConditionTrace> trace = new ArrayList<>(traces);
                trace.addAll(excluded);
                misses.add(new PolicyMiss(policy.id(), policy.name(),
                        excluded.stream().map(t -> "被排除：" + t.text()).toList(),
                        trace));
                if (firstExcluded == null) {
                    firstExcluded = policy;
                    firstExcludedBy = List.copyOf(excluded);
                }
                continue;
            }

            // 5c 无排除 → 自动过审候选（还要过 [7] 预检）
            return autoPassOrDowngrade(ctx, s, policy, traces, misses);
        }

        // ---------------- [6] 全都不中 ----------------
        if (firstExcluded != null) {
            // 命中过策略、只是被排除 → MANUAL（而不是 NO_MATCH）：
            // 「差在哪」与「配了但被例外挡掉」是两件不同的事，运营的处理动作也不同。
            return new AuditVerdict(AuditResult.MANUAL, firstExcluded, List.of(), firstExcludedBy, misses,
                    null, List.of(), null,
                    "转人工：策略「" + firstExcluded.name() + "」条件满足但被排除 —— " + join(firstExcludedBy),
                    evidence(ctx, s, "MANUAL", WouldBe.none(), null));
        }
        String detail = misses.isEmpty() ? "没有启用任何 AUTO 策略"
                : misses.stream().map(m -> m.policyName() + "：" + String.join("；", m.reasons())).reduce((a, b) -> a + " | " + b).orElse("");
        return new AuditVerdict(AuditResult.NO_MATCH, null, List.of(), List.of(), misses,
                null, List.of(), null, "无匹配：没有任何 AUTO 策略命中 —— " + detail,
                noMatchEvidence(ctx, s));
    }

    // ================================================================== [7a] 预检 + 结论（AUTO_PASS 专用）

    private AuditVerdict autoPassOrDowngrade(AuditContext ctx, AuditSetting setting, PolicyView policy,
                                             List<ConditionTrace> traces, List<PolicyMiss> misses) {
        // 风险③：毛利 ≤ 0（或没算过）而成本价缺失（=0/NULL）→ 不许沉默放行
        BigDecimal profit = ctx.grossProfit();
        boolean profitNonPositive = profit == null || profit.signum() <= 0;
        if (profitNonPositive && ctx.costMissing()) {
            String text = "转人工：毛利 " + Fmt.num(profit, true) + " ≤ 0 且成本价缺失（cost_price=0/NULL），"
                    + "无法确认毛利是否真实（策略「" + policy.name() + "」其余条件满足）";
            return new AuditVerdict(AuditResult.MANUAL, policy, traces, List.of(), misses,
                    null, List.of(), AuditVerdict.DOWNGRADE_COST_MISSING, text,
                    evidence(ctx, setting, "MANUAL", WouldBe.none(), null));
        }

        // 库存预检：判据是 SUM(sku_bucket.stock)，不是 sku.stock 缓存（决策 D5）
        if (stockShort(ctx)) {
            String text = "转人工：库存不足 —— 可用 " + ctx.stockAvailable() + " < 需求 " + ctx.itemQtyTotal()
                    + "（判据 SUM(sku_bucket.stock)=" + ctx.stockAvailable()
                    + "；sku.stock 缓存=" + (ctx.skuStockCache() == null ? "无值" : ctx.skuStockCache()) + "）";
            if (setting.stockGuard() == AuditSetting.StockGuard.STRICT) {
                return new AuditVerdict(AuditResult.MANUAL, policy, traces, List.of(), misses,
                        null, List.of(), AuditVerdict.DOWNGRADE_STOCK_SHORT, text,
                        evidence(ctx, setting, "MANUAL", WouldBe.none(), null));
            }
            // LENIENT：不阻断（扣减 SQL 仍是防超卖的最终闸门），但把告警留在证据里
            Map<String, Object> ev = evidence(ctx, setting, "AUTO_PASS", WouldBe.none(), null);
            @SuppressWarnings("unchecked")
            Map<String, Object> stock = (Map<String, Object>) ev.get("stock");
            stock.put("warning", "LENIENT 档：可用 " + ctx.stockAvailable() + " < 需求 " + ctx.itemQtyTotal() + "，仍放行");
            return new AuditVerdict(AuditResult.AUTO_PASS, policy, traces, List.of(), misses,
                    null, List.of(), null,
                    "自动过审：策略「" + policy.name() + "」全部条件满足 —— " + join(traces),
                    ev);
        }

        return new AuditVerdict(AuditResult.AUTO_PASS, policy, traces, List.of(), misses,
                null, List.of(), null,
                "自动过审：策略「" + policy.name() + "」全部条件满足 —— " + join(traces),
                evidence(ctx, setting, "AUTO_PASS", WouldBe.none(), null));
    }

    // ================================================================== [7b] NO_MATCH 的只读库存预检

    /**
     * 判定链 [7b]：<b>不命中的单也查一次库存</b>，结论一个字不改（result 仍 NO_MATCH、
     * downgrade_reason 仍 null、不推进状态、不打任何挂起标）。
     *
     * <p>与 [7a]（{@link #autoPassOrDowngrade}）的区别只在一个字：那条会<b>改结论</b>
     * （AUTO_PASS → MANUAL + STOCK_SHORT），本条<b>只写证据</b>。所以这里不读
     * {@link AuditSetting.StockGuard}：LENIENT 表示「缺口也允许放行」，而这里根本没有放行可言，
     * 有缺口就是有缺口，照实写（档位仍原样记在 {@code stock.guard} 里，读证据的人自己判断）。
     *
     * <p>证据形状与 AUTO_PASS 那条链同口径（同一个 {@code stock} 段：bucket_sum / sku_stock_cache /
     * demand / guard / short），多的只有 {@code stock.hint} 这句人话 —— 运营看的是同一块面板，
     * 两处字段名不同就等于两套读法。
     */
    private Map<String, Object> noMatchEvidence(AuditContext ctx, AuditSetting setting) {
        Map<String, Object> ev = evidence(ctx, setting, "NO_MATCH", WouldBe.none(), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> stock = (Map<String, Object>) ev.get("stock");
        String hint;
        if (stockShort(ctx)) {
            hint = "库存预检：缺货 —— 可用 " + ctx.stockAvailable() + " < 需求 " + ctx.itemQtyTotal()
                    + "（判据 SUM(sku_bucket.stock)），若人工审核通过将因缺货失败；"
                    + "本单结论仍是 NO_MATCH（预检只写证据，不改判定、不打标）";
        } else {
            hint = "库存预检：可用 " + ctx.stockAvailable() + " ≥ 需求 " + ctx.itemQtyTotal()
                    + "（判据 SUM(sku_bucket.stock)），人工审核不会因缺货失败";
        }
        stock.put("hint", hint);
        return ev;
    }

    /** 库存是否不足以整单发出（[7a][7b] 共用的唯一判据，避免两处各写一遍比较） */
    private static boolean stockShort(AuditContext ctx) {
        return ctx.stockAvailable() < ctx.itemQtyTotal();
    }

    // ================================================================== 回填（只看不执行）

    private record WouldBe(PolicyView policy, List<ConditionTrace> conditions) {
        static WouldBe none() {
            return new WouldBe(null, List.of());
        }
    }

    private WouldBe wouldBe(AuditContext ctx, List<PolicyView> active, List<ConditionView> globalExclusions) {
        for (PolicyView policy : sorted(active, PolicyType.AUTO)) {
            List<ConditionTrace> traces = evalMatches(ctx, policy);
            if (!allSatisfied(traces)) continue;
            List<ConditionTrace> excluded = new ArrayList<>(satisfied(ctx, policy.policyExclusions()));
            excluded.addAll(satisfied(ctx, globalExclusions));
            if (!excluded.isEmpty()) continue;      // 会被排除的，本来也不会自动过审 → 不算 would-be
            return new WouldBe(policy, traces);
        }
        return WouldBe.none();
    }

    // ================================================================== 工具

    private static List<PolicyView> sorted(List<PolicyView> policies, PolicyType type) {
        return policies.stream().filter(p -> p.type() == type).sorted(BY_PRIORITY).toList();
    }

    /** 逐条求值（**全部求值**，不只求到第一个不满足 —— miss_reason 要说清「差在哪几条」） */
    private static List<ConditionTrace> evalMatches(AuditContext ctx, PolicyView policy) {
        return policy.matchConditions().stream().map(c -> ConditionEvaluator.evaluate(ctx, c)).toList();
    }

    /** 只保留**满足**的条件（排除条件用：不满足的排除不影响判定） */
    private static List<ConditionTrace> satisfied(AuditContext ctx, List<ConditionView> conditions) {
        return conditions.stream().map(c -> ConditionEvaluator.evaluate(ctx, c))
                .filter(ConditionTrace::satisfied).toList();
    }

    private static boolean allSatisfied(List<ConditionTrace> traces) {
        return traces.stream().allMatch(ConditionTrace::satisfied);
    }

    /**
     * 按 <b>AND 链</b>标 {@code evaluated}：首个不满足之后的条件标 {@code evaluated=false}。
     *
     * <p>值不重算也不清空 —— 引擎确实把每条条件都求过值（{@link #evalMatches} 不做短路，
     * 「差在哪几条」要一次说清）。{@code evaluated=false} 表达的是「这条<b>不再决定结论</b>」，
     * 不是「值不可信」；前端据此把它画成灰色「未求值」，而不是红色「不满足」。
     * 不这么标，前端就只能自己按 AND 规则猜，猜错就把没比过的条件说成比过且不满足。
     */
    static List<ConditionTrace> andChain(List<ConditionTrace> traces) {
        boolean broken = false;
        List<ConditionTrace> out = new ArrayList<>(traces.size());
        for (ConditionTrace t : traces) {
            out.add(t.withEvaluated(!broken));
            if (!t.satisfied()) broken = true;
        }
        return out;
    }

    private static String join(List<ConditionTrace> traces) {
        return traces.stream().map(ConditionTrace::text).reduce((a, b) -> a + "；" + b).orElse("");
    }

    private AuditVerdict skipped(AuditContext ctx, String reason) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("reason", reason);
        ev.put("order_status", ctx.status());
        ev.put("frozen", ctx.frozen());
        ev.put("frozen_tag", ctx.frozenTag());
        return new AuditVerdict(AuditResult.SKIPPED, null, List.of(), List.of(), List.of(),
                null, List.of(), null, reason, ev);
    }

    private Map<String, Object> evidence(AuditContext ctx, AuditSetting setting, String result,
                                         WouldBe wouldBe, ActionSpec action) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("result", result);

        Map<String, Object> stock = new LinkedHashMap<>();
        stock.put("bucket_sum", ctx.stockAvailable());
        stock.put("sku_stock_cache", ctx.skuStockCache());
        stock.put("demand", ctx.itemQtyTotal());
        stock.put("guard", setting.stockGuard().name());
        // 判定结论之外最容易被误读的一个事实：只写「桶里够不够」，由各分支自己决定怎么用
        stock.put("short", stockShort(ctx));
        ev.put("stock", stock);

        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("gross_profit", ctx.grossProfit());
        cost.put("cost_missing", ctx.costMissing());
        ev.put("cost", cost);

        if (wouldBe != null && wouldBe.policy() != null) {
            Map<String, Object> wb = new LinkedHashMap<>();
            wb.put("policy_id", wouldBe.policy().id());
            wb.put("policy_name", wouldBe.policy().name());
            wb.put("conditions", wouldBe.conditions().stream().map(ConditionTrace::toMap).toList());
            ev.put("would_be", wb);
        }
        if (action != null) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("tag", action.tagCode());
            act.put("reason", action.reason());
            ev.put("action", act);
        }
        return ev;
    }
}
