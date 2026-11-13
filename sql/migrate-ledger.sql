-- ============================================================================
-- 迁移：台账体系 DDL（新增 5 张只追加表 + 4 张现有表加固）
-- ============================================================================
--
-- ── 交付范围 ────────────────────────────────────────────────────────────────
--   新增 5 张：order_status_ledger / delivery_status_ledger / return_status_ledger /
--              admin_audit_log / wms_exchange_ledger
--   加固 4 张：order（created_at + status_seq）
--              delivery_order（status_seq）
--              return_apply（status_seq + pending_order_id 虚拟列；唯一键 uk_pending_order 本轮**刻意不建**，
--                            原因见下方【已知问题·待办】）
--              payment_record（8 个新列 + 2 个唯一键 + order_id 回填）
--   全库从 25 张表 → 30 张表。
--
-- ── 设计口径（照定稿执行，本文件不重新论证设计）────────────────────────────
--   ① 三张状态台账 = 13 列共同契约**逐字一致** + 每表 1 个业务主键 + **允许域内冗余列**。
--      同构口径（2026-11-13 裁决）：13 列共同契约完全相同；冗余列必须先登记；
--      verify 脚本用「白名单比对」自证每表独有的列恰好等于登记清单，多一列就报错。
--
--      ── 冗余列登记表（REGISTRY）────────────────────────────────────────
--        表                       业务主键          已登记冗余列
--        order_status_ledger      order_id          （无）
--        delivery_status_ledger   delivery_id       delivery_sn, order_id
--        return_status_ledger     return_apply_id   order_id
--      ───────────────────────────────────────────────────────────────────
--
--      · delivery_sn：OrderPurgeService 物理删包裹，单号必须留快照
--      · order_id（包裹）：OrderPurgeService 物理删包裹行，`delivery_order` 这座桥一断，
--        「从订单反查包裹状态流水」就彻底查不到了 —— 与售后台账的 order_id 冗余是同一类缺口，
--        两张表必须对称（2026-11-13 补登）。历史行按 delivery_id → delivery_order.order_id 回填；
--        包裹已被物理删除的旧行回填不到，保持 NULL（那时桥本来就已断，只能靠 delivery_sn 追）
--      · order_id（售后）：return_apply 对 order 是 CASCADE + 物理删单，
--        删单后无 order_id 冗余则从订单反查售后流水彻底断链
--      · 新增冗余列必须先改这张登记表 + ER 图例，再改 verify 的白名单，
--        否则 verify 会直接报「同构被破坏」
--
--   ② 幂等键 = [shard:]{bizId}:{statusSeq}，seq 由 order/delivery_order/return_apply 的
--      status_seq 列在**同一条 UPDATE 里自增**得到。
--      ⚠️ 刻意**不**用 (biz_id, from_status, to_status) 当唯一键：
--         7→8 会合法地发生两次（审核 → 包裹全取消退回 7 → 再审核），三元组会误杀合法流转。
--      ⚠️ **admin_audit_log.idem_key 反过来：必须由 request_id 派生，严禁业务键派生。**
--         审计要求"一次请求一条，连点两次留两条（第二次失败也是证据）"；业务键派生会让
--         第二次点击撞 uk_idem → INSERT 失败 → 事务回滚 → 把本该留下的失败证据抹掉。
--         写 Java 的人一定会踩这条，见该列注释。
--   ③ 时间列一律 created_at DATETIME(3)，**不设 updated_at** —— 只追加表没有"更新时间"概念。
--   ④ 新表显式 COLLATE=utf8mb4_0900_ai_ci：真实库 25 张表实测全是 0900，
--      sql/schema.sql 里声明的 utf8mb4_general_ci 是历史漂移，新表跟**真实库**走。
--   ⑤ CHECK 只加在「台账自身的闭集」上（operator_type / wms.direction）。
--      **业务状态枚举（order.status 等）一律不加 CHECK** —— CHECK 违规会回滚整个业务事务。
--
-- ── 【已知问题·待办】同单可产生两条待处理退货申请（本轮刻意不修）──────────────
--   现状：portal 侧（PortalReturnController#apply）有应用层守卫「该订单已有待处理的退货申请」，
--         admin 侧（POST /admin/return-applies）**没有**这道守卫 ⇒ 并发/管理员手点时，
--         同一张订单可以留下两条 status=0 的申请。
--   原方案：return_apply.pending_order_id 虚拟列（仅 status=0 时非空）+ UNIQUE KEY uk_pending_order，
--         用 DB 唯一键把「一单最多一条待处理」钉死。
--   为什么本轮**不建这个唯一键**：
--     ① 它是一条**新的业务规则**（admin 端现在允许第二条待处理申请，加了键就从「成功」变成
--        DuplicateKey → 500「数据处理失败」），会改变现有行为；
--     ② 它同时让既有集成测试 OptimisticLockIntegrationTest ㉛-C 的构造方式失效
--        （那段故意给同一订单插两条 status=0 的申请来验证「还有别的申请在审批中 → 订单状态不动」）。
--     本条工作线（P2 第一步）的验收标准是「行为完全不变」，同时改业务规则会让失败无法归因。
--   修复方案（单独一轮做）：唯一键 + 给 admin 端补上中文守卫文案 + 改 ㉛-C 的构造方式。
--   ⚠️ 因此 `return_apply.pending_order_id` 列**保留但本轮不使用**（不建唯一键）；
--      sql/schema.sql 与 sql/testdb/01-schema.sql 也**不包含**该唯一键。
--
-- ── 幂等 ────────────────────────────────────────────────────────────────────
--   可重复执行：CREATE TABLE IF NOT EXISTS；加列/加索引/加 CHECK 前先查
--   information_schema（COLUMNS / STATISTICS / TABLE_CONSTRAINTS）。第二遍执行时
--   每一步都会打印 [SKIP ...]，**不报错、不改数据**。
--   回填语句带 `WHERE ... IS NULL`，重跑为 0 行。
--
--   ⚠️ 升级注意：若某个环境**已经按 11-13 旧稿建过** admin_audit_log 的 actor_* 版本，
--      CREATE TABLE IF NOT EXISTS 会跳过建表、留下旧的 actor_* 三列。那 5 张表都是全新的
--      只追加表、无历史数据，直接 `DROP TABLE admin_audit_log;` 再跑本文件即可（本文件自身
--      不做破坏性操作）。当前 qiqi_mall / qiqi_mall_test / e2e 各库都没有这 5 张表，无需处理。
--
-- ── 执行方式（必须用 mysql 客户端：本文件用了 DELIMITER）──────────────────
--   mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/migrate-ledger.sql
--   ⚠️ **必须带 --default-character-set=utf8mb4**：客户端默认 latin1 时，列/表 COMMENT 里的中文会被
--      二次编码成「ä¸»é”®」这种乱码落库（实测踩过：结构对、注释烂，verify 对比时才暴露）。
--   目标库由 DATABASE() 决定（不写死 USE），因此可在 scratch 库上验证。
--   建议顺序：先跑本文件 → 再跑 sql/migrate-ledger-verify.sql。
--   本文件**不**执行任何 DROP / DELETE / TRUNCATE，不含破坏性语句。
-- ============================================================================

