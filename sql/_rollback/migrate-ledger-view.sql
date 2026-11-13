-- ============================================================================
-- 迁移：业务状态时间线只读视图 v_biz_status_timeline（为 P3 的 UI 准备）
-- ============================================================================
--
-- ── 这是什么 ────────────────────────────────────────────────────────────────
--   把三张状态台账（order_status_ledger / delivery_status_ledger / return_status_ledger）
--   **UNION ALL** 成一张统一时间线，带 type 字段区分业务对象：
--
--     biz_type = 'ORDER'    → biz_id = order_id        （订单状态台账 13 列契约）
--     biz_type = 'DELIVERY' → biz_id = delivery_id     （包裹状态台账，额外带 delivery_sn 快照）
--     biz_type = 'RETURN'   → biz_id = return_apply_id （售后台账，额外带 order_id 冗余）
--
--   用途：任何一个页面上的「这张单/这个包裹/这张申请是怎么走到今天的」都只查这一个视图，
--   不用在 UI 里写三段 SQL、也不用把「哪些表是台账」这个知识复制到前端。
--
-- ── 为什么是视图而不是表 ────────────────────────────────────────────────────
--   时间线是**纯派生物**：三张台账已经各自是事实源，再维护一张汇总表就有了第二个写者，
--   两张表迟早对不上。视图没有写者，永远与台账同构。
--
-- ── 口径（照定稿执行，不再重新论证）────────────────────────────────────────
--   ① 三个分支的**共同 13 列**必须逐字一致（同构契约）；各分支独有的列只能以 NULL 补齐：
--      order_id（**三个分支都有值**：订单分支 = 订单 id；包裹分支 = 已登记的冗余列
--                delivery_status_ledger.order_id；售后分支 = 冗余的 return_status_ledger.order_id）
--      delivery_sn（订单/售后分支没有 → NULL）
--      ⚠️ 包裹分支的 order_id 曾经是 `CAST(NULL AS SIGNED)`（包裹台账当时不冗余订单 id），
--         后果是「从订单反查这个订单的几个包裹是怎么走的」只能靠 `delivery_order` 搭桥，
--         而 OrderPurgeService 会**物理删除**包裹行 —— 桥一断就永久查不到。
--         2026-11-13 补登 delivery_status_ledger.order_id 冗余列（与售后台账对称），这里改读真实列。
--   ② **UNION ALL**，不是 UNION：台账里同一秒可能有 from=to 的幂等重算行，去重会把「又算了一次」
--      这个事实抹掉；而且 UNION 要做全量排序去重，视图会变得很慢。
--   ③ 只读：视图不带 INSTEAD OF 触发器（MySQL 不支持），任何人都只能 SELECT。
--   ④ 全部列显式 CAST：UNION 的第一分支决定类型推断，不写死会出现「NULL 列被推断成 BINARY」
--      这类怪结果（前端拿到字节串）。
--   ⑤ `to_status_text`：把状态码翻成中文，避免前端再复制一份映射表。三张表的状态枚举不同，
--      所以按 biz_type 各写一段 CASE。**只翻 to_status**（时间线读的是「变成了什么」）。
--
-- ── 幂等 ────────────────────────────────────────────────────────────────────
--   CREATE OR REPLACE VIEW：可重复执行，不改数据、不做破坏性操作。
--
-- ── 执行 ────────────────────────────────────────────────────────────────────
--   mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/migrate-ledger-view.sql
--   （必须带 --default-character-set=utf8mb4：否则视图里的中文标签会二次编码成乱码落库。）
--
-- ── 查询示例（直接可用）──────────────────────────────────────────────────────
--   -- 1) 一张订单的完整时间线（含它的包裹与售后申请）
--   --    ⚠️ 这条曾经是"错例句"：包裹分支的 order_id 当时是 CAST(NULL)，`biz_type='DELIVERY' AND order_id=12`
--   --       恒不成立（永远 0 行），等于告诉人一个查不到东西的写法。现在 order_id 是真实列，示例成立。
--   SELECT biz_type, biz_id, order_id, delivery_sn, from_status_text, to_status_text,
--          reason, operator_type, operator_name, ref_type, ref_id, created_at
--     FROM v_biz_status_timeline
--    WHERE order_id = 12
--    ORDER BY created_at, id;
--   --（`WHERE order_id = 12` 等价于上面那个三段 OR 的写法，且能用上 delivery_status_ledger.idx_order）
--
--   -- 1b) 按订单反查该订单下**所有包裹**的状态流水（包裹行已被物理删除也查得到：走冗余 order_id）
--   SELECT biz_id AS delivery_id, delivery_sn, from_status_text, to_status_text, reason, created_at
--     FROM v_biz_status_timeline
--    WHERE biz_type = 'DELIVERY' AND order_id = 12
--    ORDER BY created_at, id;

