package com.qiqi.mall.shared.ledger;

/**
 * 台账里**自由文本**列的安全裁剪（唯一用途：用户输入的长文本 + 展示型快照列）。
 *
 * <h3>为什么只裁自由文本，不裁原因码/类型/操作者</h3>
 * 两类文本在台账里的地位完全不同：
 * <ul>
 *   <li><b>代码常量</b>（{@code reason} / {@code action} / {@code target_type}）：宽度是契约的一部分，
 *       超宽说明常量写错了 —— 必须**抛**（STRICT 模式下 MySQL 直接报 1406，业务回滚），
 *       由 {@code ReasonCodesTest} 在单测里提前拦住，绝不能悄悄截断成另一个合法值；</li>
 *   <li><b>自由文本</b>（审核意见、退货说明、摘要句）：长度由用户决定，而台账列宽有限
 *       （{@code reason_detail VARCHAR(255)}、{@code summary VARCHAR(255)}）。这里若也抛，
 *       用户填了 300 字的退货说明就会把整个审核事务打回 —— 那是**新增的业务故障**，
 *       与「台账不该改变业务行为」直接冲突。</li>
 * </ul>
 * 所以这里裁的是自由文本，并且裁的时候明确留一个省略号：读台账的人一眼能看出「这里被截过」，
 * 完整原文仍在业务表（{@code return_apply.description} 等）里。
 */
public final class LedgerText {

    private static final String ELLIPSIS = "…";

    private LedgerText() {
    }

    /**
     * 裁到 {@code width} 个字符以内（按字符而不是字节：列是 utf8mb4 VARCHAR，宽度单位就是字符）。
     *
     * @param value 原文，null 原样返回 null
     * @param width 目标列宽（VARCHAR(n) 的 n）
     */
    public static String clip(String value, int width) {
        if (value == null || width <= 0) return value;
        if (value.length() <= width) return value;
        return value.substring(0, width - ELLIPSIS.length()) + ELLIPSIS;
    }
}
