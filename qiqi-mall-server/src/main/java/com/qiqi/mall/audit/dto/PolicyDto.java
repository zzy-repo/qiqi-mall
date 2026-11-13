package com.qiqi.mall.audit.dto;

import java.util.List;

/** 策略出参。 */
public record PolicyDto(
        Long id,
        String type,
        Integer priority,
        String name,
        Boolean enabled,
        Integer versionNo,
        String actionTag,
        String actionReason,
        List<ConditionDto> conditions) {
}
