package com.qiqi.mall.audit.dto;

import java.math.BigDecimal;

/**
 * 条件入参（策略编辑页提交的一条条件）。
 *
 * <p>{@code valueType} 收但不信任：值的类型由 {@code FieldRegistry} 决定（同一个字段不可能这次是 NUM、
 * 下次是 TEXT），接受客户端传值只会多一条「类型和字段对不上」的脏数据来源。
 */
public record ConditionRequest(
        String kind,
        String scope,
        String fieldCode,
        String opCode,
        String valueType,
        BigDecimal valueNum,
        String valueText,
        String valueJson) {
}
