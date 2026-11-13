-- ============================================================================
-- 验证：台账体系迁移（sql/migrate-ledger.sql）事后自证
-- ============================================================================
--   用法：mysql -uroot -p <目标库> < sql/migrate-ledger-verify.sql
--   只读：本文件全是 SELECT / SET PREPARE，**不含任何 DDL/DML**，可在任何库安全重跑。
--   目标库由 DATABASE() 决定。
--   每个 section 都先打印 !! 标题，便于比对输出。
-- ============================================================================

SET @db := DATABASE();

SELECT '############################################################' AS s;
SELECT '  0 · 目标库与总体' AS s;
SELECT '############################################################' AS s;

SELECT @db AS target_db, VERSION() AS mysql_version;
SELECT COUNT(*) AS total_base_tables,
       IF(COUNT(*) = 30, '✓ 25 现有 + 5 新增 = 30', '✗ 表数不是 30，请核对') AS verdict
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';


SELECT '############################################################' AS s;
SELECT '  1 · 新表存在 且 列数正确' AS s;
SELECT '############################################################' AS s;

WITH want AS (
  SELECT 'order_status_ledger'    AS t, 14 AS expect UNION ALL   -- 13 共同契约 + order_id
  SELECT 'delivery_status_ledger' AS t, 16 AS expect UNION ALL   -- 13 共同契约 + delivery_id + delivery_sn + order_id(冗余)
  SELECT 'return_status_ledger'   AS t, 15 AS expect UNION ALL   -- 13 共同契约 + return_apply_id + order_id(冗余)
  SELECT 'admin_audit_log'        AS t, 16 AS expect UNION ALL   -- 13 + target_sn/summary/reason
  SELECT 'wms_exchange_ledger'    AS t, 13 AS expect
), got AS (
  SELECT c.TABLE_NAME AS t, COUNT(*) AS col_cnt, COUNT(DISTINCT c.COLUMN_NAME) AS uniq_col_cnt
    FROM information_schema.COLUMNS c
    JOIN want w ON w.t = c.TABLE_NAME
   WHERE c.TABLE_SCHEMA = DATABASE()
   GROUP BY c.TABLE_NAME
)
SELECT w.t AS table_name,
       IF(g.t IS NULL, 0, g.col_cnt)      AS actual_col_cnt,
       w.expect                            AS expected_col_cnt,
       IFNULL(g.col_cnt, 0) = g.uniq_col_cnt AS no_dup_column,
       IF(IFNULL(g.col_cnt,0) = w.expect, '✓', '✗') AS verdict
  FROM want w LEFT JOIN got g ON g.t = w.t
 ORDER BY w.expect DESC, w.t;


SELECT '############################################################' AS s;
SELECT '  2 · 三张状态台账：13 列共同契约同构 + 冗余列白名单（SQL 自证）' AS s;
SELECT '############################################################' AS s;

-- 口径（2026-11-13 裁决）：13 列共同契约必须**逐字一致**；冗余列必须先登记；
-- 每表「独有的列」必须**恰好等于**登记清单 —— 多一列就报错。
-- 本节 reg/redun 必须与 migrate-ledger.sql 头部 REGISTRY 逐行一致。
--   biz_pk        = 业务主键列（不属于 13 列共同契约）
--   expect_extras = 「业务主键 + 已登记冗余列」的字典序清单（白名单）

