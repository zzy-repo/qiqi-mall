-- 乐观锁 version —— 5 张最容易发生 lost update 的热表
--
-- 背景（已核实的真实风险）：两个人同时编辑同一个商品，后保存的完整覆盖前保存的，
-- 双方的修改都丢，且谁都不知道。product / sku / order / delivery_order / member
-- 原本既没有 version 也没有 update_time，`PUT /admin/products/{id}` 就是
-- `productService.updateById(product)` —— 纯 last-write-wins。
--
-- 现在：这 5 张表加 `version INT NOT NULL DEFAULT 0`，实体加 @Version（MyBatis-Plus
-- OptimisticLockerInnerInterceptor 生成 `UPDATE ... SET version = version + 1
-- WHERE id = ? AND version = ?`），后端 5 个 update 入口 fail-closed：
--   缺 version          → code=400「缺少版本号，请刷新页面后重试」
--   影响行数 0（被抢改） → code=409「数据已被他人修改（可能其他人在编辑），请刷新后重试」
-- 绝不静默降级成「无版本校验」——那等于没加。
--
-- 幂等：MySQL（8.0 / 8.4）不支持 `ADD COLUMN IF NOT EXISTS`，所以先查 information_schema
-- 判断列是否存在，再动态拼 ALTER；整个判断包在存储过程里，重跑第二次全部命中「已存在」→ 跳过，
-- 不报错、不动数据。库不存在（如某台机器没有 e2e 分片库）也静默跳过。
--
-- 执行：docker exec -i -e MYSQL_PWD=$MYSQL_ROOT_PASSWORD qiqi-mysql \
--         mysql --default-character-set=utf8mb4 -uroot < sql/migrate-optimistic-lock.sql
-- 说明：本文件用了 mysql 客户端的 DELIMITER 指令，必须用 mysql 客户端执行（不能走 JDBC/DataGrip 的单语句模式）。

-- 存储过程需要「有默认库」才能创建（否则 ERROR 1046 No database selected）；
-- qiqi_mall 是主库，正常一定存在，这里仍显式建一次，保证脚本在任何一台机器上都能跑。
CREATE DATABASE IF NOT EXISTS qiqi_mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE qiqi_mall;

DROP PROCEDURE IF EXISTS qiqi_add_version_column;

DELIMITER $$

CREATE PROCEDURE qiqi_add_version_column(IN db_name VARCHAR(64), IN tbl_name VARCHAR(64))
BEGIN
  DECLARE db_exists INT DEFAULT 0;
  DECLARE col_exists INT DEFAULT 0;

  SELECT COUNT(*) INTO db_exists
    FROM information_schema.SCHEMATA
   WHERE SCHEMA_NAME = db_name;

  IF db_exists > 0 THEN
    SELECT COUNT(*) INTO col_exists
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = db_name
       AND TABLE_NAME = tbl_name
       AND COLUMN_NAME = 'version';

    IF col_exists = 0 THEN
      SET @ddl = CONCAT('ALTER TABLE `', db_name, '`.`', tbl_name,
                        '` ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号''');
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
    END IF;
  END IF;
END$$

DELIMITER ;

-- ---------- dev 库 qiqi_mall（本地 mvn test 也连这个库）----------
CALL qiqi_add_version_column('qiqi_mall', 'product');
CALL qiqi_add_version_column('qiqi_mall', 'sku');
CALL qiqi_add_version_column('qiqi_mall', 'order');
CALL qiqi_add_version_column('qiqi_mall', 'delivery_order');
CALL qiqi_add_version_column('qiqi_mall', 'member');

-- ---------- 容器栈测试库 qiqi_mall_test（18080）----------
CALL qiqi_add_version_column('qiqi_mall_test', 'product');
CALL qiqi_add_version_column('qiqi_mall_test', 'sku');
CALL qiqi_add_version_column('qiqi_mall_test', 'order');
CALL qiqi_add_version_column('qiqi_mall_test', 'delivery_order');
CALL qiqi_add_version_column('qiqi_mall_test', 'member');

-- ---------- e2e 分片库（Playwright 每个 worker 一套；库不存在时上面的 EXIT 分支会跳过）----------
CALL qiqi_add_version_column('qiqi_mall_e2e_a', 'product');
CALL qiqi_add_version_column('qiqi_mall_e2e_a', 'sku');
CALL qiqi_add_version_column('qiqi_mall_e2e_a', 'order');
CALL qiqi_add_version_column('qiqi_mall_e2e_a', 'delivery_order');
CALL qiqi_add_version_column('qiqi_mall_e2e_a', 'member');

CALL qiqi_add_version_column('qiqi_mall_e2e_b', 'product');
CALL qiqi_add_version_column('qiqi_mall_e2e_b', 'sku');
CALL qiqi_add_version_column('qiqi_mall_e2e_b', 'order');
CALL qiqi_add_version_column('qiqi_mall_e2e_b', 'delivery_order');
CALL qiqi_add_version_column('qiqi_mall_e2e_b', 'member');

CALL qiqi_add_version_column('qiqi_mall_e2e_c', 'product');
CALL qiqi_add_version_column('qiqi_mall_e2e_c', 'sku');
CALL qiqi_add_version_column('qiqi_mall_e2e_c', 'order');
CALL qiqi_add_version_column('qiqi_mall_e2e_c', 'delivery_order');
CALL qiqi_add_version_column('qiqi_mall_e2e_c', 'member');

CALL qiqi_add_version_column('qiqi_mall_e2e_d', 'product');
CALL qiqi_add_version_column('qiqi_mall_e2e_d', 'sku');
CALL qiqi_add_version_column('qiqi_mall_e2e_d', 'order');
CALL qiqi_add_version_column('qiqi_mall_e2e_d', 'delivery_order');
CALL qiqi_add_version_column('qiqi_mall_e2e_d', 'member');

DROP PROCEDURE qiqi_add_version_column;

-- ---------- 自检（重跑时也应全部为 1）----------
SELECT TABLE_SCHEMA AS db, TABLE_NAME AS tbl, COLUMN_NAME AS col, COLUMN_DEFAULT AS dflt, IS_NULLABLE AS nullable
  FROM information_schema.COLUMNS
 WHERE COLUMN_NAME = 'version'
   AND TABLE_NAME IN ('product', 'sku', 'order', 'delivery_order', 'member')
   AND TABLE_SCHEMA IN ('qiqi_mall', 'qiqi_mall_test',
                        'qiqi_mall_e2e_a', 'qiqi_mall_e2e_b', 'qiqi_mall_e2e_c', 'qiqi_mall_e2e_d')
 ORDER BY TABLE_SCHEMA, TABLE_NAME;
