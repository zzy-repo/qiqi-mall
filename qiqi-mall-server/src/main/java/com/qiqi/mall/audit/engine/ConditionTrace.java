package com.qiqi.mall.audit.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条条件的求值留痕：**expected vs actual + 一句可以直接给人看的话**。
 *
 * <p>为什么判定结果必须带「具体数值差」而不是一个布尔：运营看到「未通过」只会来问
 * 「为什么没通过」。{@code 毛利 −12.00 ≤ 0} 这种文案把「哪个字段、现在多少、门槛多少」
 * 一次说完，才不需要人再把订单翻出来算一遍。
 *
 * <h3>为什么还要 {@code seq} 与 {@code evaluated}（前端「差在哪」抽屉的三态）</h3>
 * 条件之间是 <b>AND</b>：首个不满足之后的条件不再决定结论。前端必须能区分三种状态 ——
 * 满足（绿）/ 不满足（红，就是「差在哪」）/ <b>未求值</b>（灰，不在结论链上）。
 * 只给「失败条件的文案」时，前端只能按 AND 短路规则**自己猜**哪些是未求值，猜错就把
 * 「引擎根本没比过」说成「比对过且不满足」。所以口径落在这里，而不是前端：
 * <ul>
 *   <li>{@code seq}：条件在策略里的求值/展示顺序（{@code audit_policy_condition.seq}）；</li>
 *   <li>{@code satisfied}：这条条件成立吗（引擎口径：实际值 null 一律不满足，{@code null ≠ 0}）；</li>
 *   <li>{@code evaluated}：这条条件是否真的在 AND 链上被求值到。
 *       首个不满足之后的条件标 {@code false}（结论已定），但 {@code expected/actual} <b>照实给出</b> ——
 *       引擎是「全部求值」的（要一次说清差在哪几条），值是真的，只是不再是结论依据。</li>
 * </ul>
 *
 * @param seq       求值/展示顺序（可为 null = 未落库的临时条件）
 * @param fieldCode 字段码
 * @param fieldLabel 字段中文名
 * @param opCode    操作符码
 * @param expected  门槛（已格式化）
 * @param actual    实际值（已格式化）
 * @param satisfied 是否满足
 * @param evaluated 是否在 AND 链上被求值到（见上）
 * @param text      人话：「毛利 −12.00 ≤ 0」
 */
public record ConditionTrace(
        Integer seq,
        String fieldCode,
        String fieldLabel,
        String opCode,
        String expected,
        String actual,
        boolean satisfied,
        boolean evaluated,
        String text) {

    /** 完整求值（{@code ConditionEvaluator} 的正常出口：这条确实算过） */
    public static ConditionTrace evaluated(Integer seq, String fieldCode, String fieldLabel, String opCode,
                                           String expected, String actual, boolean satisfied, String text) {
        return new ConditionTrace(seq, fieldCode, fieldLabel, opCode, expected, actual, satisfied, true, text);
    }

    /** 同一个事实换一个 {@code evaluated} 标记（不重算、不改值：引擎确实算过，只是不算结论依据） */
    public ConditionTrace withEvaluated(boolean value) {
        return value == evaluated ? this
                : new ConditionTrace(seq, fieldCode, fieldLabel, opCode, expected, actual, satisfied, value, text);
    }

    /**
     * 落 JSON 列用（{@code matched_condition_json} / {@code excluded_by_json} / {@code miss_reason_json}）。
     *
     * <p>键名与前端 {@code useAuditExplain.fromBackendMiss} 对齐（它读 {@code fieldCode/fieldLabel/opCode/
     * satisfied/evaluated}，也兼容旧的短键 {@code field/label/op}）。{@code reason} 与 {@code text} 同义：
     * 前者是新口径的名字，后者是旧模板读的键，两个都给免得下游有一边空白。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("fieldCode", fieldCode);
        m.put("fieldLabel", fieldLabel);
        m.put("opCode", opCode);
        m.put("expected", expected);
        m.put("actual", actual);
        m.put("satisfied", satisfied);
        m.put("evaluated", evaluated);
        m.put("reason", text);
        // 兼容键：老的 matched_condition_json 消费方（含历史 SQL 断言）按这三个短键读
        m.put("field", fieldCode);
        m.put("label", fieldLabel);
        m.put("op", opCode);
        m.put("text", text);
        return m;
    }
}
