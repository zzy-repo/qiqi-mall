package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.stream.Collectors;

/** 判定文案里的数值格式（引擎与执行层共用，避免一边写 −12.00 一边写 -12）。 */
public final class Fmt {

    private Fmt() {
    }

    /** 金额类字段（固定两位小数，与 DDL DECIMAL(x,2) 对齐） */
    public static boolean isMoney(String fieldCode) {
        return FieldRegistry.RECEIVABLE_AMOUNT.equals(fieldCode) || FieldRegistry.GROSS_PROFIT.equals(fieldCode);
    }

    /**
     * 数值：金额固定两位小数；其它（件数、小时数）去掉多余零。
     *
     * <p>负号用排版减号 {@code −}（U+2212）：设计文案写的是「毛利 −12.00 ≤ 0」，
     * 台账/证据里也用同一个字符，人眼比对时不会出现「两个看起来一样但搜不到」的坑。
     */
    public static String num(BigDecimal value, boolean money) {
        if (value == null) return "无值";
        BigDecimal v = money ? value.setScale(2, RoundingMode.HALF_UP) : value.stripTrailingZeros();
        String s = v.toPlainString();
        return s.startsWith("-") ? "−" + s.substring(1) : s;
    }

    /**
     * 任意字段值 → 人话（布尔是/否、无值、文本原样、集合逐个列出）。
     *
     * <h3>为什么多一个 {@code ctx} 参数</h3>
     * 标量字段的文案只依赖"取值结果"；多值字段（{@link ValueType#LIST}）不行 ——
     * 「{@code raw == null}」有两种完全不同的含义，<b>必须回到上下文里才能分辨</b>：
     * <ul>
     *   <li>上下文里这个组件装配了、而它是空集合 → 「一标没有」（可评估，HAS 判 false）；</li>
     *   <li>上下文里这个组件根本是 {@code null} → 「未装配」（不可评估，引擎会抛异常）。</li>
     * </ul>
     * 文案如果都写成「无值」，排障时看到的是一模一样的一句话，而两者的处理动作相反
     * （一个是"这单确实没标"，一个是"装配代码漏了"）。所以格式化要拿到上下文，不能只看取值。
     *
     * @param raw 字段取值（可能是 null、Boolean、BigDecimal、String、Collection）
     * @param def 字段定义（决定按哪种类型格式化）
     * @param ctx 判定上下文（LIST 的空集合 / 未装配判定要用；标量字段用不到）
     */
    public static String value(Object raw, FieldRegistry.FieldDef def, AuditContext ctx) {
        if (def != null && def.type() == ValueType.LIST) return list(raw, ctx);
        if (raw == null) return "无值";
        if (raw instanceof Boolean b) return b ? "是" : "否";
        if (raw instanceof BigDecimal d) return num(d, isMoney(def == null ? null : def.code()));
        if (raw instanceof Number n) return String.valueOf(n);
        String s = String.valueOf(raw);
        return s.isBlank() ? "无值" : s;
    }

    /**
     * 多值字段的人话：「A、B」/「（空集合）」/「未装配」。
     *
     * <p>空集合写成「（空集合）」而不是「无值」：这单<b>确实查过了</b>，就是一个标都没有 ——
     * 与"没查到 / 没装配"不是同一件事（见 {@link #value} 的说明）。
     */
    private static String list(Object raw, AuditContext ctx) {
        if (raw == null) {
            // 上下文里组件是 null = 装配漏了（不可评估）；理论上 raw 与 ctx.activeTags() 同源，
            // 但文案的权威判据是上下文，不假设调用方一定按字段取值传参
            return ctx == null || ctx.activeTags() == null ? "未装配" : "无值";
        }
        if (raw instanceof Collection<?> c) {
            if (c.isEmpty()) return "（空集合）";
            return c.stream().map(String::valueOf).collect(Collectors.joining("、"));
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? "（空集合）" : s;
    }
}
