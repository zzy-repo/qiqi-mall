-- ============================================================================
-- 迁移：订单自动审核模块 DDL（6 张新表 + 6 张现有表加列）
-- ============================================================================
--
-- ── 交付范围 ────────────────────────────────────────────────────────────────
--   新增 6 张：audit_policy / audit_policy_condition / audit_policy_version /
--              audit_run / order_audit_decision / order_audit_tag
--   加列 6 张：order（8 列）、member（1 列）、sku（1 列）、order_item（1 列）、
--              order_setting（3 列）、order_audit_tag（建表即含虚拟列）
--   全库从 30 张表 → 36 张表。
--
-- ── 设计口径（照定稿执行，本文件不重新论证设计）────────────────────────────
--   ① 策略主体 audit_policy 用 CHECK 钉死「type ↔ 专属列」的对应关系：
--      action_json 只属于 EXCEPTION（冻结动作），AUTO/BLOCK 必须为 NULL。
--      CHECK 违规会回滚整个业务事务 ⇒ 只钉「结构自洽」，不钉业务枚举值域。
--   ② 条件用**行模型**（audit_policy_condition），不用 JSON 列：
--      field_code / op_code **刻意不做 DDL CHECK 闭集** —— 闭集住在
--      com.qiqi.mall.audit.engine.FieldRegistry / OperationRegistry，
--      加一个字段就是改一处 Java 枚举 + 一条前端下拉，不用再跑 DDL。
--   ③ scope=ALL_AUTO 是**全局排除**（决策 C1：客服备注含「改」不是策略 1 专属）：
--      这类行的 policy_id 必须为 NULL（不属于任何策略），引擎在评估任一 AUTO 策略时
--      把它们并进排除集合。POLICY 级的行才必须挂 policy_id。
--   ④ audit_run.idem_key **只允许 SCHEDULE 触发时填 {trigger}:{yyyyMMddHHmm}**，
--      **严禁由业务键派生** —— 沿用 admin_audit_log 的红线：业务键派生会让
--      「同一次调度重放」与「两次真实调度」撞同一个键，前者该跳过、后者必须留两条。
--      MANUAL 触发一律 NULL（人工点两次就是两次 run，第二次的失败/差异本身是证据）。
--   ⑤ order_audit_decision 双唯一键：
--      uk_idem(order_id:round_no) + uk_order_round(order_id, round_no)。
--      前者是幂等键明文（排查时直接读得懂），后者是并发兜底下限（即使将来有人改了
--      幂等键拼接格式，也不允许同一单同一轮出现两行决策）。
--   ⑥ order_audit_tag 用虚拟列 active_tag 做唯一键：冻结不是新状态码（决策 J1），
--      同一订单同一 tag 只允许**一条 active**；解冻 = active 置 0（虚拟列随之变 NULL，
--      唯一键不再约束，历史行全留）。MySQL 唯一索引允许多个 NULL，于是「发布新 tag
--      后再冻结」不会撞键，而「同时冻结两次」会被 DB 直接拒掉。
--   ⑦ 冻结/解冻**不写 order_status_ledger**（7→7 会被 TRANSITIONS 拦，且那不是状态流转），
--      只写 admin_audit_log（AUDIT_FREEZE / AUDIT_UNFREEZE）。
--   ⑧ 台账口径：order_audit_decision / audit_run 是**只追加**（无 updated_at 语义），
--      但 audit_run 的五个计数在 run 结束时回写一次 —— 见下方注释，这是刻意的例外：
--      run 行必须在「跑之前」就存在（决策行要引用 run_id），计数只能跑完再补。
--   ⑨ 时间列口径：新表一律 DATETIME(3)（与台账一致）；order 上给业务列用 DATETIME。
--   ⑩ 新表显式 COLLATE=utf8mb4_0900_ai_ci（跟真实库走，见 migrate-ledger.sql 说明）。
--
-- ── 幂等 ────────────────────────────────────────────────────────────────────
--   可重复执行：CREATE TABLE IF NOT EXISTS；加列前先查 information_schema.COLUMNS。
--   第二遍执行时每一步打印 [SKIP ...]，不报错、不改数据。本文件不含 DROP / DELETE / TRUNCATE。
--
-- ── 执行方式（必须用 mysql 客户端：本文件用了 DELIMITER）──────────────────
--   mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/migrate-order-audit.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4：客户端默认 latin1 时列注释里的中文会二次编码成乱码。
--   目标库由 DATABASE() 决定（不写死 USE），因此可在 scratch 库上验证。
-- ============================================================================