-- 2.1 判据：三张表的 13 列定义串必须只有一种
WITH reg AS (
  SELECT 'order_status_ledger'    AS tbl, 'order_id'        AS biz_pk, 'order_id'                   AS expect_extras
  UNION ALL SELECT 'delivery_status_ledger', 'delivery_id',     'delivery_id, delivery_sn, order_id'
  UNION ALL SELECT 'return_status_ledger',   'return_apply_id', 'order_id, return_apply_id'
), redun AS (
  SELECT 'delivery_status_ledger' AS tbl, 'delivery_sn' AS col
  UNION ALL SELECT 'delivery_status_ledger', 'order_id'
  UNION ALL SELECT 'return_status_ledger', 'order_id'
), cols AS (
  SELECT c.TABLE_NAME AS tbl, c.COLUMN_NAME AS col,
         CONCAT(c.COLUMN_NAME, ':', LOWER(c.COLUMN_TYPE), ':', c.IS_NULLABLE) AS def
    FROM information_schema.COLUMNS c
    JOIN reg r ON r.tbl = c.TABLE_NAME
   WHERE c.TABLE_SCHEMA = DATABASE()
), shared AS (
  -- 剔除该表业务主键 + 该表已登记冗余列，剩下的就是 13 列共同契约
  SELECT s.tbl, s.col, s.def
    FROM cols s
   WHERE NOT EXISTS (SELECT 1 FROM redun d WHERE d.tbl = s.tbl AND d.col  = s.col)
     AND NOT EXISTS (SELECT 1 FROM reg   r WHERE r.tbl = s.tbl AND r.biz_pk = s.col)
), agg AS (
  SELECT tbl, COUNT(*) AS cnt,
         GROUP_CONCAT(def ORDER BY col SEPARATOR ' | ') AS defs
    FROM shared
   GROUP BY tbl
)
SELECT COUNT(*)             AS tables_cnt,
       MIN(cnt)             AS shared_col_cnt_min,
       MAX(cnt)             AS shared_col_cnt_max,
       COUNT(DISTINCT defs) AS distinct_defsets,
       IF(COUNT(*) = 3 AND MIN(cnt) = 13 AND MAX(cnt) = 13 AND COUNT(DISTINCT defs) = 1,
          '✓ 13 列共同契约同构成立（冗余列已按登记表剔除）',
          '✗ 13 列契约不同构，见下方 2.2 明细') AS verdict
  FROM agg;

-- 2.2 逐表明细：三行 MD5 相同 = 13 列逐字相同
WITH reg AS (
  SELECT 'order_status_ledger'    AS tbl, 'order_id'        AS biz_pk, 'order_id'                   AS expect_extras
  UNION ALL SELECT 'delivery_status_ledger', 'delivery_id',     'delivery_id, delivery_sn, order_id'
  UNION ALL SELECT 'return_status_ledger',   'return_apply_id', 'order_id, return_apply_id'
), redun AS (
  SELECT 'delivery_status_ledger' AS tbl, 'delivery_sn' AS col
  UNION ALL SELECT 'delivery_status_ledger', 'order_id'
  UNION ALL SELECT 'return_status_ledger', 'order_id'
), cols AS (
  SELECT c.TABLE_NAME AS tbl, c.COLUMN_NAME AS col,
         CONCAT(c.COLUMN_NAME, ':', LOWER(c.COLUMN_TYPE), ':', c.IS_NULLABLE) AS def
    FROM information_schema.COLUMNS c
    JOIN reg r ON r.tbl = c.TABLE_NAME
   WHERE c.TABLE_SCHEMA = DATABASE()
), shared AS (
  -- 剔除该表业务主键 + 该表已登记冗余列，剩下的就是 13 列共同契约
  SELECT s.tbl, s.col, s.def
    FROM cols s
   WHERE NOT EXISTS (SELECT 1 FROM redun d WHERE d.tbl = s.tbl AND d.col  = s.col)
     AND NOT EXISTS (SELECT 1 FROM reg   r WHERE r.tbl = s.tbl AND r.biz_pk = s.col)
), agg AS (
  SELECT tbl, COUNT(*) AS cnt,
         GROUP_CONCAT(def ORDER BY col SEPARATOR ' | ') AS defs
    FROM shared
   GROUP BY tbl
)
SELECT tbl       AS table_name,
       cnt       AS shared_col_cnt,
       MD5(defs) AS defs_md5,
       defs      AS shared_13_cols
  FROM agg
 ORDER BY tbl;

