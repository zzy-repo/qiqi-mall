package com.qiqi.mall.audit.engine;

/**
 * 条件操作符（10 个，与设计定稿逐字一致）。
 *
 * <p>{@code CONTAINS / NOT_CONTAINS} 走 <b>内存 String.contains</b>，<b>绝不拼 SQL LIKE</b>：
 * 配置里的 {@code %} / {@code _} 是用户输入的普通字符，交给 LIKE 会被当通配符 ——
 * 「备注含 100%」于是会匹配上「备注含 1000」，判定结果与配置意图不一致且没人看得出原因。
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