-- ---------- 前置守卫：必须已选中目标库 ----------
SET @db := DATABASE();
SET @s := IF(@db IS NULL,
  'SELECT ''✗ 未选中目标库：请用 mysql -uroot -p <库名> < sql/migrate-order-audit.sql'' AS guard',
  'SELECT ''✓ 目标库'' AS guard, @db AS db');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@db IS NULL,
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''migrate-order-audit: DATABASE() 为空，拒绝执行''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;


-- ############################################################################
-- 一、新增 6 张表
-- ############################################################################

-- ---------- 1. 审核策略主体 ----------
CREATE TABLE IF NOT EXISTS `audit_policy` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT          COMMENT '主键',
  `type`        ENUM('AUTO','BLOCK','EXCEPTION') NOT NULL     COMMENT 'AUTO=自动过审候选 / BLOCK=前置拦截 / EXCEPTION=异常冻结',
  `priority`    INT         NOT NULL DEFAULT 100             COMMENT '同类型内升序执行：数字小的先跑',
  `name`        VARCHAR(64) NOT NULL                         COMMENT '策略名（决策行会快照它，改名不影响历史决策）',
  `enabled`     TINYINT     NOT NULL DEFAULT 1               COMMENT '1启用 0停用（停用不删，历史决策仍指得到）',
  `version_no`  INT         NOT NULL DEFAULT 1               COMMENT '策略版本号：每次改条件 +1，决策行记当时版本',
  `action_json` JSON        DEFAULT NULL                     COMMENT 'EXCEPTION 专属冻结动作：{"tag":"FROZEN","reason":"..."}；AUTO/BLOCK 必须 NULL（见 CHECK）',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_name` (`type`, `name`),
  KEY `idx_type_priority` (`type`, `priority`, `enabled`),
  -- type ↔ 专属列：EXCEPTION 必须有冻结动作；AUTO/BLOCK 不许带动作（带了就说明有人在配一个不会被执行的动作）
  CONSTRAINT `ck_audit_policy_type_action`
    CHECK ((`type` = 'EXCEPTION' AND `action_json` IS NOT NULL)
        OR (`type` IN ('AUTO','BLOCK') AND `action_json` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审核策略主体（AUTO/BLOCK/EXCEPTION）';

-- ---------- 2. 策略条件（行模型）----------
CREATE TABLE IF NOT EXISTS `audit_policy_condition` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT          COMMENT '主键',
  `policy_id`   BIGINT      DEFAULT NULL                     COMMENT '所属策略；scope=ALL_AUTO 时必须 NULL（全局排除不属于任何策略）',
  `seq`         INT         NOT NULL DEFAULT 0                COMMENT '展示/求值顺序（升序）',
  `kind`        ENUM('MATCH','EXCLUDE') NOT NULL              COMMENT 'MATCH=必须满足 / EXCLUDE=命中即排除',
  `scope`       ENUM('POLICY','ALL_AUTO') NOT NULL DEFAULT 'POLICY' COMMENT 'POLICY=本策略级 / ALL_AUTO=全部 AUTO 策略共用（全局排除）',
  `field_code`  VARCHAR(48) NOT NULL                          COMMENT '字段码；**不做 DDL CHECK 闭集**，由 FieldRegistry 校验',
  `op_code`     VARCHAR(24) NOT NULL                          COMMENT '操作符码；由 OperationRegistry 校验',
  `value_type`  ENUM('NUM','TEXT','BOOL','JSON') NOT NULL     COMMENT '值的类型（决定读哪个 value_* 列）',
  `value_num`   DECIMAL(20,4) DEFAULT NULL                    COMMENT 'NUM/BOOL 值（BOOL 用 1/0）',
  `value_text`  VARCHAR(255) DEFAULT NULL                     COMMENT 'TEXT 值',
  `value_json`  JSON        DEFAULT NULL                      COMMENT 'JSON 值（预留：IN 列表 / 区间等）',
  PRIMARY KEY (`id`),
  KEY `idx_policy_seq` (`policy_id`, `seq`),
  KEY `idx_scope_kind` (`scope`, `kind`),
  CONSTRAINT `fk_audit_policy_condition_policy`
    FOREIGN KEY (`policy_id`) REFERENCES `audit_policy` (`id`) ON DELETE CASCADE,
  -- 全局排除只能是 EXCLUDE（ALL_AUTO 的 MATCH 没有语义：没有 owner 就没有「它的策略」可满足）
  CONSTRAINT `ck_audit_policy_condition_scope_kind`
    CHECK (`scope` = 'POLICY' OR `kind` = 'EXCLUDE'),
  -- 归属：POLICY 级必须挂策略，ALL_AUTO 级必须不挂（否则删策略会顺手删掉全局排除）
  CONSTRAINT `ck_audit_policy_condition_owner`
    CHECK ((`scope` = 'POLICY' AND `policy_id` IS NOT NULL)
        OR (`scope` = 'ALL_AUTO' AND `policy_id` IS NULL)),
  -- value_type ↔ value_* 列对应；一元操作符（EXISTS / NOT_EXISTS）允许三列全 NULL
  CONSTRAINT `ck_audit_policy_condition_value`
    CHECK ((`value_num` IS NULL AND `value_text` IS NULL AND `value_json` IS NULL)
        OR (`value_type` = 'NUM'  AND `value_num`  IS NOT NULL AND `value_text` IS NULL     AND `value_json` IS NULL)
        OR (`value_type` = 'BOOL' AND `value_num`  IS NOT NULL AND `value_text` IS NULL     AND `value_json` IS NULL)
        OR (`value_type` = 'TEXT' AND `value_text` IS NOT NULL AND `value_num`  IS NULL     AND `value_json` IS NULL)
        OR (`value_type` = 'JSON' AND `value_json` IS NOT NULL AND `value_num`  IS NULL     AND `value_text` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审核策略条件（行模型：一条条件一行，不用 JSON 列）';

-- ---------- 3. 策略版本快照 ----------
CREATE TABLE IF NOT EXISTS `audit_policy_version` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT        COMMENT '主键',
  `policy_id`     BIGINT      NOT NULL                       COMMENT '所属策略（随策略级联删除）',
  `version_no`    INT         NOT NULL                       COMMENT '版本号（与 audit_policy.version_no 对齐）',
  `snapshot_json` JSON        NOT NULL                       COMMENT '策略 + 全部条件的快照（改完再看历史决策能还原当时配置）',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_version` (`policy_id`, `version_no`),
  CONSTRAINT `fk_audit_policy_version_policy`
    FOREIGN KEY (`policy_id`) REFERENCES `audit_policy` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审核策略版本快照（append-only）';

