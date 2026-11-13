-- ============================================================================
-- 把某个（或某一段）SKU 的库存重置为 N 件 —— **写进桶**（纯 SQL，幂等，可反复跑）
--
-- ⚠️ 为什么不能再用 `UPDATE sku SET stock = 500000`：
--   分桶模式（qiqi.stock.bucket-enabled=true）下，库存的**唯一权威**是 sku_bucket，
--   sku.stock 只是展示缓存（由后台任务按 SUM(桶) 每 250ms 回写一次）。
--   只 UPDATE sku.stock 的话：
--     · 桶里还是旧值 → 实际库存一件没变，压测「库存够不够」的前提就是错的；
--     · 而且下一次缓存回写会把你刚写的数字**覆盖回旧值** —— 你会以为设置成功了，其实没有。
--   legacy 模式（bucket-enabled=false）下 `UPDATE sku` 仍然是有效的（那时它就是权威），
--   但**为了不分模式地写同一套压测脚本**，统一用本文件的语句：它会把两边都写对。
--
-- ── 语义 ────────────────────────────────────────────────────────────────────
--   · 目标「总量 = @total」平均拆到 8 个桶（余数进 0 号桶），再把展示缓存写成同一个数；
--   · 幂等：先删该 SKU 的桶再重铺，重跑 N 次结果完全一样（不会累加、不会翻倍）；
--   · 只动库存，不碰 order / stock_ledger / inventory（分仓库存另有 backfill-inventory.sh）。
--
-- ⚠️ 本文件自上而下就是可执行语句：直接整份执行 = 把 sku1 与 sku1..21 都重置成 500000
--    （压测基线的默认场景）。只重置某一个 SKU 时，别整份跑 —— 用 §1 的 `mysql -e` 版本。
--
-- ══════════════════════════════════════════════════════════════════════════
-- 【runbook】reset 数据库之后必做的两件事（压测 / e2e 都要照做）
--
-- ① reset 会把 sku1..21 恢复成种子值（1000，其中 3 个是 6/3/8）→ 目标库存必须**在 reset 之后**
--    再设一次，别在 reset 之前设（会被种子的 TRUNCATE + INSERT 冲掉）。
--       · legacy 模式（qiqi.stock.bucket-enabled=false）：
--           UPDATE sku SET stock = 500000 WHERE id BETWEEN 1 AND 21;    ← 这条是对的，sku.stock 就是权威
--       · 分桶模式（bucket-enabled=true）：
--           用本文件的 §2（写进桶 + 同时对齐 sku.stock）；**只 UPDATE sku.stock 不生效**
--
-- ② reset 走 sql/testdb/01-schema.sql 的 DROP + CREATE（物理重建表）：
--    stock_ledger 被整表重建 ⇒ 台账 0 行、uk_idem 是**空的新 B+ 树**，
--    所以 reset 之后**不需要**再跑 sql/migrate-ledger-idem-shard.sql（回填只针对历史行，跑了也是 0 行）。
--    自检（必须全绿再开跑）：
--       SELECT COUNT(*) FROM qiqi_mall_test.stock_ledger;                        -- 0
--       SELECT COUNT(*) FROM qiqi_mall_test.sku;                                 -- 112
--       SELECT COUNT(*), COUNT(DISTINCT sku_id) FROM qiqi_mall_test.sku_bucket;  -- 896 / 112
--       SELECT COUNT(*) FROM qiqi_mall_test.sku WHERE stock <> (SELECT COALESCE(SUM(stock),0)
--         FROM qiqi_mall_test.sku_bucket b WHERE b.sku_id = sku.id);             -- 0
-- ══════════════════════════════════════════════════════════════════════════
--
-- ── 桶数必须与配置一致 ──────────────────────────────────────────────────────
--   本文件用 8（= qiqi.stock.bucket-count: 8 = sql/migrate-sku-bucket.sql 回填里的 8）。
--   改桶数要三处一起改，否则桶号对不上。
-- ============================================================================

-- ⚠️ 本文件不写库名（无 USE）：库名由调用方传，例如
--      mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/reset-sku-bucket.sql
--    写死库名 = 谁 source 它就重置谁（2026-11-13 两次事故的根因，见 sql/_rollback/README.txt）。