--   -- 2) 某个包裹（含被物理删除的包裹：单号只能从台账里读）
--   SELECT * FROM v_biz_status_timeline WHERE biz_type = 'DELIVERY' AND biz_id = 34 ORDER BY created_at;
--
--   -- 3) 最近 24h 谁改了什么的敏感动作（审计视角，另一张表）
--   SELECT operator_type, operator_name, action, target_type, target_id, summary, created_at
--     FROM admin_audit_log WHERE created_at >= NOW() - INTERVAL 1 DAY ORDER BY id DESC;
--
--   -- 4) 某天的状态流转统计（哪个原因改得最多）
--   SELECT biz_type, reason, COUNT(*) AS n
--     FROM v_biz_status_timeline
--    WHERE created_at >= CURDATE()
--    GROUP BY biz_type, reason ORDER BY n DESC;
-- ============================================================================

CREATE OR REPLACE VIEW `v_biz_status_timeline` AS
SELECT
    CAST('ORDER' AS CHAR(8))          AS `biz_type`,
    CAST(l.`order_id` AS SIGNED)      AS `biz_id`,
    CAST(l.`order_id` AS SIGNED)      AS `order_id`,
    CAST(NULL AS CHAR(50))            AS `delivery_sn`,
    CAST(l.`from_status` AS SIGNED)   AS `from_status`,
    CAST(l.`to_status` AS SIGNED)     AS `to_status`,
    CAST(CASE l.`from_status`
              WHEN 0 THEN '待付款' WHEN 1 THEN '已付款' WHEN 2 THEN '已发货' WHEN 3 THEN '已完成'
              WHEN 4 THEN '已关闭' WHEN 5 THEN '退款中' WHEN 6 THEN '已退款' WHEN 7 THEN '待审核'
              WHEN 8 THEN '待发货' WHEN 9 THEN '部分发货' ELSE NULL END AS CHAR(12)) AS `from_status_text`,
    CAST(CASE l.`to_status`
              WHEN 0 THEN '待付款' WHEN 1 THEN '已付款' WHEN 2 THEN '已发货' WHEN 3 THEN '已完成'
              WHEN 4 THEN '已关闭' WHEN 5 THEN '退款中' WHEN 6 THEN '已退款' WHEN 7 THEN '待审核'
              WHEN 8 THEN '待发货' WHEN 9 THEN '部分发货' ELSE NULL END AS CHAR(12)) AS `to_status_text`,
    CAST(l.`reason` AS CHAR(32))                          AS `reason`,
    CAST(l.`reason_detail` AS CHAR(255))                  AS `reason_detail`,
    CAST(l.`operator_type` AS CHAR(16))                   AS `operator_type`,
    CAST(l.`operator_id` AS SIGNED)                       AS `operator_id`,
    CAST(l.`operator_name` AS CHAR(64))                   AS `operator_name`,
    CAST(l.`ref_type` AS CHAR(24))                        AS `ref_type`,
    CAST(l.`ref_id` AS SIGNED)                            AS `ref_id`,
    CAST(l.`event_key` AS CHAR(96))                       AS `event_key`,
    CAST(l.`idem_key` AS CHAR(96))                        AS `idem_key`,
    l.`created_at`                                        AS `created_at`,
    CAST(l.`id` AS SIGNED)                                AS `id`