-- ---------- 4. 一次 run ----------
CREATE TABLE IF NOT EXISTS `audit_run` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT         COMMENT '主键',
  `trigger_type` ENUM('MANUAL','SCHEDULE') NOT NULL           COMMENT 'MANUAL=人工触发 / SCHEDULE=定时任务',
  `idem_key`     VARCHAR(96) DEFAULT NULL                     COMMENT '⚠️仅 SCHEDULE 填 {trigger}:{yyyyMMddHHmm}；严禁业务键派生，MANUAL 一律 NULL',
  `total`        INT         NOT NULL DEFAULT 0               COMMENT '本轮产出决策的单数（含 SKIPPED：五类之和可能小于 total）',
  `auto_pass`    INT         NOT NULL DEFAULT 0               COMMENT '自动过审数',
  `manual`       INT         NOT NULL DEFAULT 0               COMMENT '转人工数（含降级）',
  `blocked`      INT         NOT NULL DEFAULT 0               COMMENT '被 BLOCK 策略拦截数',
  `frozen`       INT         NOT NULL DEFAULT 0               COMMENT '异常冻结数',
  `no_match`     INT         NOT NULL DEFAULT 0               COMMENT '没有任何 AUTO 策略命中数',
  `created_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='一次自动审核 run（计数在建 run 时写 0、跑完回写一次）';

-- ---------- 5. 决策台账（每单每轮一行）----------
CREATE TABLE IF NOT EXISTS `order_audit_decision` (
  `id`                        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`                  BIGINT      NOT NULL                COMMENT '订单ID（业务主键·无外键：证据不随订单级联删除）',
  `run_id`                    BIGINT      NOT NULL                COMMENT '所属 run（audit_run.id，无外键同理）',
  `round_no`                  INT         NOT NULL                COMMENT '该订单的第几轮审核（1 起，按 MAX+1）',
  `result`                    ENUM('AUTO_PASS','MANUAL','BLOCKED','FROZEN','NO_MATCH','SKIPPED') NOT NULL COMMENT '本轮判定结论',
  `matched_policy_id`         BIGINT      DEFAULT NULL            COMMENT '命中的策略（AUTO_PASS/BLOCKED/FROZEN）',
  `matched_policy_name`       VARCHAR(64) DEFAULT NULL            COMMENT '命中策略名快照',
  `matched_policy_version_no` INT         DEFAULT NULL            COMMENT '命中策略当时的版本号',
  `matched_condition_json`    JSON        DEFAULT NULL            COMMENT '命中/满足的条件明细（含 expected vs actual）',
  `excluded_by_json`          JSON        DEFAULT NULL            COMMENT '排除条件命中明细（POLICY ∪ ALL_AUTO）',
  `miss_reason_json`          JSON        DEFAULT NULL            COMMENT '逐条策略「差在哪」（NO_MATCH / 未命中时）',
  `would_be_auto_policy_id`   BIGINT      DEFAULT NULL            COMMENT '只读回填：若没被拦截/冻结，会命中哪条 AUTO 策略',
  `would_be_auto_policy_name` VARCHAR(64) DEFAULT NULL            COMMENT '只读回填的策略名快照',
  `downgrade_reason`          VARCHAR(32) DEFAULT NULL            COMMENT 'AUTO_PASS 后预检降级原因：STOCK_SHORT / COST_MISSING',
  `evidence_json`             JSON        DEFAULT NULL            COMMENT '证据：人话原因（含具体数值差）+ 库存/成本/冻结动作细节',
  `order_status_seq`          INT         DEFAULT NULL            COMMENT '本轮订单状态流转后的 status_seq（对得上 order_status_ledger）',
  `operator_type`             VARCHAR(16) NOT NULL                COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id`               BIGINT      DEFAULT NULL            COMMENT '操作者ID（系统/调度为 NULL）',
  `operator_name`             VARCHAR(64) DEFAULT NULL            COMMENT '操作者名快照',
  `idem_key`                  VARCHAR(96) NOT NULL                COMMENT '幂等键 {orderId}:{roundNo}',
  `created_at`                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '判定时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_order_round` (`order_id`, `round_no`),
  KEY `idx_run_result` (`run_id`, `result`),
  KEY `idx_order` (`order_id`, `created_at`),
  CONSTRAINT `ck_order_audit_decision_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单审核决策台账（每单每轮一行，只追加）';

-- ---------- 6. 订单审核标记（冻结/挂起不是状态码）----------
CREATE TABLE IF NOT EXISTS `order_audit_tag` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT          COMMENT '主键',
  `order_id`    BIGINT      NOT NULL                         COMMENT '订单ID',
  `tag_code`    VARCHAR(32) NOT NULL                         COMMENT 'FROZEN / STOCK_SHORT / COST_MISSING …',
  `active_tag`  VARCHAR(48) GENERATED ALWAYS AS (IF(`active` = 1, CONCAT(`order_id`, ':', `tag_code`), NULL)) VIRTUAL
                                                            COMMENT '仅 active 时非空的唯一键载体：{orderId}:{tagCode}',
  `active`      TINYINT     NOT NULL DEFAULT 1               COMMENT '1生效 0已解除',
  `reason`      VARCHAR(255) DEFAULT NULL                    COMMENT '为什么打这个标',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '打标时间',
  `released_at` DATETIME(3) DEFAULT NULL                     COMMENT '解除时间（解冻/恢复）',
  `released_by` VARCHAR(64) DEFAULT NULL                     COMMENT '解除人（ADMIN 用户名 / APIKEY 名 / system）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active` (`active_tag`),
  KEY `idx_order` (`order_id`, `tag_code`),
  CONSTRAINT `ck_order_audit_tag_active` CHECK (`active` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单审核标记（冻结/挂起用标表达，不动 order.status）';


-- ############################################################################
-- 二、幂等 DDL 助手（跑完即 DROP，不留在库里）
-- ############################################################################

DROP PROCEDURE IF EXISTS zz_audit_add_col;

DELIMITER $$

-- 加列：information_schema.COLUMNS 里没有才加
CREATE PROCEDURE zz_audit_add_col(IN p_tbl VARCHAR(64), IN p_col VARCHAR(64), IN p_def TEXT)
BEGIN
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl AND COLUMN_NAME = p_col) = 0 THEN
    SET @s = CONCAT('ALTER TABLE `', p_tbl, '` ADD COLUMN `', p_col, '` ', p_def);
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    SELECT CONCAT('[ADD COLUMN] ', p_tbl, '.', p_col) AS ddl_step;
  ELSE
    SELECT CONCAT('[SKIP COLUMN EXISTS] ', p_tbl, '.', p_col) AS ddl_step;
  END IF;
