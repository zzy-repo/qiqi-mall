-- ============================================================================
-- 按目标桶数 N 重铺 sku_bucket（参数化；幂等，可反复跑）
--
-- 用途：验证「最合适桶数」（N=8 / 16 / 32 / 64）。
--   切桶数时**代码按新 N 找桶号 0..N-1**，而库里还是旧 N 个桶 —— 不重铺就会有一半桶号不存在，
--   扣减会「明明有库存却判不足」。所以每切一档桶数，必须先跑本文件。
--
-- 语义（与 sql/migrate-sku-bucket.sql、SkuBucketService#resetTo 完全一致）：
--   · 先删该 SKU 的**所有**桶，再按 N 重新均分 ⇒ 幂等，重跑结果一样（不会累加/翻倍）；
--   · 余数进 0 号桶 ⇒ 拆完 SUM(bucket.stock) **严格等于**目标总量；
--   · 分桶模式下 sku.stock 只是展示缓存，所以**同时**把它写成目标值（否则界面/后台显示旧数）。
--
-- 用法（压测方只需要改前 4 行）：
--   SET @n := 16;        -- 目标桶数，必须与镜像里的 qiqi.stock.bucket-count 一致！
--   SET @total := 500000;-- 每个 SKU 的目标总量
--   SET @from := 1; SET @to := 21;   -- 目标 SKU 范围（单个 SKU 就写 @from=@to=id）
--
-- 执行：
--   docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" qiqi-mysql \
--     mysql --default-character-set=utf8mb4 -uroot < sql/reset-sku-bucket-N.sql
--
-- ── 【runbook 规矩】发布构建期间，任何人不许改会被打进镜像的配置文件 ──────────────
--   事故实例（2026-11-13）：publish v1.0.1 正在 docker build 时，工作树里 application-test.yml 的
--   bucket-count 正被改成实验值 32 → 发布镜像 v1.0.1 里就烙上了 32（代码 32 / 库里 8 桶 = 脏组合）。
--   正确做法：
--     · 实验用**独立镜像 tag**（step5-bucket16 / 32 / 64），靠 docker tag + up -d 切档；
--     · 工作树的 application-*.yml 在实验期间始终保持**发布值**（8 或最终选定的 N），
--       切档只经由镜像 tag，绝不靠改 yml；
--     · publish.sh 与任何实验构建不得并行 —— 它直接吃工作树。
-- ────────────────────────────────────────────────────────────────────────────
--
-- ⚠️ 顺序要求：先切镜像（代码按新 N 跑）→ 立刻跑本文件重铺桶 → 再开跑。
--    反了的话中间那段时间扣减会判「库存不足」（桶号对不上），压测数据就废了。
-- ============================================================================

USE qiqi_mall_test;

-- ---------- ① 参数（改这 4 行）----------
SET @n     := 16;        -- 目标桶数（8 / 16 / 32 / 64；代码里 bucket-count 必须同值）
SET @total := 500000;    -- 每个 SKU 的目标总量（件）
SET @from  := 1;         -- SKU id 起（含）
SET @to    := 21;        -- SKU id 止（含）
-- ----------------------------------------

-- ---------- ② 校验参数（N 必须在 1..64，且 total 非负）----------
SELECT IF(@n BETWEEN 1 AND 64 AND @total >= 0,
          CONCAT('参数 OK：N=', @n, ' total=', @total, ' sku=', @from, '..', @to),
          CONCAT('✗ 参数非法：N=', @n, '（须 1..64） total=', @total)) AS param_check;

-- ---------- ③ 删掉目标 SKU 的所有桶（幂等前提）----------
DELETE FROM sku_bucket WHERE sku_id BETWEEN @from AND @to;