-- ============================================================================
-- §1 单个 SKU（最常用；可直接 `mysql -e` 拼一行执行）
-- ============================================================================
SET @sku_id := 1;         -- ← 目标 SKU
SET @total  := 500000;    -- ← 目标总量（件）

DELETE FROM sku_bucket WHERE sku_id = @sku_id;

INSERT INTO sku_bucket (sku_id, bucket_no, stock)
SELECT @sku_id, seq.n,
       FLOOR(@total / 8) + IF(seq.n = 0, @total - FLOOR(@total / 8) * 8, 0)
  FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
        UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7) seq;

-- 展示缓存也对齐（不写它的话，界面/后台列表会显示旧数字，且下一次回写才会追上）
UPDATE sku SET stock = @total WHERE id = @sku_id;

-- ============================================================================
-- §2 一段 SKU（压测基线场景：sku1..21 都重置成 500000）
-- ============================================================================
SET @total := 500000;
SET @from_id := 1;
SET @to_id := 21;

DELETE FROM sku_bucket WHERE sku_id BETWEEN @from_id AND @to_id;

INSERT INTO sku_bucket (sku_id, bucket_no, stock)
SELECT s.id, seq.n,
       FLOOR(@total / 8) + IF(seq.n = 0, @total - FLOOR(@total / 8) * 8, 0)
  FROM sku s
  CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7) seq
 WHERE s.id BETWEEN @from_id AND @to_id;

UPDATE sku SET stock = @total WHERE id BETWEEN @from_id AND @to_id;

-- ============================================================================
-- §3 校验：桶总量 = 目标值、桶数 = 8、缓存一致、单桶非负
--     （正常结果：bucket_sum 全 = 500000，buckets 全 = 8，cache_diff 全 = 0，min_bucket = 62500）
-- ============================================================================
SELECT s.id                    AS sku_id,
       COUNT(b.id)             AS buckets,
       COALESCE(SUM(b.stock),0) AS bucket_sum,
       s.stock                 AS sku_stock_cache,
       s.stock - COALESCE(SUM(b.stock),0) AS cache_diff,
       COALESCE(MIN(b.stock),0) AS min_bucket,
       COALESCE(MAX(b.stock),0) AS max_bucket
  FROM sku s
  LEFT JOIN sku_bucket b ON b.sku_id = s.id
 WHERE s.id BETWEEN 1 AND 21
 GROUP BY s.id, s.stock
 ORDER BY s.id;

-- ============================================================================
-- §4 用法（把上面的 §1/§2 直接丢给 mysql 客户端；口令不回显）
-- ============================================================================
-- 单 SKU：
--   docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" qiqi-mysql \
--     mysql --default-character-set=utf8mb4 -uroot -e "
--       USE qiqi_mall_test;
--       SET @sku_id := 1, @total := 500000;
--       DELETE FROM sku_bucket WHERE sku_id = @sku_id;
--       INSERT INTO sku_bucket (sku_id, bucket_no, stock)
--       SELECT @sku_id, seq.n, FLOOR(@total/8) + IF(seq.n = 0, @total - FLOOR(@total/8)*8, 0)
--         FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
--               UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7) seq;
--       UPDATE sku SET stock = @total WHERE id = @sku_id;"
--
-- 整段（sku1..21 = 500000）：
--   docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" qiqi-mysql \
--     mysql --default-character-set=utf8mb4 -uroot < sql/reset-sku-bucket.sql
--   （整份执行 = §1 + §2 + §3 校验，默认就是「sku1 与 sku1..21 都设成 500000」）
--
-- ── 给压测方的一句话说明（可直接复制进 runbook）─────────────────────────────
--   压测前重置热点/冷门 SKU 库存请用：sql/reset-sku-bucket.sql 里的 §1（单 SKU）/ §2（一段），
--   或 §3 的 `mysql -e` 一行版（幂等，可反复跑；跑完用 §3 的校验 SQL 看一眼）。
--   不要再 UPDATE sku.stock（分桶模式下它只是展示缓存：改了不生效，还会被回写覆盖）。
-- ============================================================================
