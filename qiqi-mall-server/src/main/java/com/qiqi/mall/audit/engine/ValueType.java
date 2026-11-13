package com.qiqi.mall.audit.engine;

/**
 * 条件值的类型：决定读 {@code audit_policy_condition} 的哪个 value_* 列。
 *
 * <p>DDL 上这个枚举是 {@code ENUM('NUM','TEXT','BOOL','JSON')}，且有一条 CHECK 钉住
 * 「value_type ↔ 值列」的对应关系（见 {@code sql/migrate-order-audit.sql}）。
 * 这里保持同名同序 —— 名字一旦漂移，读出来的条件就会和配置的不是同一条。
 */
public enum ValueType {
    /** 数值：金额 / 件数 / 小时数 */
    NUM,
    /** 文本：渠道 / 店铺 / 备注 / 城市 / 标签 */
    TEXT,
    /** 布尔：用 1 / 0 落在 value_num 上 */
    BOOL,
    /** 预留：IN 列表 / 区间等复合值，本轮引擎不消费（配置了会直接报错，而不是静默忽略） */
    JSON
}
