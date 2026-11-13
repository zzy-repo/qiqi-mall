package com.qiqi.mall.shared.ledger.mapper;

/**
 * 台账查询 SQL 的**唯一出处**（{@link LedgerQueryMapper} 用 {@code @Select} 引用这里的常量）。
 *
 * <h3>为什么是常量类而不是 XML / Provider</h3>
 * 本仓库 mapper 目录零 XML，自定义 SQL 一律写在 {@code @Select} 注解里。
 * 但「六个分支」的列清单很长，直接塞进注解会让注解不可读 —— 所以拆成<b>编译期常量</b>：
 * 注解值允许常量表达式（JLS 9.7.1），拼接后仍是同一个 {@code @Select}，
 * 既不引入 XML，也不引入 {@code @SelectProvider}（SQL 在编译期就固定下来，能一眼看全）。
 *
 * <h3>六个分支（与前端 {@code LEDGER_TYPE_TEXT} 逐字对应）</h3>
 * <ul>
 *   <li>{@code ORDER_STATUS} / {@code DELIVERY_STATUS} / {@code RETURN_STATUS} —— 查视图
 *       {@code v_biz_status_timeline}（三张状态台账 UNION ALL 的只读派生，状态中文名也在视图里）；</li>
 *   <li>{@code AUDIT} —— {@code admin_audit_log}（后台操作审计，带 target 单号快照）；</li>
 *   <li>{@code WMS} —— {@code wms_exchange_ledger}（REQ/ACK 两阶段）；</li>
 *   <li>{@code STOCK} —— {@code stock_ledger}（库存扣/补流水）。</li>
 * </ul>
 *
 * <h3>「全部类型」用视图、「单类型」直查</h3>
 * 分支由 service 传入（{@code branches} 参数，一个或六个），UNION 只拼**传进来的**那些。
 * 所以「全部类型」= 六个分支 UNION；「单类型」= 只有一个分支 —— 视图那一支上
 * MySQL 还会把 {@code biz_type = 'X'} 推到各 UNION 分支、另外两支判 {@code Impossible WHERE}
 * 直接不扫（EXPLAIN 实测），等价于直查对应的那张台账表。
 *
 * <h3>列序（16 列，所有分支逐字一致）</h3>
 * {@code type, id, biz_id, biz_sn, from_status, to_status, reason, reason_detail,
 * operator_type, operator_id, operator_name, ref_type, ref_id, summary, created_at}
 * —— 与 {@link com.qiqi.mall.shared.ledger.dto.LedgerRow} 一一对应（下划线 → 驼峰自动映射）。
 *
 * <h3>三个口径决定（改之前先读）</h3>
 * <ol>
 *   <li><b>三个状态分支的 id 不是同一个序列</b>（三张台账各自的 AUTO_INCREMENT），合并后
 *       {@code id} 会重复。前端只把它当行标识（{@code data-testid="ledger-row-{id}"}），
 *       排序用 {@code (created_at, id)}，不假设全局唯一。</li>
 *   <li><b>{@code stock_ledger} 没有 operator 列</b>：这里如实写 {@code 'SYSTEM'}（记账动作由系统完成），
 *       并且**按 operatorType 过滤时 STOCK 分支不参与**（{@code 1 = 0}）——
 *       否则「筛管理员」会混进一堆没人手工碰过的系统记账行。</li>
 *   <li><b>bizId 在每个分支里就是该台账自己的业务主键</b>（订单 id / 包裹 id / 售后 id /
 *       target_id / delivery_id / order_id）。跨分支的「这行属于哪张订单」两个状态分支<b>都读视图的
 *       {@code order_id} 真实列</b>：包裹分支读的是已登记冗余列
 *       {@code delivery_status_ledger.order_id}（2026-11-13 补登，与售后台账对称），
 *       售后分支读 {@code return_status_ledger.order_id}。
 *       <b>包裹分支额外保留 {@code delivery_order} 搭桥做兜底</b>：迁移前写入的历史行可能
 *       order_id 回填不到（包裹那时已被物理删除），桥上还通的行仍要能查到 ——
 *       但桥一断就只剩冗余列，这正是补这一列的原因。</li>
 * </ol>
 *
 * <p><b>关于三处 {@code WHERE 1 = 1}</b>：审计 / WMS / 库存三支本身没有 WHERE，而可选过滤条件
 * 都以 {@code AND} 开头拼接 —— 没有这个锚点就成了 {@code FROM t AND cond} 的语法错误
 * （第一次跑就撞上了）。它不产生任何执行成本（优化器直接消掉）。
 */