END$$

DELIMITER ;


-- ############################################################################
-- 三、现有表加列
-- ############################################################################

-- ---------- 3.1 order：渠道/店铺/毛利/备注/多地址 ----------
-- 这些列是**判定上下文的输入**：策略条件全靠它们，缺一列就有一条字段取不到值。
-- 全部可空（历史订单没有这些信息）—— 字段取值函数对 NULL 返回「无值」，
-- EXISTS/NOT_EXISTS 才表达得出「这单没有客服备注」。
CALL zz_audit_add_col('order', 'channel', 'VARCHAR(16) DEFAULT NULL COMMENT ''销售渠道（直营/分销/…）：CHANNEL 字段来源''');
CALL zz_audit_add_col('order', 'shop_name', 'VARCHAR(64) DEFAULT NULL COMMENT ''店铺名：SHOP_NAME 字段来源''');
CALL zz_audit_add_col('order', 'gross_profit', 'DECIMAL(12,2) DEFAULT NULL COMMENT ''毛利（下单时算好落库）：GROSS_PROFIT 字段来源；NULL=没算过''');
CALL zz_audit_add_col('order', 'cs_remark', 'VARCHAR(255) DEFAULT NULL COMMENT ''客服备注：CS_REMARK 字段来源（含「改」= 全局排除）''');
CALL zz_audit_add_col('order', 'platform_remark', 'VARCHAR(255) DEFAULT NULL COMMENT ''平台备注（买家留言同步）：PLATFORM_REMARK_UPDATED 字段来源''');
CALL zz_audit_add_col('order', 'platform_remark_updated_at', 'DATETIME DEFAULT NULL COMMENT ''平台备注最后更新时间；NULL=从未更新（字段取值为「无值」）''');
CALL zz_audit_add_col('order', 'remark_consumed_at', 'DATETIME DEFAULT NULL COMMENT ''备注被审核消费的时间（防止同一条备注反复触发排除）''');
CALL zz_audit_add_col('order', 'audit_addr_multi', 'TINYINT NOT NULL DEFAULT 0 COMMENT ''多地址订单标记：ADDRESS_MULTI 字段来源''');

