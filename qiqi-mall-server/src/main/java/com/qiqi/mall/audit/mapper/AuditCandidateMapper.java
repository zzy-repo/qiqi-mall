package com.qiqi.mall.audit.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 本轮的候选订单。
 *
 * <p>范围刻意是「status = 7 **或** 有 active 的 FROZEN 类标」：
 * <ul>
 *   <li>只看 status=7 的话，第 2 步（已冻结 → SKIPPED）永远不会触发；</li>
 *   <li>把已冻结的单也算进来，是为了在决策台账里留下「这一轮它被跳过了」的一行 ——
 *       「为什么这张单一直没人审」的答案在台账里，而不是靠人去猜它的标。</li>
 * </ul>
 */
@Mapper
public interface AuditCandidateMapper {

    @Select("SELECT o.id FROM `order` o "
            + "WHERE o.status = 7 "
            + "   OR EXISTS (SELECT 1 FROM order_audit_tag t "
            + "               WHERE t.order_id = o.id AND t.active = 1 AND t.tag_code LIKE 'FROZEN%') "
            + "ORDER BY o.id ASC")
    List<Long> selectCandidateIds();
}
