#!/usr/bin/env bash
#
# 把测试库 qiqi_mall_test 建出来并灌入测试种子数据。
#
#   ./scripts/init-test-db.sh
#
# 幂等策略（可反复重跑）：
#   - 01-schema.sql 自带 CREATE DATABASE IF NOT EXISTS + 逐表 DROP TABLE IF EXISTS → 每次全新建表
#   - 02-seed.sql 是 fetch-seed-data.mjs 生成的，内部 SET FOREIGN_KEY_CHECKS=0 + 逐表 TRUNCATE →
#     每次清空重灌，固定 id 插入（不含自增漂移）
#   所以重跑的语义是「测试库整体重置」，dev 库 qiqi_mall 完全不受影响。
#
# 依赖：qiqi-mysql 容器在跑（docker compose up -d mysql），本地有 docker CLI。
# 种子文件缺失时会提示先生成：node scripts/fetch-seed-data.mjs
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"
DB_NAME="qiqi_mall_test"
SCHEMA="$REPO_DIR/sql/testdb/01-schema.sql"
SEED="$REPO_DIR/sql/testdb/02-seed.sql"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi

# MySQL 客户端放容器里跑；密码走 MYSQL_PWD，避免命令行明文告警。
# --default-character-set=utf8mb4 必须显式指定：容器里 mysql 客户端默认字符集是 latin1，
# 不加的话 UTF-8 的中文会被二次编码（存进库就是「苹果」→「è‹¹æžœ」这种乱码）。
mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$@"
}

echo "▶ 建表：sql/testdb/01-schema.sql → $DB_NAME"
mysql_exec < "$SCHEMA"

if [[ -f "$SEED" ]]; then
  echo "▶ 灌种子：sql/testdb/02-seed.sql"
  mysql_exec < "$SEED"
else
  echo "! 没找到 $SEED"
  echo "  先跑：node scripts/fetch-seed-data.mjs （会拉 DummyJSON 数据并把图片传进 MinIO）"
  echo "  本次只建了空表结构。"
fi

echo
echo "▶ 校验 $DB_NAME 关键表行数"
mysql_exec -t -e "USE $DB_NAME;
SELECT 'category' t, COUNT(*) c FROM category
UNION ALL SELECT 'product', COUNT(*) FROM product
UNION ALL SELECT 'sku', COUNT(*) FROM sku
UNION ALL SELECT 'sku_spec', COUNT(*) FROM sku_spec
UNION ALL SELECT 'product_gallery', COUNT(*) FROM product_gallery
UNION ALL SELECT 'inventory', COUNT(*) FROM inventory
UNION ALL SELECT 'warehouse', COUNT(*) FROM warehouse
UNION ALL SELECT 'member', COUNT(*) FROM member
UNION ALL SELECT 'admin', COUNT(*) FROM admin
UNION ALL SELECT 'order', COUNT(*) FROM \`order\`;"

echo
echo "▶ 隔离确认：dev 库 qiqi_mall 未被改动"
mysql_exec -t -e "SELECT 'qiqi_mall.product' t, COUNT(*) c FROM qiqi_mall.product
UNION ALL SELECT 'qiqi_mall.order', COUNT(*) FROM qiqi_mall.\`order\`;"

echo
echo "✓ 完成。容器栈（server-1/server-2，库名由 MYSQL_DATABASE 注入）连的就是 $DB_NAME。"
echo "  若刚重建过 server 容器，记得：docker compose restart gateway（否则网关缓存旧 IP 会 502）"
