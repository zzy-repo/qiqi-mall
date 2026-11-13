-- ============================================================================
-- 按目标桶数 N 重铺 sku_bucket（参数化；幂等，可反复跑）
--
-- 用途：把库里已有的桶「重铺」成目标桶数 N。
--   切桶数时**代码按新 N 找桶号 0..N-1**，而库里还是旧 N 个桶 —— 不重铺就会出现
--   「桶号对不上」：扣减按 bucket_no = hash % N 去找桶，找不到 → 明明有库存却判「库存不足」。
--   所以每换一档桶数，必须先跑本文件。
--
-- 语义（与 sql/migrate-sku-bucket.sql、SkuBucketService#resetTo 完全一致）：
--   · 先删该 SKU 的**所有**桶，再按 N 重新均分 ⇒ 幂等，重跑结果一样（不会累加/翻倍）；
--   · 余数进 0 号桶 ⇒ 拆完 SUM(bucket.stock) **严格等于**目标总量；
--   · 分桶模式下 sku.stock 只是展示缓存，所以给了统一总量时**同时**把它写回（否则界面/后台显示旧数）。
--
-- ----------------------------------------------------------------------------
-- 两种用法（改顶部 4 个变量即可，别的一个字都不用动）
--
--  【用法 A】单个 SKU 或一段 id 区间，指定统一总量
--     SET @n := 8; SET @total := 500000;      -- 每个 SKU 都重铺成 500000
--     SET @from := 1; SET @to := 21;          -- 只动 sku 1..21（单个 SKU 就 @from=@to=id）
--     → 这 21 个 SKU：桶重铺成 8 个/每个总量 500000，并把 sku.stock 写成 500000。
--
--  【用法 B】全库所有 SKU，按各自现有 sku.stock 重铺（最常用，切换 N 就走这条）
--     SET @n := 8; SET @total := 0;           -- total = 0 或 NULL ⇒ 每个 SKU 用**自己**的 sku.stock 当总量
--     SET @from := 0; SET @to := 0;           -- from/to 都为 0 或 NULL ⇒ 全库所有 SKU
--     → 全库 112 个 SKU：每个都重铺成 8 个桶，总量 = 该 SKU 当前 sku.stock（**不统一 500000**，
--       也不回写 sku.stock —— 因为 sku.stock 就是这次重铺的输入）。
--     ※ MySQL 不接受「空字面量」，所谓「留空」就写成 0 或 NULL；两者等价。
--     ※ 全库模式会先 TRUNCATE 等价地清空整张桶表（含已删 SKU 的孤儿桶），所以跑完行数
--       严格 = SKU 数 × N，不会带残留。
--     ※ 只要 @from / @to 有**任何一个**非 0（用法 A/B 混着写，例如 @from=0 @to=21），
--       就按「一侧缺省」解释成 0..21 / 21..∞ 的区间，而不是全库 —— 避免误把全库刷成统一总量。
--
--  两个开关互相独立：@total 也可以配区间用（用法 A），@from/@to 也可以配 0 总量用
--  （即「只重铺某段区间，但每个 SKU 用自己的库存」）。
-- ----------------------------------------------------------------------------
--
-- 执行：
--   docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" qiqi-mysql \
--     mysql --default-character-set=utf8mb4 -uroot < sql/reset-sku-bucket-N.sql
--
-- ⚠️ 顺序要求：停流量（确认没有压测/没有下单）→ **先跑本文件重铺桶** → 再切到新 N 的镜像 → 核验 → 放量。
--    两个方向都有一个「代码 N 与数据 N 不一致」的窗口，只是表现不同：
--      · 先切镜像（代码 N=8，数据还是 32）：代码只找 0..7 号桶，8..31 号桶的库存看不见 ⇒ 少算 75%；
--      · 先重铺（数据 N=8，代码还是 32）：代码可能去改 8..31 号桶，改 0 行 ⇒ 误判库存不足。
--    所以真正的规矩是：**切换期间不能有人在扣库存**。无人流时两者等价，本文件按「先重铺」写。
--
-- ── 【runbook 规矩】发布构建期间，任何人不许改会被打进镜像的配置文件 ──────────────
--   事故实例（2026-11-13）：publish v1.0.1 正在 docker build 时，工作树里 application-test.yml 的
--   bucket-count 正被改成实验值 32 → 发布镜像里就烙上了 32（代码 32 / 库里 8 桶 = 脏组合）。
--   正确做法：
--     · 实验用**独立镜像 tag**（step5-bucket16 / 32 / 64），靠 docker tag + up -d 切档；
--     · 工作树的 application-*.yml 在实验期间始终保持**发布值**（当前 = 8），切档只经由镜像 tag；
--     · publish.sh 与任何实验构建不得并行 —— 它直接吃工作树。
-- ────────────────────────────────────────────────────────────────────────────
-- ============================================================================

