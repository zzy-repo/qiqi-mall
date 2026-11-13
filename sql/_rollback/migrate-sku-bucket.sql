-- ============================================================================
-- 迁移：SKU 库存分桶 sku_bucket（幂等 DDL + 幂等回填，可反复重跑）
--
-- 背景（性能缺陷：热点 SKU 的单行串行）：
--   扣减一直是 `UPDATE sku SET stock = stock - N WHERE id = ? AND stock >= N`。
--   这条 SQL 本身是对的（原子、不超卖），但它把「同一个 SKU 的所有并发」压进
--   **一把行锁**：同一时刻只有一个事务能改那一行，其余全部排队 —— 热点 SKU 的 TPS 上限
--   就是「行锁吞吐」，与机器核数无关。
--
-- 修法：逻辑上仍然是「一份库存」，只在物理上拆成 N 行（默认 N=8）：
--   ① 新增 sku_bucket(sku_id, bucket_no, stock)，UNIQUE(sku_id, bucket_no)
--      —— 8 行 = 8 把行锁，并发压到 8 个互不相干的行上；
--   ② 权威裁决在 bucket：扣减对某个桶做 `stock = stock - N AND stock >= N`，
--      0 行就换下一个桶（最多试 N 次，全失败才算库存不足）；
--      回补回到台账里记的那一行的 bucket_no；
--   ③ sku.stock 降级为**展示缓存**，扣减/回补路径不再写它（不再碰那把热行锁），
--      由后台短周期任务按 SUM(bucket.stock) 回写（见 SkuBucketService#refreshSkuStockCache）。
--
-- 关键约束：这是「一份库存 + 一个缓存」，不是两套逻辑 ——
--   只有 sku_bucket 能裁决库存；sku.stock 只是给它算出来的一个展示用的数。
--
-- 与 sql/schema.sql / sql/testdb/01-schema.sql 的关系：
--   那两份是「新建库时的最终状态」（已直接包含 sku_bucket 与 stock_ledger.bucket_no）；
--   本文件是「已有库的补齐」，三处的 DDL 必须保持一致（改动请同时改）。
--
-- 桶数量：本文件的 N 固定写成 8（SQL 里读不到应用配置）。改 N 需要同时改：
--   ① 本文件回填里的 8；② 应用配置 qiqi.stock.bucket-count（dev/test 一致）；
--   ③ 重跑本文件（回填只补「一个桶都没有」的 SKU，已有桶的 SKU 不会被重新拆分）。
--
-- 执行：docker exec -i -e MYSQL_PWD=$MYSQL_ROOT_PASSWORD qiqi-mysql \
--         mysql --default-character-set=utf8mb4 -uroot < sql/migrate-sku-bucket.sql
-- 说明：本文件用了 mysql 客户端的 DELIMITER 指令，必须用 mysql 客户端执行（不能走 JDBC/DataGrip 的单语句模式）。
--
-- 幂等性：
--   · 建表           —— CREATE TABLE IF NOT EXISTS：表已在就跳过；
--   · stock_ledger.bucket_no —— MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--                        先查 information_schema，再动态拼 ALTER；
--   · 回填           —— 只处理「该 SKU 一个桶都没有」的情况（NOT EXISTS 守卫），
--                        已经有桶的 SKU 整行跳过；重复拆分会凭空多出库存，所以这道守卫是硬要求。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS qiqi_mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE qiqi_mall;

DROP PROCEDURE IF EXISTS qiqi_setup_sku_bucket;

DELIMITER $$