-- ---------- 前置守卫：必须已选中目标库 ----------
SET @db := DATABASE();
SET @s := IF(@db IS NULL,
  'SELECT ''✗ 未选中目标库：请用 mysql -uroot -p <库名> < sql/migrate-ledger.sql'' AS guard',
  'SELECT ''✓ 目标库'' AS guard, @db AS db');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 未选库时立刻失败，避免 CREATE TABLE 落到无库上下文
SET @s := IF(@db IS NULL,
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''migrate-ledger: DATABASE() 为空，拒绝执行''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;


-- ############################################################################
-- 一、新增 5 张只追加台账表
-- ############################################################################

-- ---------- 1. 订单状态台账 ----------
CREATE TABLE IF NOT EXISTS `order_status_ledger` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`      BIGINT       NOT NULL                             COMMENT '订单ID（业务主键·无外键：证据不随订单级联删除）',
  `from_status`   TINYINT      DEFAULT NULL                         COMMENT '变更前状态；NULL=出生事件（建单时无前态）',
  `to_status`     TINYINT      NOT NULL                             COMMENT '变更后状态',
  `reason`        VARCHAR(32)  NOT NULL                             COMMENT '流转原因码',
  `reason_detail` VARCHAR(255) DEFAULT NULL                         COMMENT '原因补充说明',
  `operator_type` VARCHAR(16)  NOT NULL                             COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id`   BIGINT       DEFAULT NULL                         COMMENT '操作者ID（系统事件可空）',
  `operator_name` VARCHAR(64)  DEFAULT NULL                         COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type`      VARCHAR(24)  DEFAULT NULL                         COMMENT '触发单据类型：DELIVERY / RETURN_APPLY / PAYMENT …',
  `ref_id`        BIGINT       DEFAULT NULL                         COMMENT '触发单据ID',
  `event_key`     VARCHAR(96)  DEFAULT NULL                         COMMENT '外部事件号（重投只留一条；允许多 NULL）',
  `idem_key`      VARCHAR(96)  NOT NULL                             COMMENT '幂等键 [shard:]{orderId}:{statusSeq}',
  `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem`  (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`order_id`, `created_at`),
  CONSTRAINT `ck_order_status_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单状态台账（只追加证据链）';

-- ---------- 2. 包裹状态台账（多两列冗余：delivery_sn 快照 + order_id）----------
--   ⚠️ 已登记冗余列：delivery_sn、order_id。
--   OrderPurgeService 会**物理删除**包裹行，所以单号必须在此留快照，否则删单后证据链断裂。
--   order_id 同理：删包裹行后 `delivery_order` 这条桥没了，「从订单反查包裹流水」会断链；
--   与 return_status_ledger.order_id 是同一类缺口，必须对称。
CREATE TABLE IF NOT EXISTS `delivery_status_ledger` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `delivery_id`   BIGINT       NOT NULL                             COMMENT '包裹ID（业务主键·无外键：证据不随包裹级联删除）',
  `delivery_sn`   VARCHAR(50)  NOT NULL                             COMMENT '【已登记冗余列】发货单号快照（包裹被物理删除后仍可读）',
  `order_id`      BIGINT       DEFAULT NULL                         COMMENT '【已登记冗余列】冗余订单ID：包裹行被物理删除（OrderPurgeService）后，仍能从订单反查包裹状态流水',
  `from_status`   TINYINT      DEFAULT NULL                         COMMENT '变更前状态；NULL=出生事件（建包裹）',
  `to_status`     TINYINT      NOT NULL                             COMMENT '变更后状态',
  `reason`        VARCHAR(32)  NOT NULL                             COMMENT '流转原因码',
  `reason_detail` VARCHAR(255) DEFAULT NULL                         COMMENT '原因补充说明',
  `operator_type` VARCHAR(16)  NOT NULL                             COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id`   BIGINT       DEFAULT NULL                         COMMENT '操作者ID（系统事件可空）',
  `operator_name` VARCHAR(64)  DEFAULT NULL                         COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type`      VARCHAR(24)  DEFAULT NULL                         COMMENT '触发单据类型',
  `ref_id`        BIGINT       DEFAULT NULL                         COMMENT '触发单据ID',
  `event_key`     VARCHAR(96)  DEFAULT NULL                         COMMENT '仓侧事件号（重投只留一条；允许多 NULL）',
  `idem_key`      VARCHAR(96)  NOT NULL                             COMMENT '幂等键 [shard:]{deliveryId}:{statusSeq}',
  `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem`  (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`delivery_id`, `created_at`),
  KEY `idx_order` (`order_id`, `created_at`),
  CONSTRAINT `ck_delivery_status_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='包裹状态台账（只追加证据链；带单号快照 + 冗余订单ID）';

-- ---------- 3. 售后状态台账 ----------
--   ⚠️ 已登记冗余列：order_id。
--   原因：return_apply 对 order 是 ON DELETE CASCADE，且 OrderPurgeService 会**物理删单**；
--   删单后这条台账既没有 order_id 冗余、也没有 return_apply 可 JOIN ⇒ 从订单反查售后流水
--   彻底断链。与包裹台账加 delivery_sn 快照是同一类问题（ER 售后域图里本就画了 order_id）。
CREATE TABLE IF NOT EXISTS `return_status_ledger` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
  `return_apply_id` BIGINT       NOT NULL                           COMMENT '退货申请ID（业务主键·无外键：证据不随申请级联删除）',
  `order_id`        BIGINT       DEFAULT NULL                       COMMENT '【已登记冗余列】冗余订单ID：return_apply 随订单级联删除/物理删单后，仍能从订单反查售后流水',
  `from_status`     TINYINT      DEFAULT NULL                       COMMENT '变更前状态；NULL=出生事件（提交申请）',
  `to_status`       TINYINT      NOT NULL                           COMMENT '变更后状态',
  `reason`          VARCHAR(32)  NOT NULL                           COMMENT '流转原因码',
  `reason_detail`   VARCHAR(255) DEFAULT NULL                       COMMENT '原因补充说明',
  `operator_type`   VARCHAR(16)  NOT NULL                           COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id`     BIGINT       DEFAULT NULL                       COMMENT '操作者ID（系统事件可空）',
  `operator_name`   VARCHAR(64)  DEFAULT NULL                       COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type`        VARCHAR(24)  DEFAULT NULL                       COMMENT '触发单据类型',
  `ref_id`          BIGINT       DEFAULT NULL                       COMMENT '触发单据ID',
  `event_key`       VARCHAR(96)  DEFAULT NULL                       COMMENT '外部事件号（重投只留一条；允许多 NULL）',
  `idem_key`        VARCHAR(96)  NOT NULL                           COMMENT '幂等键 [shard:]{returnApplyId}:{statusSeq}',
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem`  (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`return_apply_id`, `created_at`),
  CONSTRAINT `ck_return_status_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='售后状态台账（只追加证据链）';

-- ---------- 4. 后台操作审计（目标多态 + before/after JSON）----------
--   与状态台账的幂等口径不同：一次请求一条，同一个人连点两次要留两条
--   （第二次失败也是证据）⇒ idem_key 必须由 request_id 派生。
--   见下方 `idem_key` 列注释里的红线说明 —— 写 Java 的人一定会踩这里。
CREATE TABLE IF NOT EXISTS `admin_audit_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `operator_type` VARCHAR(16)  NOT NULL                             COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id`   BIGINT       DEFAULT NULL                         COMMENT '操作者ID',
  `operator_name` VARCHAR(64)  DEFAULT NULL                         COMMENT '操作者名快照',
  `action`        VARCHAR(48)  NOT NULL                             COMMENT '动作码：改价/删单/拆单/合并/发货/退货审核…',
  `target_type`   VARCHAR(24)  NOT NULL                             COMMENT '目标类型（多态）：ORDER/PACKAGE/RETURN/SKU/MEMBER/APIKEY',
  `target_id`     BIGINT       DEFAULT NULL                         COMMENT '目标ID（可空：目标可能已被物理删除）',
  `target_sn`     VARCHAR(64)  DEFAULT NULL                         COMMENT '目标单号或名称快照（目标被删后仍可读）',
  `summary`       VARCHAR(255) DEFAULT NULL                         COMMENT '一句人话摘要，列表页直接展示',
  `reason`        VARCHAR(32)  DEFAULT NULL                         COMMENT '管理员填写的原因，敏感动作必填',
  `order_id`      BIGINT       DEFAULT NULL                         COMMENT '落在哪张订单上（冗余，便于按订单检索）',
  `before_json`   JSON         DEFAULT NULL                         COMMENT '变更前快照',
  `after_json`    JSON         DEFAULT NULL                         COMMENT '变更后快照',
  `request_id`    VARCHAR(64)  DEFAULT NULL                         COMMENT '请求追踪号，一次请求一条',
  `idem_key`      VARCHAR(96)  NOT NULL                             COMMENT '⚠️红线：必须由 request_id 派生（如 request_id 或 request_id:seq），严禁由业务键派生。业务键派生会让"连点两次"的第二次撞 uk_idem → INSERT 失败 → 事务回滚 → 本该留下的失败证据被抹掉，与"一次请求一条、连点留两条"的审计语义直接冲突',
  `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_operator` (`operator_id`, `created_at`),
  KEY `idx_target`   (`target_type`, `target_id`),
  KEY `idx_order`    (`order_id`),
  CONSTRAINT `ck_admin_audit_log_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='后台操作审计日志（只追加，不修改不软删）';

-- ---------- 5. WMS 下发/回传（REQ/ACK 两阶段，write-ahead）----------
--   一次外发最多两行：先写 REQ 行（write-ahead，回执列留空），拿到回执后追加 ACK 行。
--   ⚠️ 两行都在同一张表 ⇒ idem_key 必须含 direction。
--   **实现口径（以此为准，2026-11-13 对齐）**：`{deliveryId}:{direction}:{attemptNo}`，
--     例 `3364:REQ:1` —— 见 `WmsExchangeLedgerWriter#key`。
--     （曾写作 `{deliveryId}:WMS:{attemptNo}:{REQ|ACK}`，那是过期描述，实现里从来没有 `WMS` 段。）
--   不设 updated_at：ACK 行是新的一行，不是对 REQ 行的修改。
--   request_payload/response_payload 允许 NULL：REQ 行落库时回执还不存在。
CREATE TABLE IF NOT EXISTS `wms_exchange_ledger` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT           COMMENT '主键',
  `delivery_id`      BIGINT      NOT NULL                          COMMENT '包裹ID（无外键：证据不随包裹级联删除）',
  `direction`        VARCHAR(8)  NOT NULL                          COMMENT 'REQ=下发 / ACK=回传',
  `attempt_no`       INT         NOT NULL DEFAULT 1                COMMENT '第几次尝试（重试用，从 1 开始）',
  `request_payload`  JSON        DEFAULT NULL                      COMMENT '下发报文',
  `response_payload` JSON        DEFAULT NULL                      COMMENT '回传报文（REQ 行落库时为 NULL）',
  `result`           VARCHAR(16) DEFAULT NULL                      COMMENT 'SUCCESS / FAIL / TIMEOUT（REQ 行未定论时为 NULL）',
  `latency_ms`       INT         DEFAULT NULL                      COMMENT '往返耗时（毫秒）',
  `operator_type`    VARCHAR(16) NOT NULL                          COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id`      BIGINT      DEFAULT NULL                      COMMENT '操作者ID（系统重试可空）',
  `operator_name`    VARCHAR(64) DEFAULT NULL                      COMMENT '操作者名快照（密钥名 / 登录名）',
  `idem_key`         VARCHAR(96) NOT NULL                          COMMENT '幂等键 {deliveryId}:{direction}:{attemptNo}（如 3364:REQ:1）',
  `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_delivery` (`delivery_id`, `created_at`),
  CONSTRAINT `ck_wms_exchange_ledger_direction` CHECK (`direction` IN ('REQ','ACK')),
  CONSTRAINT `ck_wms_exchange_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WMS 下发/回传台账（REQ/ACK 两阶段 write-ahead）';


-- ############################################################################
-- 二、幂等 DDL 助手（跑完即 DROP，不留在库里）
-- ############################################################################

DROP PROCEDURE IF EXISTS zz_ledger_add_col;
DROP PROCEDURE IF EXISTS zz_ledger_add_idx;
DROP PROCEDURE IF EXISTS zz_ledger_add_ck;

DELIMITER $$

-- 加列：COLUMNS 里没有才加
CREATE PROCEDURE zz_ledger_add_col(IN p_tbl VARCHAR(64), IN p_col VARCHAR(64), IN p_def TEXT)
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

-- 加索引：STATISTICS 里没有这个索引名才加
CREATE PROCEDURE zz_ledger_add_idx(IN p_tbl VARCHAR(64), IN p_idx VARCHAR(64), IN p_cols TEXT)
BEGIN
  IF (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl AND INDEX_NAME = p_idx) = 0 THEN
    SET @s = CONCAT('ALTER TABLE `', p_tbl, '` ADD ', p_cols);
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    SELECT CONCAT('[ADD INDEX] ', p_tbl, '.', p_idx) AS ddl_step;
  ELSE
    SELECT CONCAT('[SKIP INDEX EXISTS] ', p_tbl, '.', p_idx) AS ddl_step;
  END IF;
END$$

-- 加 CHECK：TABLE_CONSTRAINTS 里没有才加
CREATE PROCEDURE zz_ledger_add_ck(IN p_tbl VARCHAR(64), IN p_ck VARCHAR(64), IN p_expr TEXT)
BEGIN
  IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
       WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl
         AND CONSTRAINT_NAME = p_ck AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
    SET @s = CONCAT('ALTER TABLE `', p_tbl, '` ADD CONSTRAINT `', p_ck, '` CHECK (', p_expr, ')');
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    SELECT CONCAT('[ADD CHECK] ', p_tbl, '.', p_ck) AS ddl_step;
  ELSE
    SELECT CONCAT('[SKIP CHECK EXISTS] ', p_tbl, '.', p_ck) AS ddl_step;
  END IF;
END$$

DELIMITER ;


-- ############################################################################
-- 三、现有表加固
-- ############################################################################

-- ---------- 3.1 order：补下单时间 + 状态流转序号 ----------
-- created_at 用 DATETIME（非 DATETIME(3)）：与同库其它业务表口径一致；
-- 台账表才是 DATETIME(3)。可空：历史行允许不知道下单时间。
CALL zz_ledger_add_col('order', 'created_at', 'DATETIME DEFAULT NULL COMMENT ''下单时间（历史行可能为 NULL）'' AFTER `payment_time`');
CALL zz_ledger_add_col('order', 'status_seq', 'INT NOT NULL DEFAULT 0 COMMENT ''状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源''');

-- ---------- 3.2 delivery_order：状态流转序号 ----------
CALL zz_ledger_add_col('delivery_order', 'status_seq', 'INT NOT NULL DEFAULT 0 COMMENT ''状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源''');

-- ---------- 3.3 return_apply：状态流转序号 + 并发唯一约束 ----------
CALL zz_ledger_add_col('return_apply', 'status_seq', 'INT NOT NULL DEFAULT 0 COMMENT ''状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源''');

-- 并发漏洞的**预留列**（本轮只建列、不建唯一键）：
--   MySQL 没有部分索引（WHERE 子句索引），虚拟生成列 + UNIQUE 是标准等价物：
--     status=0 的行 pending_order_id = order_id（互相冲突）；
--     status≠0 的行 pending_order_id = NULL（UNIQUE 允许多个 NULL，不互斥）。
--   ⚠️ 本轮**刻意不建** uk_pending_order —— 理由见头部【已知问题·待办】：
--      它是新业务规则（admin 端第二条待处理申请会从成功变 500），
--      与「状态写入收敛、行为不变」是双变量，留到单独一轮「唯一键 + admin 守卫文案 + 改 ㉛-C」一起做。
CALL zz_ledger_add_col('return_apply', 'pending_order_id',
  'BIGINT GENERATED ALWAYS AS (IF(`status` = 0, `order_id`, NULL)) VIRTUAL COMMENT ''待处理请求的订单ID：仅 status=0 时非空（预留列，本轮不建唯一键，见头部【已知问题·待办】）''');

-- ---------- 3.4 payment_record：原地加固（不新建表）----------
CALL zz_ledger_add_col('payment_record', 'order_id',      'BIGINT DEFAULT NULL COMMENT ''关联订单ID（回填自 order_sn；order 被物理删后可能悬空）'' AFTER `order_sn`');
CALL zz_ledger_add_col('payment_record', 'ref_type',      'VARCHAR(24) DEFAULT NULL COMMENT ''触发单据类型''');
CALL zz_ledger_add_col('payment_record', 'ref_id',        'BIGINT DEFAULT NULL COMMENT ''触发单据ID''');
CALL zz_ledger_add_col('payment_record', 'origin_flow_id','BIGINT DEFAULT NULL COMMENT ''退款指向的原支付流水ID（type=2 时必填）''');
CALL zz_ledger_add_col('payment_record', 'operator_type', 'VARCHAR(16) DEFAULT NULL COMMENT ''ADMIN / MEMBER / APIKEY / SYSTEM''');
CALL zz_ledger_add_col('payment_record', 'operator_id',   'BIGINT DEFAULT NULL COMMENT ''操作者ID''');
CALL zz_ledger_add_col('payment_record', 'operator_name', 'VARCHAR(64) DEFAULT NULL COMMENT ''操作者名快照''');
CALL zz_ledger_add_col('payment_record', 'idem_key',      'VARCHAR(96) DEFAULT NULL COMMENT ''幂等键（历史行为 NULL，新写入必填）''');


-- ---------- 3.5 delivery_status_ledger：补已登记冗余列 order_id ----------
-- 为什么必须放在这里（而不是只改上面的 CREATE TABLE）：
--   上面是 CREATE TABLE IF NOT EXISTS —— 已经建过这张表的库（qiqi_mall / qiqi_mall_test / e2e_*）
--   会**整表跳过**，新列永远加不上去。所以「已登记的冗余列」在 CREATE 之外还要走一次幂等 ADD COLUMN。
-- 位置用 AFTER delivery_sn：与上面的 CREATE TABLE 列序一致，避免「新库/老库 SHOW CREATE TABLE
--   列序不同」这种只在 diff 时才暴露的漂移。
CALL zz_ledger_add_col('delivery_status_ledger', 'order_id',
  'BIGINT DEFAULT NULL COMMENT ''【已登记冗余列】冗余订单ID：包裹行被物理删除（OrderPurgeService）后，仍能从订单反查包裹状态流水'' AFTER `delivery_sn`');
CALL zz_ledger_add_idx('delivery_status_ledger', 'idx_order',
  'KEY `idx_order` (`order_id`, `created_at`)');
-- 表注释与 CREATE TABLE 对齐（ALTER ... COMMENT 本身幂等，重跑只是重设同一个值）
ALTER TABLE `delivery_status_ledger`
  COMMENT='包裹状态台账（只追加证据链；带单号快照 + 冗余订单ID）';

-- ---------- 3.6 wms_exchange_ledger.idem_key：注释与实现对齐 ----------
-- 实现生成 `{deliveryId}:{direction}:{attemptNo}`（如 `3364:REQ:1`，见 WmsExchangeLedgerWriter#key）；
-- DDL 旧注释写的 `{deliveryId}:WMS:{attemptNo}:{REQ|ACK}` 是过期描述（实现里从来没有 `WMS` 段）。
-- 只改注释、不动类型：先查 information_schema 里的当前注释，不一致才 MODIFY，重跑打 [SKIP]。
SET @old_wms_ck := (SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'wms_exchange_ledger' AND COLUMN_NAME = 'idem_key');
SET @new_wms_ck := '幂等键 {deliveryId}:{direction}:{attemptNo}（如 3364:REQ:1）';
SET @s := IF(@old_wms_ck IS NULL OR @old_wms_ck = @new_wms_ck,
  'SELECT ''[SKIP] wms_exchange_ledger.idem_key 注释已对齐'' AS ddl_step',
  'ALTER TABLE `wms_exchange_ledger` MODIFY COLUMN `idem_key` VARCHAR(96) NOT NULL COMMENT ''幂等键 {deliveryId}:{direction}:{attemptNo}（如 3364:REQ:1）''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;


-- ############################################################################
-- 四、回填（全部带 IS NULL 守卫，重跑 0 行）
-- ############################################################################

-- ---------- 4.1 payment_record.order_id ← order_sn 关联 ----------
-- order_sn 是全局唯一对账键（uk_order_sn），14 行流水应 14/14 命中。
UPDATE `payment_record` p
  JOIN `order` o ON o.order_sn = p.order_sn
   SET p.order_id = o.id
 WHERE p.order_id IS NULL;

SELECT CONCAT('[BACKFILL] payment_record.order_id：本次回填 ', ROW_COUNT(), ' 行') AS ddl_step;

-- ---------- 4.2 order.created_at ← 从 22 位订单号解析 ----------
-- 真实订单号格式（qiqi_mall 实测）：`QQ` + yyyyMMddHHmmss(14 位) + 6 位随机 = 22 位
--   例 QQ20261111152351943571 → 2026-11-11 15:23:51
-- 种子/测试短号（13 位 `QQ20261109001`、14 位 `QQ202611111001`）**解析不出时分秒** ⇒
--   留 NULL，不猜。
--
-- ⚠️ 刻意**不用** STR_TO_DATE：它遇到「格式对但日子不存在」的串（如 2 月 30 日）会抛
--    ERROR 1411 而不是返回 NULL，strict 模式下会把整个迁移中断（实测踩到）。
--   这里用纯算术 MAKEDATE + INTERVAL 构造，任何输入都不报错；
--   日期合法性用 LAST_DAY 判真实当月天数（2 月 30 日、4 月 31 日都拦得住）。
--   AND 的求值顺序在 SQL 里**没有保证**，所以每个条件是独立的、不依赖前一个条件兜底。
UPDATE `order`
   SET created_at = MAKEDATE(CAST(SUBSTRING(order_sn, 3, 4) AS UNSIGNED), 1)
                  + INTERVAL (CAST(SUBSTRING(order_sn, 7, 2) AS UNSIGNED) - 1) MONTH
                  + INTERVAL (CAST(SUBSTRING(order_sn, 9, 2) AS UNSIGNED) - 1) DAY
                  + INTERVAL  CAST(SUBSTRING(order_sn, 11, 2) AS UNSIGNED) HOUR
                  + INTERVAL  CAST(SUBSTRING(order_sn, 13, 2) AS UNSIGNED) MINUTE
                  + INTERVAL  CAST(SUBSTRING(order_sn, 15, 2) AS UNSIGNED) SECOND
 WHERE created_at IS NULL
   AND CHAR_LENGTH(order_sn) = 22
   AND LEFT(order_sn, 2) = 'QQ'
   AND SUBSTRING(order_sn, 3, 20) REGEXP '^[0-9]{20}$'
   AND CAST(SUBSTRING(order_sn, 3, 4) AS UNSIGNED) BETWEEN 1970 AND 2100
   AND CAST(SUBSTRING(order_sn, 7, 2) AS UNSIGNED) BETWEEN 1 AND 12
   AND CAST(SUBSTRING(order_sn, 9, 2) AS UNSIGNED) BETWEEN 1 AND
       DAY(LAST_DAY(MAKEDATE(CAST(SUBSTRING(order_sn, 3, 4) AS UNSIGNED), 1)
             + INTERVAL (CAST(SUBSTRING(order_sn, 7, 2) AS UNSIGNED) - 1) MONTH))
   AND CAST(SUBSTRING(order_sn, 11, 2) AS UNSIGNED) <= 23
   AND CAST(SUBSTRING(order_sn, 13, 2) AS UNSIGNED) <= 59
   AND CAST(SUBSTRING(order_sn, 15, 2) AS UNSIGNED) <= 59;

SELECT CONCAT('[BACKFILL] order.created_at：本次回填 ', ROW_COUNT(), ' 行（其余为短号，按设计留 NULL 不猜）') AS ddl_step;

-- ---------- 4.3 delivery_status_ledger.order_id ← delivery_id → delivery_order.order_id ----------
-- 这是本轮新加的**已登记冗余列**（与 return_status_ledger.order_id 对称）的历史数据回填。
-- 能回多少回多少：包裹行还在 → 桥还在 → 能回填；
-- **包裹已被 OrderPurgeService 物理删除的历史行回填不到**，保持 NULL —— 那时 `delivery_order`
-- 行本来就没了，本来也查不到，不是回填写漏（那种行只能用 delivery_sn 在台账里追）。
-- 🔑 回填之后，即使以后再删包裹行，新写入的行已经自带 order_id，桥断掉也能按订单反查。
UPDATE `delivery_status_ledger` l
  JOIN `delivery_order` d ON d.id = l.delivery_id
   SET l.order_id = d.order_id
 WHERE l.order_id IS NULL;

SELECT CONCAT('[BACKFILL] delivery_status_ledger.order_id：本次回填 ', ROW_COUNT(), ' 行') AS ddl_step;

-- 回填后仍为 NULL 的行数（= 包裹已被物理删除的历史行，按设计保持 NULL）
SELECT COUNT(*) AS ledger_rows,
       SUM(order_id IS NOT NULL) AS with_order_id,
       SUM(order_id IS NULL)     AS without_order_id_null_by_design,
       CONCAT(SUM(order_id IS NOT NULL), '/', COUNT(*)) AS fill_rate
  FROM `delivery_status_ledger`;


-- ############################################################################
-- 五、唯一键（放在回填之后：先填数据，再上闸门）
-- ############################################################################

-- 幂等键唯一：允许多 NULL（历史 14 行 idem_key 全 NULL 时不互斥）
CALL zz_ledger_add_idx('payment_record', 'uk_idem',
  'UNIQUE KEY `uk_idem` (`idem_key`)');

-- 渠道流水号唯一：同一渠道同一笔第三方流水只能入账一次。
-- trade_no 可空 ⇒ 多个 NULL 不互斥（历史"模拟支付"若有空号不会被误杀）。
CALL zz_ledger_add_idx('payment_record', 'uk_channel_trade',
  'UNIQUE KEY `uk_channel_trade` (`channel`, `trade_no`)');


-- ############################################################################
-- 六、收尾：DROP 助手 + 打印结果
-- ############################################################################

DROP PROCEDURE IF EXISTS zz_ledger_add_col;
DROP PROCEDURE IF EXISTS zz_ledger_add_idx;
DROP PROCEDURE IF EXISTS zz_ledger_add_ck;

SELECT 'migrate-ledger 执行完毕' AS result,
       (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE') AS table_cnt,
       (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME IN ('order_status_ledger','delivery_status_ledger',
                              'return_status_ledger','admin_audit_log','wms_exchange_ledger')) AS new_ledger_tables;
