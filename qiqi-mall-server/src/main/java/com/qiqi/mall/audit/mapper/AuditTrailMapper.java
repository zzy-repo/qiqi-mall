package com.qiqi.mall.audit.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 审核时间线的三段查询（决策 + 订单状态台账 + 后台操作审计）。
 *
 * <p>三段各自独立查询、在服务层按时间合并 —— 不做 SQL UNION：
 * 三张表的列语义不同（决策讲「怎么判的」、状态台账讲「状态怎么变」、操作审计讲「谁点了什么」），
 * UNION 会把它们压平成同一个形状，前端反而再也分不出哪一行是哪一类证据。
 */
@Mapper
public interface AuditTrailMapper {

    @Select("SELECT id, round_no, result, matched_policy_name, matched_policy_version_no, downgrade_reason, "
            + "JSON_UNQUOTE(JSON_EXTRACT(evidence_json, '$.reason')) AS reason, created_at "
            + "FROM order_audit_decision WHERE order_id = #{orderId} ORDER BY round_no ASC, id ASC")
    List<Map<String, Object>> selectDecisions(@Param("orderId") Long orderId);

    @Select("SELECT from_status, to_status, reason, reason_detail, operator_type, operator_name, ref_type, ref_id, created_at "
            + "FROM order_status_ledger WHERE order_id = #{orderId} ORDER BY created_at ASC, id ASC")
    List<Map<String, Object>> selectStatusLedger(@Param("orderId") Long orderId);

    @Select("SELECT action, target_type, summary, reason, operator_type, operator_name, created_at "
            + "FROM admin_audit_log WHERE order_id = #{orderId} ORDER BY created_at ASC, id ASC")
    List<Map<String, Object>> selectAdminAudit(@Param("orderId") Long orderId);
}