-- ---------------------------------------------------------------------------
-- 一个库的整套补齐：建表 → 加 stock_ledger.bucket_no → 回填。
-- 库不存在（例如某台机器没有 e2e 分片库）时整个库静默跳过。
-- ---------------------------------------------------------------------------
CREATE PROCEDURE qiqi_setup_sku_bucket(IN db_name VARCHAR(64))
BEGIN
  DECLARE db_exists INT DEFAULT 0;
  DECLARE col_exists INT DEFAULT 0;

  SELECT COUNT(*) INTO db_exists FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = db_name;

  IF db_exists > 0 THEN
    -- ① 分桶表（CHECK 保证单桶不为负 ⇒ 任何时刻 SUM(stock) >= 0，「SUM < 0」在库层面就不可写）
    SET @ddl = CONCAT('CREATE TABLE IF NOT EXISTS `', db_name, '`.`sku_bucket` (',
      '  `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT ''主键'',',
      '  `sku_id`     BIGINT   NOT NULL COMMENT ''所属规格'',',
      '  `bucket_no`  INT      NOT NULL COMMENT ''桶号 0..N-1（N = qiqi.stock.bucket-count，默认 8）'',',
      '  `stock`      INT      NOT NULL DEFAULT 0 COMMENT ''本桶可用库存（权威值；sku.stock 只是它的缓存）'',',
      '  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''最近变动时间'',',
      '  PRIMARY KEY (`id`),',
      '  UNIQUE KEY `uk_sku_bucket` (`sku_id`, `bucket_no`),',
      '  KEY `idx_sku` (`sku_id`),',
      '  CONSTRAINT `ck_bucket_stock` CHECK (`stock` >= 0),',
      '  CONSTRAINT `fk_bucket_sku` FOREIGN KEY (`sku_id`) REFERENCES `', db_name, '`.`sku` (`id`) ON DELETE CASCADE',
      ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
      ' COMMENT=''SKU 库存分桶（一份库存物理拆成 N 行，权威裁决处；sku.stock 是它的展示缓存）''');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    -- ② 台账加 bucket_no（可空）：只用于「回补回到原桶」的定位，不参与守恒计算。
    --    历史台账行为 NULL = 那次扣减发生在分桶之前，回补时按 sku 任选一个桶。
    SELECT COUNT(*) INTO col_exists
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = db_name AND TABLE_NAME = 'stock_ledger' AND COLUMN_NAME = 'bucket_no';
    IF col_exists = 0 THEN
      SET @ddl = CONCAT('ALTER TABLE `', db_name, '`.`stock_ledger` ',
        'ADD COLUMN `bucket_no` INT NULL COMMENT ''扣减/回补落点桶号（可空：历史台账）；只用于回补定位，不参与守恒计算''');
      PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;

    -- ③ 幂等回填：把现有 sku.stock 平均拆到 8 个桶（余数进 0 号桶），只补「一个桶都没有」的 SKU。
    --    例：stock=1000 → 每桶 125；stock=10 → 0 号桶 3、1..7 号桶各 1。
    --    拆完桶内总量严格等于原 sku.stock（守恒），sku.stock 本身不动 —— 它这时刚好是正确缓存。
    SET @ddl = CONCAT('
      INSERT INTO `', db_name, '`.`sku_bucket` (`sku_id`, `bucket_no`, `stock`)
      SELECT s.`id`, seq.n,
             FLOOR(s.`stock` / 8) + IF(seq.n = 0, s.`stock` - FLOOR(s.`stock` / 8) * 8, 0)
        FROM `', db_name, '`.`sku` s
        CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7) seq
       WHERE NOT EXISTS (SELECT 1 FROM `', db_name, '`.`sku_bucket` b WHERE b.`sku_id` = s.`id`)');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

-- ---------- dev 库 qiqi_mall（本地 mvn test 也连这个库）----------
CALL qiqi_setup_sku_bucket('qiqi_mall');

-- ---------- 容器栈测试库 qiqi_mall_test（18080）----------
-- 注意：qiqi_mall_test 是压测基线库 —— 本迁移只「加表 + 加列 + 回填」，不删不改任何既有业务数据。
CALL qiqi_setup_sku_bucket('qiqi_mall_test');

-- ---------- e2e 分片库（Playwright 每个 worker 一套；库不存在时静默跳过）----------
CALL qiqi_setup_sku_bucket('qiqi_mall_e2e_a');
CALL qiqi_setup_sku_bucket('qiqi_mall_e2e_b');
CALL qiqi_setup_sku_bucket('qiqi_mall_e2e_c');
CALL qiqi_setup_sku_bucket('qiqi_mall_e2e_d');

DROP PROCEDURE qiqi_setup_sku_bucket;

-- ---------- 自检 ----------
-- bucket_rows 应全为 8（对应 N=8）；bucket_sum 与 sku_stock 应相等（缓存尚未被任务改写过时也相等）；
-- min_bucket_stock 必须 >= 0。
SELECT COUNT(*)                     AS sku_total,
       SUM(t.bucket_rows = 8)       AS sku_with_8_buckets,
       SUM(t.bucket_rows <> 8)      AS sku_without_8_buckets,
       SUM(t.bucket_sum <> t.sku_stock) AS cache_off_by_sum,
       MIN(t.bucket_min)            AS min_bucket_stock
  FROM (SELECT s.id,
               (SELECT COUNT(*) FROM qiqi_mall.sku_bucket b WHERE b.sku_id = s.id) AS bucket_rows,
               (SELECT COALESCE(SUM(stock), 0) FROM qiqi_mall.sku_bucket b WHERE b.sku_id = s.id) AS bucket_sum,
               (SELECT COALESCE(MIN(stock), 0) FROM qiqi_mall.sku_bucket b WHERE b.sku_id = s.id) AS bucket_min,
               s.stock AS sku_stock
          FROM qiqi_mall.sku s) t;
