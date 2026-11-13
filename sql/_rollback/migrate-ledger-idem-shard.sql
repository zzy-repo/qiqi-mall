-- ============================================================================
-- 迁移：给 stock_ledger.idem_key 加「打散前缀」（幂等、分批、可重复跑）
--
-- ── 为什么 ──────────────────────────────────────────────────────────────────
-- uk_idem 是唯一二级索引。InnoDB 往唯一二级索引插入时，要在**该 key 所在的 gap** 上取共享锁
-- 做重复性检查；并发插入同一个 gap 需要插入意向锁 → 两者互斥。
-- idem_key 的格式是 `orderId:skuId:reason:refId`，orderId 单调自增 ⇒ **所有插入都落在 uk_idem
-- 最右端同一个 gap** ⇒ 全局单点排队。
-- 压测实测：行锁等待次数与并发无关地恒定在 ~2.8 万/分钟，单次等待 c8 19ms → c32 125ms 线性增长，
-- 吞吐被钉在 ~300 TPS（而单连接逐笔持久化的物理上限是 1,173 TPS）。
--
-- 修法：把 orderId 的信息挪到字符串最前面当「打散前缀」
--     idem_key = LPAD(orderId % N, 位宽, '0') + ':' + 原键
--     例（N=64）：`07:100023:5:ORDER_CREATE:0`
-- N 个前缀 = N 个互不相干的 gap ⇒ 互斥面除以 N。
--
-- ── 唯一性语义不变 ──────────────────────────────────────────────────────────
-- 前缀是 orderId 的**纯函数**（不是随机、不是哈希），等于把同一份信息换个位置；
-- 整个字符串仍然全局唯一，uk_idem 仍然是硬闸门。N=1 时**不写前缀**，与历史数据逐字节一致。
--
-- ── ⚠️ 执行顺序（必须严格照做，不可并发）──────────────────────────────────
--   ① 停流量 / 停压测
--   ② 跑本文件（把历史行补上前缀）
--   ③ 起新镜像（qiqi.stock.idem-key-shards=N）
-- 反过来的后果：旧触发（同一 order+sku+reason+ref）重放时，应用算出的是「带前缀」的新键，
-- 而库里那行还是「无前缀」的旧键 → 查不到 → 被当成首次触发 → **重复扣减 / 重复回补**。
--
-- ── 幂等 ────────────────────────────────────────────────────────────────────
-- 只处理「还没有前缀」的行，判据 = 冒号个数：
--   旧格式 4 段 = 3 个冒号；新格式 5 段 = 4 个冒号。
-- 分批（每批 5000 行）循环到 0 行为止 —— 重跑第二遍直接 0 行，不报错、不改数据。
--
-- ── 回滚 ────────────────────────────────────────────────────────────────────
-- 把应用配置改回 idem-key-shards=1（行为逐字节回到今天），然后（仅需要时）执行文件末尾的
-- 「回滚 SQL」把前缀剥掉 —— 同样必须先停流量。
--
-- 说明：本文件用了 mysql 客户端的 DELIMITER 指令，必须用 mysql 客户端执行。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS qiqi_mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE qiqi_mall;

DROP PROCEDURE IF EXISTS qiqi_prefix_idem_keys;

DELIMITER $$

