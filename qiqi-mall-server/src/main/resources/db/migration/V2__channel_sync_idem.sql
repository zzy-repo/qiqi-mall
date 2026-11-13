-- ============================================================================
-- V2 · channel_sync_log 补一道幂等闸：生成列 idem_key + 唯一索引 uk_sync_idem
-- ============================================================================
-- 为什么补（对照 wms_exchange_ledger）
-- ----------------------------------
--   台账 wms_exchange_ledger 有幂等键 {deliveryId}:{direction}:{attemptNo} + uk_idem，
--   同一次尝试被重放不会多行。channel_sync_log 只追加、**没有任何唯一键**：回传重试路径
--   被重复调用（人工连点、调度重入、异常重放）就会重复落行，而库层面没有兜底 ——
--   「重推不重单」在接入层只能靠代码自觉，这是叙事上的漏洞。本迁移把它补上。
--
-- 难点：这张表里混了两种行
-- ------------------------
--   · 单据行：action='SHIP_PUSH' / ref_type='DELIVERY' / ref_id=包裹id / attempt=第几次
--     → 天然唯一键 {shopId}:SHIP_PUSH:{refId}:{attempt}；同一次尝试只该有一行。
--   · 汇总行：action='PULL' / ref_type='SHOP'，一次拉单落一行「店铺级汇总（新建x/跳过y/失败z）」
--     → **没有**天然唯一键：同一店铺连拉两次是两件不同的事，两行都要留。
--     单张平台单的 SKIP/FAIL 也是 PULL 行（ref_type='PLATFORM_ORDER'，ref_id 为 NULL），
--     同一张单被平台重推两次同样必须能看到两行。
--
-- 做法（照 order_audit_tag.active_tag 的「生成列 + 唯一索引」手法，不发明新概念）
-- ------------------------------------------------------------------------------
--   idem_key VARCHAR(160) GENERATED ALWAYS AS (
--       IF(action='SHIP_PUSH', CONCAT(shop_id,':SHIP_PUSH:',ref_id,':',attempt), NULL)
--   ) VIRTUAL，配 UNIQUE uk_sync_idem(idem_key)。
--   · 仅 SHIP_PUSH 行非空；PULL 汇总行 / PULL 明细行一律 NULL ——
--     MySQL 唯一索引允许多个 NULL，所以它们**完全不参与**唯一约束。这是有意的，不是漏的。
--   · ref_id 为 NULL 时 CONCAT 整链为 NULL（MySQL 语义），同样不参与约束。
--   · 键定义放在库里而不是写入侧算：任何写入路径（含将来的手工 SQL、别的服务、
--     数据修复脚本）都自动受同一道闸约束，不需要"记得调某个方法"。
--   ⚠️ 因此这道闸只闸「回传发货的单据行」，不闸拉单流水；拉单的幂等仍由
--      channel_order_map 的唯一键 + ChannelOrderImporter 的 DuplicateOrderException 负责。
--
-- 写入侧（配套改动，见 ChannelSyncLogMapper#insertOrTouch / ChannelSyncLogWriter）
-- ---------------------------------------------------------------------------------
--   INSERT ... ON DUPLICATE KEY UPDATE id = id（**不用 INSERT IGNORE**，项目铁律）；
--   影响 0 行 = 这次尝试已记过账 = 当 SKIP 处理（不报错、不覆盖第一行）。
--
-- 本文件幂等：可重复执行
-- ----------------------
--   加列 / 加索引前先查 information_schema，已存在则空转；删除重复只删「多余的」，
--   重跑为 0 行。每一步都打印一行结果，便于人工核对。
--
-- ⚠️ 不要改 V1__baseline.sql：改它会让四个库的 flyway validate 全部 checksum 失败。
-- ============================================================================

