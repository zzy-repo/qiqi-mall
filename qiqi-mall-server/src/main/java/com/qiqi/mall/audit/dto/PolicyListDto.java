package com.qiqi.mall.audit.dto;

import java.util.List;

/**
 * 策略页一次拿全：策略 + 全局排除 + 可选字段 + 可选操作符。
 *
 * <p>为什么把元数据也放进来：字段/操作符的闭集住在后端（FieldRegistry / OpCode），
 * 前端若自己抄一份，加一个字段就要两边改、还会悄悄漂移（前端有个「毛利」后端没有 = 配了永远不命中）。
 */
public record PolicyListDto(List<PolicyDto> policies,
                            List<ConditionDto> globalExclusions,
                            List<FieldMetaDto> fields,
                            List<String> operators) {
}
