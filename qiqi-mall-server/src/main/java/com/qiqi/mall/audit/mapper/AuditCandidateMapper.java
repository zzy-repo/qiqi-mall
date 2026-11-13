package com.qiqi.mall.audit.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 本轮的候选订单。
 *
 * <p>范围刻意是「status = 7 **或** 有 active 的 FROZEN 类标」：
 * <ul>
 *   <li>只看 status=7 的话，第 2 步（已冻结 → SKIPPED）永远不会触发，冻结单就会带着
 *       「最新状态仍是待审核」一路排进人工审核队列；</li>
 *   <li>把已冻结的单也算进来，是为了让它<b>每轮都被检查一次、每轮都能在被解冻的当轮就重新判定</b>
 *       （解冻后它可能立刻满足 AUTO 条件）；</li>
 *   <li>但「每轮跳过它」这件事<b>不再写决策行</b>：冻结的原因在 {@code order_audit_tag}，
 *       每轮补一行 SKIPPED 只会把 {@code round_no} 撑大、把证据表塞满空行。
 *       跳过的笔数由 run 的 {@code skipped} 计数体现（{@code RunResultDto#skipped()}）。</li>
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