-- 2.3 冗余列白名单：每表独有的列必须恰好等于登记清单（多一列 / 少一列都报 ✗）
WITH reg AS (
  SELECT 'order_status_ledger'    AS tbl, 'order_id'        AS biz_pk, 'order_id'                   AS expect_extras
  UNION ALL SELECT 'delivery_status_ledger', 'delivery_id',     'delivery_id, delivery_sn, order_id'
  UNION ALL SELECT 'return_status_ledger',   'return_apply_id', 'order_id, return_apply_id'
), redun AS (
  SELECT 'delivery_status_ledger' AS tbl, 'delivery_sn' AS col
  UNION ALL SELECT 'delivery_status_ledger', 'order_id'
  UNION ALL SELECT 'return_status_ledger', 'order_id'
), cols AS (
  SELECT c.TABLE_NAME AS tbl, c.COLUMN_NAME AS col
    FROM information_schema.COLUMNS c
    JOIN reg r ON r.tbl = c.TABLE_NAME
   WHERE c.TABLE_SCHEMA = DATABASE()
), extras AS (
  SELECT s.tbl, GROUP_CONCAT(s.col ORDER BY s.col SEPARATOR ', ') AS actual_extras
    FROM cols s
   WHERE EXISTS (SELECT 1 FROM redun d WHERE d.tbl = s.tbl AND d.col = s.col)
      OR EXISTS (SELECT 1 FROM reg  r WHERE r.tbl = s.tbl AND r.biz_pk = s.col)
   GROUP BY s.tbl
)
SELECT r.tbl AS table_name,
       r.expect_extras                  AS registered_extras,
       IFNULL(e.actual_extras,'（无）')   AS actual_extras,
       IF(IFNULL(e.actual_extras,'（无）') = r.expect_extras,
          '✓ 恰好等于登记清单', '✗ 出现未登记列 / 登记列缺失') AS verdict
  FROM reg r LEFT JOIN extras e ON e.tbl = r.tbl
 ORDER BY r.tbl;


SELECT '  3 · payment_record.order_id 回填率' AS s;
SELECT '############################################################' AS s;

SELECT COUNT(*)                                   AS total_rows,
       SUM(order_id IS NOT NULL)                  AS filled_order_id,
       SUM(order_id IS NULL)                      AS null_order_id,
       CONCAT(SUM(order_id IS NOT NULL), '/', COUNT(*)) AS fill_rate,
       IF(COUNT(*) = 0, '— 本库无数据行（结构副本），回填率无意义',
          IF(SUM(order_id IS NULL) = 0, '✓ 全部回填', '✗ 有漏回填')) AS verdict
  FROM `payment_record`;

-- 漏网明细（应为空）：order_sn 在 order 表里找不到的流水
SELECT p.id AS payment_id, p.order_sn, '✗ 对不上订单' AS problem
  FROM `payment_record` p
  LEFT JOIN `order` o ON o.order_sn = p.order_sn
 WHERE p.order_id IS NULL AND o.id IS NULL;

-- 新增 8 列是否齐（order_id/ref_type/ref_id/origin_flow_id/operator_type/operator_id/operator_name/idem_key）
SELECT COUNT(*) AS new_cols_present,
       IF(COUNT(*) = 8, '✓ 8 列齐', '✗ 缺列') AS verdict,
       GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) AS cols
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_record'
   AND COLUMN_NAME IN ('order_id','ref_type','ref_id','origin_flow_id',
                       'operator_type','operator_id','operator_name','idem_key');


SELECT '############################################################' AS s;
SELECT '  4 · return_apply.pending_order_id 列已建；唯一键 uk_pending_order 本轮刻意不建（见 migrate-ledger.sql 头部【已知问题·待办】）' AS s;
SELECT '############################################################' AS s;

SELECT COLUMN_NAME AS col, COLUMN_TYPE AS type, IS_NULLABLE AS nullable,
       EXTRA AS extra, GENERATION_EXPRESSION AS expr
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'return_apply'
   AND COLUMN_NAME = 'pending_order_id';

SELECT INDEX_NAME AS idx, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME AS col
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'return_apply'
   AND INDEX_NAME = 'uk_pending_order';

-- 唯一键的存在性**不再判 ✗**：本轮刻意延后（理由见 migrate-ledger.sql 头部【已知问题·待办】）。
-- 这里只把「有没有建」如实打印出来，供人工核对「确实是按延后口径执行的」。
SELECT IF(COUNT(*) = 1 AND MIN(NON_UNIQUE) = 0,
          '⏸ uk_pending_order 存在（本轮口径：应**不存在**，若出现说明迁移被旧版本执行过）',
          '✓ 按延后口径执行：uk_pending_order 未建（pending_order_id 列仍在）') AS verdict
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'return_apply'
   AND INDEX_NAME = 'uk_pending_order' AND SEQ_IN_INDEX = 1;

