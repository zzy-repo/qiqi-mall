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
 * @param fieldCode 字段码
 * @param fieldLabel 字段中文名
 * @param opCode    操作符码
 * @param expected  门槛（已格式化）
 * @param actual    实际值（已格式化）
 * @param satisfied 是否满足
 * @param text      人话：「毛利 −12.00 ≤ 0」
 */
public record ConditionTrace(
        String fieldCode,
        String fieldLabel,
        String opCode,
        String expected,
        String actual,
        boolean satisfied,
        String text) {

    /** 落 JSON 列用（{@code matched_condition_json} / {@code excluded_by_json} / {@code miss_reason_json}） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", fieldCode);
        m.put("label", fieldLabel);
        m.put("op", opCode);
        m.put("expected", expected);
        m.put("actual", actual);
        m.put("satisfied", satisfied);
        m.put("text", text);
        return m;
    }
}