USE qiqi_mall_test;

-- ---------- ① 参数（改这 4 行）----------
SET @n     := 8;         -- 目标桶数（1..64；代码里 qiqi.stock.bucket-count 必须同值）
SET @total := 0;         -- 0/NULL = 每个 SKU 用自己的 sku.stock；>0 = 统一总量
SET @from  := 0;         -- 0/NULL = 全库所有 SKU（需 @to 也为 0/NULL）
SET @to    := 0;         -- 0/NULL = 到最大 id 为止
-- ----------------------------------------

-- ---------- ② 归一化参数（NULL / 0 → 区间与总量语义）----------
SET @p_from  := COALESCE(@from, 0);
SET @p_to    := COALESCE(@to, 0);
SET @all_sku := (@p_from = 0 AND @p_to = 0);                       -- 1 = 全库模式
SET @per_sku := (@total IS NULL OR @total = 0);                    -- 1 = 按各 SKU 现有 sku.stock
SET @eff_from := IF(@all_sku, 0,
                    IF(@p_from = 0, @p_to, LEAST(@p_from, @p_to)));
SET @eff_to   := IF(@all_sku, 2147483647,
                    IF(@p_to = 0, @p_from, GREATEST(@p_from, @p_to)));

-- ---------- ③ 校验参数 + 打印将要做什么 ----------
SELECT IF(@n BETWEEN 1 AND 64 AND (@per_sku OR @total >= 0),
          CONCAT('参数 OK：N=', @n,
                 '  SKU 范围 = ', IF(@all_sku, '全库所有 SKU', CONCAT(@eff_from, '..', @eff_to)),
                 '  总量 = ', IF(@per_sku, '按每个 SKU 现有 sku.stock（不统一）', @total),
                 '  目标 SKU 数 = ',
                 (SELECT COUNT(*) FROM sku WHERE id BETWEEN @eff_from AND @eff_to),
                 '  目标桶行数 = ',
                 (SELECT COUNT(*) FROM sku WHERE id BETWEEN @eff_from AND @eff_to) * @n),
          CONCAT('✗ 参数非法：N=', @n, '（须 1..64） total=', @total)) AS param_check;

-- ---------- ④ 删掉目标 SKU 的所有桶（幂等前提）----------
--    全库模式连「已删 SKU 的孤儿桶」一起清掉，保证行数严格 = SKU 数 × N。
DELETE FROM sku_bucket WHERE @all_sku = 1;
DELETE FROM sku_bucket WHERE sku_id BETWEEN @eff_from AND @eff_to;

-- ---------- ⑤ 按 N 均分重铺（余数进 0 号桶）----------
--    数字表 0..63 固定写死，用 seq.n < @n 截到 N 个 —— 同一个文件能跑 1/8/16/32/64 任意档。
--    tot = 该 SKU 的目标总量：@per_sku=1 取它自己的 sku.stock，否则取统一的 @total；
--    GREATEST(...,0) 兜底负值（CHECK stock>=0 会让负数插不进去，宁可钳 0 再由自检③报出来）。
INSERT INTO sku_bucket (sku_id, bucket_no, stock)
SELECT t.id,
       seq.n,
       FLOOR(t.tot / @n) + IF(seq.n = 0, t.tot - FLOOR(t.tot / @n) * @n, 0)
  FROM (SELECT id, GREATEST(COALESCE(IF(@per_sku = 1, stock, @total), 0), 0) AS tot
          FROM sku
         WHERE id BETWEEN @eff_from AND @eff_to) t
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
 WHERE seq.n < @n;

-- ---------- ⑥ 展示缓存对齐（分桶模式下 sku.stock 是缓存，给了统一总量才回写）----------
--    @per_sku=1 时本条整体不生效（sku.stock 是这次重铺的输入，不能反过来被它覆盖）。
UPDATE sku SET stock = @total
 WHERE @per_sku = 0 AND id BETWEEN @eff_from AND @eff_to;