-- 现状盘点（**只是观测，不是门禁**）：允许「多张订单各一条待处理」；同一订单若有两条待处理，
-- 说明【已知问题·待办】仍然存在 —— 本轮不修，等「唯一键 + admin 守卫文案 + 改 ㉛-C」那一轮。
SELECT COUNT(*) AS pending_apply_rows,
       COUNT(DISTINCT pending_order_id) AS distinct_pending_orders,
       IF(COUNT(*) = COUNT(DISTINCT pending_order_id),
          '观测：当前无重复待处理申请', '观测：存在同单多条待处理申请（已登记待办，本轮不修）') AS note_
  FROM `return_apply`
 WHERE pending_order_id IS NOT NULL;


SELECT '############################################################' AS s;
SELECT '  5 · order.created_at 回填了几行 / 几行 NULL' AS s;
SELECT '############################################################' AS s;

SELECT COUNT(*)                        AS total_rows,
       SUM(created_at IS NOT NULL)     AS filled_created_at,
       SUM(created_at IS NULL)         AS null_created_at,
       IF(COUNT(*) = 0, '— 本库无数据行（结构副本）',
          CONCAT('回填 ', SUM(created_at IS NOT NULL), ' 行，留 NULL ',
                 SUM(created_at IS NULL), ' 行')) AS summary
  FROM `order`;

-- 按 order_sn 长度分布，解释「为什么回填了这些、NULL 了那些」
--   22 位 = QQ+yyyyMMddHHmmss+6 随机 ⇒ 可解析出时分秒 ⇒ 回填
--   <22 位 = 种子/测试短号 ⇒ 解析不出时分秒 ⇒ 按设计留 NULL 不猜
SELECT t.sn_len,
       t.rows_cnt,
       t.filled,
       t.kept_null,
       CASE t.sn_len
         WHEN 22 THEN 'QQ+yyyyMMddHHmmss+6随机 → 可解析'
         ELSE '短号：无时分秒 → 按设计留 NULL 不猜'
       END AS why
  FROM (
        SELECT CHAR_LENGTH(order_sn) AS sn_len,
               COUNT(*)              AS rows_cnt,
               SUM(created_at IS NOT NULL) AS filled,
               SUM(created_at IS NULL)     AS kept_null
          FROM `order`
         GROUP BY CHAR_LENGTH(order_sn)
       ) t
 ORDER BY t.sn_len;

-- 已回填行的抽样（人工核对解析是否正确）
SELECT id, order_sn, created_at, payment_time
  FROM `order`
 WHERE created_at IS NOT NULL
 ORDER BY id LIMIT 5;

