#!/usr/bin/env bash
#
# 把测试库 qiqi_mall_test 建出来并灌入测试种子数据。
#
#   ./scripts/init-test-db.sh
#
# 结构从哪来：Flyway（qiqi-mall-server/src/main/resources/db/migration/V*.sql）
#   以前是 `mysql < sql/testdb/01-schema.sql` —— 那份 DDL 是手抄的第二份副本，新加表忘同步
#   就会缺表（今晚 qiqi_mall_test 缺 3 张 channel 表即此类）。01-schema.sql 已下线到
#   sql/_rollback/。
#
# 幂等策略（可反复重跑）：
#   1) DROP DATABASE + CREATE DATABASE ⇒ 空库（注意是整库删：只删表会留下
#      flyway_schema_history，Flyway 就认为 V1 已应用、什么都不做，库是空的）
#   2) flyway migrate ⇒ 空库上全量执行 V1（39 表 + 1 视图 + channel_shop 3 行参考数据）
#   3) 02-seed.sql 由 fetch-seed-data.mjs 生成，内部 SET FOREIGN_KEY_CHECKS=0 + 逐表 TRUNCATE
#      + 固定 id 重灌（不含自增漂移）⇒ 每次清空重灌
#   4) 收尾调用 scripts/set-admin-password.sh 覆盖 admin 口令：种子里的 admin 哈希是演示
#      弱口令，不覆盖就等于把后台口令打回弱口令（安全回退）。口令只从 ADMIN_PASSWORD 或
#      /root/.qiqi-secrets/admin.txt 读，脚本里没有任何默认值；该步骤失败会让本脚本失败。
#   所以重跑的语义是「测试库整体重置」，dev 库 qiqi_mall 完全不受影响。
#
# 依赖：qiqi-mysql 容器在跑（docker compose up -d mysql），本地有 docker CLI + mvn。
#       种子文件缺失时会提示先生成：node scripts/fetch-seed-data.mjs
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
DB_NAME="qiqi_mall_test"
SEED="$REPO_DIR/sql/testdb/02-seed.sql"
SERVER_POM="$REPO_DIR/qiqi-mall-server/pom.xml"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi

# 预检（必须在动库之前）：admin 口令拿得到才允许开始灌种子。
#   否则会出现「种子已灌完、收尾步骤才失败」——库里就停在种子弱口令上，
#   而那正是要堵的安全回退。所以这里先探一次，拿不到就一步都不动。
ADMIN_SECRET_FILE="${ADMIN_SECRET_FILE:-/root/.qiqi-secrets/admin.txt}"
if [ -z "${ADMIN_PASSWORD:-}" ] && [ ! -s "$ADMIN_SECRET_FILE" ]; then
  echo "❌ 缺少 admin 密码：设 ADMIN_PASSWORD，或写入 $ADMIN_SECRET_FILE（chmod 600）" >&2
  echo "   预检失败，未改动任何数据（避免灌完种子却停在弱口令上）" >&2
  exit 1
fi

# MySQL 客户端放容器里跑；密码走 MYSQL_PWD，避免命令行明文告警。
# --default-character-set=utf8mb4 必须显式指定：容器里 mysql 客户端默认字符集是 latin1，
# 不加的话 UTF-8 的中文会被二次编码（存进库就是「苹果」→「è‹¹æžœ」这种乱码）。
mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$@"
}

echo "▶ 重建 $DB_NAME：DROP DATABASE → CREATE DATABASE（整库删，含 flyway_schema_history）"
mysql_exec -e "DROP DATABASE IF EXISTS \`$DB_NAME\`;
               CREATE DATABASE \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

echo "▶ 建表：Flyway（db/migration/V*.sql）→ $DB_NAME"
mvn -B -q -f "$SERVER_POM" flyway:migrate \
  -Dflyway.url="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${DB_NAME}?characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
  -Dflyway.user="$DB_USER" \
  -Dflyway.password="$DB_PASS" \
  || { echo "✗ flyway migrate 失败" >&2; exit 1; }

# 结构断言：缺表要在这里炸，别等 e2e/演示跑到一半
verdict="$(mysql_exec -N -e "
  SELECT CONCAT(
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME<>'flyway_schema_history'), ',',
    (SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA='$DB_NAME'), ',',
    (SELECT COUNT(*) FROM \`$DB_NAME\`.flyway_schema_history WHERE version='1' AND success=1), ',',
    (SELECT COUNT(*) FROM \`$DB_NAME\`.channel_shop));")"
IFS=',' read -r n_tab n_view n_hist n_shop <<< "$verdict"
if [[ "$n_tab" != "39" || "$n_view" != "1" || "$n_hist" != "1" || "$n_shop" != "3" ]]; then
  echo "✗ 结构校验失败：业务表=$n_tab（应 39）视图=$n_view（应 1）V1记录=$n_hist（应 1）channel_shop=$n_shop（应 3）" >&2
  exit 1
fi
echo "  ✓ 结构就绪：39 表 + 1 视图（V1 全量执行），channel_shop 3 行"

if [[ -f "$SEED" ]]; then
  echo "▶ 灌种子：sql/testdb/02-seed.sql → $DB_NAME"
  # 库名一律由本脚本显式传给 mysql（`mysql <库名> < 文件`）。
  # 02-seed.sql 里**不再有任何 USE / 建库语句** —— 2026-11-13 有人用改了库名的副本去重置别的库，
  # seed 里写死的 `USE qiqi_mall_test` 把种子灌进了真库、还 TRUNCATE 了它。所以规矩是：
  # 文件里不写库名，调用方必须传库名。（同一条规矩见 sql/_rollback/README.txt）
  mysql_exec "$DB_NAME" < "$SEED"
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

# 安全收尾（必须最后一步，且失败即整体失败）：
#   02-seed.sql 里 admin 的口令哈希是固定的演示弱口令。灌完种子不覆盖的话，
#   这次复位就等于把后台 admin 口令打回了弱口令 —— 这就是要堵的回退。
#   口令只能来自 ADMIN_PASSWORD 或 /root/.qiqi-secrets/admin.txt（脚本里没有默认值），
#   set-admin-password.sh 自己幂等（已是目标口令就不写库），所以重复跑本脚本不会漂。
#   放在 init-test-db.sh（而不是只放 reset-test-db.sh）：这是「重灌测试库」的唯一入口，
#   直接跑本脚本的人也受保护；reset-test-db.sh 复用本脚本，自然一起被覆盖。
#   e2e 独立库走的是 reset-e2e-db.sh（不经过本脚本），仍然保持种子里的演示弱口令。
echo
echo "▶ 用密钥文件重设 admin 口令（覆盖种子弱口令）"
"$REPO_DIR/scripts/set-admin-password.sh"
echo "✅ 已用密钥文件重设 admin 密码（不回退到种子弱密码）"

echo
echo "✓ 完成。容器栈（server-1/server-2，库名由 MYSQL_DATABASE 注入）连的就是 $DB_NAME。"
echo "  若刚重建过 server 容器，记得：docker compose restart gateway（否则网关缓存旧 IP 会 502）"
