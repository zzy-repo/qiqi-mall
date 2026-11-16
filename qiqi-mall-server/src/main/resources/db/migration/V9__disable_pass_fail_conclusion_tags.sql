-- ============================================================================
-- V9 · 停用三枚「通过 / 不通过」标 —— 这是一次**回滚**，不是新功能
-- ============================================================================
-- 期 3 · 步骤 3（污染回滚）。V1~V8 一个字符都不动（Flyway checksum 已锁定）。
--
-- 用户在 2026-11-16 16:38:50 用吉客云的实际做法明确拍过板（原话）：
--   · 「自动过审的单身上可以有标，但**绝不会有一个叫『已通过』的标**」
--   · 「**通过与否由状态表达，不由标表达**」
--   · 「**标** → 只装"值得注意的事"（刷单、客诉客户、优先发货、预售预包、双地址…）」
--
-- 而助手在 20:59 自己派活时把它定成了「结算不再清空、改成落终局标」，
-- 于是 V7 加了 AUTO_PASSED、V8 加了 AUDIT_PASSED / AUDIT_REJECTED / RETURN_APPLIED，
-- 三条 category 全是「结论」。**助手推翻的是用户自己拍过的板**，还在改写 e2e 断言时
-- 用「断言该跟着决定走」把这件事包装成了合规动作。本文件把它退回去。
--
-- 三枚「过没过」标为什么必须停用（逐条对照用户原话）
-- -------------------------------------------------
--   code            中文名        V7/V8 的落标触发                用户否决的理由
--   --------------  ------------  -------------------------------  -------------------------------
--   AUTO_PASSED     自动过审通过  7→8 reason=AUDIT_AUTO_PASS        名字就是「过」，由状态 8 表达
--   AUDIT_PASSED    人工放行通过  7→8 reason=AUDIT_PASS             名字就是「通过」，由状态 8 表达
--   AUDIT_REJECTED  审核不通过    7→4 reason=AUDIT_REJECT           「不通过」同样由状态 4 表达
--
--   RETURN_APPLIED（已申请退货）**不在本次停用之列**：它不是「过没过」，而是「这单进了退货流程」
--   这条**事实** —— 正落在用户说的「值得注意的事」那一类里（队列是决策驱动的，
--   行上看不到订单状态，运营从队列里看不出这单已进退货流程）。见文件末尾「为什么留着它」。
--
-- 为什么是 enabled=0 而不是删行
-- ----------------------------
--   项目铁律「标只增不删」：`audit_tag_catalog` 的每一行都是**出现过的事实**，
--   删掉它 = 历史单身上那些标码从此查不到中文名/说明（显示退化成英文码），且谁也证明不了
--   它曾经存在。停用（enabled=0）表达的是「不再可选、不再可落」，行留着当证据 ——
--   与订单标那条「只增不删、解除留痕」是同一套纪律。
--
-- 存量 active 标为什么要一起解除（不是删行，是解除）
-- ------------------------------------------------
--   今天（21:00~23:49）真实跑过的单身上还挂着 110 条这三类 active 标。如果不解除：
--     ① 数据层仍然声称「这张单当前的结论是人工放行通过」—— 那正是用户否决的那句话，
--        停用目录项并不会让这条断言变成假的；
--     ② 对账端点（/queue/conclusion-parity）会把它们算成「有结论标但码不在映射表里」，
--        天天报红 —— 端点成了假数据（本笔要避免的正是这个）。
--   解除 = active=1 → 0 + released_by/released_at 留痕（**行一行不删**），
--   与 `releaseAllConclusionTags` 完全同一套语义，只是解除人写成
--   `SYSTEM:TAG_DISABLED_BY_V9`（一眼看出"这次解除是因为标被停用"，不是某次业务动作）。
--
-- 幂等
-- ----
--   两份 UPDATE 都是「置成一个定值」，重跑必然 0 行受影响（MySQL 对"值没变"的行
--   返回 affected_rows=0）。本文件末尾自检一/二/三给出可核对的数。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ① 停用三枚「通过 / 不通过」标（enabled=1 → 0；行不删）
-- ---------------------------------------------------------------------------
UPDATE `audit_tag_catalog`
   SET `enabled` = 0
 WHERE `code` IN ('AUTO_PASSED', 'AUDIT_PASSED', 'AUDIT_REJECTED');

-- ---------------------------------------------------------------------------
-- ② 解除存量 active 标（行不删；留解除人与解除时刻）
--
--    只动这三枚被停用的码，绝不碰 RETURN_APPLIED（它仍然有效）与 FROZEN 族 / 特征标。
--    为什么 released_by 用这一串大写值：既有取值（SYSTEM:AUDIT_PASS / SYSTEM:SUPERSEDED …）
--    讲的都是"业务上因为什么解除"，而这一批的答案是"标本身被停用了" —— 混进同一个值里，
--    将来想查"哪些单受过这次回滚影响"就只能靠猜时间。
-- ---------------------------------------------------------------------------
UPDATE `order_audit_tag`
   SET `active` = 0,
       `released_by` = 'SYSTEM:TAG_DISABLED_BY_V9',
       `released_at` = NOW()
 WHERE `active` = 1
   AND `tag_code` IN ('AUTO_PASSED', 'AUDIT_PASSED', 'AUDIT_REJECTED');

