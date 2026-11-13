package com.qiqi.mall.audit.dto;

import java.math.BigDecimal;

/** 条件出参（带字段中文名，前端不用再维护一份 code → 名字的映射）。 */
public record ConditionDto(
        Long id,
        Integer seq,
        String kind,
        String scope,
        String fieldCode,
        String fieldLabel,
        String opCode,
        String valueType,
        BigDecimal valueNum,
        String valueText,
        String valueJson) {
}
