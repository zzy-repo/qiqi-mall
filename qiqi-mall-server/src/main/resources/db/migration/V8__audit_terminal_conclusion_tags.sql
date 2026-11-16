-- ============================================================================
-- V8 · 结算不再「清空」，而是「落一条终局结论标」
-- ============================================================================
-- 期 3 · 步骤 2.5。V1~V7 一个字符都不动（Flyway checksum 已锁定）。
--
-- 问题（上一轮实测出来的口径缺口）
-- ------------------------------
--   期 1.6 起「离开待审核(7)」就把该单所有 active 的结论标置 active=0（releaseAll）。
--   于是队列里的终局单（8 已发货 / 4 已关闭 / 5 退款中）读「当前结论」时：
--     conclusionFromTag=null ⇒ 一旦结论改从标读，这些单会从 NO_MATCH/BLOCKED/… 变成**没有结论**。
--   这不是数据不一致（valueMismatch=0），是**口径定义缺口**：结算只回答了"旧结论不成立"，
--   没回答"那它最后怎么了"。
--
-- 语义（本迁移引入的规矩）
-- ----------------------
--   订单离开待审核后，「当前结论」仍然存在，只是变成了「它最后怎么了」：
--     · 「过没过」仍由**订单状态**表达（吉客云口径：通过由状态表达，不落"通过"状态码）；
--     · 标记录的是**它是怎么过的 / 为什么没过 / 去哪了** —— 这是状态答不出的东西。
--   所以结算 = ① 先 release 掉"待审核期间的临时结论"（AUDIT_PENDING / BLOCKED / NO_POLICY_MATCH…），
--            ② 再落一条**终局结论标**。顺序不能反（反了会被自己刚 release 掉）。
--
-- 三条终局标（全部 category='结论'：互斥 / 结算 / 下发保险的 category 判据都按这一档走）
-- --------------------------------------------------------------------------------
--   code            中文名         visible  blocking  触发（只有这四条路会落）
--   --------------- ------------- -------- --------- --------------------------------------
--   AUDIT_PASSED    人工放行通过      0        0      7→8 + reason=AUDIT_PASS（人工点「审核通过」）
--   AUDIT_REJECTED  审核不通过        0        0      7→4 + reason=AUDIT_REJECT
--   RETURN_APPLIED  已申请退货        1        0      7→5 + reason=RETURN_APPLY
--   （已有，V7）AUTO_PASSED 自动过审通过  0   0      7→8 + reason=AUDIT_AUTO_PASS
--
-- 为什么 visible=0 / 1 要分开定（这不是随手取的）
-- ----------------------------------------------
--   · AUDIT_PASSED / AUDIT_REJECTED 是**每张单都会有的**（过审/关单是绝大多数结局）。
--     visible=1 会把队列 200 行全刷成一枚"人工放行通过"，真正要看的 5 条当场被淹掉 ——
--     这条理由与 V7 为 AUTO_PASSED 记录的那条**完全同源**（期 1 的"显示噪声"判断没过时，
--     过时的是"因此不落标"）。所以：数据上有事实，队列里不显示。
--   · RETURN_APPLIED 是**少数单**（退货申请），而队列的「判定」列里看不到订单状态
--     （`order.status` 只在订单页；队列是决策驱动的），运营从队列里根本看不出这单已进退货流程。
--     噪声风险与信息增量不对称 ⇒ visible=1。
--
-- 为什么三条 blocking 都是 0
-- -------------------------
--   `blocking` 的既有语义是「会拦住下单往下走」。而**结论标从不参与下发保险**
--   （DeliveryFlowService#enforceDispatchTagGate 明确只拦 FROZEN 族 + 特征标里 blocking=1 的，
--    并写清了"结论标按 blocking 一刀切会误拦且无解"）。给这三条写 blocking=1，就是让字典
--   承诺一件系统不执行的事。更实在的风险：5→1（退货审核拒绝，直发单）之后单子还能 1→2 发货，
--   那时 blocking=1 的 RETURN_APPLIED 仍 active —— 将来谁把结论标接进第二道保险，就是真死锁。
--   可见性不靠 blocking：队列的标 chip 只按 visible 过滤。
--
-- ★ 历史单**不回填**（理由同前：回填 = 篡改过去）
--   本迁移不往 order_audit_tag 写任何一行。切口径之前离开过待审核的单，标里确实没有终局结论
--   ⇒ 它们的"当前结论"走**决策行退回**（conclusionSource=DECISION），这是如实回答而不是缺口。
--   「历史单」与「真缺口」的区分口径见文件末尾只读自检二：
--     本规则自目录里落下这三条标的那一刻起生效（`audit_tag_catalog.created_at` 就是那个时刻），
--     之后按结算四条路离开 7 却没有终局标的，才是**真缺口**（= 代码 bug）。
--
-- 幂等：uk_code + ON DUPLICATE KEY UPDATE id=id（项目铁律：不用 INSERT IGNORE）。
--       故意**不更新** name/description/visible：运营将来在维护页改过的中文名不能被重放冲掉。
--       ⚠️ 因此「规则生效时刻」= 这三条的**首次** created_at；重跑本文件不会刷新它（正是我们要的）。
-- ============================================================================

