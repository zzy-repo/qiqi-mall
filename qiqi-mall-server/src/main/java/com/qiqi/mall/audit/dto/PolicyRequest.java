package com.qiqi.mall.audit.dto;

import java.util.List;

/**
 * 策略入参。
 *
 * @param type             AUTO / BLOCK / EXCEPTION
 * @param priority         同类型内升序（默认 100）
 * @param name             策略名（同类型内唯一）
 * @param enabled          默认 true
 * @param actionTag        EXCEPTION 的冻结标码（默认 FROZEN；AUTO/BLOCK 传了会被忽略）
 * @param actionReason     EXCEPTION 的冻结原因（默认用策略名）
 * @param conditions       本策略的条件（kind MATCH/EXCLUDE，scope 一律 POLICY）
 * @param globalExclusions 全局排除（scope=ALL_AUTO）。**null = 不动全局排除**；非 null = 整套替换。
 *                         为什么用 null 区分：全局排除没有归属策略，若每次保存策略都重写它，
 *                         改策略 B 会顺手把在策略 A 里配的全局排除抹掉。
 */
public record PolicyRequest(
        String type,
        Integer priority,
        String name,
        Boolean enabled,
        String actionTag,
        String actionReason,
        List<ConditionRequest> conditions,
        List<ConditionRequest> globalExclusions) {
}
