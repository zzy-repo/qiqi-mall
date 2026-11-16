-- ============================================================================
-- V5 · 让「标」能进条件：value_type 加 LIST，op_code 加 HAS / NOT_HAS
-- ============================================================================
-- ⚠️ 一旦落库就不可再改（Flyway checksum）；V1~V4 一个字符都不动。
--
-- 为什么做（期 2 步骤 1 需求原话）
-- ------------------------------
--   ④ 标是策略之间的传送带：策略的条件要能引用标。第一个检测点命中 → 打标；
--     第二个检测点读标 → 决定动作。
--     **没有这一条，管线不成立，标永远只是装饰。**
--
--   期 1 / 1.5 已经把标做成了一等公民（audit_tag_catalog 两档 + 五种结论自动落标 + 结论标互斥 +
--   队列 ?tag=）。但那是"落标"和"看标"，**没有任何一条策略能读标**：标写得再齐，
--   第二个检测点也没法据它做决定。本迁移只补数据层的取值域，引擎侧（ValueType/OpCode/
--   FieldRegistry/ConditionEvaluator）在同一次变更里一起改。
--
-- 这是一次**纯取值域扩容**：不加表、不加列、不动一行数据
-- --------------------------------------------------
--   · 表数保持 41（scripts/reset-e2e-db.sh / init-test-db.sh 的 EXPECT_TABLES 不用改）；
--   · `value_type` 的 ENUM 加一个 'LIST'；
--   · `ck_audit_policy_condition_value` 这条 CHECK 多认一种合法组合（LIST ↔ value_text）。
--   · `op_code` 是 varchar(24) 且 DDL 刻意不设闭集（闭集在 OpCode 里，见 V1 注释），
--     所以 HAS / NOT_HAS **不需要改结构** —— 只把列注释补上，让读 DDL 的人知道它们从哪来。
--   ⚠️ 但 CHECK 必须跟着改：老 CHECK 只认 NUM/BOOL/TEXT/JSON 四种组合，
--     一条 LIST 条件写进库会被它当场拒掉（报的是 MySQL 约束错误，没人看得懂）。
--
-- 为什么 LIST 值落 value_text（而不是 value_json）
-- ---------------------------------------------
--   集合在**字段侧**（「该单当前挂着哪些标」是一个集合），条件侧问的是"集合里有没有**这一个**元素"：
--     ACTIVE_TAGS HAS 'VIP_CUSTOMER'
--   所以一条条件只带**一个元素码**，正好是 value_text 的形状。value_json 是"一个条件带多个候选值"
--   的预留位置（IN 列表 / 区间），与「集合包含」不是一回事 —— 混用会让同一列有两种语义。
--
-- 为什么必须"集合语义"而不是字符串包含（build 时最容易做错的地方）
-- --------------------------------------------------------------
--   标码是 FROZEN / FROZEN_RETURN_APPLY 这种"前缀 + 细分"的命名。若拿逗号串 + CONTAINS 判，
--   `FROZEN` 会命中 `FROZEN_RETURN_APPLY` —— 一条"有没有普通冻结标"的条件会命中所有细分冻结标。
--   这类错判不报错、不留痕，只会让策略静默地多拦/少拦一批单。所以 LIST 只允许 HAS / NOT_HAS
--   （元素相等），库里也不存逗号串。
--
-- 本文件幂等：可重复执行（Flyway 正常只跑一次，这里保证手工重放/多环境安全）
-- ------------------------------------------------------------------------------
--   · 改 ENUM 前查 information_schema.COLUMNS.COLUMN_TYPE 是否已含 LIST，已含则 DO 0；
--   · 改 CHECK 前查 CHECK_CLAUSE 是否已含 LIST，已含则两条语句都 DO 0；
--   · 全程没有一句写业务数据表（audit_policy_condition 的行一个字节都不动）。
--   实测：整份重跑 0 行受影响。
-- ============================================================================

SET @db := DATABASE();

-- ---------------------------------------------------------------------------
-- 一、value_type：ENUM('NUM','TEXT','BOOL','JSON') → 末尾加 'LIST'
-- ---------------------------------------------------------------------------
-- 顺序必须与 ValueType.java 逐字一致（名字一旦漂移，读出来的条件就和配置的不是同一条）。
-- 追加在**末尾**：MySQL 的 ENUM 是按下标存的，插在中间会改变已有值的比较/排序行为。
SET @need_enum := (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'audit_policy_condition'
                     AND COLUMN_NAME = 'value_type' AND COLUMN_TYPE NOT LIKE '%LIST%');
