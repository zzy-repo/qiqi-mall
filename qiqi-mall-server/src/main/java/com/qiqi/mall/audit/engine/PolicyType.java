package com.qiqi.mall.audit.engine;

/**
 * 策略类型（与 DDL 的 {@code ENUM('AUTO','BLOCK','EXCEPTION')} 同名同序）。
 *
 * <p>三类不是「优先级不同的同一种东西」，而是**判定链上三个不同位置**：
 * <ul>
 *   <li>{@link #BLOCK}：判定链第 3 步，命中即 BLOCKED（前置拦截，不再看后面）；</li>
 *   <li>{@link #EXCEPTION}：第 4 步，命中即打标 + 冻结（FROZEN）；</li>
 *   <li>{@link #AUTO}：第 5 步，条件全满足且无排除 → 自动过审。</li>
 * </ul>
 * 所以 {@code priority} 只在**同类型内**排序：一个 BLOCK 永远先于任何 AUTO，
 * 哪怕它的数字更大 —— 顺序由判定链定，不由配置定。
 */
public enum PolicyType {
    AUTO, BLOCK, EXCEPTION
}