-- 解析能力自证：对 22 位订单号的解析结果（不写库，只 SELECT）
--   本库若全是短号，这里会返回 0 行 —— 属正常，说明"没人可解析"。
--   表达式与 migrate-ledger.sql 的回填表达式逐字一致：纯算术，不用 STR_TO_DATE
--   （STR_TO_DATE 遇到 2 月 30 日这类"格式对但日子不存在"的串会抛 ERROR 1411）。
SELECT id, order_sn,
       SUBSTRING(order_sn, 3, 14) AS parsed_digits,
       MAKEDATE(CAST(SUBSTRING(order_sn, 3, 4) AS UNSIGNED), 1)
         + INTERVAL (CAST(SUBSTRING(order_sn, 7, 2) AS UNSIGNED) - 1) MONTH
         + INTERVAL (CAST(SUBSTRING(order_sn, 9, 2) AS UNSIGNED) - 1) DAY
         + INTERVAL  CAST(SUBSTRING(order_sn, 11, 2) AS UNSIGNED) HOUR
         + INTERVAL  CAST(SUBSTRING(order_sn, 13, 2) AS UNSIGNED) MINUTE
         + INTERVAL  CAST(SUBSTRING(order_sn, 15, 2) AS UNSIGNED) SECOND AS parsed_datetime,
       IF(created_at IS NOT NULL, '✓ 已写入', '— 未解析') AS state
  FROM `order`
 WHERE CHAR_LENGTH(order_sn) = 22
   AND LEFT(order_sn, 2) = 'QQ'
   AND SUBSTRING(order_sn, 3, 20) REGEXP '^[0-9]{20}$'
   AND CAST(SUBSTRING(order_sn, 3, 4) AS UNSIGNED) BETWEEN 1970 AND 2100
   AND CAST(SUBSTRING(order_sn, 7, 2) AS UNSIGNED) BETWEEN 1 AND 12
   AND CAST(SUBSTRING(order_sn, 9, 2) AS UNSIGNED) BETWEEN 1 AND
       DAY(LAST_DAY(MAKEDATE(CAST(SUBSTRING(order_sn, 3, 4) AS UNSIGNED), 1)
             + INTERVAL (CAST(SUBSTRING(order_sn, 7, 2) AS UNSIGNED) - 1) MONTH))
   AND CAST(SUBSTRING(order_sn, 11, 2) AS UNSIGNED) <= 23
   AND CAST(SUBSTRING(order_sn, 13, 2) AS UNSIGNED) <= 59
   AND CAST(SUBSTRING(order_sn, 15, 2) AS UNSIGNED) <= 59
 ORDER BY id LIMIT 5;

-- 跨库旁证（只读）：真实业务库 qiqi_mall 里 22 位订单号的解析成功率
SET @n := (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = 'qiqi_mall');
SET @s := IF(@n > 0,
  'SELECT ''qiqi_mall·22位订单号'' AS probe, COUNT(*) AS sn22_cnt,
          SUM(CHAR_LENGTH(order_sn) = 22 AND LEFT(order_sn,2) = ''QQ''
              AND SUBSTRING(order_sn,3,20) REGEXP ''^[0-9]{20}$''
              AND CAST(SUBSTRING(order_sn,3,4) AS UNSIGNED) BETWEEN 1970 AND 2100
              AND CAST(SUBSTRING(order_sn,7,2) AS UNSIGNED) BETWEEN 1 AND 12
              AND CAST(SUBSTRING(order_sn,9,2) AS UNSIGNED) BETWEEN 1 AND
                  DAY(LAST_DAY(MAKEDATE(CAST(SUBSTRING(order_sn,3,4) AS UNSIGNED),1) + INTERVAL (CAST(SUBSTRING(order_sn,7,2) AS UNSIGNED) - 1) MONTH))
              AND CAST(SUBSTRING(order_sn,11,2) AS UNSIGNED) <= 23
              AND CAST(SUBSTRING(order_sn,13,2) AS UNSIGNED) <= 59
              AND CAST(SUBSTRING(order_sn,15,2) AS UNSIGNED) <= 59) AS parseable,
          IF(SUM(CHAR_LENGTH(order_sn) = 22 AND LEFT(order_sn,2) = ''QQ''
              AND SUBSTRING(order_sn,3,20) REGEXP ''^[0-9]{20}$''
              AND CAST(SUBSTRING(order_sn,3,4) AS UNSIGNED) BETWEEN 1970 AND 2100
              AND CAST(SUBSTRING(order_sn,7,2) AS UNSIGNED) BETWEEN 1 AND 12
              AND CAST(SUBSTRING(order_sn,9,2) AS UNSIGNED) BETWEEN 1 AND
                  DAY(LAST_DAY(MAKEDATE(CAST(SUBSTRING(order_sn,3,4) AS UNSIGNED),1) + INTERVAL (CAST(SUBSTRING(order_sn,7,2) AS UNSIGNED) - 1) MONTH))
              AND CAST(SUBSTRING(order_sn,11,2) AS UNSIGNED) <= 23
              AND CAST(SUBSTRING(order_sn,13,2) AS UNSIGNED) <= 59
              AND CAST(SUBSTRING(order_sn,15,2) AS UNSIGNED) <= 59) = COUNT(*),''✓ 100% 可解析'',''✗ 有解析失败'') AS verdict
     FROM qiqi_mall.`order` WHERE CHAR_LENGTH(order_sn) = 22',
  'SELECT ''qiqi_mall 不存在，跳过跨库旁证'' AS probe');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;


