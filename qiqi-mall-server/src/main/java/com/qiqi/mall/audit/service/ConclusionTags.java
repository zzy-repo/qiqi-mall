package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.engine.ActionSpec;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditVerdict;

/**
 * 判定结论 → 标码（期 1 的唯一映射表）。
 *
 * <h3>这张表存在的理由</h3>
 * 在此之前，标是引擎的<b>私有开关</b>：AUTO_PASS 分支顺手解掉两个挂起标、FROZEN 分支顺手打一个冻结标，
 * 而 NO_MATCH / BLOCKED / MANUAL（被排除）三条路一个标都不落 —— 于是队列里一片空白，
 * 业务人员只能读英文 result 码去猜"这单怎么了"。
 *
 * <p>现在：<b>每种结论都落一个可读、可筛、可聚合的标</b>，落标只在这一处决定，
 * 谁想加一类结论就只有这一个地方要改（漏了就会在 {@code AuditExecutor} 的计数自洽校验旁边暴露）。
 *
 * <table border="1">
 *   <caption>结论 → 标</caption>
 *   <tr><th>result</th><th>落什么标</th><th>为什么</th></tr>
 *   <tr><td>AUTO_PASS</td><td><b>不落标</b></td>
 *       <td>自动过审等于"什么都没发生"，落上去就是噪声（一屏 200 条"自动过审"标会把真正要看的 5 条淹掉）。
 *           注意：这是「AUTO_PASS 这个<b>结论</b>不产标」，<b>不等于</b>"自动过审的单身上不能有标" ——
 *           特征标（客诉客户/绿旗急发/双地址…）与"过没过"无关，这种单打了标照样自动过审。</td></tr>
 *   <tr><td>MANUAL</td><td>AUDIT_PENDING / STOCK_SHORT / COST_MISSING</td>
 *       <td>MANUAL 有三支来路，result 上是同一个值，靠标把它们拆开：
 *           被排除条件挡下 → AUDIT_PENDING；库存预检降级 → STOCK_SHORT；成本缺失降级 → COST_MISSING。
 *           拆开的理由与 P2 拆冻结原因同源：{@code result} 只能回答"留人工"，
 *           回答不了"留人工是为了什么"。</td></tr>
 *   <tr><td>BLOCKED</td><td>BLOCKED</td><td>拦是结论不是动作，订单状态不动，只有标能证明这一轮拦过它。</td></tr>
 *   <tr><td>NO_MATCH</td><td>NO_POLICY_MATCH</td>
 *       <td><b>本期最大的缺口</b>：现在 18 条空白单全是这一类，一个标都没有。落上之后
 *           {@code ?tag=NO_POLICY_MATCH} 就能一次筛出"系统不知道该拿它怎么办"的全部单。</td></tr>
 *   <tr><td>FROZEN</td><td>FROZEN（或策略配的细分码 {@code FROZEN_RETURN_APPLY}…）</td>
 *       <td>由冻结分支（{@code OrderAuditTagService#freeze}）落，它还要带 {@code reason_id} 字典 ——
 *           <b>写入</b>只在那一条路上做，本表只回答"这轮冻结落的<b>码</b>是什么"。
 *           为什么本表必须回答它（期 1.6 修正）：冻结<b>也是</b>「当前结论」，不参与结论唯一性，
 *           一单就会同时挂 NO_POLICY_MATCH + FROZEN（两条都是 category='结论'）。</td></tr>
 *   <tr><td>SKIPPED</td><td>{@code null}</td>
 *       <td>跳过的单不写判定记录（见 {@code AuditExecutor} 类注释），也就不落标：
 *           它的"为什么"已经由那条 active 的冻结标回答了。</td></tr>
 * </table>
 *
 * <h3>⚠️ 结论标互斥（期 1.5 立规矩，期 1.6 补齐全部路径）：同一时刻一单只有一个 active 的结论标</h3>
 * 结论标描述的是"<b>当前</b>判定结论"，而当前结论天然只有一个 —— 一单不可能同时是
 * "无策略命中"和"待人工审核"。所以每次落新结论标前，先把该单其他 active 的结论档标置
 * {@code active=0} 并记 {@code released_by=SYSTEM:SUPERSEDED}（见 {@code AuditExecutor#settleConclusionTag}、
 * {@code OrderAuditTagService#supersedeOtherConclusionTags}）。
 *
 * <p><b>期 1.5 只覆盖了「引擎落新标」这一条路</b>，实测漏了三条（期 1.6 补）：
 * ① 人工冻结走 {@code OrderAuditTagService#freeze}，以前从不 supersede ⇒「先 NO_MATCH 再人工冻」
 * 的单并挂 NO_POLICY_MATCH + FROZEN；② 本表的 {@code codeOf(FROZEN)} 以前返回 {@code null}
 * ⇒ 策略冻结也不参与唯一性；③ 人工放行整条链（7→8）一处释放都没有 ⇒ 放行后还挂着
 * AUDIT_PENDING / BLOCKED（blocking=1），下游按"结论标 + blocking"一刀切就会把已经放行的单锁死。
 * 现在四条路（引擎落标 / 人工冻结 / 策略冻结 / 离开待审核结算）都收敛到同一条互斥规则上。
 *
 * <p><b>这不是"删历史"</b>：只增不删管的是"事实"（行不删、解除留痕），不管"当前状态标"。
 * 不做互斥的后果（期 1 实测过）：一单从 NO_MATCH 变 MANUAL 后两个标并列 active，
 * {@code ?tag=NO_POLICY_MATCH} 把它算进两类，"这个月多少单无策略命中"被高估。
 *
 * <p><b>特征标不参与互斥</b>：一单可以既刷单又客诉，那些标描述"这单是什么样"，与"过没过"无关。
 *
 * <h3>⚠️ 本期只落「结论」档，不落「特征」档</h3>
 * 特征标来自标记策略 / 平台旗帜 / 人工打标 —— 打标发生在<b>建单或人工</b>那一刻，不是审核的产物
 * （吉客云原话：这些单打了标照样自动审核通过）。所以这里一个特征标都不该出现；
 * 特征标的落标路径是期 2 的检测点/标记策略。
 */
