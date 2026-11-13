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
 * [6] 全都不中 → NO_MATCH（逐条「差在哪」）
 * [7] 库存预检（仅 AUTO_PASS）：SUM(sku_bucket.stock) &gt;= 需求，不足 → MANUAL + STOCK_SHORT
 *     成本预检：毛利 ≤ 0 而成本价缺失 → MANUAL + COST_MISSING（风险③：不许沉默放行）
 * </pre>
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

            // 5a 条件没全满足 → 记「差在哪」
            if (!failed.isEmpty()) {
                misses.add(new PolicyMiss(policy.id(), policy.name(), failed.stream().map(ConditionTrace::text).toList()));
                continue;
            }

            // 5b 排除条件：本策略 POLICY 级 ∪ 全部 ALL_AUTO 级
            List<ConditionTrace> excluded = new ArrayList<>(satisfied(ctx, policy.policyExclusions()));
            excluded.addAll(satisfied(ctx, globalExclusions));
            if (!excluded.isEmpty()) {
                misses.add(new PolicyMiss(policy.id(), policy.name(),
                        excluded.stream().map(t -> "被排除：" + t.text()).toList()));
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
                evidence(ctx, s, "NO_MATCH", WouldBe.none(), null));
    }

    // ================================================================== [7] 预检 + 结论

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
        if (ctx.stockAvailable() < ctx.itemQtyTotal()) {
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