SELECT '############################################################' AS s;
SELECT '  6 · 所有 uk_idem 索引存在' AS s;
SELECT '############################################################' AS s;

WITH want AS (
  SELECT 'order_status_ledger'    AS t UNION ALL
  SELECT 'delivery_status_ledger' UNION ALL
  SELECT 'return_status_ledger'   UNION ALL
  SELECT 'admin_audit_log'        UNION ALL
  SELECT 'wms_exchange_ledger'    UNION ALL
  SELECT 'payment_record'
), got AS (
  SELECT TABLE_NAME AS t, COUNT(*) AS parts, MIN(NON_UNIQUE) AS non_unique
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME = 'uk_idem'
   GROUP BY TABLE_NAME
)
SELECT w.t AS table_name,
       IF(g.t IS NULL, '缺失', '已建') AS uk_idem,
       g.non_unique,
       IF(g.t IS NOT NULL AND g.non_unique = 0, '✓', '✗') AS verdict
  FROM want w LEFT JOIN got g ON g.t = w.t
 ORDER BY w.t;


SELECT '############################################################' AS s;
SELECT '  7 · 其余契约自证（status_seq / 排序规则 / CHECK 边界）' AS s;
SELECT '############################################################' AS s;

-- 7.1 status_seq 三张业务表都有，且为 INT NOT NULL DEFAULT 0
SELECT TABLE_NAME AS table_name, COLUMN_NAME AS col, COLUMN_TYPE AS type,
       IS_NULLABLE AS nullable, COLUMN_DEFAULT AS dflt,
       IF(COLUMN_TYPE = 'int' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = '0', '✓', '✗') AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND COLUMN_NAME = 'status_seq'
   AND TABLE_NAME IN ('order','delivery_order','return_apply')
 ORDER BY TABLE_NAME;

-- 7.2 5 张新表排序规则必须是 utf8mb4_0900_ai_ci（与真实库一致，不跟 schema.sql 的漂移）
SELECT TABLE_NAME AS table_name, TABLE_COLLATION,
       IF(TABLE_COLLATION = 'utf8mb4_0900_ai_ci', '✓', '✗ 排序规则不对') AS verdict
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('order_status_ledger','delivery_status_ledger',
                      'return_status_ledger','admin_audit_log','wms_exchange_ledger')
 ORDER BY TABLE_NAME;

-- 7.3 只追加表不该有 updated_at
SELECT COUNT(*) AS updated_at_cols_on_new_tables,
       IF(COUNT(*) = 0, '✓ 无 updated_at（只追加表正确）', '✗ 出现了 updated_at') AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND COLUMN_NAME = 'updated_at'
   AND TABLE_NAME IN ('order_status_ledger','delivery_status_ledger',
                      'return_status_ledger','admin_audit_log','wms_exchange_ledger');

-- 7.4 新表时间列必须是 DATETIME(3)
SELECT TABLE_NAME AS table_name, COLUMN_NAME AS col, COLUMN_TYPE AS type,
       DATETIME_PRECISION AS frac,
       IF(DATA_TYPE = 'datetime' AND DATETIME_PRECISION = 3, '✓', '✗') AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('order_status_ledger','delivery_status_ledger',
                      'return_status_ledger','admin_audit_log','wms_exchange_ledger')
   AND COLUMN_NAME = 'created_at'
 ORDER BY TABLE_NAME;

-- 7.5 CHECK 约束边界：台账闭集有 CHECK，业务状态枚举**必须没有** CHECK
--     （业务 status 上挂 CHECK，一次非法枚举就会回滚整个业务事务）
--     注意：information_schema.CHECK_CONSTRAINTS 没有 TABLE_NAME 列，必须 JOIN TABLE_CONSTRAINTS。
SELECT cc.CONSTRAINT_NAME AS ck, tc.TABLE_NAME AS table_name, cc.CHECK_CLAUSE AS clause_
  FROM information_schema.CHECK_CONSTRAINTS cc
  JOIN information_schema.TABLE_CONSTRAINTS tc
    ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
   AND tc.CONSTRAINT_NAME   = cc.CONSTRAINT_NAME
   AND tc.CONSTRAINT_TYPE   = 'CHECK'
 WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
   AND tc.TABLE_NAME IN ('order_status_ledger','delivery_status_ledger','return_status_ledger',
                         'admin_audit_log','wms_exchange_ledger','order','return_apply',
                         'delivery_order','payment_record')
 ORDER BY tc.TABLE_NAME, cc.CONSTRAINT_NAME;