INSERT INTO `audit_tag_catalog`
  (`code`, `name`, `description`, `category`, `blocking`, `visible`, `sort_no`, `enabled`, `builtin`) VALUES
  ('AUDIT_PASSED', '人工放行通过',
   '人工点「审核通过」（7→8）：这张单是被放行的，状态里看得出来，这条标记的是"怎么过的"（数据上有、队列里不显示）',
   '结论', 0, 0, 71, 1, 1),
  ('AUDIT_REJECTED', '审核不通过',
   '人工审核不通过（7→4 已关闭）：单子不再流转，这条标记的是"为什么没过"（数据上有、队列里不显示）',
   '结论', 0, 0, 72, 1, 1),
  ('RETURN_APPLIED', '已申请退货',
   '已登记退货申请（7→5 退款中）：这张单已进退货流程，不再走审核下发（队列里显示，因为队列看不到订单状态）',
   '结论', 0, 1, 73, 1, 1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 自检一：三条都在目录里，visible / blocking / category 与设计逐条一致
-- ---------------------------------------------------------------------------
SELECT 'V8 自检·终局结论标目录项' AS step,
       `code`, `name`, `category`, `blocking`, `visible`, `builtin`, `sort_no`, `created_at`
  FROM `audit_tag_catalog`
 WHERE `code` IN ('AUDIT_PASSED', 'AUDIT_REJECTED', 'RETURN_APPLIED')
 ORDER BY `sort_no`;

SELECT 'V8 自检·三条的取值是否与设计一致（应为 3 行 ok）' AS step,
       `code`,
       CASE WHEN `category` = '结论' AND `enabled` = 1 AND `builtin` = 1 AND `blocking` = 0
                 AND `visible` = CASE `code` WHEN 'RETURN_APPLIED' THEN 1 ELSE 0 END
            THEN 'ok' ELSE 'MISMATCH' END AS verdict
  FROM `audit_tag_catalog`
 WHERE `code` IN ('AUDIT_PASSED', 'AUDIT_REJECTED', 'RETURN_APPLIED')
 ORDER BY `sort_no`;

-- ---------------------------------------------------------------------------
-- 自检二：**规则生效后**该有终局标却没有的单（真缺口，只读，一行都不写）
--
-- 判据（与 GET /admin/order-audit/queue/conclusion-parity 的 terminalTagMissing 同口径）：
--   ① 该单有决策行（被判定过）；
--   ② 现在身上没有任何 active 的结论标（含 FROZEN 族前缀兜底）；
--   ③ 离开待审核的台账行 reason ∈ {AUDIT_PASS, AUDIT_AUTO_PASS, AUDIT_REJECT, RETURN_APPLY}
--      —— 也就是"按设计必须落终局标"的那四条路（包裹聚合推导的 DELIVERY_AGGREGATE 不算）；
--   ④ 那条台账行的 created_at >= 本规则的生效时刻（= 三条终局标的首次 created_at）。
--
-- 分开数三个数，因为它们要的处置完全不同：
--   · terminal_tag_missing  真缺口 → 代码 bug，要查（预期 0）；
--   · legacy_decision       历史单（规则生效前就离开 7）→ 正常，结论退回决策行（不回填）；
--   · derived_no_conclusion 包裹聚合推导离开 7（7→2/3/9）→ 正常，本就不该有审核结论。
-- ---------------------------------------------------------------------------
SELECT 'V8 自检·终局结论标覆盖率（真缺口应为 0）' AS step,
       SUM(kind = 'TERMINAL_MISSING')    AS terminal_tag_missing,
       SUM(kind = 'LEGACY_DECISION')     AS legacy_decision,
       SUM(kind = 'DERIVED_NO_CONCLUSION') AS derived_no_conclusion,
       COUNT(*)                          AS orders_left_audit_without_conclusion_tag
  FROM (
        SELECT o.`id`,
               CASE WHEN l.`reason` NOT IN ('AUDIT_PASS', 'AUDIT_AUTO_PASS', 'AUDIT_REJECT', 'RETURN_APPLY')
                         THEN 'DERIVED_NO_CONCLUSION'
                    WHEN l.`created_at` >= (SELECT MIN(`created_at`) FROM `audit_tag_catalog`
                                             WHERE `code` IN ('AUDIT_PASSED','AUDIT_REJECTED','RETURN_APPLIED'))
                         THEN 'TERMINAL_MISSING'
                    ELSE 'LEGACY_DECISION' END AS kind
          FROM `order` o
          -- 每单「最后一次离开待审核」那一行：一次分组取 MAX(id)，不写成相关子查询（大库上会退化成 N 次回表）
          JOIN (SELECT `order_id`, MAX(`id`) AS mid FROM `order_status_ledger`
                 WHERE `from_status` = 7 GROUP BY `order_id`) m ON m.`order_id` = o.`id`
          JOIN `order_status_ledger` l ON l.`id` = m.mid
         WHERE EXISTS (SELECT 1 FROM `order_audit_decision` d WHERE d.`order_id` = o.`id`)
           AND NOT EXISTS (SELECT 1 FROM `order_audit_tag` t
                            LEFT JOIN `audit_tag_catalog` c ON c.`code` = t.`tag_code`
                            WHERE t.`order_id` = o.`id` AND t.`active` = 1
                              AND (t.`tag_code` LIKE 'FROZEN%' OR c.`category` = '结论'))
       ) x;

-- ---------------------------------------------------------------------------
-- 自检三：本迁移对存量标**零影响**（只加三条目录项，order_audit_tag 一个字节都不动）
-- ---------------------------------------------------------------------------
SELECT 'V8 自检·order_audit_tag 存量（迁移前后必须一致）' AS step,
       COUNT(*) AS rows_cnt, SUM(`active` = 1) AS active_cnt,
       SUM(`tag_code` IN ('AUDIT_PASSED','AUDIT_REJECTED','RETURN_APPLIED')) AS terminal_tag_rows
  FROM `order_audit_tag`;
