-- ============================================================================
-- V3 · 冻结原因做成一等公民：字典表 audit_freeze_reason + order_audit_tag.reason_id/reason_code
-- ============================================================================
-- ⚠️ 一旦落库就不可再改（Flyway checksum）；V1/V2 一个字符都不动。
--
-- 为什么做（P2 需求原话）
-- ----------------------
--   「reason 是自由文本，库里躺着 1231、2131、111；没法统计、没法做豁免的键。」
--   2026-11-16 落库前现读现算，qiqi_mall_test 里 FROZEN 的 reason 是：
--     存在待处理退款申请 x3（id 44/45/87，其中 2 条已解除）/ 风控确认 x1（id 88）/
--     111、2131、1231（id 89/90/91，active）—— 共 7 行、5 个 distinct 值。
--   e2e 库（_a/_b）跑批时会重置，行数不固定，回填对它们是 0 行或 1 行，都安全。
--   ⇒ 只有把原因变成**有限闭集 + 稳定键**，才答得上「这个月为什么冻了 50 单」，
--     也才做得成 P1 的「不再以此原因冻结该单」（豁免要以原因为键，键必须稳定）。
--
-- 三个设计决定（详见 P2 设计稿 ①②，已获批）
-- ----------------------------------------
--   ① 关联用 **reason_id（FK）+ reason_code（快照）双列**，不是二选一：
--      · reason_id 给库层引用完整性（不许指向不存在的字典项）+ 零字符串比较的统计；
--      · reason_code 是**对外稳定键**（P1 豁免 / 策略 action_json / 导出回放都用它）——
--        因为 id 是自增代理键，跨库不稳定（dev 手工插一条，e2e 的 id 就漂了），
--        「策略配置按 id 引用」会在另一个库静默指错，这个风险比"一份冗余"大得多。
--      · P1 的豁免键 = reason_code。**不是 id、不是中文名** ⇒ 改中文名永远不破坏豁免。
--   ② 原 reason 自由文本列**保留**，语义降级为「冻结那一刻的人话快照」，迁移里**一个字符都不改**：
--      · 历史原文是回滚锚点（物理回滚靠它）与取证快照（字典改名后历史行仍显示"当时叫什么"）；
--      · 已有 e2e 断言压在这列上（判定链与四结局-阻止与冻结.spec.js:212、P0 的
--        冻结看得见撤得掉.spec.js 共 13 处 frozenReason 断言）—— 动它就是改证据；
--      · 这也是本迁移「只加列、不动数据」的原因：它让整个 P2 可以一夜退回。
--
--   ★ reason 列到底装什么（2026-11-16 决策，别以为漏了列）
--     reason 承载的是**「冻结那一刻的人话快照」**：
--         人工冻结 → 备注（运营手打的那句），备注为空则写字典中文名；
--         自动冻结 → 策略 action_json 里配的原话，没配则写字典中文名；
--         历史行   → 原文一个字符不改（111 / 1231 / 2131 仍是原样）。
--     **它不是字典名的副本**。字典中文名由 reason_id 解析（读侧一律 join/缓存取值，永远显示中文不显示 ID）。
--     名字的历史（"这条原因三年前叫什么"）不在本表，见 admin_audit_log：
--     冻结那一刻的完整人话（中文名 + 备注 + 原因码）固化在 AUDIT_FREEZE 的
--     summary（人话）与 after_json（结构化 reason_code / reason_name）里。
--
--   ★ 审计摘要里这三个东西的顺序**不许改**：`原因码 → 中文名 → 备注`
--     冻结那笔审计（AUDIT_FREEZE）的 summary 由 OrderAuditTagService#freeze 拼，
--     AdminAuditWriter 会把它裁到 255 字符（LedgerText.clip，裁到 254 + "…"，不抛异常）。
--     于是"按直觉把原因码放在句尾"就会被**从尾部静默截断**——备注一长，REFUND_PENDING 消失，
--     而压在这句话上的断言就会随机变红（明明冻结是成功的）。
--     规则：机器要断言的靠前、人写的靠后。截断只该吃备注的尾巴。
--     另：码与名字的权威落点是 admin_audit_log.after_json（json，不受 255 限制），
--     summary 只作为人话可读面；admin_audit_log.reason 只有 varchar(32)，装不下码+名字，
--     且它是"敏感动作管理员填写的原因"，与冻结原因语义不同 ⇒ 本设计不占用它。
--
--     之所以**不做独立的 note 列**：P0 已经把「冻结原因」的展示契约定成 frozenReason = 人话
--     （冻结看得见撤得掉.spec.js 有 13 处断言压在这上面），再开 note 列会让同一件事有两个存处、
--     两处漂移；合并成一列既兼容既有展示语义，又少一列少一个查询少一处漂移。
--   ③ 回填只做「脏值归因」，不做「美化」：不做 UPDATE ... SET reason = 字典名。
--      111/1231/2131 → LEGACY_PLACEHOLDER（历史未填）；未映射 → UNCLASSIFIED（可见、不可选）。
--
-- 本文件幂等：可重复执行（Flyway 正常只跑一次，这里保证手工重放/多环境安全）
-- ------------------------------------------------------------------------------
--   · 建表 IF NOT EXISTS；加列/加索引前查 information_schema，已存在则跳过；
--   · 字典 seed 靠 uk_code + ON DUPLICATE KEY UPDATE id=id（**不用 INSERT IGNORE**，项目铁律）；
--     故意不更新 name/description：运维在字典页改过的中文名不能被重放冲掉；
--   · 回填 WHERE reason_id IS NULL（不是"靠文本相同"）：第二次执行影响 0 行，
--     且即使中途有人手工改过 tag 行也不会被覆盖；
--   · 范围限 tag_code LIKE 'FROZEN%'：STOCK_SHORT / COST_MISSING 一行不碰（见下「边界」）。
--
-- 边界（明确划出去，本迁移不碰）
-- ----------------------------
--   · STOCK_SHORT / COST_MISSING 的 reason 是 verdict.reasonText() 长句，它们是
--     「留人工的原因」而不是「冻结原因」，且已有结构化字段 downgrade_reason 表达 ⇒ reason_id 留 NULL。
--   · order_audit_decision.evidence_json.$.reason 是**判定理由**（含数值差），与冻结原因无关，不动。
--   · FROZEN_RETURN_APPLY 这类细分**标码**继续由 tag_code 表达：标码说"哪类拦截"，原因说"为什么"。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 一、原因字典表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_freeze_reason` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`        varchar(32)  NOT NULL COMMENT '稳定键：大写字母+下划线。一经写入永不修改——P1 冻结豁免、策略 action_json、导出/回放都以它为键',
  `name`        varchar(50)  NOT NULL COMMENT '中文名（人看的那一句）；随便改，改名不影响任何键',
  `description` varchar(255) DEFAULT NULL COMMENT '说明：为什么冻、下一步该做什么（给客服看的操作指引）',
  `enabled`     tinyint      NOT NULL DEFAULT '1' COMMENT '1 启用 0 停用：停用后新冻结不能再选它，历史行照旧显示与统计',
  `selectable`  tinyint      NOT NULL DEFAULT '1' COMMENT '1 允许人工选择 0 仅系统兜底/迁移归类项（报表与字典页可见，但不许拿它去冻新单）',
  `builtin`     tinyint      NOT NULL DEFAULT '0' COMMENT '1 系统内置（兜底/未分类）：禁止删除、禁止停用',
  `sort_no`     int          NOT NULL DEFAULT '100' COMMENT '排序（升序，小的在前）',
  `created_at`  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_enabled_sort` (`enabled`,`sort_no`),
  CONSTRAINT `ck_freeze_reason_enabled`    CHECK ((`enabled` in (0,1))),
  CONSTRAINT `ck_freeze_reason_selectable` CHECK ((`selectable` in (0,1))),
  CONSTRAINT `ck_freeze_reason_builtin`    CHECK ((`builtin` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='冻结原因字典：审核标记 FROZEN 的原因（一等公民，可统计/可作豁免键）';

-- ---------------------------------------------------------------------------
-- 二、order_audit_tag 加关联列（**不重建表**：它带 active_tag 生成列 + uk_active，重建风险高）
-- ---------------------------------------------------------------------------
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 兜幂等
SET @db := DATABASE();

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'order_audit_tag' AND COLUMN_NAME = 'reason_id');
SET @sql := IF(@has_col = 0,
  'ALTER TABLE `order_audit_tag` ADD COLUMN `reason_id` bigint DEFAULT NULL
     COMMENT ''冻结原因字典ID（FROZEN 类标必填；STOCK_SHORT/COST_MISSING 等非冻结标留 NULL）'' AFTER `reason`',
  'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'order_audit_tag' AND COLUMN_NAME = 'reason_code');
SET @sql := IF(@has_col = 0,
  'ALTER TABLE `order_audit_tag` ADD COLUMN `reason_code` varchar(32) DEFAULT NULL
     COMMENT ''原因码快照（= audit_freeze_reason.code，写入时由字典带出）：对外/统计/豁免都用它，改名不受影响'' AFTER `reason_id`',
  'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'order_audit_tag' AND INDEX_NAME = 'idx_reason');
SET @sql := IF(@has_idx = 0,
  'ALTER TABLE `order_audit_tag` ADD KEY `idx_reason` (`reason_id`)', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 统计主索引：{reason_code, created_at} 直接支撑「这个月为什么冻了 N 单」，无需 join 字典
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'order_audit_tag' AND INDEX_NAME = 'idx_reason_code_created');
SET @sql := IF(@has_idx = 0,
  'ALTER TABLE `order_audit_tag` ADD KEY `idx_reason_code_created` (`reason_code`,`created_at`)', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_fk := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'order_audit_tag'
                  AND CONSTRAINT_NAME = 'fk_tag_reason');
SET @sql := IF(@has_fk = 0,
  'ALTER TABLE `order_audit_tag` ADD CONSTRAINT `fk_tag_reason`
     FOREIGN KEY (`reason_id`) REFERENCES `audit_freeze_reason` (`id`)', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- reason 列注释改语义（只改注释，不动一个字节的数据 + 不重建表：COMMENT 是 metadata-only）
ALTER TABLE `order_audit_tag`
  MODIFY COLUMN `reason` varchar(255) DEFAULT NULL
    COMMENT '人话快照/备注（写入规则：人工冻结=备注，空则字典中文名；自动冻结=策略配的原话，空则字典中文名）；迁移前的历史行保留原文不动。原因本身看 reason_id/reason_code';

-- ---------------------------------------------------------------------------
-- 三、字典初版（11 条）
-- ---------------------------------------------------------------------------
-- 依据：代码里会写 FROZEN 标的三条来路（AuditExecutor FROZEN 分支 / OrderAuditController#freeze
-- 人工 / action_json 缺失时的兜底）+ 存量 5 种值。人工侧原本没有枚举，CUSTOMER_HOLD 起的几条
-- 是按运营常见场景给的初版，待客服确认后再增删（第二期有维护页）。
INSERT INTO `audit_freeze_reason`
  (`code`,`name`,`description`,`enabled`,`selectable`,`builtin`,`sort_no`) VALUES
  ('REFUND_PENDING',            '退款申请未处理',        '有未处理完的退款/退货申请，处理完申请再解冻放行',           1,1,0,  10),
  ('PLATFORM_REMARK_CHANGED',   '平台备注刚被改过',      '24 小时内平台备注被修改过，需人工核对改了什么',             1,1,0,  20),
  ('RISK_REVIEW',               '风控核实中',            '风控/安全要求暂缓发货，等核实结论再放行',                   1,1,0,  30),
  ('CUSTOMER_HOLD',             '客户要求暂缓发货',      '客户明确要求延后发货或改期',                               1,1,0,  40),
  ('SUSPECTED_BRUSHING',        '疑似刷单待核实',        '疑似刷单/异常下单，等风控确认',                             1,1,0,  50),
  ('PRICE_OR_DISCOUNT_ANOMALY', '价格或优惠异常',        '成交价/优惠明显反常（错价、优惠叠加异常、凑单）',           1,1,0,  60),
  ('BIG_ORDER_REVIEW',          '大额订单人工核单',      '金额超出常规区间，先线下核单再发',                         1,1,0,  70),
  ('ADDRESS_SUSPECT',           '收货信息可疑',          '地址/电话明显异常（重复地址、虚假号码）',                   1,1,0,  80),
  ('POLICY_FREEZE_UNSPECIFIED', '异常策略命中（未填原因）','系统兜底：异常策略命中但策略没配具体原因',                 1,0,1, 900),
  ('LEGACY_PLACEHOLDER',        '历史未填（占位）',      '迁移前的占位/乱填值（如 111/1231/2131），无法归因',         1,0,1, 910),
  ('UNCLASSIFIED',              '未分类（待人工归类）',  '迁移时词表没覆盖到的自由文本；保留可见，可人工改归',         1,0,1, 920)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 四、回填历史脏值（只归因，不美化；只补 reason_id 为空的行 ⇒ 幂等）
-- ---------------------------------------------------------------------------
-- 规则顺序有讲究：**数字规则必须排在中文关键词之前**，否则将来出现「1231退款」这种
-- 混合脏值会被归到 REFUND_PENDING 而不是 LEGACY_PLACEHOLDER（乱填的就是乱填的）。
-- 关键词从具体到宽泛排；ELSE 兜到 UNCLASSIFIED（enabled=1 可见、selectable=0 不可选）——
-- 这就是「无法映射的统一兜底、保留可见、不许悄悄丢掉」的落地。
UPDATE `order_audit_tag` t
  JOIN `audit_freeze_reason` d
    ON d.`code` = CASE
         WHEN t.`reason` IS NULL OR TRIM(t.`reason`) = ''            THEN 'LEGACY_PLACEHOLDER'
         WHEN TRIM(t.`reason`) REGEXP '^[0-9]+$'                     THEN 'LEGACY_PLACEHOLDER'
         WHEN t.`reason` LIKE '%退款%' OR t.`reason` LIKE '%退货%'    THEN 'REFUND_PENDING'
         WHEN t.`reason` LIKE '%备注%'                                THEN 'PLATFORM_REMARK_CHANGED'
         WHEN t.`reason` LIKE '%风控%'                                THEN 'RISK_REVIEW'
         WHEN t.`reason` LIKE '%刷单%'                                THEN 'SUSPECTED_BRUSHING'
         WHEN t.`reason` LIKE '%暂缓%' OR t.`reason` LIKE '%延后%'
           OR t.`reason` LIKE '%改期%'                                THEN 'CUSTOMER_HOLD'
         WHEN t.`reason` LIKE '%错价%' OR t.`reason` LIKE '%优惠%'
           OR t.`reason` LIKE '%价格%'                                THEN 'PRICE_OR_DISCOUNT_ANOMALY'
         WHEN t.`reason` LIKE '%大额%' OR t.`reason` LIKE '%核单%'    THEN 'BIG_ORDER_REVIEW'
         WHEN t.`reason` LIKE '%地址%' OR t.`reason` LIKE '%电话%'    THEN 'ADDRESS_SUSPECT'
         ELSE 'UNCLASSIFIED'
       END
   SET t.`reason_id`   = d.`id`,
       t.`reason_code` = d.`code`
 WHERE t.`tag_code` LIKE 'FROZEN%'
   AND t.`reason_id` IS NULL;

-- ---------------------------------------------------------------------------
-- 五、迁移自检（只读，输出在 flyway 日志里；预期值见下）
-- ---------------------------------------------------------------------------
-- 预期：qiqi_mall_test → REFUND_PENDING 3 / RISK_REVIEW 1 / LEGACY_PLACEHOLDER 3 / 其余 0
--       qiqi_mall_e2e_a、_b → 各 REFUND_PENDING 1
--       qiqi_mall / qiqi_mall_it / qiqi_mall_e2e_c → 0 行；qiqi_mall_e2e_d → 空表
SELECT 'V3 自检·冻结原因分布' AS step, t.`tag_code`, IFNULL(t.`reason_code`,'<NULL>') AS reason_code,
       COUNT(*) AS rows_cnt
  FROM `order_audit_tag` t
 WHERE t.`tag_code` LIKE 'FROZEN%'
 GROUP BY t.`tag_code`, t.`reason_code`
 ORDER BY rows_cnt DESC;

-- 兜底可见性自检：这个数必须能对上「上面分布里 LEGACY + UNCLASSIFIED 之和」，不许为负
SELECT 'V3 自检·未归因行数（必须可见，不许为 0 被悄悄丢掉）' AS step, COUNT(*) AS rows_cnt
  FROM `order_audit_tag`
 WHERE `tag_code` LIKE 'FROZEN%'
   AND `reason_code` IN ('LEGACY_PLACEHOLDER','UNCLASSIFIED');
