-- ============================================================================
-- 迁移：多平台接入模块（3 张新表，只做接入闭环）
-- ============================================================================
--
-- ── 交付范围（本轮**只**这三张表，不扩范围）──────────────────────────────────
--   channel_shop        渠道店铺（三个：MALL / TAOBAO_MOCK / DOUYIN_MOCK）
--   channel_order_map   平台单号 → 内部订单 id 的映射，uk_map 就是**幂等闸门**
--   channel_sync_log    同步日志（PULL / SHIP_PUSH 各一次一行，只追加）
--
--   不做：库存同步、退款同步、对账、限流调度、策略化（都在范围外）。
--
-- ── 铁律（本文件必须遵守）────────────────────────────────────────────────────
--   ① 台账/日志表**一律无外键**：channel_order_map / channel_sync_log 都不建 FK，
--      shop_id / order_id 只是业务引用（跑批清理、跨模块迁移时不会被约束卡死）。
--   ② 幂等键**不许业务键派生**：channel_order_map 的唯一键是
--      (shop_id, platform_sn) —— 这是集成层天然的幂等闸门，由平台给出的事实拼成；
--      重复拉单靠 `INSERT ... ON DUPLICATE KEY UPDATE id = id` 或捕获 1062，
--      **不许用 INSERT IGNORE**（它会把「字段超长/CHECK 违规」等真错误一起吞掉）。
--   ③ 时间列：业务表 DATETIME；日志表 DATETIME(3)（与既有台账一致，排查时能看毫秒）。
--   ④ 显式 COLLATE=utf8mb4_0900_ai_ci（跟真实库走，见 migrate-ledger.sql 的说明）。
--
-- ── 幂等 ────────────────────────────────────────────────────────────────────
--   可重复执行：CREATE TABLE IF NOT EXISTS + 店铺种子 `ON DUPLICATE KEY UPDATE id = id`。
--   第二遍执行不报错、不新增行、不改数据。本文件不含 DROP / DELETE / TRUNCATE。
--
-- ── 执行方式 ────────────────────────────────────────────────────────────────
--   mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/migrate-channel.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则列注释里的中文会二次编码成乱码。
--   目标库由当前连接决定（不写 USE），因此可以在任意库（dev / e2e 分片 / scratch）上验证。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 渠道店铺：一个平台可以有多个店铺；本轮每个平台一条
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `channel_shop` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform_type` ENUM('MALL','TAOBAO_MOCK','DOUYIN_MOCK') NOT NULL COMMENT '平台类型：MALL=商城自营开放接口；后两个=本地 mock 平台',
  `shop_code` VARCHAR(64) NOT NULL COMMENT '平台侧店铺编码（调接口时带上）',
  `shop_name` VARCHAR(64) NOT NULL COMMENT '店铺名（后台卡片标题）',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用（停用后不允许拉单/回传）',
  `watermark_at` DATETIME DEFAULT NULL COMMENT '拉单水位：上次成功拉单的**开始时刻**，下次 since 传它',
  `last_pull_at` DATETIME DEFAULT NULL COMMENT '上次拉单结束时刻（含失败），卡片上展示',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop` (`platform_type`, `shop_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道店铺';

-- ----------------------------------------------------------------------------
-- 2. 平台单号映射：**幂等闸门**
--
--    重复拉单的判定只认这一张表：uk_map(shop_id, platform_sn) 命中 = 这批单已经进过库。
--    为什么不用「查订单表里有没有同号」：内部订单号是本地生成的（OrderSnGenerator），
--    与平台单号根本不同源；把业务键拼起来当幂等键会在「平台改号 / 大小写差异」时失守。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `channel_order_map` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` BIGINT NOT NULL COMMENT 'channel_shop.id（无外键，见文件头铁律①）',
  `platform_sn` VARCHAR(64) NOT NULL COMMENT '平台单号（MALL 直接用商城 order_sn）',
  `order_id` BIGINT NOT NULL COMMENT '内部订单 order.id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_map` (`shop_id`, `platform_sn`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台单号→内部订单映射（幂等闸门）';

-- ----------------------------------------------------------------------------
-- 3. 同步日志：只追加，一条外呼一行（拉单失败/SKIP 也各留一行）
--
--    attempt：同一次业务动作的第几次尝试。回传发货失败会重试一次，
--             于是「一次失败 + 一次成功」在库里是 attempt=1 FAIL、attempt=2 OK 两行 ——
--             重试次数由数据自己说明，不用再单独存一列。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `channel_sync_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` BIGINT NOT NULL COMMENT 'channel_shop.id（无外键，见文件头铁律①）',
  `action` ENUM('PULL','SHIP_PUSH') NOT NULL COMMENT '动作：PULL=拉单 SHIP_PUSH=回传发货',
  `ref_type` VARCHAR(32) DEFAULT NULL COMMENT '关联对象类型：ORDER / PLATFORM_ORDER / DELIVERY',
  `ref_id` BIGINT DEFAULT NULL COMMENT '关联对象 id（内部订单 id / 发货单 id；平台单号见 request_payload）',
  `request_payload` TEXT COMMENT '请求报文（截断，够排查即可）',
  `response_payload` TEXT COMMENT '响应报文（截断）',
  `result` ENUM('OK','FAIL','SKIP') NOT NULL COMMENT 'OK=成功 FAIL=失败 SKIP=幂等跳过',
  `reason` VARCHAR(255) DEFAULT NULL COMMENT '结果原因：IDEMPOTENT / SKU_NOT_MATCHED / 异常信息…',
  `attempt` INT NOT NULL DEFAULT 1 COMMENT '第几次尝试（回传重试时第 2 次记 attempt=2）',
  `latency_ms` INT DEFAULT NULL COMMENT '外呼耗时（毫秒）',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_shop_time` (`shop_id`, `created_at`),
  KEY `idx_action_result` (`action`, `result`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道同步日志（只追加）';

-- ----------------------------------------------------------------------------
-- 4. 三个店铺种子（幂等：已存在则原地不动，不覆盖人工改过的启用/水位）
-- ----------------------------------------------------------------------------
INSERT INTO `channel_shop` (`platform_type`, `shop_code`, `shop_name`) VALUES
  ('MALL', 'MALL-001', '七七商城自营'),
  ('TAOBAO_MOCK', 'TB-001', '淘宝旗舰店（模拟）'),
  ('DOUYIN_MOCK', 'DY-001', '抖音小店（模拟）')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ----------------------------------------------------------------------------
-- 5. 自证（跑完可直接看：3 张表都在，唯一键与索引按设计落库）
-- ----------------------------------------------------------------------------
SELECT table_name, table_rows
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('channel_shop', 'channel_order_map', 'channel_sync_log')
 ORDER BY table_name;
