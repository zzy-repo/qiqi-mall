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
 *
 * <h3>队列行为什么还要补 {@code order_audit_tag} 的冻结事实（frozen / frozen_reason）</h3>
 * 「引擎这轮判了什么」（{@code d.result}）与「这单现在冻着没有」（{@code order_audit_tag}）是**两件事**：
 * <ul>
 *   <li>策略冻的（EXCEPTION 命中）：当轮 {@code result='FROZEN'}，同时打了 FROZEN 标 —— 两个事实都成立；</li>
 *   <li>人工冻的（后台点「冻结」）：**不产生新的决策行**，{@code result} 还是上一轮的
 *       MANUAL / NO_MATCH / AUTO_PASS…，但标是实打实 active 的。</li>
 * </ul>
 * 只查决策表的话，页面只能拿 {@code result === 'FROZEN'} 去猜「冻没冻」—— 人工冻的单永远猜不出来
 * （永远显示「冻结」按钮、点不到解冻）。所以这一行把标表的事实**显式**带出来，与 {@code result} 解耦。
 *
 * <p>标的口径与 {@link OrderAuditTagMapper#selectActiveFrozen} 一致：{@code active=1 AND tag_code LIKE 'FROZEN%'}。
 * 用前缀而不是等值，因为 FROZEN 有细分标（如 {@code FROZEN_RETURN_APPLY}），
 * 而解冻接口 {@link OrderAuditTagMapper#releaseFrozen} 也是按前缀一次放掉的 ——
 * 「队列说冻着」和「点解冻真的能解」必须是同一个判据，否则又会出一个点不动的按钮。
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
     * <p>每行额外带 {@code frozen}（该单当前是否有 active 的冻结标）/ {@code frozen_reason} /
     * {@code frozen_tag_code} —— 见类注释最后一段：人工冻的单没有新决策行，只能从标表读。
     *
     * <p>为什么按 MAX(id) 去重而不是 DISTINCT：一轮一行、id 单调，取最新行是确定的；
     * 用 round_no 去重会在「同一单不同 run 但轮次相同」的脏数据上给出随机结果。
     *
     * <p><b>期 1 新增：{@code tag} 参数（标码筛选），与 {@code result} 正交、可并列。</b>
     * 判据是「该单现在挂着一条 active 的同码标」，与「引擎这轮判了什么」无关 ——
     * 本期标只增不删，所以一单 result=MANUAL 也可能同时挂着 NO_POLICY_MATCH。
     * ⚠️ 这是**平行新增**的筛选入口，本期不动 {@code result} 那六个页签的语义（期 3 才改）。
     */
    @Select("<script>"
            + "SELECT d.id, d.order_id, d.run_id, d.round_no, d.result, d.matched_policy_id, d.matched_policy_name, "
            + "       d.matched_policy_version_no, d.matched_condition_json, d.excluded_by_json, d.miss_reason_json, "
            + "       d.downgrade_reason, d.would_be_auto_policy_name, d.order_status_seq, "
            + "       JSON_UNQUOTE(JSON_EXTRACT(d.evidence_json, '$.reason')) AS reason_text, d.evidence_json, d.created_at, "
            + "       o.order_sn, o.status AS order_status, o.total_amount, "
            + "       o.shop_name, o.channel, o.gross_profit, "
            + "       (SELECT COUNT(DISTINCT i.product_sku_id) FROM order_item i WHERE i.order_id = d.order_id) AS item_kind_count, "
            + "       (SELECT COALESCE(SUM(i.product_quantity), 0) FROM order_item i WHERE i.order_id = d.order_id) AS item_qty_total, "
            // ---- 冻结事实（来自标表，与 d.result 完全解耦；见类注释最后一段）----
            // 三列都是**相关子查询**而不是 JOIN：一单可能同时挂着 FROZEN + FROZEN_RETURN_APPLY 两条 active 标，
            // JOIN 会把这一行复制成 2 行（分页 total 与行数立刻对不上）。子查询只对**本页这几十行**执行，
            // 走 idx_order(order_id, tag_code)，不是 N+1（没有第二次往返、没有 O(行数) 次查询）。
            + "       EXISTS(SELECT 1 FROM order_audit_tag t WHERE t.order_id = d.order_id "
            + "              AND t.active = 1 AND t.tag_code LIKE 'FROZEN%') AS frozen, "
            + "       (SELECT t.reason FROM order_audit_tag t WHERE t.order_id = d.order_id "
            + "        AND t.active = 1 AND t.tag_code LIKE 'FROZEN%' ORDER BY t.id ASC LIMIT 1) AS frozen_reason, "
            + "       (SELECT t.tag_code FROM order_audit_tag t WHERE t.order_id = d.order_id "
            + "        AND t.active = 1 AND t.tag_code LIKE 'FROZEN%' ORDER BY t.id ASC LIMIT 1) AS frozen_tag_code, "
            // 原因字典 ID（P2）：中文名/原因码由 AuditQueryService 用内存字典翻出来。
            // 为什么不在这里 JOIN 字典：会变成第 5 个同构相关子查询，或者要把上面三个改写成
            // JOIN LATERAL（MySQL 8.0.14+）—— 为了"形态统一"去重写 P0 刚验证过的 SQL，风险不对等。
            // 字典只有十几行且全量在内存里，翻名字是零成本。
            + "       (SELECT t.reason_id FROM order_audit_tag t WHERE t.order_id = d.order_id "
            + "        AND t.active = 1 AND t.tag_code LIKE 'FROZEN%' ORDER BY t.id ASC LIMIT 1) AS frozen_reason_id "
            + "FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
            + "WHERE d.id IN (SELECT MAX(id) FROM order_audit_decision GROUP BY order_id) "
            // 标筛选（期 1 新增，**平行于 result**，不改它）：命中即「该单挂着一条 active 的同码标」。
            // 用 EXISTS 而不是 JOIN 的理由同上：一单多标时 JOIN 会把队列行复制成多行。
            + "<if test='tag != null and tag != \"\"'> AND EXISTS (SELECT 1 FROM order_audit_tag ft "
            + "     WHERE ft.order_id = d.order_id AND ft.active = 1 AND ft.tag_code = #{tag})</if> "
            + "<if test='result != null and result != \"\"'> AND d.result = #{result}</if> "
            + "ORDER BY d.id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<Map<String, Object>> selectQueue(@Param("result") String result, @Param("tag") String tag,
                                          @Param("offset") int offset, @Param("limit") int limit);

    /**
     * {@link #selectQueue} 的计数口径 —— <b>必须与它逐条同构</b>（同一个 FROM/JOIN、同一组 where）。
     *
     * <h3>为什么这里也非得 JOIN {@code order}</h3>
     * 决策台账<b>刻意不建指向 {@code order} 的外键</b>（V1：「证据不随订单级联删除」），
     * 所以「订单已删、决策行还在」的<b>孤儿行</b>是合法数据。曾有一版 countQueue 少了这个 JOIN：
     * <pre>
     *   selectQueue: … FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id  ⇒ 孤儿行不在 rows 里
     *   countQueue : … FROM order_audit_decision d（无 JOIN）                          ⇒ 孤儿行被算进 total
     *   ⇒ total &gt; 行数，分页器多出一页空白（dev 库实测 total=4 / rows=2）
     * </pre>
     * 修的时候只补 JOIN 是不够的：{@code result} 与 {@code tag} 两条 {@code <if>} 也必须与 selectQueue
     * <b>逐条一致</b>（{@code tag} 走 EXISTS 而不是 JOIN —— 一单多标时 JOIN 会把行复制成多行，那又是一种口径漂移）。
     * 这条不变量由 {@code AuditQueueCountParityIntegrationTest} 用「造一条孤儿行」钉住，别再靠删库里那几行治症状。
     */
    @Select("<script>"
            + "SELECT COUNT(*) FROM order_audit_decision d JOIN `order` o ON o.id = d.order_id "
            + "WHERE d.id IN (SELECT MAX(id) FROM order_audit_decision GROUP BY order_id) "
            + "<if test='tag != null and tag != \"\"'> AND EXISTS (SELECT 1 FROM order_audit_tag ft "
            + "     WHERE ft.order_id = d.order_id AND ft.active = 1 AND ft.tag_code = #{tag})</if> "
            + "<if test='result != null and result != \"\"'> AND d.result = #{result}</if>"
            + "</script>")
    long countQueue(@Param("result") String result, @Param("tag") String tag);

    /**
     * 一页里这些订单身上的 active 标（期 1：队列行要显示「这单有哪些标」）。
     *
     * <p>为什么是**一次批量查询**而不是第 6 个相关子查询（也不是 JOIN）：
     * <ul>
     *   <li>JOIN 会复制行（一单 3 个标 → 队列里 3 行），分页 total 与行数当场对不上；</li>
     *   <li>第 6 个同构子查询只能回一个聚合串，拿不到**打标时间**（抽屉要逐条显示"什么时候打的"），
     *       而 P0 那三段 SQL 刚刚验证过，没必要为了"形态统一"再动它一次；</li>
     *   <li>一次 {@code WHERE order_id IN (本页 ≤100 个 id)} 走 {@code idx_order}：1 次往返、不走全表，
     *       比逐行查询少 N 次往返，比子查询多不了几个字节。</li>
     * </ul>
     */
    /**
     * 一页里这些订单身上的 active 标（期 1：队列行要显示「这单有哪些标」；期 3：影子字段/主标要用）。
     *
     * <p>期 3 起多回一列 {@code id}：显示层的压缩规则「阻断型优先 → <b>同类型取最新</b>」需要一个
     * 可判定的"最新"，而 {@code id} 单调（{@code created_at} 是毫秒截断，同毫秒多标会并列）。
     * 它<b>只在服务端排序用</b>，不出现在接口返回体里（跨库不稳定的代理键，界面上永远用 {@code tag_code}）。
     *
     * <p>排序 {@code ORDER BY order_id, id} 是契约：服务端据此认为"越靠后越新"，不必再自己排一遍。
     */
    @Select("<script>"
            + "SELECT id, order_id, tag_code, reason, created_at FROM order_audit_tag "
            + "WHERE active = 1 AND order_id IN "
            + "<foreach collection='orderIds' item='oid' open='(' separator=',' close=')'>#{oid}</foreach> "
            + "ORDER BY order_id ASC, id ASC"
            + "</script>")
    List<Map<String, Object>> selectActiveTagsByOrders(@Param("orderIds") java.util.List<Long> orderIds);

    /**
     * 从**没被判定过**的订单数（一行决策都没有）。
     *
     * <p>为什么它要单独存在：人工队列是 {@code FROM order_audit_decision} 的，所以这类单永远不会出现在
     * 队列里 —— 于是「切换口径会不会漏掉一批单」这个问题，光扫队列是看不见的。
     * 它们是"标驱动结论"答不出来的最极端一类：标没有、决策行也没有 ⇒ 结论来源 {@code NONE}。
     */
    @Select("SELECT COUNT(*) FROM `order` o "
            + "WHERE NOT EXISTS (SELECT 1 FROM order_audit_decision d WHERE d.order_id = o.id)")
    long countOrdersWithoutAnyDecision();

    @Select("SELECT * FROM order_audit_decision WHERE order_id = #{orderId} ORDER BY round_no ASC, id ASC")
    List<OrderAuditDecision> selectByOrder(@Param("orderId") Long orderId);

    @Select("SELECT * FROM order_audit_decision WHERE run_id = #{runId} ORDER BY order_id ASC")
    List<OrderAuditDecision> selectByRun(@Param("runId") Long runId);

    @Select("SELECT result, COUNT(*) AS cnt FROM order_audit_decision WHERE run_id = #{runId} GROUP BY result")
    List<Map<String, Object>> countByRunGroupByResult(@Param("runId") Long runId);
}
