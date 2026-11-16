-- ============================================================================
-- V4 · 标目录化：audit_tag_catalog（标码的稳定键 + 中文名 + 一句话说明 + 分组）
-- ============================================================================
-- ⚠️ 一旦落库就不可再改（Flyway checksum）；V1/V2/V3 一个字符都不动。
--
-- 为什么做（期 1 需求原话）
-- ------------------------
--   「判定结论必须落成**可读、可筛、可聚合的标**；决策行退回审计层。
--     业务人员看标，排障人员看矩阵。」
--
--   现状实测（2026-11-16，qiqi_mall_test）：
--     · order_audit_tag 里真用着的标只有 FROZEN 一种（17 行、9 active）；
--     · STOCK_SHORT / COST_MISSING 代码里出现过，库里 0 行；
--     · 最近一轮判定有 18 条 NO_MATCH，**全部没有任何标** —— 队列里一片空白，这就是缺口。
--
--   所以本期只做两件事：**标目录化** + **五种结论全部落地**。不改队列口径（那是期 3）。
--
-- 这张表是什么 / 不是什么
-- ----------------------
--   是：标码（tag_code）的**字典**。它回答「STOCK_SHORT 中文叫什么、属于哪一类、
--       会不会拦住下单、业务要不要看见」。没有它，前端只能把 FROZEN / NO_POLICY_MATCH
--       这些英文码硬编码在自己的常量表里 —— 于是"加一个标"要改前端、改后端、改三处文案。
--   不是：标本身的实例。谁身上有什么标仍然只在 order_audit_tag 里（那才是事实）。
--       catalog 里登记 STOCK_SHORT 不代表任何单身上有它。
--
-- ★ FROZEN 只是**在目录里登记元数据**，本迁移**不重建、不回填、不碰 order_audit_tag 一行**。
--   库里已有的 15+ 行 FROZEN（带 reason_id / reason_code）是 P2 的成果，
--   它们的中文名/原因码由 audit_freeze_reason 解释，与本表无语义重叠：
--     · audit_tag_catalog     → 「FROZEN 这个**标**叫什么」（标维度）
--     · audit_freeze_reason   → 「这单**为什么**冻」（原因维度，FROZEN 标一单一条原因）
--   两表之间**没有外键**，也刻意不加：原因字典是 FROZEN 标专属的细化，标目录不认识原因。
--
-- ★ AUTO_PASS 不登记（已拍过的决定）
--   自动过审等于"什么都没发生"，落成标就是噪声：一屏 200 条全是"自动过审"标，
--   真正要看的 5 条被淹掉。标是用来**指出异常**的，不是用来记录"一切正常"的。
--   所以 5 类结论里只有 5 个有标（BLOCKED / FROZEN / STOCK_SHORT / COST_MISSING /
--   AUDIT_PENDING / NO_POLICY_MATCH —— 加上 NO_POLICY_MATCH 共 6 个，
--   因为 MANUAL 在库里由三支来路共享 result，标把它们拆开）。
--
-- ★ category 有两档，本期只落地一档（2026-11-16 口径修正 ④）
--   吉客云的实际做法：检测点在「建单**或**审核」⇒ **打标发生在审核之前**，标不是审核的产物。
--   所以标有两个来源：
--     · 结论（CONCLUSION 语义，本表存中文 '结论'）：审核跑完的产物
--       —— 本期全部 6 条都是这一类，本期**只**实现它们的自动落标；
--     · 特征（'特征'）：标记策略、平台旗帜、人工打标（客诉客户、绿旗急发、双地址…）。
--       与"过没过"完全无关：吉客云原话是这些单打了标**照样自动审核通过**，标从头到尾没参与判断。
--   本期**不实现**特征标的落标路径（那是期 2 的检测点/标记策略），但字典里必须先留出这一档，
--   否则将来加特征标又要改表 + 改 CHECK + 再迁移一次。
--
-- ★ 数据层**不设主标**（口径修正 ②）
--   没有 is_primary / priority 之类的列，也永远不该有：数据层就是并列多个标
--   （吉客云能"挑着清"正说明它们并列共存）。若前端第一层要压缩显示，
--   那条规则写在前端、只影响显示，不进字典、不产生第二套口径。
--
-- ★ 字典一开始就必须能新增（口径修正 ③）
--   builtin=1 = 迁移种下的内置项（不可删）；builtin=0 = 运行期人工新增的自定义值。
--   为什么不许只读：只读 ⇒ 人工遇到新情况只能写自由文本 ⇒ 就回到我们库里
--   reason="1231"/"111" 那个灾难（V3 正是为了消灭它才建了冻结原因字典）。
--   本期只做「新增」（中文名必填、code 自动生成）；改名 / 停用 / 排序留给期 2 的维护页。
--
-- 本文件幂等：可重复执行（Flyway 正常只跑一次，这里保证手工重放/多环境安全）
-- ------------------------------------------------------------------------------
--   · 建表 IF NOT EXISTS；
--   · seed 靠 uk_code + ON DUPLICATE KEY UPDATE id=id（**不用 INSERT IGNORE**，项目铁律）；
--     故意不更新 name/description/category：运营将来在维护页改过的中文名不能被重放冲掉；
--   · **全程没有任何一句写 order_audit_tag** —— 存量 FROZEN 行连一个字节都不动。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 一、标目录表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_tag_catalog` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`        varchar(32)  NOT NULL COMMENT '稳定键 = order_audit_tag.tag_code。一经写入永不修改（队列 ?tag= 筛选、报表聚合、策略 action_json 都以它为键）',
  `name`        varchar(50)  NOT NULL COMMENT '中文名（业务看的那一句）；随便改，改名不影响任何键',
  `description` varchar(255) DEFAULT NULL COMMENT '一句话说明：这个标是什么意思、下一步该做什么（给业务/客服看）',
  `category`    enum('结论','特征') NOT NULL DEFAULT '结论' COMMENT '分组：结论=审核跑完的产物（期1只实现这一档的落标）；特征=标记策略/平台旗帜/人工打标（期2，本期只留位置）',
  `blocking`    tinyint      NOT NULL DEFAULT '0' COMMENT '1=会拦住下单往下走（人工必须处理才能继续）；0=只是提示，不影响流程',
  `visible`     tinyint      NOT NULL DEFAULT '1' COMMENT '1=业务可见（队列/抽屉里显示）；0=只在排障/报表里可见',
  `sort_no`     int          NOT NULL DEFAULT '100' COMMENT '排序（升序，小的在前）',
  `enabled`     tinyint      NOT NULL DEFAULT '1' COMMENT '1 启用 0 停用（历史行照旧按它显示中文名，停用不删）',
  `builtin`     tinyint      NOT NULL DEFAULT '0' COMMENT '1=迁移种下的内置项（禁止删除）；0=运行期人工新增的自定义值（本期允许新增，改名/停用留给期2）',
  `created_at`  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_enabled_category_sort` (`enabled`,`category`,`sort_no`),
  CONSTRAINT `ck_tag_catalog_blocking` CHECK ((`blocking` in (0,1))),
  CONSTRAINT `ck_tag_catalog_visible`  CHECK ((`visible`  in (0,1))),
  CONSTRAINT `ck_tag_catalog_enabled`  CHECK ((`enabled`  in (0,1))),
  CONSTRAINT `ck_tag_catalog_builtin`  CHECK ((`builtin`  in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审核标目录：标码的稳定键 + 中文名 + 一句话说明（标实例仍然只在 order_audit_tag）';

-- ---------------------------------------------------------------------------
-- 二、内置字典初版（6 条，全部 category='结论'）
-- ---------------------------------------------------------------------------
-- 与需求表的逐行对应（依据 = 现在库里/代码里真实的形状）：
--   NO_POLICY_MATCH  现在 18 条空白单全是这个（result=NO_MATCH，一个标都没有）
--   STOCK_SHORT      代码里已有（AuditVerdict.DOWNGRADE_STOCK_SHORT），库里 0 行
--   COST_MISSING     代码里已有（AuditVerdict.DOWNGRADE_COST_MISSING）
--   BLOCKED          result=BLOCKED 这条
--   FROZEN           已有 15+ 行真实数据 —— **只登记元数据，不重建、不回填**
--   AUDIT_PENDING    result=MANUAL（被排除 / 没命中任何 AUTO 策略的"留人工"）
--
-- 为什么 STOCK_SHORT / COST_MISSING 虽然叫"挂起标"却归在 category='结论'：
--   它们由**判定链的预检**产出（库存预检降级 / 成本缺失降级），是审核的产物，不是外部特征；
--   "挂起"描述的是**后果**（单子停在 7 等补货/补成本），不是来源。来源决定分组。
INSERT INTO `audit_tag_catalog`
  (`code`,`name`,`description`,`category`,`blocking`,`visible`,`sort_no`,`enabled`,`builtin`) VALUES
  ('BLOCKED',         '被策略拦截', '命中拦截策略，这张单不能往下走（拦是结论，不是动作）',           '结论',1,1, 10,1,1),
  ('FROZEN',          '被冻结',     '被按住等处理（退款/风控/客服），订单仍停在待审核，处理完解冻即恢复', '结论',1,1, 20,1,1),
  ('STOCK_SHORT',     '库存不足',   '库存不够发这件货；补货后重跑一轮即可过审（标会自动解掉）',         '结论',1,1, 30,1,1),
  ('COST_MISSING',    '毛利为负',   '成本价没维护，算不出毛利；补上成本再审核',                       '结论',1,1, 40,1,1),
  ('AUDIT_PENDING',   '待人工审核', '自动策略没放行，留给人工看一眼再决定',                           '结论',1,1, 50,1,1),
  ('NO_POLICY_MATCH', '无策略命中', '没有任何自动策略命中，系统不知道该拿它怎么办，需人工处理',       '结论',0,1, 60,1,1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 三、迁移自检（只读，输出在 flyway 日志里）
-- ---------------------------------------------------------------------------
SELECT 'V4 自检·标目录' AS step, `category`, COUNT(*) AS rows_cnt, SUM(`builtin`) AS builtin_cnt
  FROM `audit_tag_catalog`
 GROUP BY `category`
 ORDER BY `category`;

-- 存量标 vs 目录的差集：**必须看得见，不许悄悄丢掉**。
-- 预期 qiqi_mall_test → FROZEN 17 行（已在目录里，差集 0）；
-- e2e_a / e2e_b → 各 1 行；qiqi_mall / _it / _e2e_c → 0 行；
-- e2e_d → 25 张表（历史遗留的旧结构，本迁移在它上面只建表不涉及存量）。
-- 若出现 FROZEN_RETURN_APPLY 这类细分标（策略 action_json 里配的），它们**允许**不在目录里：
-- 前缀 FROZEN 仍能按冻结语义显示，只是没有专属中文名 —— 这是期 2 的事。
SELECT 'V4 自检·不在目录里的存量标码' AS step, t.`tag_code`, COUNT(*) AS rows_cnt
  FROM `order_audit_tag` t
  LEFT JOIN `audit_tag_catalog` c ON c.`code` = t.`tag_code`
 WHERE c.`id` IS NULL
 GROUP BY t.`tag_code`
 ORDER BY rows_cnt DESC;

-- 存量不变自检：总量与 active 数（本迁移不写这张表，数字必须与执行前逐位相同）
SELECT 'V4 自检·order_audit_tag 存量（迁移前后必须一致）' AS step,
       COUNT(*) AS rows_cnt, SUM(`active` = 1) AS active_cnt
  FROM `order_audit_tag`;