-- ---------- ⑦ 自检（四条必须全绿）----------
--  ① 桶行数 == SKU 数 × N
SELECT CONCAT('① 桶行数 = ', (SELECT COUNT(*) FROM sku_bucket WHERE sku_id BETWEEN @eff_from AND @eff_to),
              '  应为 ', (SELECT COUNT(*) FROM sku WHERE id BETWEEN @eff_from AND @eff_to), ' SKU × ', @n,
              ' = ', (SELECT COUNT(*) FROM sku WHERE id BETWEEN @eff_from AND @eff_to) * @n,
              '  不同 SKU = ', (SELECT COUNT(DISTINCT sku_id) FROM sku_bucket WHERE sku_id BETWEEN @eff_from AND @eff_to),
              IF(@all_sku, CONCAT('（全库桶表总行数 = ', (SELECT COUNT(*) FROM sku_bucket), '）'), '')) AS c1_rows;

--  ② 每个 SKU 的桶数都等于 N（应为 0）；外加「一个桶都没有的 SKU」（应为 0）
SELECT CONCAT('② 桶数 ≠ ', @n, ' 的 SKU 个数 = ', COUNT(*), '  应为 0') AS c2_bucket_count
  FROM (SELECT sku_id, COUNT(*) c FROM sku_bucket
         WHERE sku_id BETWEEN @eff_from AND @eff_to GROUP BY sku_id) x
 WHERE x.c <> @n;

SELECT CONCAT('②b 一个桶都没有的 SKU 个数 = ', COUNT(*), '  应为 0') AS c2b_no_bucket
  FROM sku s
 WHERE s.id BETWEEN @eff_from AND @eff_to
   AND NOT EXISTS (SELECT 1 FROM sku_bucket b WHERE b.sku_id = s.id);

--  ③ 桶总量 == sku.stock 的偏差 SKU 个数（应为 0 ⇒ 「应为全部」）
SELECT CONCAT('③ 桶总量 ≠ sku.stock 的 SKU 个数 = ', COUNT(*), '  应为 0',
              '（对比范围 SKU 数 = ', (SELECT COUNT(*) FROM sku WHERE id BETWEEN @eff_from AND @eff_to), '）') AS c3_cache
  FROM (SELECT s.id, s.stock st,
               (SELECT COALESCE(SUM(b.stock), 0) FROM sku_bucket b WHERE b.sku_id = s.id) bs
          FROM sku s WHERE s.id BETWEEN @eff_from AND @eff_to) t
 WHERE t.bs <> t.st;

--  ④ 越界桶号（bucket_no >= N）必须为 0 —— 切档后若不为 0，说明旧 N 的桶有残留。
--     注意本条是**全表**检查（不按 @from/@to 过滤）：它回答「整库的桶号是否都 < N」。
--     所以用法 B（全库模式）跑完必须是 0；用法 A（只重铺一段区间）若把这段改成与其它 SKU 不同的 N，
--     本条会如实报出「别的 SKU 还是旧 N」—— 那不是 bug，是提醒你别把同一库混成两个 N。
SELECT CONCAT('④ bucket_no >= ', @n, ' 的越界桶行数 = ', COUNT(*), '  应为 0（全表检查）') AS c4_out_of_range
  FROM sku_bucket WHERE bucket_no >= @n;

--  ⑤ 抽查第一个目标 SKU（全库模式就抽最小 id 那个）
SELECT CONCAT('⑤ 抽查 sku', (SELECT MIN(id) FROM sku WHERE id BETWEEN @eff_from AND @eff_to),
              '：桶总量 = ', (SELECT COALESCE(SUM(stock), 0) FROM sku_bucket
                                WHERE sku_id = (SELECT MIN(id) FROM sku WHERE id BETWEEN @eff_from AND @eff_to)),
              '  sku.stock = ', (SELECT stock FROM sku
                                  WHERE id = (SELECT MIN(id) FROM sku WHERE id BETWEEN @eff_from AND @eff_to)),
              '  0 号桶 = ', COALESCE((SELECT stock FROM sku_bucket
                                        WHERE sku_id = (SELECT MIN(id) FROM sku WHERE id BETWEEN @eff_from AND @eff_to) AND bucket_no = 0), 'N/A'),
              '  1 号桶 = ', COALESCE((SELECT stock FROM sku_bucket
                                        WHERE sku_id = (SELECT MIN(id) FROM sku WHERE id BETWEEN @eff_from AND @eff_to) AND bucket_no = 1), 'N/A（N=1 无此桶）')) AS c5_sample;

-- ---------- 回滚：把 @n 改回旧值、@total/@from/@to 按需要设好重跑本文件即可，无需别的动作 ----------