FROM `order_status_ledger` l

UNION ALL

SELECT
    CAST('DELIVERY' AS CHAR(8)),
    CAST(l.`delivery_id` AS SIGNED),
    CAST(l.`order_id` AS SIGNED),                          -- 已登记冗余列：删包裹行后仍能从订单反查包裹流水
    CAST(l.`delivery_sn` AS CHAR(50)),                     -- 已登记冗余列：删包后唯一的追踪线索
    CAST(l.`from_status` AS SIGNED),
    CAST(l.`to_status` AS SIGNED),
    CAST(CASE l.`from_status`
              WHEN 0 THEN '待下发' WHEN 1 THEN '已下发' WHEN 2 THEN '已拣货' WHEN 3 THEN '已出库'
              WHEN 4 THEN '运输中' WHEN 5 THEN '已签收' WHEN 9 THEN '已取消' ELSE NULL END AS CHAR(12)),
    CAST(CASE l.`to_status`
              WHEN 0 THEN '待下发' WHEN 1 THEN '已下发' WHEN 2 THEN '已拣货' WHEN 3 THEN '已出库'
              WHEN 4 THEN '运输中' WHEN 5 THEN '已签收' WHEN 9 THEN '已取消' ELSE NULL END AS CHAR(12)),
    CAST(l.`reason` AS CHAR(32)),
    CAST(l.`reason_detail` AS CHAR(255)),
    CAST(l.`operator_type` AS CHAR(16)),
    CAST(l.`operator_id` AS SIGNED),
    CAST(l.`operator_name` AS CHAR(64)),
    CAST(l.`ref_type` AS CHAR(24)),
    CAST(l.`ref_id` AS SIGNED),
    CAST(l.`event_key` AS CHAR(96)),
    CAST(l.`idem_key` AS CHAR(96)),
    l.`created_at`,
    CAST(l.`id` AS SIGNED)
FROM `delivery_status_ledger` l

UNION ALL

SELECT
    CAST('RETURN' AS CHAR(8)),
    CAST(l.`return_apply_id` AS SIGNED),
    CAST(l.`order_id` AS SIGNED),                          -- 已登记冗余列：删单后仍能从订单反查售后流水
    CAST(NULL AS CHAR(50)),
    CAST(l.`from_status` AS SIGNED),
    CAST(l.`to_status` AS SIGNED),
    CAST(CASE l.`from_status`
              WHEN 0 THEN '待处理' WHEN 1 THEN '已通过' WHEN 2 THEN '已拒绝' ELSE NULL END AS CHAR(12)),
    CAST(CASE l.`to_status`
              WHEN 0 THEN '待处理' WHEN 1 THEN '已通过' WHEN 2 THEN '已拒绝' ELSE NULL END AS CHAR(12)),
    CAST(l.`reason` AS CHAR(32)),
    CAST(l.`reason_detail` AS CHAR(255)),
    CAST(l.`operator_type` AS CHAR(16)),
    CAST(l.`operator_id` AS SIGNED),
    CAST(l.`operator_name` AS CHAR(64)),
    CAST(l.`ref_type` AS CHAR(24)),
    CAST(l.`ref_id` AS SIGNED),
    CAST(l.`event_key` AS CHAR(96)),
    CAST(l.`idem_key` AS CHAR(96)),
    l.`created_at`,
    CAST(l.`id` AS SIGNED)
FROM `return_status_ledger` l;

SELECT 'v_biz_status_timeline 已建立' AS result,
       (SELECT COUNT(*) FROM information_schema.VIEWS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'v_biz_status_timeline') AS view_cnt;
