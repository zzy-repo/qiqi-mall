package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 单条条件的求值（纯函数）。
 *
 * <h3>三条口径</h3>
 * <ol>
 *   <li><b>字段/操作符拼错 → 抛异常，不返回「不满足」</b>：返回 false 会让一条配错的策略
 *       永久静默失效（永远不命中），运营只会觉得「配了没用」。</li>
 *   <li><b>实际值为 null ≠ 0</b>：带值操作符一律判不满足，文案写「无值」。
 *       null 当 0 参与比较正是风险③（毛利缺失被当成毛利 0 而放行）的成因。</li>
 *   <li><b>CONTAINS 走内存 contains</b>，不拼 LIKE（见 {@link OpCode}）。</li>
 * </ol>
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public static ConditionTrace evaluate(AuditContext ctx, ConditionView condition) {
        FieldRegistry.FieldDef def = FieldRegistry.require(condition.fieldCode());
        OpCode op = OpCode.of(condition.opCode());
        if (op == null) {
            throw new IllegalArgumentException("未知操作符「" + condition.opCode() + "」，可用操作符：" + Arrays.toString(OpCode.values()));
        }
        checkCompatible(def, op);
        if (op.needsValue()) requireValue(condition, def, op);

        Object raw = def.getter().apply(ctx);
        String actualText = Fmt.value(raw, def);

        if (op == OpCode.EXISTS || op == OpCode.NOT_EXISTS) {
            boolean present = raw != null && (!(raw instanceof String s) || !s.isBlank());
            boolean satisfied = op == OpCode.EXISTS ? present : !present;
            String text = def.label() + (present ? " 有值" : " 无值") + "（实际 " + actualText + "）";
            return ConditionTrace.evaluated(condition.seq(), def.code(), def.label(), op.name(), "", actualText, satisfied, text);
        }

        String expectedText = expectedText(condition, def);
        if (raw == null) {
            String text = def.label() + " 无值，无法比较 " + op.symbol() + " " + expectedText;
            return ConditionTrace.evaluated(condition.seq(), def.code(), def.label(), op.name(), expectedText, actualText, false, text);
        }

        boolean satisfied = switch (def.type()) {
            case NUM -> compareNum(toNum(raw), condition.valueNum(), op);
            case BOOL -> compareBool((Boolean) raw, condition.valueNum(), op);
            case TEXT -> compareText(String.valueOf(raw), condition.valueText(), op);
            case JSON -> throw new IllegalArgumentException("JSON 值类型的条件本轮引擎不消费（字段 " + def.code() + "）");
        };
        String text = text(def, op, actualText, expectedText);
        return ConditionTrace.evaluated(condition.seq(), def.code(), def.label(), op.name(), expectedText, actualText, satisfied, text);
    }

    // ------------------------------------------------------------------ 类型校验

    private static void checkCompatible(FieldRegistry.FieldDef def, OpCode op) {
        if (op == OpCode.EXISTS || op == OpCode.NOT_EXISTS) return;
        boolean ok = switch (def.type()) {
            case NUM -> true;
            case BOOL -> op == OpCode.EQ || op == OpCode.NE;
            case TEXT -> op == OpCode.EQ || op == OpCode.NE || op == OpCode.CONTAINS || op == OpCode.NOT_CONTAINS;
            case JSON -> false;
        };
        if (!ok) {
            throw new IllegalArgumentException("字段「" + def.label() + "」是 " + def.type()
                    + " 类型，不支持操作符 " + op.name() + "（可用：" + allowed(def.type()) + "）");
        }
    }

    private static String allowed(ValueType type) {
        return switch (type) {
            case NUM -> "EQ/NE/LT/LTE/GT/GTE/EXISTS/NOT_EXISTS";
            case BOOL -> "EQ/NE/EXISTS/NOT_EXISTS";
            case TEXT -> "EQ/NE/CONTAINS/NOT_CONTAINS/EXISTS/NOT_EXISTS";
            case JSON -> "（本轮不支持）";
        };
    }

    private static void requireValue(ConditionView c, FieldRegistry.FieldDef def, OpCode op) {
        switch (def.type()) {
            case NUM, BOOL -> {
                if (c.valueNum() == null) {
                    throw new IllegalArgumentException("字段「" + def.label() + "」用 " + op.name() + " 必须配数值（value_num）");
                }
            }
            case TEXT -> {
                if (c.valueText() == null) {
                    throw new IllegalArgumentException("字段「" + def.label() + "」用 " + op.name() + " 必须配文本（value_text）");
                }
            }
            case JSON -> throw new IllegalArgumentException("字段「" + def.label() + "」暂不支持 JSON 值");
        }
    }

    // ------------------------------------------------------------------ 比较

    private static BigDecimal toNum(Object raw) {
        if (raw instanceof BigDecimal d) return d;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(String.valueOf(raw));
    }

    private static boolean compareNum(BigDecimal actual, BigDecimal expected, OpCode op) {
        int cmp = actual.compareTo(expected);
        return switch (op) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            default -> throw new IllegalArgumentException("数值字段不支持 " + op);
        };
    }

    private static boolean compareBool(Boolean actual, BigDecimal expected, OpCode op) {
        boolean want = expected != null && expected.signum() != 0;
        return op == OpCode.EQ ? actual == want : actual != want;
    }

    private static boolean compareText(String actual, String expected, OpCode op) {
        return switch (op) {
            case EQ -> actual.equals(expected);
            case NE -> !actual.equals(expected);
            // 内存 contains：% 与 _ 是普通字符，不会被当通配符
            case CONTAINS -> actual.contains(expected);
            case NOT_CONTAINS -> !actual.contains(expected);
            default -> throw new IllegalArgumentException("文本字段不支持 " + op);
        };
    }

    // ------------------------------------------------------------------ 文案

    private static String expectedText(ConditionView c, FieldRegistry.FieldDef def) {
        return switch (def.type()) {
            case NUM -> Fmt.num(c.valueNum(), Fmt.isMoney(def.code()));
            case BOOL -> c.valueNum() != null && c.valueNum().signum() != 0 ? "是" : "否";
            case TEXT -> c.valueText();
            case JSON -> String.valueOf(c.valueJson());
        };
    }

    private static String text(FieldRegistry.FieldDef def, OpCode op, String actual, String expected) {
        return switch (op) {
            case EQ -> def.label() + " = " + expected + "（实际 " + actual + "）";
            case NE -> def.label() + " ≠ " + expected + "（实际 " + actual + "）";
            case CONTAINS -> def.label() + " 含「" + expected + "」（实际 " + actual + "）";
            case NOT_CONTAINS -> def.label() + " 不含「" + expected + "」（实际 " + actual + "）";
            // 关系比较：数值差一眼可见 —— 「毛利 −12.00 ≤ 0」
            default -> def.label() + " " + actual + " " + op.symbol() + " " + expected;
        };
    }
}