public final class ConclusionTags {

    private ConclusionTags() {
    }

    /**
     * 这条结论该落哪个标码。
     *
     * @return 标码；{@code null} 表示这条结论不落标（AUTO_PASS / SKIPPED，见类注释）。
     *         注意 FROZEN 也返回值（{@code FROZEN} 或策略配的细分码）：冻结是结论，
     *         它必须参与「一单只有一个当前结论」这条唯一性规则。
     */
    public static String codeOf(AuditVerdict verdict) {
        if (verdict == null || verdict.result() == null) return null;
        return switch (verdict.result()) {
            case MANUAL -> {
                if (AuditVerdict.DOWNGRADE_STOCK_SHORT.equals(verdict.downgradeReason())) {
                    yield AuditTagCatalogService.CODE_STOCK_SHORT;
                }
                if (AuditVerdict.DOWNGRADE_COST_MISSING.equals(verdict.downgradeReason())) {
                    yield AuditTagCatalogService.CODE_COST_MISSING;
                }
                yield AuditTagCatalogService.CODE_AUDIT_PENDING;
            }
            case BLOCKED -> AuditTagCatalogService.CODE_BLOCKED;
            case NO_MATCH -> AuditTagCatalogService.CODE_NO_POLICY_MATCH;
            case FROZEN -> frozenCodeOf(verdict);
            case AUTO_PASS, SKIPPED -> null;
        };
    }

    /**
     * 冻结这一轮落的**码**：策略 {@code action_json} 配了细分码就用它（{@code FROZEN_RETURN_APPLY}…），
     * 没配就是 {@code FROZEN}。
     *
     * <p>为什么必须回具体码而不是恒回 {@code FROZEN}：冻结标的写入方是
     * {@code OrderAuditTagService#freeze}（它要同时写 {@code reason_id} / {@code reason_code}），
     * 两边必须说同一个码 —— 若这里恒回 {@code FROZEN}，而 freeze 落的是 {@code FROZEN_RETURN_APPLY}，
     * 互斥步骤就会把刚落的细分码当成"别的结论"挤掉，细分原因当场丢失（队列里只剩一个没有原因的 FROZEN）。
     *
     * <p><b>细分码与 FROZEN 是同一个结论的粗细两种说法，不是两个结论</b>：
     * 互斥按「FROZEN 家族」整族算一类（{@code OrderAuditTagService#supersedeOtherConclusionTags}），
     * 与解冻「按前缀一次放掉」的判据同口径。
     */
    public static String frozenCodeOf(AuditVerdict verdict) {
        ActionSpec action = verdict == null || verdict.matchedPolicy() == null
                ? null : verdict.matchedPolicy().action();
        String configured = action == null ? null : action.tagCode();
        return configured == null || configured.isBlank()
                ? AuditTagCatalogService.CODE_FROZEN
                : configured.trim();
    }

    /** 供文档/测试引用：落在 order_audit_tag 上的全部结论标码（FROZEN 由冻结分支独占写入） */
    public static java.util.List<String> conclusionCodesWrittenHere() {
        return java.util.List.of(AuditTagCatalogService.CODE_AUDIT_PENDING,
                AuditTagCatalogService.CODE_STOCK_SHORT,
                AuditTagCatalogService.CODE_COST_MISSING,
                AuditTagCatalogService.CODE_BLOCKED,
                AuditTagCatalogService.CODE_NO_POLICY_MATCH);
    }

    /**
     * {@link AuditResult} 的哪几类<b>会落标</b>（AUTO_PASS / SKIPPED 不落）。
     *
     * <p>期 1.6 起 FROZEN 也算：它落 FROZEN 类标（写入方是 freeze，但"这条结论产标"是真的）。
     */
    public static boolean producesTag(AuditResult result) {
        return result == AuditResult.MANUAL || result == AuditResult.BLOCKED
                || result == AuditResult.NO_MATCH || result == AuditResult.FROZEN;
    }
}
