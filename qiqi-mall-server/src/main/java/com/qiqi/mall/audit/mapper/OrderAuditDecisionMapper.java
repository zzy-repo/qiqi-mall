package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.OrderAuditDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 决策台账 DAO（只追加）。
 *
 * <p>导航口径：一张单可以有很多轮（每轮一行），运营要看的是「<b>这张单现在是什么状态</b>」——
 * 所以队列查询取的是每单 {@code MAX(id)} 那一行（最新一轮），而不是全量流水；
 * 全量流水留给 {@code audit-trail}。
 */
@Mapper
public interface OrderAuditDecisionMapper extends BaseMapper<OrderAuditDecision> {

    /** 该订单已有多少轮（round_no 用 MAX+1，唯一键 uk_order_round 兜并发） */
    @Select("SELECT COALESCE(MAX(round_no), 0) FROM order_audit_decision WHERE order_id = #{orderId}")
    int selectMaxRound(@Param("orderId") Long orderId);

    @Select("SELECT COALESCE(MAX(round_no), 0) FROM order_audit_decision")
    int selectGlobalMaxRound();

    /**
     * 人工队列：每单最新一轮决策，可按 result 过滤。
     *
     * <p>为什么按 MAX(id) 去重而不是 DISTINCT：一轮一行、id 单调，取最新行是确定的；
     * 用 round_no 去重会在「同一单不同 run 但轮次相同」的脏数据上给出随机结果。
     */
    @Select("<script>"
            + "SELECT d.id, d.order_id, d.run_id, d.round_no, d.result, d.matched_policy_id, d.matched_policy_name, "
            + "       d.downgrade_reason, d.would_be_auto_policy_name, JSON_UNQUOTE(JSON_EXTRACT(d.evidence_json, '$.reason')) AS reason_text, d.created_at, "
            + "       o.order_sn, o.status AS order_status, o.total_amount "
            + "FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
            + "WHERE d.id IN (SELECT MAX(id) FROM order_audit_decision GROUP BY order_id) "
            + "<if test='result != null and result != \"\"'> AND d.result = #{result}</if> "
            + "ORDER BY d.id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<Map<String, Object>> selectQueue(@Param("result") String result,
                                          @Param("offset") int offset, @Param("limit") int limit);

    @Select("<script>"
            + "SELECT COUNT(*) FROM order_audit_decision d "
            + "WHERE d.id IN (SELECT MAX(id) FROM order_audit_decision GROUP BY order_id) "
            + "<if test='result != null and result != \"\"'> AND d.result = #{result}</if>"
            + "</script>")
    long countQueue(@Param("result") String result);

    @Select("SELECT * FROM order_audit_decision WHERE order_id = #{orderId} ORDER BY round_no ASC, id ASC")
    List<OrderAuditDecision> selectByOrder(@Param("orderId") Long orderId);

    @Select("SELECT * FROM order_audit_decision WHERE run_id = #{runId} ORDER BY order_id ASC")
    List<OrderAuditDecision> selectByRun(@Param("runId") Long runId);

    @Select("SELECT result, COUNT(*) AS cnt FROM order_audit_decision WHERE run_id = #{runId} GROUP BY result")
    List<Map<String, Object>> countByRunGroupByResult(@Param("runId") Long runId);
}