final class LedgerSql {

    private LedgerSql() {
    }

    /** ============================================================ 订单状态分支（视图 ORDER） */
    private static final String ORDER_STATUS_BRANCH = """
            SELECT 'ORDER_STATUS' AS type,
                   v.id AS id,
                   v.biz_id AS biz_id,
                   o.order_sn AS biz_sn,
                   v.from_status AS from_status,
                   v.to_status AS to_status,
                   v.reason AS reason,
                   v.reason_detail AS reason_detail,
                   v.operator_type AS operator_type,
                   v.operator_id AS operator_id,
                   v.operator_name AS operator_name,
                   v.ref_type AS ref_type,
                   v.ref_id AS ref_id,
                   CONCAT('订单 ', COALESCE(o.order_sn, CAST(v.biz_id AS CHAR)), '：',
                          COALESCE(v.from_status_text, '出生'), ' → ',
                          COALESCE(v.to_status_text, CAST(v.to_status AS CHAR))) AS summary,
                   v.created_at AS created_at
              FROM v_biz_status_timeline v
              LEFT JOIN `order` o ON o.id = v.biz_id
             WHERE v.biz_type = 'ORDER'
            """;

    /** ============================================================ 包裹状态分支（视图 DELIVERY） */
    private static final String DELIVERY_STATUS_BRANCH = """
            SELECT 'DELIVERY_STATUS' AS type,
                   v.id AS id,
                   v.biz_id AS biz_id,
                   v.delivery_sn AS biz_sn,
                   v.from_status AS from_status,
                   v.to_status AS to_status,
                   v.reason AS reason,
                   v.reason_detail AS reason_detail,
                   v.operator_type AS operator_type,
                   v.operator_id AS operator_id,
                   v.operator_name AS operator_name,
                   v.ref_type AS ref_type,
                   v.ref_id AS ref_id,
                   CONCAT('包裹 ', COALESCE(v.delivery_sn, CAST(v.biz_id AS CHAR)), '：',
                          COALESCE(v.from_status_text, '建包'), ' → ',
                          COALESCE(v.to_status_text, CAST(v.to_status AS CHAR))) AS summary,
                   v.created_at AS created_at
              FROM v_biz_status_timeline v
             WHERE v.biz_type = 'DELIVERY'
            """;

    /** ============================================================ 售后状态分支（视图 RETURN） */
    private static final String RETURN_STATUS_BRANCH = """
            SELECT 'RETURN_STATUS' AS type,
                   v.id AS id,
                   v.biz_id AS biz_id,
                   o.order_sn AS biz_sn,
                   v.from_status AS from_status,
                   v.to_status AS to_status,
                   v.reason AS reason,
                   v.reason_detail AS reason_detail,
                   v.operator_type AS operator_type,
                   v.operator_id AS operator_id,
                   v.operator_name AS operator_name,
                   v.ref_type AS ref_type,
                   v.ref_id AS ref_id,
                   CONCAT('售后 #', CAST(v.biz_id AS CHAR), '（订单 ',
                          COALESCE(o.order_sn, CAST(v.order_id AS CHAR)), '）：',
                          COALESCE(v.from_status_text, '提交'), ' → ',
                          COALESCE(v.to_status_text, CAST(v.to_status AS CHAR))) AS summary,
                   v.created_at AS created_at
              FROM v_biz_status_timeline v
              LEFT JOIN `order` o ON o.id = v.order_id
             WHERE v.biz_type = 'RETURN'
            """;

    /** ============================================================ 后台审计分支 */
    private static final String AUDIT_BRANCH = """
            SELECT 'AUDIT' AS type,
                   a.id AS id,
                   a.target_id AS biz_id,
                   a.target_sn AS biz_sn,
                   CAST(NULL AS SIGNED) AS from_status,
                   CAST(NULL AS SIGNED) AS to_status,
                   a.action AS reason,
                   a.reason AS reason_detail,
                   a.operator_type AS operator_type,
                   a.operator_id AS operator_id,
                   a.operator_name AS operator_name,
                   a.target_type AS ref_type,
                   a.target_id AS ref_id,
                   a.summary AS summary,
                   a.created_at AS created_at
              FROM admin_audit_log a
             WHERE 1 = 1
            """;

