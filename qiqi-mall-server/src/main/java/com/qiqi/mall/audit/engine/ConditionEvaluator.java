package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;

/**
 * 单条条件的求值（纯函数）。
 *
 * <h3>四条口径</h3>
 * <ol>
 *   <li><b>字段/操作符拼错 → 抛异常，不返回「不满足」</b>：返回 false 会让一条配错的策略
 *       永久静默失效（永远不命中），运营只会觉得「配了没用」。</li>
 *   <li><b>实际值为 null ≠ 0</b>：带值操作符一律判不满足，文案写「无值」。
 *       null 当 0 参与比较，正是「毛利缺失被当成毛利 0 而放行」的成因。</li>
 *   <li><b>CONTAINS 走内存 contains</b>，不拼 LIKE（见 {@link OpCode}）。</li>
 *   <li><b>多值字段（{@link ValueType#LIST}）走集合语义</b>：{@code HAS / NOT_HAS} 判的是
 *       「集合里有没有<b>这个元素</b>」，元素按<b>相等</b>比较 —— 绝不做子串匹配
 *       （{@code FROZEN} 不许命中 {@code FROZEN_RETURN_APPLY}，见 {@link #evaluateList}）。</li>
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
        // 多值字段：整条走集合语义（含 EXISTS/NOT_EXISTS 的"空集合算不算有值"）
        if (def.type() == ValueType.LIST) {
            return evaluateList(ctx, condition, def, op, raw);
        }
        String actualText = Fmt.value(raw, def, ctx);

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
            // LIST 在上面的 if 里已经 return 了；这里保留一个"绝不静默"的出口，
            // 免得将来有人调换分支顺序时，多值字段被当标量比较而得出猜测的结果
            case LIST -> throw new IllegalStateException("LIST 字段必须走 evaluateList（字段 " + def.code() + "）");
            case JSON -> throw new IllegalArgumentException("JSON 值类型的条件本轮引擎不消费（字段 " + def.code() + "）");
        };
        String text = text(def, op, actualText, expectedText);
        return ConditionTrace.evaluated(condition.seq(), def.code(), def.label(), op.name(), expectedText, actualText, satisfied, text);
    }

    // ------------------------------------------------------------------ 多值字段（读标）

    /**
     * 多值字段（{@link ValueType#LIST}）的求值：{@code HAS / NOT_HAS} + {@code EXISTS / NOT_EXISTS}。
     *
     * <h3>为什么必须单独一条路径，而不是复用 CONTAINS</h3>
     * 标码是 {@code FROZEN} / {@code FROZEN_RETURN_APPLY} 这种"前缀 + 细分"的命名。
     * 用字符串包含判的话，{@code HAS 'FROZEN'} 会命中 {@code FROZEN_RETURN_APPLY} ——
     * 一个"有没有普通冻结标"的条件会把所有细分冻结标也算上。这类错判<b>不报错、不留痕</b>，
     * 只会让策略静默地多拦/少拦一批单，事后谁也说不清。所以这里是<b>元素相等</b>：
     * {@code set.contains(expected)}。
     *
     * <h3>三条判定口径</h3>
     * <ul>
     *   <li><b>空集合不算"有值"</b>：{@code HAS} 任意码 → false，{@code NOT_HAS} → true
     *       （"这单没有这个标"在空集合上确实成立），{@code EXISTS} → false。</li>
     *   <li><b>字段缺失 ≠ 空集合</b>：{@code raw == null} 表示装配方没把标集合传进上下文
     *       （代码缺陷），此时<b>抛异常</b>而不是判 false。判 false 会让一条配好的读标策略
     *       永远不命中，且没有任何痕迹 —— 与口径①「拼错字段不许静默失效」是同一条理由。</li>
     *   <li>{@code NOT_HAS} 不是 {@code HAS} 的反面兜底：它同样要求集合已装配，同样按元素相等判。</li>
     * </ul>
     */
    private static ConditionTrace evaluateList(AuditContext ctx, ConditionView c, FieldRegistry.FieldDef def,
                                               OpCode op, Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("字段「" + def.label() + "」未装配（上下文里这个组件为 null）："
                    + "未装配 ≠ 空集合，不能当成「条件不满足」放过去 —— 请检查装配点是否传了 "
                    + "AuditContext.activeTags()（空集合请传 List.of()）");
        }
        Collection<?> set = raw instanceof Collection<?> c0 ? c0 : java.util.List.of(raw);
        String actualText = Fmt.value(set, def, ctx);

        if (op == OpCode.EXISTS || op == OpCode.NOT_EXISTS) {
            boolean present = !set.isEmpty();       // 空集合不算"有值"
            boolean satisfied = op == OpCode.EXISTS ? present : !present;
            String text = def.label() + (present ? " 有值" : " 无值（空集合）") + "（实际 " + actualText + "）";
            return ConditionTrace.evaluated(c.seq(), def.code(), def.label(), op.name(), "", actualText, satisfied, text);
        }

        String expected = c.valueText() == null ? "" : c.valueText().trim();
        // 元素相等，不是子串包含（子串陷阱见本方法注释）
        boolean has = set.stream().anyMatch(e -> e != null && expected.equals(String.valueOf(e).trim()));
        boolean satisfied = op == OpCode.HAS ? has : !has;
        String text = def.label() + (op == OpCode.HAS ? " 有标「" : " 无标「") + expected
                + "」（实际 " + actualText + "）";
        return ConditionTrace.evaluated(c.seq(), def.code(), def.label(), op.name(), expected, actualText, satisfied, text);
    }

    // ------------------------------------------------------------------ 类型校验

    private static void checkCompatible(FieldRegistry.FieldDef def, OpCode op) {
        if (op == OpCode.EXISTS || op == OpCode.NOT_EXISTS) return;
        boolean ok = switch (def.type()) {
            case NUM -> true;
            case BOOL -> op == OpCode.EQ || op == OpCode.NE;
            case TEXT -> op == OpCode.EQ || op == OpCode.NE || op == OpCode.CONTAINS || op == OpCode.NOT_CONTAINS;
            // 多值字段只认集合语义：HAS/NOT_HAS。CONTAINS 在这里是**明确禁止**的
            // （子串陷阱：FROZEN 会命中 FROZEN_RETURN_APPLY），EQ 之类则没有定义
            case LIST -> op == OpCode.HAS || op == OpCode.NOT_HAS;
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
            case LIST -> "HAS/NOT_HAS/EXISTS/NOT_EXISTS";
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
            case LIST -> {
                // 多值字段的条件值是"一个元素码"，落在 value_text 上（见 ValueType#LIST）
                if (c.valueText() == null || c.valueText().isBlank()) {
                    throw new IllegalArgumentException("字段「" + def.label() + "」用 " + op.name()
                            + " 必须配要判的标码（value_text），例如 VIP_CUSTOMER");
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
            // 多值字段的"门槛"就是一个标码（实际走 evaluateList，这里是同口径兜底）
            case LIST -> c.valueText();
            case JSON -> String.valueOf(c.valueJson());
        };
    }

    private static String text(FieldRegistry.FieldDef def, OpCode op, String actual, String expected) {
        return switch (op) {
            case EQ -> def.label() + " = " + expected + "（实际 " + actual + "）";
            case NE -> def.label() + " ≠ " + expected + "（实际 " + actual + "）";
            case CONTAINS -> def.label() + " 含「" + expected + "」（实际 " + actual + "）";
            case NOT_CONTAINS -> def.label() + " 不含「" + expected + "」（实际 " + actual + "）";
            // 集合语义：文案必须有「标」字，否则「当前标记 有标 FROZEN」和「含 FROZEN」看起来是同一件事
            case HAS -> def.label() + " 有标「" + expected + "」（实际 " + actual + "）";
            case NOT_HAS -> def.label() + " 无标「" + expected + "」（实际 " + actual + "）";
            // 关系比较：数值差一眼可见 —— 「毛利 −12.00 ≤ 0」
            default -> def.label() + " " + actual + " " + op.symbol() + " " + expected;
        };
    }
}
