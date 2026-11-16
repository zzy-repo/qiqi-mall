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
 *   <tr><td>AUTO_PASS</td><td>AUTO_PASSED（<b>visible=0</b>）</td>
 *       <td><b>期 3 步骤 1 改口径</b>（推翻期 1 的「AUTO_PASS 不落标」）：
 *           期 1 那条理由针对的是<b>显示</b>（一屏 200 条"自动过审"标会把真正要看的 5 条淹掉），
 *           而期 3 要让「结论从标读」成立 —— 最常见的那个结论若是空的，"标驱动结论"就在这批单上给不出答案，
 *           只能回退读决策行（把刚切掉的耦合从后门接回来）。所以：<b>落标（事实），但 visible=0（不显示）</b>。
 *           注意：这是「AUTO_PASS 这个<b>结论</b>产出标」，不等于"自动过审的单身上只会有一个标" ——
 *           特征标（客诉客户/绿旗急发/双地址…）与"过没过"无关，可以并存。</td></tr>
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
 *
 * <h3>⚠️ 期 3 步骤 2.5：结论标有两个"轴"，别混（混了就会把历史单误判成缺口）</h3>
 * <ul>
 *   <li><b>判定轴</b>（{@link #codeOf}）：这一轮引擎判成了什么 —— MANUAL / BLOCKED / NO_MATCH /
 *       FROZEN / AUTO_PASS，与 {@code order_audit_decision.result} 必须逐单对得上；</li>
 *   <li><b>终局轴</b>（{@link #terminalCodeOf}）：离开待审核之后它最后怎么了 ——
 *       {@code AUDIT_PASSED}/{@code AUDIT_REJECTED}/{@code RETURN_APPLIED}（+ {@code AUTO_PASSED}）。
 *       这是状态答不出的那半句，<b>不与 result 比</b>。</li>
 * </ul>
 * 两条轴共用同一份"一单只有一个 active 结论标"的互斥规则（结算时先 release 再落终局标），
 * 但它们回答的是两个问题 —— 对账端点按轴分流，正是为了不把"AUTO_PASS 判了但人工关单"
 * 这种完全正常的数据算成"标与决策冲突"。
 */
public final class ConclusionTags {

    private ConclusionTags() {
    }

    /**
     * 这条结论该落哪个标码。
     *
     * @return 标码；{@code null} 表示这条结论不落标（只剩 SKIPPED，见类注释）。
     *         期 3 起 AUTO_PASS 回 {@code AUTO_PASSED}（visible=0：有事实、不显示）。
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
            case AUTO_PASS -> AuditTagCatalogService.CODE_AUTO_PASSED;
            // SKIPPED：不写决策行、不落标 —— 它"为什么没判"由那条 active 的冻结标回答
            case SKIPPED -> null;
        };
    }

    /**
     * 标码 → 结论值（{@link #codeOf} 的<b>反函数</b>，期 3 步骤 2 的"影子字段"用它）。
     *
     * <h3>为什么必须和 {@link #codeOf} 放在同一个文件里</h3>
     * 正向（结论 → 标）与反向（标 → 结论）是同一张表的两个方向。分散在两处，加一类结论时
     * 一定会出现"正向改了、反向忘改"—— 那时影子字段会算出一个**不存在的结论**，
     * 而它恰好是对账端点的判据，于是"零不一致"变成谎言。
     *
     * <h3>为什么认前缀 FROZEN，而不只认等值</h3>
     * 策略可以配细分冻结码（{@code FROZEN_RETURN_APPLY}…），它与 {@code FROZEN} 是同一个结论的粗细两种说法
     * （见 {@link #frozenCodeOf}）。只认等值会把"冻着的单"读成"没有结论"。
     *
     * @return 结论；<b>不认识的码回 {@link java.util.Optional#empty()}</b> ——
     *         宁可承认"读不出"，也不拿 NO_MATCH 之类最像的值兜底（那是伪造结论，
     *         会让对账把"没结论"算成"结论一致"）。
     *         <p>⚠️ 期 3 步骤 2.5 起，<b>终局标</b>（{@code AUDIT_PASSED}/{@code AUDIT_REJECTED}/
     *         {@code RETURN_APPLIED}）<b>故意回 empty</b>：它们回答的不是"这一轮判成了什么"，
     *         而是"离开待审核之后去哪了"，与 {@code AuditResult} 的取值域不是同一个问题
     *         （同一张单可以是 result=NO_MATCH + 终局标 AUDIT_REJECTED，两边都对）。
     *         给它们编一个最像的 AuditResult 才会制造假冲突。谁要读终局去向，用
     *         {@link #isTerminalConclusion} 认出这一档、直接读标码本身。
     */
    public static java.util.Optional<AuditResult> resultOf(String tagCode) {
        if (tagCode == null || tagCode.isBlank()) return java.util.Optional.empty();
        String code = tagCode.trim();
        if (code.startsWith(AuditTagCatalogService.CODE_FROZEN)) return java.util.Optional.of(AuditResult.FROZEN);
        if (AuditTagCatalogService.CODE_AUTO_PASSED.equals(code)) return java.util.Optional.of(AuditResult.AUTO_PASS);
        if (AuditTagCatalogService.CODE_AUDIT_PENDING.equals(code)
                || AuditTagCatalogService.CODE_STOCK_SHORT.equals(code)
                || AuditTagCatalogService.CODE_COST_MISSING.equals(code)) {
            return java.util.Optional.of(AuditResult.MANUAL);
        }
        if (AuditTagCatalogService.CODE_BLOCKED.equals(code)) return java.util.Optional.of(AuditResult.BLOCKED);
        if (AuditTagCatalogService.CODE_NO_POLICY_MATCH.equals(code)) return java.util.Optional.of(AuditResult.NO_MATCH);
        // 目录里允许先落标、后补登记；这里对"还不认识的结论标码"如实回 empty
        return java.util.Optional.empty();
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

    /**
     * 供文档/测试引用：落在 {@code order_audit_tag} 上的全部结论标码。
     *
     * <p>口径是「这一处映射表能产出的码」，FROZEN 由冻结分支独占写入（码由 {@link #frozenCodeOf} 给），
     * 所以它不在这个列表里 —— 但"冻结也是结论"这一点由 {@link #producesTag} 表达。
     *
     * <p>期 3 步骤 2.5 起多三枚<b>终局</b>码（见 {@link #terminalCodeOf}）：它们不是"这一轮判成了什么"，
     * 而是"离开待审核之后它最后怎么了"。仍属「结论」档（互斥 / 结算 / 目录 category 都按这一档走）。
     */
    public static java.util.List<String> conclusionCodesWrittenHere() {
        return java.util.List.of(AuditTagCatalogService.CODE_AUDIT_PENDING,
                AuditTagCatalogService.CODE_STOCK_SHORT,
                AuditTagCatalogService.CODE_COST_MISSING,
                AuditTagCatalogService.CODE_BLOCKED,
                AuditTagCatalogService.CODE_NO_POLICY_MATCH,
                // 期 3 步骤 1：AUTO_PASS 不是"什么都不发生"，它是**最常发生的那个结论**
                AuditTagCatalogService.CODE_AUTO_PASSED,
                // 期 3 步骤 2.5：结算 = 落终局标（不再是一把清空）
                AuditTagCatalogService.CODE_AUDIT_PASSED,
                AuditTagCatalogService.CODE_AUDIT_REJECTED,
                AuditTagCatalogService.CODE_RETURN_APPLIED);
    }

    // ================================================================== 期 3 步骤 2.5：终局结论标

    /**
     * <b>「离开待审核 ⇒ 落哪一枚终局标」的唯一映射表</b>（期 3 步骤 2.5）。
     *
     * <h3>为什么需要它（口径缺口，不是数据不一致）</h3>
     * 期 1.6 起，单子离开 7 就把身上所有结论标 {@code releaseAll} 掉。于是队列里的终局单读"当前结论"时
     * 只剩 {@code null} —— 一旦结论改从标读，它们会从 NO_MATCH/BLOCKED/… 变成<b>没有结论</b>。
     * 清空只回答了"旧结论不成立"，没回答"那它最后怎么了"。所以结算改成两步：
     * <b>先 release 旧的，再落一条终局标</b>（顺序不能反：反了会被自己刚 release 掉）。
     *
     * <h3>「过没过」仍然由状态表达（吉客云口径）</h3>
     * 落标不是把"通过"变成一种状态码；状态照样是 8 待发货 / 4 已关闭 / 5 退款中。
     * 标回答的是状态答不出的那半句：<b>它是怎么过的 / 为什么没过 / 去哪了</b>。
     *
     * <table border="1">
     *   <caption>离开 7 的出口 → 终局标</caption>
     *   <tr><th>流转</th><th>reason（台账原因码）</th><th>终局标</th></tr>
     *   <tr><td>7→8</td><td>{@code AUDIT_PASS}（人工点「审核通过」）</td><td>{@code AUDIT_PASSED}</td></tr>
     *   <tr><td>7→8</td><td>{@code AUDIT_AUTO_PASS}（引擎自动过审）</td><td>{@code AUTO_PASSED}</td></tr>
     *   <tr><td>7→4</td><td>{@code AUDIT_REJECT}</td><td>{@code AUDIT_REJECTED}</td></tr>
     *   <tr><td>7→5</td><td>{@code RETURN_APPLY}</td><td>{@code RETURN_APPLIED}</td></tr>
     *   <tr><td>7→2/3/9（或 7→8）</td><td>{@code DELIVERY_AGGREGATE}（包裹事实推导）</td>
     *       <td><b>{@code null}：不落</b></td></tr>
     * </table>
     *
     * <h3>为什么「聚合推导」那一支故意不落标</h3>
     * 聚合把 7 推成 2/3/9 讲的是<b>包裹事实</b>（包裹全出库了/取消了），它不是一条"审核结论"。
     * 给它落一枚审核结论标等于伪造一条判定证据（"人工放行通过"？谁放的？"审核不通过"？没有这回事）。
     * 这一支只做 {@code releaseAll}：该单随后**没有**结论标 —— 这是设计如此，
     * 对账端点用 {@code order_status_ledger.reason} 把它单列成 {@code noAuditConclusionByDesign}（正常），
     * 不会被误算成"该有结论标却没有"的真缺口。
     *
     * <p>⚠️ 这也是为什么判据必须带 reason 而不能只看 {@code to==8}：7→8 既可能是人工放行，
     * 也可能是聚合推导的（{@code DERIVED} 允许），只看目标状态会把后者错标成"人工放行通过"。
     *
     * @param to     目标状态
     * @param reason 台账原因码（{@link com.qiqi.mall.shared.ledger.ReasonCodes}）
     * @return 终局标码；{@code null} = 这一支不落终局标（聚合推导 / 不认识的出口）
     */
    public static String terminalCodeOf(Integer to, String reason) {
        if (to == null) return null;
        if (to == 8) {
            if (com.qiqi.mall.shared.ledger.ReasonCodes.AUDIT_PASS.equals(reason)) {
                return AuditTagCatalogService.CODE_AUDIT_PASSED;
            }
            if (com.qiqi.mall.shared.ledger.ReasonCodes.AUDIT_AUTO_PASS.equals(reason)) {
                return AuditTagCatalogService.CODE_AUTO_PASSED;
            }
            return null;      // 7→8 的聚合推导：包裹事实，不是审核结论
        }
        if (to == 4 && com.qiqi.mall.shared.ledger.ReasonCodes.AUDIT_REJECT.equals(reason)) {
            return AuditTagCatalogService.CODE_AUDIT_REJECTED;
        }
        if (to == 5 && com.qiqi.mall.shared.ledger.ReasonCodes.RETURN_APPLY.equals(reason)) {
            return AuditTagCatalogService.CODE_RETURN_APPLIED;
        }
        return null;
    }

    /**
     * 这个码是不是「终局」结论标（= 该单已经离开待审核，标回答的是"它最后怎么了"）。
     *
     * <p>为什么要区分"终局"与"待审核期间的结论"：两者的<b>判据不同源</b>。
     * {@code AUDIT_PENDING}/{@code BLOCKED}/{@code NO_POLICY_MATCH}/… 是引擎这一轮判出的结果，
     * 必须与 {@code order_audit_decision.result} 逐单对得上（对账的 {@code valueMismatch} 管它们）；
     * 而终局标讲的是"离开 7 之后去哪了"，与 {@code result} 是<b>两个问题</b>，
     * 拿它们逐单"必须相等"去对账只会制造假冲突（例如 NO_MATCH 被判人工关单：result 仍是 NO_MATCH，
     * 终局标是 AUDIT_REJECTED —— 两边都对）。
     *
     * <p>所以对账端点必须按这一档分流：终局标回答的那批单算"标答得出来"（{@code answeredByTerminalTag}），
     * 不参与 result 比较。这也是 {@link #resultOf} 故意<b>不</b>给这三枚码返回值的原因
     * （给它们编一个"最像的"AuditResult 就是伪造结论）。
     */
    public static boolean isTerminalConclusion(String tagCode) {
        if (tagCode == null) return false;
        String code = tagCode.trim();
        return AuditTagCatalogService.CODE_AUDIT_PASSED.equals(code)
                || AuditTagCatalogService.CODE_AUDIT_REJECTED.equals(code)
                || AuditTagCatalogService.CODE_RETURN_APPLIED.equals(code)
                // AUTO_PASSED 也是终局去向（自动过审离开 7 落的那一枚），但它同时也是
                // "这一轮判成了 AUTO_PASS" ⇒ 它**既能**与 result 对齐（当前实现正是如此，e2e_a 8/8），
                // 又要能被终局轴认出来。所以它不在返回 true 的集合里：{@link #resultOf} 认得它，
                // 对账按"标能回答且与 result 一致"处理。
                ;
    }

    /** 供测试/文档引用：三枚"离开待审核"的终局码（不含 AUTO_PASSED，理由见 {@link #isTerminalConclusion}） */
    public static java.util.List<String> terminalCodesFromSettlement() {
        return java.util.List.of(AuditTagCatalogService.CODE_AUDIT_PASSED,
                AuditTagCatalogService.CODE_AUDIT_REJECTED,
                AuditTagCatalogService.CODE_RETURN_APPLIED);
    }

    /**
     * {@link AuditResult} 的哪几类<b>会落标</b>（只剩 SKIPPED 不落：它不写决策行）。
     *
     * <p>期 1.6 起 FROZEN 也算：它落 FROZEN 类标（写入方是 freeze，但"这条结论产标"是真的）。
     * <p>期 3 步骤 1 起 <b>AUTO_PASS 也算</b>（落 AUTO_PASSED，visible=0）：结论从标读时，
     * 最常见的那个结论不能是空的 —— 否则"标驱动结论"在这批单上根本给不出答案。
     */
    public static boolean producesTag(AuditResult result) {
        return result == AuditResult.MANUAL || result == AuditResult.BLOCKED
                || result == AuditResult.NO_MATCH || result == AuditResult.FROZEN
                || result == AuditResult.AUTO_PASS;
    }
}