    /** ============================================================ WMS 下发/回传分支 */
    private static final String WMS_BRANCH = """
            SELECT 'WMS' AS type,
                   w.id AS id,
                   w.delivery_id AS biz_id,
                   d.delivery_sn AS biz_sn,
                   CAST(NULL AS SIGNED) AS from_status,
                   CAST(NULL AS SIGNED) AS to_status,
                   w.direction AS reason,
                   w.result AS reason_detail,
                   w.operator_type AS operator_type,
                   w.operator_id AS operator_id,
                   w.operator_name AS operator_name,
                   'DELIVERY' AS ref_type,
                   w.delivery_id AS ref_id,
                   CONCAT('WMS ', w.direction, ' 第 ', w.attempt_no, ' 次',
                          IF(w.result IS NULL, '', CONCAT('：', w.result)),
                          IF(w.latency_ms IS NULL, '', CONCAT('（', w.latency_ms, 'ms）'))) AS summary,
                   w.created_at AS created_at
              FROM wms_exchange_ledger w
              LEFT JOIN delivery_order d ON d.id = w.delivery_id
             WHERE 1 = 1
            """;

    /** ============================================================ 库存流水分支 */
    private static final String STOCK_BRANCH = """
            SELECT 'STOCK' AS type,
                   s.id AS id,
                   s.order_id AS biz_id,
                   o.order_sn AS biz_sn,
                   CAST(NULL AS SIGNED) AS from_status,
                   CAST(NULL AS SIGNED) AS to_status,
                   s.reason AS reason,
                   CASE WHEN s.bucket_no IS NULL THEN NULL
                        ELSE CONCAT('落点桶 ', s.bucket_no) END AS reason_detail,
                   'SYSTEM' AS operator_type,
                   CAST(NULL AS SIGNED) AS operator_id,
                   CAST(NULL AS CHAR(64)) AS operator_name,
                   s.ref_type AS ref_type,
                   s.ref_id AS ref_id,
                   CONCAT('库存 ', IF(s.delta > 0, '+', ''), s.delta, '（', s.reason, '）') AS summary,
                   s.created_at AS created_at
              FROM stock_ledger s
              LEFT JOIN `order` o ON o.id = s.order_id
             WHERE 1 = 1
            """;

    // ============================================================ 各分支的可选过滤条件
    // 写在分支内（而不是外层 WHERE）的原因：外层过滤发生在 UNION 结果上，MySQL 得先 materialize
    // 六个分支再筛；写在分支里则每支各自走自己的索引（idx_biz / idx_order / idx_target /
    // idx_delivery / idx_sku），单类型查询就是一次普通索引扫描。

    /** 订单：bizId 就是订单 id */
    private static final String ORDER_STATUS_FILTER =
            "<if test='bizId != null'> AND v.biz_id = #{bizId} </if>"
                    + "<if test='operatorType != null'> AND v.operator_type = #{operatorType} </if>"
                    + "<if test='from != null'> AND v.created_at &gt;= #{from} </if>"
                    + "<if test='to != null'> AND v.created_at &lt;= #{to} </if>";

    /**
     * 包裹：bizId 既可以是包裹 id，也可以是订单 id。
     *
     * <p>订单维度的三个来源，按优先级：
     * <ol>
     *   <li>{@code v.biz_id = bizId} —— 传的是包裹 id；</li>
     *   <li>{@code v.order_id = bizId} —— 传的是订单 id，走<b>已登记冗余列</b>
     *       （视图 DELIVERY 分支的 order_id 现在是真实列，不再是 CAST(NULL)）：
     *       包裹行被 {@code OrderPurgeService} 物理删除后，这一条仍然查得到；</li>
     *   <li>{@code v.biz_id IN (SELECT id FROM delivery_order WHERE order_id = bizId)} ——
     *       迁移前写入的历史行兜底（那时候还没有 order_id 冗余列）。桥断了这些老行就只剩单号可追。</li>
     * </ol>
     */
    private static final String DELIVERY_STATUS_FILTER =
            "<if test='bizId != null'> AND (v.biz_id = #{bizId} OR v.order_id = #{bizId} OR v.biz_id IN "
                    + "(SELECT id FROM delivery_order WHERE order_id = #{bizId})) </if>"
                    + "<if test='operatorType != null'> AND v.operator_type = #{operatorType} </if>"
                    + "<if test='from != null'> AND v.created_at &gt;= #{from} </if>"
                    + "<if test='to != null'> AND v.created_at &lt;= #{to} </if>";