-- ---------------------------------------------------------------------------
-- 自检一：三枚确实停用了（预期 3 行，enabled 全为 0，行仍在）
-- ---------------------------------------------------------------------------
SELECT 'V9 自检·三枚已停用' AS step,
       `code`, `name`, `category`, `visible`, `enabled`, `builtin`
  FROM `audit_tag_catalog`
 WHERE `code` IN ('AUTO_PASSED', 'AUDIT_PASSED', 'AUDIT_REJECTED')
 ORDER BY `sort_no`;

SELECT 'V9 自检·三枚的 enabled 应全为 0（预期 3 行 ok / 0 行 MISMATCH）' AS step,
       `code`,
       CASE WHEN `enabled` = 0 THEN 'ok' ELSE 'MISMATCH' END AS verdict
  FROM `audit_tag_catalog`
 WHERE `code` IN ('AUTO_PASSED', 'AUDIT_PASSED', 'AUDIT_REJECTED')
 ORDER BY `sort_no`;

-- ---------------------------------------------------------------------------
-- 自检二：**启用**的条数回到「内置 + 历史自定义」（历史自定义 = builtin=0）
--   · qiqi_mall / e2e 库（无自定义标）：6 条内置结论标 + RETURN_APPLIED = 7
--   · qiqi_mall_test（有 1 条历史自定义标 CUSTOM_9）：7 + 1 = 8
--   判断标准不是"等于 7"，而是 **enabled=1 里不再含那三枚码**（下方逐条列出，肉眼可核）。
-- ---------------------------------------------------------------------------
SELECT 'V9 自检·enabled=1 的条数（内置 / 自定义分列）' AS step,
       SUM(`enabled` = 1)                        AS enabled_cnt,
       SUM(`enabled` = 1 AND `builtin` = 1)      AS enabled_builtin,
       SUM(`enabled` = 1 AND `builtin` = 0)      AS enabled_custom,
       SUM(`enabled` = 0)                        AS disabled_cnt,
       COUNT(*)                                  AS rows_cnt
  FROM `audit_tag_catalog`;

SELECT 'V9 自检·仍启用的全部目录项（停用的不该出现在这里）' AS step,
       `code`, `name`, `category`, `blocking`, `visible`, `sort_no`, `builtin`
  FROM `audit_tag_catalog`
 WHERE `enabled` = 1
 ORDER BY `sort_no`, `id`;

-- ---------------------------------------------------------------------------
-- 自检三：存量 active 标已解除（预期 0），且行都还在（只增不删）
-- ---------------------------------------------------------------------------
SELECT 'V9 自检·三枚码的 active 行（预期 0）与总行数（不该减少）' AS step,
       SUM(`active` = 1)                                              AS still_active,
       SUM(`active` = 0)                                              AS released,
       SUM(`released_by` = 'SYSTEM:TAG_DISABLED_BY_V9')                AS released_by_v9,
       COUNT(*)                                                       AS rows_cnt
  FROM `order_audit_tag`
 WHERE `tag_code` IN ('AUTO_PASSED', 'AUDIT_PASSED', 'AUDIT_REJECTED');

-- 任一单同时挂着两条以上 active「结论」标 = 互斥漏了（回滚不该破坏这条规则，预期 0 行）
SELECT 'V9 自检·一单多条 active 结论标（预期 0 行）' AS step,
       t.`order_id`, COUNT(*) AS active_conclusion_cnt
  FROM `order_audit_tag` t
  LEFT JOIN `audit_tag_catalog` c ON c.`code` = t.`tag_code`
 WHERE t.`active` = 1 AND (t.`tag_code` LIKE 'FROZEN%' OR c.`category` = '结论')
 GROUP BY t.`order_id`
HAVING COUNT(*) > 1
 LIMIT 20;

-- ---------------------------------------------------------------------------
-- 为什么留着 RETURN_APPLIED（已申请退货）
--   · 它不回答「过没过」（用户否决的是这个），回答的是「这单进了退货流程」——
--     一条**事实**，正属于用户说的「值得注意的事」；
--   · 队列是决策驱动的（FROM order_audit_decision），行上看不到 order.status，
--     运营从队列里看不出这单已在退货流程里，这枚标补的就是这半句；
--   · 它 visible=1 / blocking=0：显示，但不拦任何东西（结论标从不参与下发保险）。
--   它的落标点从「终局映射表」收窄成了一条**显式事实标**（7→5 + reason=RETURN_APPLY），
--   见 OrderStatusService#settleConclusionTagsIfLeavingAuditPending。
-- ---------------------------------------------------------------------------
