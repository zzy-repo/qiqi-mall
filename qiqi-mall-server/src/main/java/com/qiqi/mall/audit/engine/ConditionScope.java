package com.qiqi.mall.audit.engine;

/**
 * 条件作用域。
 *
 * <p>{@link #POLICY} 只作用于所属策略；{@link #ALL_AUTO} 是**全局排除**（决策 C1：
 * 「客服备注含改」不属于任何一条策略，所有 AUTO 策略都要认它）。
 * 全局排除行的 {@code policy_id} 必须为 NULL（DDL CHECK 钉死）——
 * 挂到某条策略下面就会被那条策略的删除级联带走，于是「改一次客服备注」这个排除
 * 会在别人删掉一条不相干的策略后悄悄失效。
 */
public enum ConditionScope {
    POLICY, ALL_AUTO
}