-- ---------- 3.2 member：客户标签 ----------
CALL zz_audit_add_col('member', 'customer_tag', 'VARCHAR(32) DEFAULT NULL COMMENT ''客户标签（新客/老客/黑名单…）：CUSTOMER_TAG 字段来源''');

-- ---------- 3.3 sku / order_item：成本价 ----------
-- 成本价是「毛利是否可信」的判据：缺失（=0）时不能沉默地当毛利为正（风险③），
-- 引擎会给 COST_MISSING 并转人工，而不是放过去。
CALL zz_audit_add_col('sku', 'cost_price', 'DECIMAL(12,2) DEFAULT NULL COMMENT ''成本价（当前值）；0/NULL = 未维护成本''');
CALL zz_audit_add_col('order_item', 'cost_price', 'DECIMAL(12,2) DEFAULT NULL COMMENT ''下单时成本价快照；0/NULL = 未维护成本（→ COST_MISSING）''');

-- ---------- 3.4 order_setting：自动审核开关 ----------
CALL zz_audit_add_col('order_setting', 'auto_audit_enabled', 'TINYINT NOT NULL DEFAULT 1 COMMENT ''自动审核总开关：0=定时任务不跑（人工仍可手动 run）''');
CALL zz_audit_add_col('order_setting', 'audit_stock_guard', 'VARCHAR(8) NOT NULL DEFAULT ''STRICT'' COMMENT ''库存判据档位：STRICT=不足即降级人工 / LENIENT=不足只告警不拦''');
CALL zz_audit_add_col('order_setting', 'audit_run_interval_min', 'INT NOT NULL DEFAULT 30 COMMENT ''定时审核间隔（分钟）''');

