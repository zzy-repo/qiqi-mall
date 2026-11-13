package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;

/**
 * 一条条件（引擎视角，只读）：{@code audit_policy_condition} 一行的投影。
 *
 * @param id        行 ID（null = 尚未落库，单测里直接构造）
 * @param seq       求值/展示顺序
 * @param kind      MATCH / EXCLUDE
 * @param scope     POLICY / ALL_AUTO
 * @param fieldCode 字段码（须在 {@link FieldRegistry} 里）
 * @param opCode    操作符码（须在 {@link OpCode} 里）
 * @param valueType 值类型
 * @param valueNum  NUM / BOOL 值
 * @param valueText TEXT 值
 * @param valueJson JSON 值（预留）
 */
public record ConditionView(
        Long id,
        Integer seq,
        ConditionKind kind,
        ConditionScope scope,
        String fieldCode,
        String opCode,
        ValueType valueType,
        BigDecimal valueNum,
        String valueText,
        String valueJson) {

    public static ConditionView match(String fieldCode, String opCode, BigDecimal num) {
        return new ConditionView(null, 0, ConditionKind.MATCH, ConditionScope.POLICY,
                fieldCode, opCode, ValueType.NUM, num, null, null);
    }

    public static ConditionView matchText(String fieldCode, String opCode, String text) {
        return new ConditionView(null, 0, ConditionKind.MATCH, ConditionScope.POLICY,
                fieldCode, opCode, ValueType.TEXT, null, text, null);
    }

    public static ConditionView matchBool(String fieldCode, String opCode, boolean value) {
        return new ConditionView(null, 0, ConditionKind.MATCH, ConditionScope.POLICY,
                fieldCode, opCode, ValueType.BOOL, value ? BigDecimal.ONE : BigDecimal.ZERO, null, null);
    }

    public static ConditionView exclude(String fieldCode, String opCode, String text, ConditionScope scope) {
        return new ConditionView(null, 0, ConditionKind.EXCLUDE, scope,
                fieldCode, opCode, ValueType.TEXT, null, text, null);
    }
}
