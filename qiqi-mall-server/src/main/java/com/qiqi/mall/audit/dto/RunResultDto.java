package com.qiqi.mall.audit.dto;

import java.util.List;

/**
 * 一次 run 的结果：runId + 五类计数（决策 C3：**一次 run 同时产出五类结果**）+ 逐单摘要。
 *
 * @param skipped SKIPPED 单数（状态不是 7 / 已冻结）。它不计入五类，但要让人看到
 *                「本轮范围里有这么多单没参与」，否则 total 与五类之和的差就成了谜。
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
}