DROP PROCEDURE IF EXISTS zz_audit_add_col;


-- ############################################################################
-- 四、自证（跑完打印一次，便于贴报告）
-- ############################################################################
SELECT '── 本库新表行数（应全为 0）──' AS self_check;
SELECT 'audit_policy' AS tbl, COUNT(*) AS rows_now FROM `audit_policy`
UNION ALL SELECT 'audit_policy_condition', COUNT(*) FROM `audit_policy_condition`
UNION ALL SELECT 'audit_policy_version', COUNT(*) FROM `audit_policy_version`
UNION ALL SELECT 'audit_run', COUNT(*) FROM `audit_run`
UNION ALL SELECT 'order_audit_decision', COUNT(*) FROM `order_audit_decision`
UNION ALL SELECT 'order_audit_tag', COUNT(*) FROM `order_audit_tag`;

SELECT '── 新列自证（6 张现有表）──' AS self_check;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'order'        AND COLUMN_NAME IN ('channel','shop_name','gross_profit','cs_remark','platform_remark',
                                                        'platform_remark_updated_at','remark_consumed_at','audit_addr_multi'))
    OR (TABLE_NAME = 'member'       AND COLUMN_NAME = 'customer_tag')
    OR (TABLE_NAME = 'sku'          AND COLUMN_NAME = 'cost_price')
    OR (TABLE_NAME = 'order_item'   AND COLUMN_NAME = 'cost_price')
    OR (TABLE_NAME = 'order_setting' AND COLUMN_NAME IN ('auto_audit_enabled','audit_stock_guard','audit_run_interval_min')))
ORDER BY TABLE_NAME, COLUMN_NAME;
