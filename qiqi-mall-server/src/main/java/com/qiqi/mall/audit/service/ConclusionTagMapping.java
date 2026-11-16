package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditVerdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这轮判成了什么 → 该落哪个标码」的<b>只读枚举</b>，专供一次性补标脚本
 * （{@code scripts/backfill-audit-tags.mjs}）读取。
 *
 * <h3>它为什么存在（而不让脚本自己写一张表）</h3>
 * 补标脚本必须回答的问题只有一个：<b>这条决策行的 result 该落哪个码</b>。这个答案在仓库里
 * 已经有一处，就是 {@link ConclusionTags#codeOf}（连同 {@link ConclusionTags#frozenCodeOf}）。
 * 若脚本自己在 JS 里再抄一份映射，两份就会漂：{@code ConclusionTags} 改了（例如 V9 把 AUTO_PASS
 * 的标收回去），脚本仍在按老规矩补 —— 补出来的标比没有标更糟，因为它冒充"引擎判过"。
 *
 * <p>所以脚本<b>不内嵌任何映射</b>：它先调 {@code GET /admin/order-audit/conclusion-tag-mapping}
 * 把这张表<b>从运行中的服务</b>取下来，再拿它去配 SQL 行。本类只做枚举这件事，
 * 每个格子都是**真的调用** {@link ConclusionTags#codeOf} 得到的（不是手抄的结果）：
 * 谁改 {@code codeOf}，这个端点立刻跟着变，漂不了。
 *
 * <h3>为什么必须逐 result 枚举，而不是只给五条"常用"</h3>
 * 反例就在眼前：V9 之后 {@code AUTO_PASS} 回 {@code null}（不落标）。若枚举表只列
 * "会落标的那四种"，脚本拿到 {@code AUTO_PASS} 的行会找不到映射 —— 最自然的补法就是
 * "找不到就按最近的一类兜底"，那正好补出用户 2026-11-16 16:38 明确否决的那枚"已通过"标。
 * 显式列出 {@code AUTO_PASS → null} 是**边界声明**：它写的是"这一类没有标"，不是"忘了写"。
 *
 * <h3>FROZEN 的码为什么只是"通用码"</h3>
 * 冻结类的码由策略 {@code action_json} 决定（{@code FROZEN_RETURN_APPLY} …），见
 * {@link ConclusionTags#frozenCodeOf}：策略没配才是通用 {@code FROZEN}。本端点不读策略表
 * （那是"按库现查"的事，不属于映射），所以 FROZEN 一档给的是<b>策略没配细分码时的答案</b>；
 * 脚本对 FROZEN 行会先去该单身上找现成的 {@code FROZEN%} 码，找不到才用这里的通用码，
 * 并在输出里标出来 —— 不静默降级。
 *
 * <h3>这不是业务接口</h3>
 * 只读、无库访问、无副作用：它回答的是"代码里的表长什么样"。任何业务方都不该读它。
 */
public final class ConclusionTagMapping {

    private ConclusionTagMapping() {
    }

    /** MANUAL 的三支来路（{@code downgrade_reason} 为空 / STOCK_SHORT / COST_MISSING），见 {@link ConclusionTags#codeOf} */
    private static final List<String> MANUAL_DOWNGRADES = List.of(
            "",                                         // 空串 = downgrade_reason IS NULL（被排除条件挡下）
            AuditVerdict.DOWNGRADE_STOCK_SHORT,
            AuditVerdict.DOWNGRADE_COST_MISSING);

    /**
     * 全部 (result, downgradeReason) → 标码 的格子。
     *
     * @return 每格 {@code {result, downgradeReason, code, producesTag}}；{@code code=null} 表示
     *         "这一类结论不落标"（AUTO_PASS / SKIPPED，见 {@link ConclusionTags#codeOf} 的注释）
     */
    public static List<Map<String, Object>> entries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditResult result : AuditResult.values()) {
            if (result == AuditResult.MANUAL) {
                for (String downgrade : MANUAL_DOWNGRADES) {
                    out.add(cell(result, downgrade));
                }
            } else {
                out.add(cell(result, ""));
            }
        }
        return out;
    }

    private static Map<String, Object> cell(AuditResult result, String downgrade) {
        // 直接问 ConclusionTags —— 这里不许出现任何"手写的码值"
        AuditVerdict verdict = new AuditVerdict(result, null, List.of(), List.of(), List.of(),
                null, null, downgrade == null || downgrade.isEmpty() ? null : downgrade, null, Map.of());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", result.name());
        m.put("downgradeReason", downgrade == null || downgrade.isEmpty() ? null : downgrade);
        m.put("code", ConclusionTags.codeOf(verdict));
        m.put("producesTag", ConclusionTags.producesTag(result));
        return m;
    }

    /**
     * 给补标脚本的完整答案袋：映射表 + 标记串 + 两条脚本必须遵守的边界。
     *
     * <p>{@code backfillReleasedBy} 从 {@link OrderAuditTagService#RELEASED_BY_BACKFILL} 取（不在这里
     * 再写一遍字符串）：脚本写库时用的就是这一个值，事后 {@code WHERE released_by=…} 才能查全。
     */
    public static Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frozenFamilyPrefix", OrderAuditTagService.TAG_FROZEN);
        out.put("backfillReleasedBy", OrderAuditTagService.RELEASED_BY_BACKFILL);
        out.put("entries", entries());
        out.put("notes", List.of(
                "code=null 表示这一类结论不落标（AUTO_PASS：通过由状态 8 表达，用户 2026-11-16 16:38 拍板；SKIPPED：不写决策行）",
                "FROZEN 的 code 是「策略没配细分码时」的通用码；细分码由策略 action_json 决定，脚本应先用该单已有的 FROZEN% 码",
                "映射的唯一出处是 ConclusionTags#codeOf（本端点是它的枚举投影，不是第二份表）"));
        out.put("internalOnly", true);
        out.put("internalNote", "一次性质检脚本的只读依赖：只回答「代码里的映射表长什么样」，不读库、不写库、无副作用。");
        return out;
    }
}