SELECT COUNT(*) AS checks_touching_business_status,
       IF(COUNT(*) = 0, '✓ 业务状态枚举无 CHECK（合规）', '✗ 给业务状态加了 CHECK，违反设计口径') AS verdict
  FROM information_schema.CHECK_CONSTRAINTS cc
  JOIN information_schema.TABLE_CONSTRAINTS tc
    ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
   AND tc.CONSTRAINT_NAME   = cc.CONSTRAINT_NAME
   AND tc.CONSTRAINT_TYPE   = 'CHECK'
 WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
   AND tc.TABLE_NAME IN ('order','return_apply','delivery_order','payment_record')
   AND cc.CHECK_CLAUSE REGEXP '`status`|`pay_type`|`type`|`channel`';

-- 7.6 admin_audit_log 列清单/顺序逐字比对（15 列契约 + id = 16 列）
--     命名统一为 operator_*（与三张状态台账 / wms 台账同一语义同一写法）；
--     actor_* 必须彻底不存在。
SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION SEPARATOR ',') AS actual_cols,
       IF(GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION SEPARATOR ',') =
          'id,operator_type,operator_id,operator_name,action,target_type,target_id,target_sn,summary,reason,order_id,before_json,after_json,request_id,idem_key,created_at',
          '✓ 列清单与顺序逐字符合契约','✗ 列清单或顺序不符') AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log';

-- 7.7 actor_* 命名残留必须为 0（统一成 operator_* 的证据）
SELECT COUNT(*) AS actor_star_cols_left,
       IF(COUNT(*) = 0, '✓ 已无 actor_* 命名', '✗ 仍有 actor_* 列') AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND COLUMN_NAME IN ('actor_type','actor_id','actor_name');

-- 7.8 审计索引名同步改成 idx_operator（不再是 idx_actor）
SELECT INDEX_NAME AS idx, COLUMN_NAME AS col, SEQ_IN_INDEX
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log'
   AND INDEX_NAME IN ('idx_operator','idx_actor','idx_target','idx_order','uk_idem')
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;

SELECT IF((SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log'
              AND INDEX_NAME = 'idx_operator' AND COLUMN_NAME = 'operator_id' AND SEQ_IN_INDEX = 1) = 1
          AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log'
                  AND INDEX_NAME = 'idx_operator' AND COLUMN_NAME = 'created_at' AND SEQ_IN_INDEX = 2) = 1
          AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log'
                  AND INDEX_NAME = 'idx_actor') = 0,
          '✓ idx_operator(operator_id, created_at) 已建，且无旧 idx_actor',
          '✗ 索引名未同步') AS verdict;

-- 7.9 idem_key 派生口径提醒（结构性提醒，非数据校验）：审计表 idem_key 必须 NOT NULL 且唯一，
--     由应用侧按 request_id 派生；这里只确认"硬闸门在"，语义由 Java 保证。
SELECT COLUMN_NAME AS col, IS_NULLABLE AS nullable, COLUMN_TYPE AS type,
       IF(IS_NULLABLE = 'NO' AND COLUMN_TYPE = 'varchar(96)', '✓', '✗') AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log'
   AND COLUMN_NAME = 'idem_key';


SELECT '############################################################' AS s;
SELECT '  8 · delivery_status_ledger.order_id 冗余列（与售后台账对称）+ 视图 DELIVERY 分支可读真实列' AS s;
SELECT '############################################################' AS s;

