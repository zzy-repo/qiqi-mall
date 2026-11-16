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
 *
 * <h3>对齐口径（决策 ↔ 台账行）</h3>
 * <ul>
 *   <li>{@code order_audit_decision.order_status_seq}：本轮判定结束时这张单的 {@code status_seq}
 *       （执行层在 {@code applyActions} 之后快照，所以 AUTO_PASS 那轮记的就是「推进出来的那一步」）；</li>
 *   <li>{@code order_status_ledger.idem_key}：格式 {@code [shard:]{orderId}:{statusSeq}}
 *       （见 {@code StatusLedgerWriter.idemKey}）—— 台账表**没有** status_seq 列，
 *       所以从键尾取，{@code SUBSTRING_INDEX(idem_key, ':', -1)} 取最后一段即可（有 shard 前缀也成立）；</li>
 *   <li>台账侧同时回 {@code ref_type/ref_id}：自动过审写的台账带 {@code ref_type='AUDIT_RUN'}、
 *       {@code ref_id=run_id}，这是**强对齐**（同一条决策的行）而不只是「序号恰好相同」。</li>
 * </ul>
 * 两列原本都没回传，页面只好显示「无法精确对齐」；补上之后按序号精确配对，
 * 绝不按「时间最接近」猜一条 —— 猜错的对齐比没有对齐更糟。
 */
@Mapper
public interface AuditTrailMapper {

    @Select("SELECT id, run_id, round_no, result, matched_policy_name, matched_policy_version_no, downgrade_reason, "
            + "JSON_UNQUOTE(JSON_EXTRACT(evidence_json, '$.reason')) AS reason, "
            + "order_status_seq, idem_key, created_at "
            + "FROM order_audit_decision WHERE order_id = #{orderId} ORDER BY round_no ASC, id ASC")
    List<Map<String, Object>> selectDecisions(@Param("orderId") Long orderId);

    /**
     * 状态台账段。<b>{@code status_seq} 不是表里的列</b>，由 {@code idem_key} 尾段派生
     * （{@code [shard:]{orderId}:{statusSeq}}）；解析不出数字时给 0 而不是 NULL —— 0 永远不会
     * 等于任何一行的决策 seq（决策 seq 也是 0 起，但 0 只对应出生事件），页面最多显示「未对齐」。
     */
    @Select("SELECT id, from_status, to_status, reason, reason_detail, operator_type, operator_name, "
            + "ref_type, ref_id, idem_key, "
            + "CAST(SUBSTRING_INDEX(idem_key, ':', -1) AS UNSIGNED) AS status_seq, created_at "
            + "FROM order_status_ledger WHERE order_id = #{orderId} ORDER BY created_at ASC, id ASC")
    List<Map<String, Object>> selectStatusLedger(@Param("orderId") Long orderId);

    @Select("SELECT id, action, target_type, summary, reason, operator_type, operator_name, idem_key, created_at "
            + "FROM admin_audit_log WHERE order_id = #{orderId} ORDER BY created_at ASC, id ASC")
    List<Map<String, Object>> selectAdminAudit(@Param("orderId") Long orderId);

    /**
     * 一批订单<b>最后一次「离开待审核(7)」</b>的那行台账（{@code from_status = 7}）——
     * 对账端点用它回答"这张单是怎么离开待审核的、什么时候"。
     *
     * <h3>为什么这个信息只在台账里</h3>
     * {@code order.status} 只说"现在是什么"，说不出"怎么到的"：同样是 status=8，
     * 可能是人工放行（{@code AUDIT_PASS}）、自动过审（{@code AUDIT_AUTO_PASS}）或包裹聚合推导
     * （{@code DELIVERY_AGGREGATE}）。而"该不该有终局结论标"恰恰取决于这一列 ——
     * 聚合推导那支按设计不落标，只有结算的四条路必须落。
     *
     * <p>一次批量查（{@code order_id IN (本页 ≤200 个 id)}），不逐单回表：
     * 分组取 {@code MAX(id)} 再 JOIN 回本表，走 {@code idx_order}，不是 N+1。
     *
     * @return 每单一行：{@code order_id / to_status / reason / created_at}
     */
    @Select("<script>"
            + "SELECT l.order_id, l.to_status, l.reason, l.created_at "
            + "FROM order_status_ledger l "
            + "JOIN (SELECT order_id, MAX(id) AS mid FROM order_status_ledger "
            + "      WHERE from_status = 7 AND order_id IN "
            + "      <foreach item='oid' collection='orderIds' open='(' separator=',' close=')'>#{oid}</foreach>"
            + "      GROUP BY order_id) m ON m.mid = l.id "
            + "</script>")
    List<Map<String, Object>> selectLeaveAuditPendingBatch(@Param("orderIds") java.util.List<Long> orderIds);
}
