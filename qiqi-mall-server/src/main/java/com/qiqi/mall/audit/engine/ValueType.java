package com.qiqi.mall.audit.engine;

/**
 * 条件值的类型：决定读 {@code audit_policy_condition} 的哪个 value_* 列。
 *
 * <p><b>字段</b>的值类型决定读哪一列；<b>条件</b>的值类型决定写哪一列（保存策略时由字段定义反向钉住，
 * 客户端传什么都不采信）。DDL 上这个枚举是 {@code ENUM('NUM','TEXT','BOOL','JSON','LIST')}
 * （{@code LIST} 由 V5 加），且有一条 CHECK 钉住「value_type ↔ 值列」的对应关系。
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
    JSON,
    /**
     * 多值集合（期 2 步骤 1）：字段的取值是<b>一列元素</b>（如「该单当前挂着的标码集合」），
     * 条件侧要问的是「集合里有没有这个元素」。
     *
     * <h4>为什么必须是独立类型，而不是把逗号串塞进 TEXT</h4>
     * TEXT 上只有 {@code CONTAINS}，而 {@code CONTAINS} 是<b>子串</b>语义：
     * 标码 {@code FROZEN_RETURN_APPLY} 会让 {@code CONTAINS 'FROZEN'} 命中 —— 一条「有没有冻结标」
     * 的条件会命中所有细分冻结标，「有客诉标」会命中「有客诉待回复标」。这类错判不报错、不留痕，
     * 只会让策略静默地多拦/少拦一批单。所以：<b>LIST = 集合，只能用 {@code HAS / NOT_HAS}</b>，
     * 求值是「元素相等」而不是「字符串包含」（实现见 {@code ConditionEvaluator#evaluateList}）。
     *
     * <h4>值落在哪一列</h4>
     * {@code value_text}（存<b>一个</b>元素码；集合在字段侧，条件侧只问"有没有这一个"）。
     * 不用 {@code value_json}：那是"一个条件里带多个候选值"的预留位置，与「集合包含」不是一回事。
     */
    LIST
}
