package com.qiqi.mall.audit.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 策略出参。
 *
 * <p>{@code updatedAt} 取自 {@code audit_policy.updated_at}（列是 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，
 * 即「最后一次改动时间」，改条件 / 改优先级 / 停用都会刷新）。策略页那一列以前只能显示「—」，
 * 现在能回答「这条规则上次是谁什么时候动过的」。
 *
 * <p>为什么不给 {@code createdAt}：页面只在「上次改动」这个语境里用它（一条规则改了没改，
 * 看的是 updatedAt）。要给再加，不必现在凑。
 */
public record PolicyDto(
        Long id,
        String type,
        Integer priority,
        String name,
        Boolean enabled,
        Integer versionNo,
        String actionTag,
        String actionReason,
        List<ConditionDto> conditions,
        LocalDateTime updatedAt) {
}
