-- AI 接口访问密钥（API Key）体系
--
-- 背景：AI（MCP / 运营助手）此前用「管理员账号 + JWT 登录」的方式调后端接口，问题有三：
--   1) 等于把一个人的登录态交给了程序，权限无法单独收紧（拿到就是全后台权限）；
--   2) 无法审计到底是「谁」在调，last_used_at 也没地方记；
--   3) 想吊销必须改密码，代价过高。
--
-- 现在：管理页面（人用 JWT 操作）建 key → 配 scopes → 设过期时间 → 随时删。
-- 后端每次请求校验：key 存在？status=1？未过期？本次接口所需 scope 在 scopes 里？
-- 删记录 = 立即吊销（校验直接查库/缓存失效，不需要黑名单）。
--
-- 明文不落库：库里只存 sha256(明文) 与「明文前 8 位」用于列表展示。
-- 幂等：CREATE TABLE IF NOT EXISTS，重跑不会报错。
-- 执行：docker exec -i -e MYSQL_PWD=$MYSQL_ROOT_PASSWORD qiqi-mysql mysql --default-character-set=utf8mb4 -uroot < sql/migrate-ai-api-key.sql

-- ---------- dev 库 qiqi_mall ----------
CREATE TABLE IF NOT EXISTS qiqi_mall.ai_api_key (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `name`         VARCHAR(64)  NOT NULL COMMENT '用途名称，如 运营助手-只读',
  `key_prefix`   VARCHAR(16)  NOT NULL COMMENT '明文前 8 位，列表展示用',
  `key_hash`     CHAR(64)     NOT NULL COMMENT 'sha256(明文)，明文不落库',
  `scopes`       VARCHAR(512) NOT NULL COMMENT '逗号分隔，如 order:read,catalog:read',
  `expires_at`   DATETIME     NULL     COMMENT 'NULL = 永不过期',
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `last_used_at` DATETIME     NULL,
  `created_by`   VARCHAR(64)  NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_hash` (`key_hash`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 接口访问密钥';

-- 测试库（本地 qiqi_mall_test 若存在也一并建好；不存在时下面这条会跳过，不影响 dev）
-- 说明：qiqi_mall_test 由 scripts/init-test-db.sh 建库，这里只补表。
CREATE TABLE IF NOT EXISTS qiqi_mall_test.ai_api_key LIKE qiqi_mall.ai_api_key;
