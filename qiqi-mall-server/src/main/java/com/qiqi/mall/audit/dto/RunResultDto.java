package com.qiqi.mall.audit.dto;

import java.util.List;

/**
 * 一次 run 的结果：runId + 五类计数（决策 C3：**一次 run 同时产出五类结果**）+ 逐单摘要。
 *
 * <h3>{@code total} 与五类、skipped 的关系（唯一口径，别再各算各的）</h3>
 * <pre>
 * 五类之和 = autoPass + manual + blocked + frozen + noMatch == decisions().size()   // 每条决策行恰好属于一类
 * total    = 五类之和 + skipped               // 本轮「进入判定范围」的单数（候选数）
 * skipped == 0  →  total == 五类之和          // 没有单被跳过时，两个口径重合
 * skipped &gt; 0  →  total &gt; 五类之和          // 差额恰好是 skipped，一分不多一分不少
 * </pre>
 * 为什么 total 取「范围单数」而不是「决策行数」：{@code audit_run.total} 的对外说法是
 * 「本轮范围内 N 笔」，页面上紧接着列出的就是五类 + 未参与 —— 三组数字必须能对上，
 * 否则用户第一反应是「少算了」。{@link #skipped} 因此既是独立计数，也是
 * {@code total} 与五类之和的差额；反过来，只要拿到 {@code total} 与五类计数就能把
 * skipped 还原出来（SCHEDULE 重放路径正是这么做的，因为 {@code audit_run} 没有 skipped 列）。
 *
 * @param skipped SKIPPED 单数（状态不是 7 / 已冻结）。它不计入五类，也<b>不写决策行</b>
 *                （冻结单每轮留一行无意义的 SKIPPED 只会把 {@code round_no} 撑大）；
 *                但必须让人看到「本轮范围里有这么多单没参与」，否则 total 与五类之和的差就成了谜。
 */
public record RunResultDto(
        Long runId,
        String triggerType,
        int total,
        int autoPass,
        int manual,
        int blocked,
        int frozen,
        int noMatch,
        int skipped,
        List<DecisionRowDto> decisions) {

    /** 五类之和（== {@link #decisions()}.size()）：与 {@link #total()} 的差额恒为 {@link #skipped()} */
    public int verdictSum() {
        return autoPass + manual + blocked + frozen + noMatch;
    }
}