CREATE PROCEDURE qiqi_prefix_idem_keys(IN db_name VARCHAR(64), IN shards INT, IN batch INT)
BEGIN
  DECLARE db_exists INT DEFAULT 0;
  DECLARE tbl_exists INT DEFAULT 0;
  DECLARE width INT DEFAULT 1;
  DECLARE affected INT DEFAULT 0;
  DECLARE total INT DEFAULT 0;

  IF shards < 2 THEN
    SELECT '参数 shards < 2：无需回填（N=1 时本来就不加前缀）' AS skip_reason;
  ELSE
    SELECT COUNT(*) INTO db_exists FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = db_name;
    SELECT COUNT(*) INTO tbl_exists FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = db_name AND TABLE_NAME = 'stock_ledger';

    IF db_exists > 0 AND tbl_exists > 0 THEN
      SET width = CHAR_LENGTH(CAST(shards - 1 AS CHAR));   -- N=64 → 2 位（"00".."63"）

      loop_backfill: LOOP
        SET @ddl = CONCAT(
          'UPDATE `', db_name, '`.`stock_ledger` ',
          'SET idem_key = CONCAT(LPAD(order_id % ', shards, ', ', width, ', ''0''), '':'', idem_key) ',
          -- 只挑「还没有前缀」的行：旧格式 4 段 ⇒ 3 个冒号
          'WHERE CHAR_LENGTH(idem_key) - CHAR_LENGTH(REPLACE(idem_key, '':'', '''')) = 3 ',
          'LIMIT ', batch);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        SET affected = ROW_COUNT();
        DEALLOCATE PREPARE stmt;

        IF affected = 0 THEN
          LEAVE loop_backfill;
        END IF;
        SET total = total + affected;
      END LOOP;

      SELECT CONCAT('库 ', db_name, '：本次共补前缀 ', total, ' 行（N=', shards, '，每批 ', batch, '）') AS result;
    END IF;
  END IF;
END$$

DELIMITER ;

-- ---------- 逐库执行（N=64 与 application-*.yml 的 idem-key-shards 必须一致）----------
SET @shards := 64;
SET @batch  := 5000;

CALL qiqi_prefix_idem_keys('qiqi_mall',        @shards, @batch);
CALL qiqi_prefix_idem_keys('qiqi_mall_test',   @shards, @batch);
CALL qiqi_prefix_idem_keys('qiqi_mall_e2e_a',  @shards, @batch);
CALL qiqi_prefix_idem_keys('qiqi_mall_e2e_b',  @shards, @batch);
CALL qiqi_prefix_idem_keys('qiqi_mall_e2e_c',  @shards, @batch);
CALL qiqi_prefix_idem_keys('qiqi_mall_e2e_d',  @shards, @batch);

DROP PROCEDURE qiqi_prefix_idem_keys;

-- ---------- 自检 1：还有没有「没前缀」的漏网行（应全为 0）----------
SELECT '自检1 未补前缀行数（应 0）' AS check_item,
       (SELECT COUNT(*) FROM qiqi_mall.stock_ledger
         WHERE CHAR_LENGTH(idem_key) - CHAR_LENGTH(REPLACE(idem_key, ':', '')) = 3) AS qiqi_mall,
       (SELECT COUNT(*) FROM qiqi_mall_test.stock_ledger
         WHERE CHAR_LENGTH(idem_key) - CHAR_LENGTH(REPLACE(idem_key, ':', '')) = 3) AS qiqi_mall_test;

-- ---------- 自检 2：前缀分布（应散在 0..N-1，而不是集中在少数几个前缀上）----------
--   注意：今晚 stock_ledger 在 6 个库里都是 0 行 → 这条查出来是空集，属正常（回填本来就是 no-op）。
SELECT SUBSTRING_INDEX(idem_key, ':', 1) AS shard_prefix, COUNT(*) AS rows_cnt
  FROM qiqi_mall.stock_ledger
 GROUP BY SUBSTRING_INDEX(idem_key, ':', 1)
 ORDER BY COUNT(*) DESC
 LIMIT 10;

-- ---------- 回滚（**仅需要时**，且必须先停流量；平时不要跑）----------
-- 语义：把前缀剥掉，回到 `orderId:skuId:reason:refId`。
--   判据：新格式 4 个冒号。分批执行：
-- UPDATE stock_ledger
--    SET idem_key = SUBSTRING(idem_key, LOCATE(':', idem_key) + 1)
--  WHERE CHAR_LENGTH(idem_key) - CHAR_LENGTH(REPLACE(idem_key, ':', '')) = 4
--  LIMIT 5000;   -- 反复执行直到 0 行；然后**务必**把 qiqi.stock.idem-key-shards 改回 1 再重启
