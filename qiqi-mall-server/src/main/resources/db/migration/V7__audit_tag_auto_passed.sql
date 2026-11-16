-- ============================================================================
-- V7 · 结论标补上最后一块：AUTO_PASSED（自动过审通过，visible=0）
-- ============================================================================
-- 期 3 · 步骤 1。V1~V6 一个字符都不动（Flyway checksum 已锁定）。
--
-- 为什么现在要加这一条（**推翻了期 1 的一个决定，理由写清**）
-- ----------------------------------------------------------
--   期 1 的决定是「AUTO_PASS 不落标」，理由写在 V4 里：自动过审等于"什么都没发生"，
--   一屏 200 条"自动过审"标会把真正要看的 5 条淹掉。那条理由针对的是**显示**（噪声）。
--
--   期 3 要把「结论从标读、决策行退回审计层」，于是 AUTO_PASS 成了**唯一的洞**：
--   最常见的那个结论没有标 ⇒ 任何"从标反推结论"的实现都在这批单上给不出结论，
--   只能回退去读决策行 —— 那等于把刚切掉的耦合又从后门接回来。
--
--   所以：「标=事实」在最常见的结论上不能有洞 ⇒ 必须落标；
--         「自动过审不该在队列里显示成一枚标」⇒ visible=0。
--   `audit_tag_catalog.visible` 这一列从 V4 建了就没被用过，正是为这种"事实成立但不必显示"准备的。
--   两件事不再打架：**数据上有标，页面上没有标**。
--
-- 为什么 category 仍是「结论」
-- --------------------------
--   它描述的就是"这一轮判定给出的当前结论"，和 BLOCKED / NO_POLICY_MATCH 同一档
--   （互斥规则、结算规则、下发保险的 category 判据都按这一档走）。
--   若为了"不在界面显示"把它塞进「特征」档，等于用错误的分组换一个显示效果 ——
--   互斥就漏了它（一单能同时 active AUTO_PASSED + NO_POLICY_MATCH），那是真漏洞。
--
-- blocking=0：自动过审的单**已经放行**，它不拦任何东西（下发保险按 blocking 一刀切，
--             这里若给 1 就会把已经过审的单锁死在待发货）。
--
-- ★ 历史**不回填**
--   回填历史 = 篡改过去：那些单的"当前结论"应该由**下一次真跑**决定，不该由迁移凭空写一条
--   谁也证明不了的标。缺口有多大由本文件末尾那条**只读**自检如实量出来（不写任何行）。
--
-- 幂等：uk_code + ON DUPLICATE KEY UPDATE id=id（项目铁律：不用 INSERT IGNORE）。
--       故意**不更新** name/description/visible：运营将来在维护页改过的中文名不能被重放冲掉。
--       整份文件重跑 = 0 行受影响（本文件只有这一条写语句）。
-- ============================================================================

INSERT INTO `audit_tag_catalog`
  (`code`, `name`, `description`, `category`, `blocking`, `visible`, `sort_no`, `enabled`, `builtin`) VALUES
  ('AUTO_PASSED', '自动过审通过',
   '由系统判定为自动通过，不需要人工处理（数据上有这条标，队列里不显示：visible=0）',
   '结论', 0, 0, 70, 1, 1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 自检一：这一条真的在目录里，且 visible=0（不是 1）
-- ---------------------------------------------------------------------------
SELECT 'V7 自检·AUTO_PASSED 目录项' AS step,
       `code`, `name`, `category`, `blocking`, `visible`, `builtin`
  FROM `audit_tag_catalog`
 WHERE `code` = 'AUTO_PASSED';

-- ---------------------------------------------------------------------------
-- 自检二：**可见性缺口量化**（只读，一行都不写）
--
-- "缺口" = 这张单现在**没有任何 active 的结论标** ⇒ 一旦结论改从标读，它就答不出"当前结论"。
-- 分两档看，因为它们的严重程度完全不同：
--   · has_decision=1：它在人工队列里（队列取的是每单 MAX(id) 那条决策行）。
--     这批单就是"切换口径会立刻改变业务看到的结论"的那批 —— 期 3 步骤 2b 的对账端点量的是同一件事，
--     但那个端点按 result 与标**逐单比对**，这里只数"一条结论标都没有"的裸缺口；
--   · has_decision=0：从没判定过的单（历史遗留/手工造），本就不在队列里，缺口是"没有结论"而不是"结论不一致"。
--
-- 判据里必须**带上 FROZEN 前缀兜底**：策略可以配细分码（FROZEN_RETURN_APPLY…），
-- 那些码今天还没登记进 audit_tag_catalog，只按目录 join 会把"冻着的单"误算成没有结论标。
-- （与 OrderAuditTagService#isConclusionTag 的口径同源。）
-- ---------------------------------------------------------------------------
SELECT 'V7 自检·没有 active 结论标的单数（可见性缺口）' AS step,
       SUM(has_decision = 1) AS pending_in_queue_missing_tag,
       SUM(has_decision = 0) AS never_judged_missing_tag,
       COUNT(*)              AS orders_missing_conclusion_tag
  FROM (
        SELECT o.`id`,
               EXISTS (SELECT 1 FROM `order_audit_decision` d WHERE d.`order_id` = o.`id`) AS has_decision,
               EXISTS (SELECT 1 FROM `order_audit_tag` t
                        LEFT JOIN `audit_tag_catalog` c ON c.`code` = t.`tag_code`
                        WHERE t.`order_id` = o.`id` AND t.`active` = 1
                          AND (t.`tag_code` LIKE 'FROZEN%' OR c.`category` = '结论')) AS has_conclusion_tag
          FROM `order` o
       ) x
 WHERE has_conclusion_tag = 0;

-- 逐单明细（只打前 20 条，够看清是什么单；完整的由 GET /admin/order-audit/queue/conclusion-parity 出）
SELECT 'V7 自检·缺口前 20 单' AS step, o.`order_sn`, o.`status`,
       (SELECT d.`result` FROM `order_audit_decision` d WHERE d.`order_id` = o.`id`
         ORDER BY d.`id` DESC LIMIT 1) AS latest_result
  FROM `order` o
 WHERE NOT EXISTS (SELECT 1 FROM `order_audit_tag` t
                    LEFT JOIN `audit_tag_catalog` c ON c.`code` = t.`tag_code`
                    WHERE t.`order_id` = o.`id` AND t.`active` = 1
                      AND (t.`tag_code` LIKE 'FROZEN%' OR c.`category` = '结论'))
 ORDER BY o.`id` DESC
 LIMIT 20;

-- ---------------------------------------------------------------------------
-- 自检三：本迁移对存量标**零影响**（只加一条目录项，order_audit_tag 一个字节都不动）
-- ---------------------------------------------------------------------------
SELECT 'V7 自检·order_audit_tag 存量（迁移前后必须一致）' AS step,
       COUNT(*) AS rows_cnt, SUM(`active` = 1) AS active_cnt
  FROM `order_audit_tag`;