-- ---------- ④ 按 N 均分重铺（余数进 0 号桶）----------
--    数字表 0..63 固定写死，用 seq.n < @n 截到 N 个 —— 这样同一个文件能跑 8/16/32/64 任意档。
INSERT INTO sku_bucket (sku_id, bucket_no, stock)
SELECT s.id, seq.n,
       FLOOR(@total / @n) + IF(seq.n = 0, @total - FLOOR(@total / @n) * @n, 0)
  FROM sku s
  CROSS JOIN (SELECT 0 AS n
              UNION ALL SELECT 1 AS n
              UNION ALL SELECT 2 AS n
              UNION ALL SELECT 3 AS n
              UNION ALL SELECT 4 AS n
              UNION ALL SELECT 5 AS n
              UNION ALL SELECT 6 AS n
              UNION ALL SELECT 7 AS n
              UNION ALL SELECT 8 AS n
              UNION ALL SELECT 9 AS n
              UNION ALL SELECT 10 AS n
              UNION ALL SELECT 11 AS n
              UNION ALL SELECT 12 AS n
              UNION ALL SELECT 13 AS n
              UNION ALL SELECT 14 AS n
              UNION ALL SELECT 15 AS n
              UNION ALL SELECT 16 AS n
              UNION ALL SELECT 17 AS n
              UNION ALL SELECT 18 AS n
              UNION ALL SELECT 19 AS n
              UNION ALL SELECT 20 AS n
              UNION ALL SELECT 21 AS n
              UNION ALL SELECT 22 AS n
              UNION ALL SELECT 23 AS n
              UNION ALL SELECT 24 AS n
              UNION ALL SELECT 25 AS n
              UNION ALL SELECT 26 AS n
              UNION ALL SELECT 27 AS n
              UNION ALL SELECT 28 AS n
              UNION ALL SELECT 29 AS n
              UNION ALL SELECT 30 AS n
              UNION ALL SELECT 31 AS n
              UNION ALL SELECT 32 AS n
              UNION ALL SELECT 33 AS n
              UNION ALL SELECT 34 AS n
              UNION ALL SELECT 35 AS n
              UNION ALL SELECT 36 AS n
              UNION ALL SELECT 37 AS n
              UNION ALL SELECT 38 AS n
              UNION ALL SELECT 39 AS n
              UNION ALL SELECT 40 AS n
              UNION ALL SELECT 41 AS n
              UNION ALL SELECT 42 AS n
              UNION ALL SELECT 43 AS n
              UNION ALL SELECT 44 AS n
              UNION ALL SELECT 45 AS n
              UNION ALL SELECT 46 AS n
              UNION ALL SELECT 47 AS n
              UNION ALL SELECT 48 AS n
              UNION ALL SELECT 49 AS n
              UNION ALL SELECT 50 AS n
              UNION ALL SELECT 51 AS n
              UNION ALL SELECT 52 AS n
              UNION ALL SELECT 53 AS n
              UNION ALL SELECT 54 AS n
              UNION ALL SELECT 55 AS n
              UNION ALL SELECT 56 AS n
              UNION ALL SELECT 57 AS n
              UNION ALL SELECT 58 AS n
              UNION ALL SELECT 59 AS n
              UNION ALL SELECT 60 AS n
              UNION ALL SELECT 61 AS n
              UNION ALL SELECT 62 AS n
              UNION ALL SELECT 63 AS n
             ) seq
 WHERE s.id BETWEEN @from AND @to
   AND seq.n < @n;

-- ---------- ⑤ 展示缓存对齐（分桶模式下 sku.stock 是缓存，必须一起写）----------
UPDATE sku SET stock = @total WHERE id BETWEEN @from AND @to;

-- ---------- ⑥ 自检（三条必须全绿）----------
SELECT CONCAT('桶行数 = ', (SELECT COUNT(*) FROM sku_bucket WHERE sku_id BETWEEN @from AND @to),
              '（应为 SKU 数×', @n, '）  不同 SKU = ', (SELECT COUNT(DISTINCT sku_id) FROM sku_bucket WHERE sku_id BETWEEN @from AND @to),
              '  SKU 数 = ', (SELECT COUNT(*) FROM sku WHERE id BETWEEN @from AND @to)) AS rows_check;

SELECT CONCAT('桶数 ≠ ', @n, ' 的 SKU 个数 = ', COUNT(*), '（应为 0）') AS bucket_count_check
  FROM (SELECT sku_id, COUNT(*) c FROM sku_bucket
         WHERE sku_id BETWEEN @from AND @to GROUP BY sku_id) x WHERE c <> @n;

SELECT CONCAT('桶总量 ≠ sku.stock 的 SKU 个数 = ', COUNT(*), '（应为 0）') AS cache_check
  FROM (SELECT s.id, s.stock st,
               (SELECT COALESCE(SUM(stock),0) FROM sku_bucket b WHERE b.sku_id = s.id) bs
          FROM sku s WHERE s.id BETWEEN @from AND @to) t
 WHERE t.bs <> t.st;

SELECT CONCAT('抽查 sku', @from, '：桶总量 = ',
              (SELECT COALESCE(SUM(stock),0) FROM sku_bucket WHERE sku_id = @from),
              '  0 号桶 = ', (SELECT stock FROM sku_bucket WHERE sku_id = @from AND bucket_no = 0),
              '  1 号桶 = ', (SELECT stock FROM sku_bucket WHERE sku_id = @from AND bucket_no = 1)) AS sample_check;

-- ---------- 回滚到 8 桶（把 @n 改回 8 重跑本文件即可，无需别的动作）----------