    /** 售后：bizId 既可以是售后申请 id，也可以是订单 id（return_status_ledger 冗余了 order_id） */
    private static final String RETURN_STATUS_FILTER =
            "<if test='bizId != null'> AND (v.biz_id = #{bizId} OR v.order_id = #{bizId}) </if>"
                    + "<if test='operatorType != null'> AND v.operator_type = #{operatorType} </if>"
                    + "<if test='from != null'> AND v.created_at &gt;= #{from} </if>"
                    + "<if test='to != null'> AND v.created_at &lt;= #{to} </if>";

    /** 审计：bizId 匹配目标 id 或订单 id */
    private static final String AUDIT_FILTER =
            "<if test='bizId != null'> AND (a.target_id = #{bizId} OR a.order_id = #{bizId}) </if>"
                    + "<if test='operatorType != null'> AND a.operator_type = #{operatorType} </if>"
                    + "<if test='from != null'> AND a.created_at &gt;= #{from} </if>"
                    + "<if test='to != null'> AND a.created_at &lt;= #{to} </if>";

    /** WMS：bizId 既可以是包裹 id，也可以是订单 id（同样经 delivery_order 搭桥） */
    private static final String WMS_FILTER =
            "<if test='bizId != null'> AND (w.delivery_id = #{bizId} OR w.delivery_id IN "
                    + "(SELECT id FROM delivery_order WHERE order_id = #{bizId})) </if>"
                    + "<if test='operatorType != null'> AND w.operator_type = #{operatorType} </if>"
                    + "<if test='from != null'> AND w.created_at &gt;= #{from} </if>"
                    + "<if test='to != null'> AND w.created_at &lt;= #{to} </if>";

    /**
     * 库存：bizId = 订单 id。
     * <b>按 operatorType 筛选时整支不参与</b>（{@code 1 = 0}）：这张表没有操作人列，
     * 让它带着 {@code operator_type='SYSTEM'} 的假身份混进「筛管理员」的结果里，等于给筛选器撒谎。
     */
    private static final String STOCK_FILTER =
            "<if test='bizId != null'> AND s.order_id = #{bizId} </if>"
                    + "<if test='operatorType != null'> AND 1 = 0 </if>"
                    + "<if test='from != null'> AND s.created_at &gt;= #{from} </if>"
                    + "<if test='to != null'> AND s.created_at &lt;= #{to} </if>";

    /**
     * 分页查询的完整 SQL：{@code branches} 里每出现一个分支就拼一支，UNION ALL 成一个派生表，
     * 外层按 {@code (created_at, id)} 倒序（「最近发生的在最上面」；同一毫秒的多行按 id 定序，
     * 保证翻页稳定 —— 没有第二排序键时同一秒的行在页与页之间可能重复/丢失）。
     *
     * <p>UNION ALL 而不是 UNION：台账是只追加证据，同一毫秒 from=to 的幂等重算行也是事实，
     * 去重会把「又算了一次」抹掉。
     */
    static final String PAGE_TIMELINE =
            "<script>"
                    + "SELECT * FROM ("
                    + "<foreach collection='branches' item='branch' separator=' UNION ALL '><choose>"
                    + "<when test='\"ORDER_STATUS\".equals(branch)'>" + ORDER_STATUS_BRANCH + ORDER_STATUS_FILTER + "</when>"
                    + "<when test='\"DELIVERY_STATUS\".equals(branch)'>" + DELIVERY_STATUS_BRANCH + DELIVERY_STATUS_FILTER + "</when>"
                    + "<when test='\"RETURN_STATUS\".equals(branch)'>" + RETURN_STATUS_BRANCH + RETURN_STATUS_FILTER + "</when>"
                    + "<when test='\"AUDIT\".equals(branch)'>" + AUDIT_BRANCH + AUDIT_FILTER + "</when>"
                    + "<when test='\"WMS\".equals(branch)'>" + WMS_BRANCH + WMS_FILTER + "</when>"
                    + "<when test='\"STOCK\".equals(branch)'>" + STOCK_BRANCH + STOCK_FILTER + "</when>"
                    + "</choose></foreach>"
                    + ") t ORDER BY t.created_at DESC, t.id DESC"
                    + "</script>";
}
