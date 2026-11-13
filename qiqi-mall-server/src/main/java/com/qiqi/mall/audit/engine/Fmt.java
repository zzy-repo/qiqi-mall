package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    /** 任意字段值 → 人话（布尔是/否、无值、文本原样） */
    public static String value(Object raw, FieldRegistry.FieldDef def) {
        if (raw == null) return "无值";
        if (raw instanceof Boolean b) return b ? "是" : "否";
        if (raw instanceof BigDecimal d) return num(d, isMoney(def.code()));
        if (raw instanceof Number n) return String.valueOf(n);
        String s = String.valueOf(raw);
        return s.isBlank() ? "无值" : s;
    }
}