-- 8.1 列存在、可空、位置紧挨 delivery_sn（新库/老库列序一致）
SELECT COLUMN_NAME AS col, COLUMN_TYPE AS type, IS_NULLABLE AS nullable, ORDINAL_POSITION AS pos,
       CASE WHEN COLUMN_NAME <> 'order_id' THEN '（对照列：已登记的 delivery_sn）'
            WHEN COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'YES'
                 THEN '✓ 已登记冗余列 order_id 存在（可空）'
            ELSE '✗ 类型/可空性不对' END AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_status_ledger'
   AND COLUMN_NAME IN ('delivery_sn','order_id')
 ORDER BY ORDINAL_POSITION;

-- 8.2 回填率：能回多少回多少；包裹已被物理删除的历史行保持 NULL（设计如此，不是漏回填）
SELECT COUNT(*)                        AS ledger_rows,
       SUM(order_id IS NOT NULL)       AS filled_order_id,
       SUM(order_id IS NULL)           AS null_by_design,
       CONCAT(SUM(order_id IS NOT NULL), '/', COUNT(*)) AS fill_rate
  FROM `delivery_status_ledger`;

-- 8.2b 仍为 NULL 且「包裹行还在」的行 —— 这些才是真的漏回填，应为空
SELECT l.id AS ledger_id, l.delivery_id, l.delivery_sn, '✗ 包裹还在却没回填 order_id' AS problem
  FROM `delivery_status_ledger` l
  JOIN `delivery_order` d ON d.id = l.delivery_id
 WHERE l.order_id IS NULL
 LIMIT 20;

-- 8.3 回填值与桥一致（不一致行数应为 0）
SELECT COUNT(*) AS mismatch_rows,
       IF(COUNT(*) = 0, '✓ 冗余 order_id 与 delivery_order.order_id 一致', '✗ 存在不一致') AS verdict
  FROM `delivery_status_ledger` l
  JOIN `delivery_order` d ON d.id = l.delivery_id
 WHERE l.order_id IS NOT NULL AND l.order_id <> d.order_id;

-- 8.4 索引 idx_order(order_id, created_at) 已建（按订单反查走这里）
SELECT INDEX_NAME AS idx, COLUMN_NAME AS col, SEQ_IN_INDEX AS seq
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_status_ledger'
   AND INDEX_NAME IN ('idx_biz','idx_order')
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;

SELECT IF((SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_status_ledger'
              AND INDEX_NAME = 'idx_order' AND COLUMN_NAME = 'order_id' AND SEQ_IN_INDEX = 1) = 1,
          '✓ idx_order(order_id, created_at) 已建', '✗ 缺 idx_order') AS verdict;

-- 8.5 视图 DELIVERY 分支的 order_id 现在是**真实列**（不再是 CAST(NULL)）
SELECT COLUMN_NAME AS view_col, COLUMN_TYPE AS type,
       CASE WHEN COLUMN_TYPE LIKE '%binary%' THEN '✗ 被推断成 BINARY（前端会拿到字节串）'
            WHEN COLUMN_NAME = 'order_id' THEN '✓ order_id 是真实列（不是 CAST(NULL)）'
            ELSE '（对照列）' END AS verdict
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'v_biz_status_timeline'
   AND COLUMN_NAME IN ('biz_type','order_id','delivery_sn');

-- 若本库还没建视图（migrate-ledger-view.sql 未执行 / 纯结构副本），这里降级成一句提示，
-- 不让整个 verify 因为 1146 中断（本文件必须能在任何库安全重跑）。
SET @has_view := (SELECT COUNT(*) FROM information_schema.VIEWS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'v_biz_status_timeline');
SET @s := IF(@has_view = 0,
  'SELECT ''— 本库未建 v_biz_status_timeline（先跑 sql/migrate-ledger-view.sql）'' AS verdict',
  'SELECT COUNT(*) AS delivery_rows, SUM(order_id IS NOT NULL) AS delivery_rows_with_order_id, IF(SUM(order_id IS NOT NULL) > 0, ''✓ 视图 DELIVERY 分支能读到真实 order_id（可按订单反查包裹流水）'', ''— 本库该分支暂无带 order_id 的行'') AS verdict FROM `v_biz_status_timeline` WHERE biz_type = ''DELIVERY''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
