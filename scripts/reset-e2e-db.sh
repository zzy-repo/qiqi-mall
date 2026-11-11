#!/usr/bin/env bash
#
# 重置 e2e 专用库（每个 Playwright shard 一套），可反复重跑。
#
#   ./scripts/reset-e2e-db.sh qiqi_mall_e2e_a     # 重置单个库
#   ./scripts/reset-e2e-db.sh --all               # 重置 qiqi_mall_e2e_a / _b
#
# 为什么不复制一份 DDL：sql/testdb/01-schema.sql 与 02-seed.sql 是唯一事实来源，
# 这里只把库名 qiqi_mall_test 替换成目标库名再灌（下面的 sed）。改表结构只改那份。
#
# 幂等：schema 自带 CREATE DATABASE IF NOT EXISTS + 逐表 DROP TABLE IF EXISTS，
#       seed 自带 SET FOREIGN_KEY_CHECKS=0 + 逐表 TRUNCATE → 重跑 = 整库重置。
#       跑完打印关键表行数，两次跑出来的数字必须一致。
#
# 踩坑：容器里的 mysql 客户端默认字符集是 latin1，必须显式 --default-character-set=utf8mb4，
#      否则中文会被二次编码成「苹果 → è‹¹æžœ」。
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"
SCHEMA="$REPO_DIR/sql/testdb/01-schema.sql"
SEED="$REPO_DIR/sql/testdb/02-seed.sql"
SOURCE_DB="qiqi_mall_test"
SHARDS=("qiqi_mall_e2e_a" "qiqi_mall_e2e_b")

if [[ "${1:-}" == "--all" ]]; then
  TARGETS=("${SHARDS[@]}")
elif [[ -n "${1:-}" ]]; then
  TARGETS=("$1")
else
  echo "用法：$0 <库名> | --all" >&2
  echo "可选库名：${SHARDS[*]}" >&2
  exit 1
fi

# 只允许重置 e2e 库：dev 库、测试库、生产库一律拒绝，避免误伤
for db in "${TARGETS[@]}"; do
  if [[ "$db" != qiqi_mall_e2e_* ]]; then
    echo "✗ 拒绝重置「$db」：本脚本只处理 qiqi_mall_e2e_* 开头的库" >&2
    exit 1
  fi
done

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi
if [[ ! -f "$SCHEMA" ]]; then
  echo "✗ 找不到 $SCHEMA" >&2
  exit 1
fi
if [[ ! -f "$SEED" ]]; then
  echo "✗ 找不到 $SEED（先跑 node scripts/fetch-seed-data.mjs 生成测试种子）" >&2
  exit 1
fi

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$@"
}

for DB in "${TARGETS[@]}"; do
  echo "▶ 重置 $DB"
  # 库名替换：schema/seed 里写的是 qiqi_mall_test，换成目标库
  sed "s/\b${SOURCE_DB}\b/${DB}/g" "$SCHEMA" | mysql_exec
  sed "s/\b${SOURCE_DB}\b/${DB}/g" "$SEED" | mysql_exec

  echo "▶ $DB 行数摘要"
  mysql_exec -t -e "USE ${DB};
    SELECT 'warehouse' t, COUNT(*) c FROM warehouse
    UNION ALL SELECT 'category', COUNT(*) FROM category
    UNION ALL SELECT 'product_type', COUNT(*) FROM product_type
    UNION ALL SELECT 'attribute', COUNT(*) FROM attribute
    UNION ALL SELECT 'product', COUNT(*) FROM product
    UNION ALL SELECT 'sku', COUNT(*) FROM sku
    UNION ALL SELECT 'inventory', COUNT(*) FROM inventory
    UNION ALL SELECT 'order', COUNT(*) FROM \`order\`
    UNION ALL SELECT 'delivery_order', COUNT(*) FROM delivery_order
    UNION ALL SELECT 'member', COUNT(*) FROM member
    UNION ALL SELECT 'admin', COUNT(*) FROM admin
    UNION ALL SELECT 'order_setting', COUNT(*) FROM order_setting;"
  # 「好看的数据」校验：重置后不允许任何 e2e 残留（测试账号/订单/商品都只活在运行期）
  residue="$(mysql_exec -N -e "USE ${DB};
    SELECT CONCAT(
      (SELECT COUNT(*) FROM member WHERE username LIKE 'e2e\\_%'), ',',
      (SELECT COUNT(*) FROM product WHERE name LIKE '%E2E%' OR name LIKE '%e2e%'), ',',
      (SELECT COUNT(*) FROM admin WHERE username LIKE 'e2e\\_%'), ',',
      (SELECT COUNT(*) FROM warehouse WHERE name LIKE 'E2E%'))")"
  IFS=',' read -r r_member r_product r_admin r_wh <<< "$residue"
  if [[ "$r_member$r_product$r_admin$r_wh" != "0000" ]]; then
    echo "❌ $DB 仍有 e2e 残留：member=$r_member product=$r_product admin=$r_admin warehouse=$r_wh" >&2
    exit 1
  fi
  echo "✓ $DB 干净：无 e2e 账号/商品/管理员/仓库残留（只剩种子数据）"
  echo
done

echo "✅ 完成：${TARGETS[*]}"
