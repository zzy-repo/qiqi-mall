package com.qiqi.mall.audit.service;

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
 *   <tr><td>FROZEN</td><td>{@code null}（由冻结分支自己落）</td>
 *       <td>冻结走 {@link OrderAuditTagService#freeze}，它落的码可能是 {@code FROZEN}
 *           也可能是策略配的细分码（{@code FROZEN_RETURN_APPLY}），并且要带 {@code reason_id} 字典。
 *           这里再补一个通用 FROZEN 会让同一单同轮出现两条冻结标（前缀都是 FROZEN），
 *           队列的 frozen 判据与解冻的按前缀一次放掉都会被它们拖出歧义。</td></tr>
 *   <tr><td>SKIPPED</td><td>{@code null}</td>
 *       <td>跳过的单不写判定记录（见 {@code AuditExecutor} 类注释），也就不落标：
 *           它的"为什么"已经由那条 active 的冻结标回答了。</td></tr>
 * </table>
 *
 * <h3>⚠️ 结论标互斥（期 1.5）：同一时刻一单只有一个 active 的结论标</h3>
 * 结论标描述的是"<b>当前</b>判定结论"，而当前结论天然只有一个 —— 一单不可能同时是
 * "无策略命中"和"待人工审核"。所以每次落新结论标前，先把该单其他 active 的结论档标置
 * {@code active=0} 并记 {@code released_by=SYSTEM:SUPERSEDED}（见 {@code AuditExecutor#settleConclusionTag}、
 * {@code OrderAuditTagService#supersedeOtherConclusionTags}）。
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
     * @return 标码；{@code null} 表示这条结论不落标（AUTO_PASS / FROZEN / SKIPPED，见类注释）
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
            case AUTO_PASS, FROZEN, SKIPPED -> null;
        };
    }

    /** 供文档/测试引用：当前会落标的全部结论标码（不含 FROZEN —— 它由冻结分支落） */
    public static java.util.List<String> conclusionCodesWrittenHere() {
        return java.util.List.of(AuditTagCatalogService.CODE_AUDIT_PENDING,
                AuditTagCatalogService.CODE_STOCK_SHORT,
                AuditTagCatalogService.CODE_COST_MISSING,
                AuditTagCatalogService.CODE_BLOCKED,
                AuditTagCatalogService.CODE_NO_POLICY_MATCH);
    }

    /** {@link AuditResult} 的哪几类会落标（AUTO_PASS/FROZEN/SKIPPED 除外），给测试与报表用 */
    public static boolean producesTag(AuditResult result) {
        return result == AuditResult.MANUAL || result == AuditResult.BLOCKED || result == AuditResult.NO_MATCH;
    }
}
