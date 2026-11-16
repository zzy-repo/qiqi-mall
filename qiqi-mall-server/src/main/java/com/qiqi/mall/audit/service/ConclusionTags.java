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
 *   <tr><td>AUTO_PASS</td><td><b>{@code null}：不落标</b></td>
 *       <td><b>V9（2026-11-16）退回用户的拍板</b>：V7 曾让 AUTO_PASS 落 {@code AUTO_PASSED}
 *           （名字里带「过」），那是助手自己拍板、推翻用户 16:38 的决定 ——
 *           用户原话：「自动过审的单身上可以有标，但<b>绝不会有一个叫『已通过』的标</b>」
 *           「<b>通过与否由状态表达，不由标表达</b>」。自动过审的单照样可以带<b>特征标</b>
 *           （客诉客户/绿旗急发/双地址…），那些与"过没过"无关；而"它过了"由状态 8 表达。
 *           至于"结论从标读"最怕的那个空档（见 {@link #resultOf}），由<b>决策行</b>回答 ——
 *           那本来就是决策行的职责，不该为了它造一枚"通过"标。</td></tr>
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
 * <h3>⚠️ V9（2026-11-16）回滚：「通过 / 不通过」不是标</h3>
 * 期 3 步骤 2.5 曾把结论标拆成两个轴（判定轴 + 终局轴），让结算在离开待审核时落一枚
 * {@code AUDIT_PASSED / AUDIT_REJECTED / AUTO_PASSED}。那段设计与用户 16:38 的拍板相反
 * ——「通过与否由状态表达，不由标表达」「绝不会有一个叫『已通过』的标」——
 * 已经由 V9 整体收回（三条目录项 {@code enabled=0}、结算回到「只解不落」）。
 * 本类因此**只剩一个轴**：这一轮引擎判成了什么（与 {@code order_audit_decision.result} 逐单一致）。
 * <p>
 * 唯一被保留的"离开待审核之后"的标是 {@code RETURN_APPLIED}（已申请退货），
 * 因为它讲的是<b>事实</b>（这单进了退货流程，队列行看不到订单状态），不是"过没过"——
 * 判据见 {@link #isSettlementFact(String)}。
 */
public final class ConclusionTags {

    private ConclusionTags() {
    }

    /**
     * 这条结论该落哪个标码。
     *
     * @return 标码；{@code null} 表示这条结论不落标（SKIPPED、以及 **AUTO_PASS**）。
     *         AUTO_PASS 回 {@code null} 是 V9 退回用户拍板的结果（「通过与否由状态表达，不由标表达」）：
     *         它过了这件事由状态 8 表达，标里不落一枚"自动过审通过"。
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
            // V9：自动过审**不落标** —— "它过了"由状态 8 表达（用户 2026-11-16 16:38 的拍板）。
            // V7 曾在这里回 AUTO_PASSED（visible=0），那是助手自己拍的板，已随 V9 收回。
            case AUTO_PASS -> null;
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
     *         <p>⚠️ {@code RETURN_APPLIED}（已申请退货）<b>故意回 empty</b>：它回答的不是"这一轮判成了什么"，
     *         而是"这单进了退货流程"这条事实，与 {@code AuditResult} 的取值域不是同一个问题
     *         （同一张单可以是 result=NO_MATCH + RETURN_APPLIED，两边都对）。给它们编一个最像的
     *         AuditResult 才会制造假冲突。谁要读它，用 {@link #isSettlementFact(String)} 认出来。
     *         <p>⚠️ {@code AUTO_PASSED / AUDIT_PASSED}（V7/V8 那两枚"过没过"标）在 V9 已停用，
     *         目录项 {@code enabled=0}、代码不再落它们，因此这里也不再认它们。
     */
    public static java.util.Optional<AuditResult> resultOf(String tagCode) {
        if (tagCode == null || tagCode.isBlank()) return java.util.Optional.empty();
        String code = tagCode.trim();
        if (code.startsWith(AuditTagCatalogService.CODE_FROZEN)) return java.util.Optional.of(AuditResult.FROZEN);
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
     * <p>口径是「这一段代码能产出的码」，FROZEN 由冻结分支独占写入（码由 {@link #frozenCodeOf} 给），
     * 所以它不在这个列表里 —— 但"冻结也是结论"这一点由 {@link #producesTag} 表达。
     *
     * <p>V9（2026-11-16）起<b>不含</b> {@code AUTO_PASSED / AUDIT_PASSED / AUDIT_REJECTED}：
     * 那三枚"过没过"标已停用（目录 {@code enabled=0}），代码一处都不再落它们 —— 见类注释。
     * {@code RETURN_APPLIED} 留着：它是一枚**事实标**（这单进了退货流程），不是"过没过"。
     */
    public static java.util.List<String> conclusionCodesWrittenHere() {
        return java.util.List.of(AuditTagCatalogService.CODE_AUDIT_PENDING,
                AuditTagCatalogService.CODE_STOCK_SHORT,
                AuditTagCatalogService.CODE_COST_MISSING,
                AuditTagCatalogService.CODE_BLOCKED,
                AuditTagCatalogService.CODE_NO_POLICY_MATCH,
                // 唯一保留的"离开待审核之后"的标：退货是**事实**，不是"过没过"
                AuditTagCatalogService.CODE_RETURN_APPLIED);
    }

    // ============================================ V9：只剩一枚"结算事实标"（退货申请）

    /**
     * 这个码是不是<b>结算时落的"事实标"</b>（= 该单离开待审核之后发生的一件值得注意的事）。
     *
     * <h3>为什么只剩 {@code RETURN_APPLIED} 一枚</h3>
     * V8 曾在这里摆三枚"终局结论标"（{@code AUDIT_PASSED / AUDIT_REJECTED / RETURN_APPLIED}），
     * 由 {@code terminalCodeOf(to, reason)} 一张表决定落哪一枚 —— 那张表连同那套"终局轴"已被 V9 整体删除，
     * 因为前两枚回答的是"过没过"，而用户 2026-11-16 16:38 拍过板：
     * 「<b>通过与否由状态表达，不由标表达</b>」「<b>绝不会有一个叫『已通过』的标</b>」。
     *
     * <p>{@code RETURN_APPLIED} 不同：它讲的是"这单进了退货流程"这条<b>事实</b>，
     * 属于用户说的「值得注意的事」；而队列是决策驱动的（{@code FROM order_audit_decision}），
     * 行上看不到 {@code order.status}，运营从队列里看不出这单已在退货流程里。
     *
     * <h3>为什么对账端点要认这一档</h3>
     * {@code AUDIT_PENDING / BLOCKED / NO_POLICY_MATCH} 这些是引擎这一轮判出的结果，
     * 必须与 {@code order_audit_decision.result} 逐单对得上（对账的 {@code valueMismatch} 管它们）；
     * 而 {@code RETURN_APPLIED} 与 {@code result} 是<b>两个问题</b>，拿它逐单"必须相等"去对账只会制造假冲突
     * （例：result=NO_MATCH + 已申请退货 —— 两边都对）。这也是 {@link #resultOf} 故意不给它返回值的原因。
     */
    public static boolean isSettlementFact(String tagCode) {
        return tagCode != null && AuditTagCatalogService.CODE_RETURN_APPLIED.equals(tagCode.trim());
    }

    /**
     * {@link AuditResult} 的哪几类<b>会落标</b>（SKIPPED 不落：它不写决策行）。
     *
     * <p>期 1.6 起 FROZEN 也算：它落 FROZEN 类标（写入方是 freeze，但"这条结论产标"是真的）。
     * <p><b>V9：AUTO_PASS 不算</b>（退回用户 2026-11-16 16:38 的拍板）—— 它过了这件事由状态 8 表达，
     * 标里不落一枚"自动过审通过"。V7 曾让这一支落 {@code AUTO_PASSED}，那是助手自己拍的板，已收回。
     */
    public static boolean producesTag(AuditResult result) {
        return result == AuditResult.MANUAL || result == AuditResult.BLOCKED
                || result == AuditResult.NO_MATCH || result == AuditResult.FROZEN;
    }
}
