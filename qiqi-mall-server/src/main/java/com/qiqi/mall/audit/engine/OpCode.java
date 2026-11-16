package com.qiqi.mall.audit.engine;

/**
 * 条件操作符（12 个：10 个原有 + 期 2 的 {@code HAS / NOT_HAS}）。
 *
 * <p>{@code CONTAINS / NOT_CONTAINS} 走 <b>内存 String.contains</b>，<b>绝不拼 SQL LIKE</b>：
 * 配置里的 {@code %} / {@code _} 是用户输入的普通字符，交给 LIKE 会被当通配符 ——
 * 「备注含 100%」于是会匹配上「备注含 1000」，判定结果与配置意图不一致且没人看得出原因。
 *
 * <h3>{@code HAS / NOT_HAS} 只服务 {@link ValueType#LIST} 字段</h3>
 * <b>它的语义是「集合里有没有这个元素」，不是「字符串里有没有这段子串」</b> —— 这两件事看起来一样，
 * 在多值字段上差别巨大：{@code FROZEN_RETURN_APPLY} 用子串法会让 {@code HAS 'FROZEN'} 判 true
 * （见 {@link ValueType#LIST} 的说明）。所以类型相容校验把 LIST 字段锁在 HAS/NOT_HAS 上，
 * 反过来 HAS/NOT_HAS 也不许用在 NUM/TEXT/BOOL 上 —— 一套操作符对应一套语义，不重叠。
 *
 * <p>{@code symbol} 只用于文案/前端展示（与 {@code admin-web/src/constants/status.js}
 * 的 {@code AUDIT_OP_TEXT} 逐字对齐）。
 */
public enum OpCode {
    EQ("=", true),
    NE("≠", true),
    LT("<", true),
    LTE("≤", true),
    GT(">", true),
    GTE("≥", true),
    CONTAINS("含", true),
    NOT_CONTAINS("不含", true),
    /** 集合包含元素（LIST 专用；空集合判 false） */
    HAS("有标", true),
    /** 集合不含该元素（LIST 专用；空集合判 true —— 「没有这个标」在空集合上确实成立） */
    NOT_HAS("无标", true),
    EXISTS("有值", false),
    NOT_EXISTS("无值", false);

    private final String symbol;
    private final boolean needsValue;

    OpCode(String symbol, boolean needsValue) {
        this.symbol = symbol;
        this.needsValue = needsValue;
    }

    public String symbol() {
        return symbol;
    }

    /** 是否必须带配置值（一元操作符 EXISTS / NOT_EXISTS 不带） */
    public boolean needsValue() {
        return needsValue;
    }

    /** 解析：大小写不敏感；不认识返回 null（调用方给出可用清单的报错） */
    public static OpCode of(String code) {
        if (code == null) return null;
        for (OpCode op : values()) {
            if (op.name().equalsIgnoreCase(code.trim())) return op;
        }
        return null;
    }
}