-- ---------- 0. 前置守卫：必须已选中目标库（Flyway 会按 url 选库） ----------
SET @db := DATABASE();
SET @s := IF(@db IS NULL,
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''V2: DATABASE() 为空，拒绝执行''',
  'SELECT ''✓ V2 目标库'' AS guard');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 1. 先看：加唯一索引前，库里已有的重复 SHIP_PUSH 行 ----------
-- 没有重复时这条查询返回空结果集（MySQL 客户端会打印 Empty set），属于正常情况。
SELECT `shop_id`, `ref_id`, `attempt`,
       COUNT(*)                             AS dup_rows,
       MIN(`id`)                            AS keep_id,
       GROUP_CONCAT(`id` ORDER BY `id`)     AS all_ids
  FROM `channel_sync_log`
 WHERE `action` = 'SHIP_PUSH' AND `ref_id` IS NOT NULL
 GROUP BY `shop_id`, `ref_id`, `attempt`
HAVING COUNT(*) > 1;

-- ---------- 2. 清理：同一 (shop_id, ref_id, attempt) 只保留最小 id，其余删掉 ----------
-- 用临时表先固定「要删哪些 id」，避免 DELETE 的目标表出现在自己的子查询里（MySQL 1093）。
DROP TEMPORARY TABLE IF EXISTS `zz_sync_log_dup`;
CREATE TEMPORARY TABLE `zz_sync_log_dup` (`id` BIGINT NOT NULL PRIMARY KEY);
INSERT INTO `zz_sync_log_dup` (`id`)
SELECT `id` FROM (
  SELECT `id`, ROW_NUMBER() OVER (
           PARTITION BY `shop_id`, `ref_id`, `attempt` ORDER BY `id`
         ) AS rn
    FROM `channel_sync_log`
   WHERE `action` = 'SHIP_PUSH' AND `ref_id` IS NOT NULL
) t
 WHERE t.rn > 1;

SELECT COUNT(*) AS will_delete_duplicate_rows FROM `zz_sync_log_dup`;

DELETE FROM `channel_sync_log` WHERE `id` IN (SELECT `id` FROM `zz_sync_log_dup`);

SELECT ROW_COUNT() AS deleted_duplicate_rows;

DROP TEMPORARY TABLE `zz_sync_log_dup`;

-- ---------- 3. 加生成列 idem_key（已存在则空转） ----------
-- 位置放在 attempt 后面：紧挨着它的三个来源列（shop_id / ref_id / attempt），读表时一眼能对上。
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'channel_sync_log'
                    AND COLUMN_NAME = 'idem_key');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `channel_sync_log` ADD COLUMN `idem_key` VARCHAR(160)
     GENERATED ALWAYS AS (IF(`action` = ''SHIP_PUSH'',
                             CONCAT(`shop_id`, '':'', ''SHIP_PUSH'', '':'', `ref_id`, '':'', `attempt`),
                             NULL)) VIRTUAL
     COMMENT ''仅 SHIP_PUSH 行非空：{shopId}:SHIP_PUSH:{refId}:{attempt}；PULL 行恒 NULL（不参与唯一约束）''
     AFTER `attempt`',
  'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 4. 加唯一索引 uk_sync_idem（已存在则空转） ----------
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'channel_sync_log'
                    AND INDEX_NAME = 'uk_sync_idem');
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `channel_sync_log` ADD UNIQUE KEY `uk_sync_idem` (`idem_key`)',
  'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 5. 收尾自检：列类型 + 索引 + 现有键值分布（应当只剩 NULL 与 SHIP_PUSH 键） ----------
SELECT `COLUMN_NAME`, `COLUMN_TYPE`, `IS_NULLABLE`, `EXTRA`, `GENERATION_EXPRESSION`
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'channel_sync_log' AND COLUMN_NAME = 'idem_key';

SELECT `INDEX_NAME`, `NON_UNIQUE`, `SEQ_IN_INDEX`, `COLUMN_NAME`
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'channel_sync_log' AND INDEX_NAME = 'uk_sync_idem';

SELECT COUNT(*)                                        AS total_rows,
       SUM(`idem_key` IS NULL)                         AS null_key_rows,
       COUNT(DISTINCT `idem_key`)                      AS distinct_idem_keys
  FROM `channel_sync_log`;