SET @sql := IF(@need_enum = 1,
  'ALTER TABLE `audit_policy_condition`
     MODIFY COLUMN `value_type` enum(''NUM'',''TEXT'',''BOOL'',''JSON'',''LIST'') NOT NULL
       COMMENT ''值的类型（决定读哪个 value_* 列）；LIST = 多值集合（条件值落 value_text 的一个元素码）''',
  'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 二、ck_audit_policy_condition_value：多认一种合法组合（LIST ↔ value_text）
-- ---------------------------------------------------------------------------
-- 老定义只列了 4 种组合，一条 LIST 条件会被它拒掉。新定义 = 老定义 + 一个 LIST 分支：
--   (value_type='LIST') and value_text is not null and value_num is null and value_json is null
-- 「全 null」那个分支（第一段）保持原样不动：它表达的是"这条条件的值为空"，
-- 与类型无关；值完整性由 OpCode/ConditionEvaluator 在保存策略时把关（含 HAS 必须带标码）。
SET @need_check := (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS k
                    JOIN information_schema.TABLE_CONSTRAINTS c
                      ON c.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     AND c.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                    WHERE k.CONSTRAINT_SCHEMA = @db
                      AND c.TABLE_NAME = 'audit_policy_condition'
                      AND k.CONSTRAINT_NAME = 'ck_audit_policy_condition_value'
                      AND k.CHECK_CLAUSE LIKE '%LIST%');

SET @sql := IF(@need_check = 0,
  'ALTER TABLE `audit_policy_condition` DROP CHECK `ck_audit_policy_condition_value`',
  'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql := IF(@need_check = 0,
  'ALTER TABLE `audit_policy_condition` ADD CONSTRAINT `ck_audit_policy_condition_value` CHECK (
     ((`value_num` is null) and (`value_text` is null) and (`value_json` is null))
     or ((`value_type` = ''NUM'')  and (`value_num` is not null) and (`value_text` is null) and (`value_json` is null))
     or ((`value_type` = ''BOOL'') and (`value_num` is not null) and (`value_text` is null) and (`value_json` is null))
     or ((`value_type` = ''TEXT'') and (`value_text` is not null) and (`value_num` is null) and (`value_json` is null))
     or ((`value_type` = ''JSON'') and (`value_json` is not null) and (`value_num` is null) and (`value_text` is null))
     or ((`value_type` = ''LIST'') and (`value_text` is not null) and (`value_num` is null) and (`value_json` is null))
   )',
  'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 三、op_code 列注释：补上 HAS / NOT_HAS（**不改类型**，闭集在 OpCode.java 里）
-- ---------------------------------------------------------------------------
-- 为什么只改注释：op_code 是 varchar(24) 且刻意没有 CHECK 闭集（V1 注释：
-- 闭集钉进 DDL 的话，加一个操作符就要跑结构性迁移，而 CHECK 违规会回滚整个业务事务）。
-- 但列注释里那句"由 OperationRegistry 校验"早已不存在这个类，顺手改成 OpCode，
-- 否则下一个读 DDL 的人会去找一个找不到的类。
SET @need_cmt := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'audit_policy_condition'
                    AND COLUMN_NAME = 'op_code' AND COLUMN_COMMENT NOT LIKE '%HAS%');
SET @sql := IF(@need_cmt = 1,
  'ALTER TABLE `audit_policy_condition`
     MODIFY COLUMN `op_code` varchar(24) NOT NULL
       COMMENT ''操作符码；闭集在 OpCode（EQ/NE/LT/LTE/GT/GTE/CONTAINS/NOT_CONTAINS/HAS/NOT_HAS/EXISTS/NOT_EXISTS），DDL 刻意不设 CHECK''',
  'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 四、迁移自检（只读，输出在 flyway 日志里）
-- ---------------------------------------------------------------------------
-- ① value_type 的取值域（必须看到 LIST）
SELECT 'V5 自检·value_type 取值域' AS step, COLUMN_TYPE, IS_NULLABLE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'audit_policy_condition' AND COLUMN_NAME = 'value_type';

-- ② value 这条 CHECK 的定义（必须含 LIST 分支；其余三段与旧定义逐字一致）
SELECT 'V5 自检·value CHECK' AS step, k.CONSTRAINT_NAME, k.CHECK_CLAUSE
  FROM information_schema.CHECK_CONSTRAINTS k
  JOIN information_schema.TABLE_CONSTRAINTS c
    ON c.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND c.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
 WHERE k.CONSTRAINT_SCHEMA = @db AND c.TABLE_NAME = 'audit_policy_condition'
   AND k.CONSTRAINT_NAME = 'ck_audit_policy_condition_value';

-- ③ 存量不变自检：条件行数 + 按 value_type 的分布（本迁移不写任何业务行，数字必须与执行前逐位相同）
SELECT 'V5 自检·audit_policy_condition 存量（迁移前后必须一致）' AS step,
       COUNT(*) AS rows_cnt
  FROM `audit_policy_condition`;

SELECT 'V5 自检·按 value_type 分布' AS step, `value_type`, COUNT(*) AS rows_cnt
  FROM `audit_policy_condition`
 GROUP BY `value_type`
 ORDER BY `value_type`;
