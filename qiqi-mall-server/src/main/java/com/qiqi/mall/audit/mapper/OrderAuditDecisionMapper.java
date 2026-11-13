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
 *
 * <h3>队列行为什么 JOIN {@code order} 而不是复用决策里的快照</h3>
 * 页面要的 4 个字段里，{@code shop_name / channel / gross_profit} <b>决策行没存快照</b>
 * （只有 {@code evidence_json.cost.gross_profit} 一份旁证），而 {@code item_kind_count} 任何表都没有：
 * 它是装配上下文时算出来的派生值（去重 {@code product_sku_id} 个数）。既然每行都必须回表/回明细，
 * 就统一直接读**权威表**（{@code order} + {@code order_item}）—— 与 {@code AuditContextLoader}
 * 装配判定上下文时读的是同一份事实：
 * <ul>
 *   <li>{@code order} 表：一次 JOIN 拿三个列，O(1)，与判定时同源；</li>
 *   <li>{@code order_item}：只对**本页这几十行**做相关子查询（走 {@code idx_order}），
 *       不拉全表 GROUP BY，翻页深也不会全表扫。</li>
 * </ul>
 * 若改用 {@code evidence_json} 复用快照：{@code gross_profit} 有、{@code shop_name/channel}
 * 没有、{@code item_kind_count} 没有 —— 一个字段变三种取法，值还会与当前库漂移
 * （快照是判定那一刻的，页面上「店铺」却是现在的，对不上时没人查得清）。宁可都读当前值。
 */
@Mapper
public interface OrderAuditDecisionMapper extends BaseMapper<OrderAuditDecision> {

    /** 该订单已有多少轮（round_no 用 MAX+1，唯一键 uk_order_round 兜并发） */
    @Select("SELECT COALESCE(MAX(round_no), 0) FROM order_audit_decision WHERE order_id = #{orderId}")
    int selectMaxRound(@Param("orderId") Long orderId);

    @Select("SELECT COALESCE(MAX(round_no), 0) FROM order_audit_decision")
    int selectGlobalMaxRound();

    /**
     * 人工队列：每单最新一轮决策，可按 result 过滤（单值；为空 = 不过滤）。
     *
     * <p>为什么按 MAX(id) 去重而不是 DISTINCT：一轮一行、id 单调，取最新行是确定的；
     * 用 round_no 去重会在「同一单不同 run 但轮次相同」的脏数据上给出随机结果。
     */
    @Select("<script>"
            + "SELECT d.id, d.order_id, d.run_id, d.round_no, d.result, d.matched_policy_id, d.matched_policy_name, "
            + "       d.matched_policy_version_no, d.matched_condition_json, d.excluded_by_json, d.miss_reason_json, "
            + "       d.downgrade_reason, d.would_be_auto_policy_name, d.order_status_seq, "
            + "       JSON_UNQUOTE(JSON_EXTRACT(d.evidence_json, '$.reason')) AS reason_text, d.evidence_json, d.created_at, "
            + "       o.order_sn, o.status AS order_status, o.total_amount, "
            + "       o.shop_name, o.channel, o.gross_profit, "
            + "       (SELECT COUNT(DISTINCT i.product_sku_id) FROM order_item i WHERE i.order_id = d.order_id) AS item_kind_count, "
            + "       (SELECT COALESCE(SUM(i.product_quantity), 0) FROM order_item i WHERE i.order_id = d.order_id) AS item_qty_total "
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
