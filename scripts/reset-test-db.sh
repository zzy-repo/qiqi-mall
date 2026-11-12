#!/usr/bin/env bash
#
# 把测试库 qiqi_mall_test 复位回种子状态（录演示视频之后专用）。
#
#   ./scripts/reset-test-db.sh
#   npm run reset:test          # e2e/ 目录下，等价
#
# 为什么需要它：
#   录演示视频（`npm run demo`）走的是容器栈 5174/5176/18080 → 库 qiqi_mall_test，
#   而 demo 用的和验收用的是**同一批 spec**，spec 会造数据（注册会员、下单、改库存…）。
#   录完片子，测试环境就脏了：多出一堆 e2e_xxx 账号、演示订单、被改过的库存。
#   以前只能靠手工清，或者在录片前「先清再用」——都不对。有了这个脚本就是：
#
#       录片（打容器栈）→ npm run reset:test → 测试环境恢复干净
#
#   即「先录、后复位」，而不是「先清空、再录」。
#
# 复用什么：
#   - 重置逻辑不重复实现，直接交给 scripts/init-test-db.sh（它本身就是幂等重置：
#     sql/testdb/01-schema.sql 里逐表 DROP TABLE IF EXISTS，02-seed.sql 里逐表 TRUNCATE
#     再固定 id 重灌，所以重跑 = 整体复位，不会漂移）。
#   - 字符集坑同样交给 init-test-db.sh 处理：容器里的 mysql 客户端默认 latin1，
#     必须显式 --default-character-set=utf8mb4，否则中文会被二次编码成乱码。
#   - admin 口令也交给 init-test-db.sh 的收尾步骤（scripts/set-admin-password.sh）：
#     种子里的 admin 哈希是演示弱口令，复位后必须用 /root/.qiqi-secrets/admin.txt 覆盖，
#     否则「复位一次就把后台口令打回弱口令」。那一步失败时本脚本会失败退出（不再静默）。
#
# 安全边界：
#   - 只碰 qiqi_mall_test，绝不碰 dev 库 qiqi_mall（init-test-db.sh 只 USE 测试库；
#     本脚本最后还会把 dev 库的行数打出来做对照）。
#   - 不碰 qiqi_mall_e2e_a / _b（那是 e2e 独立库，用 scripts/reset-e2e-db.sh）。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"
DB_NAME="qiqi_mall_test"

# 只允许操作测试库，防止改错库名把 dev/生产清了
if [[ "$DB_NAME" != "qiqi_mall_test" ]]; then
  echo "✗ 本脚本只处理 qiqi_mall_test（当前写成 $DB_NAME），拒绝执行" >&2
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$@"
}

snapshot() {
  mysql_exec -N -e "SELECT CONCAT(
      (SELECT COUNT(*) FROM $DB_NAME.member),     '/',
      (SELECT COUNT(*) FROM $DB_NAME.\`order\`),    '/',
      (SELECT COUNT(*) FROM $DB_NAME.product),    '/',
      (SELECT COUNT(*) FROM $DB_NAME.sku),        '/',
      (SELECT COUNT(*) FROM $DB_NAME.inventory));" 2>/dev/null
}

echo "▶ 复位前 $DB_NAME（member/order/product/sku/inventory）：$(snapshot)"

# 真正干活：复用 init-test-db.sh（幂等重置 + 固定 id 重灌种子
# + 末尾用密钥文件覆盖 admin 口令 —— 那一步也在这里，因为它是本脚本唯一的重灌入口）
# 注意：保留 init-test-db.sh 的退出码。以前是 `| grep ... || true`，会把「admin 口令重设失败」
# 也一起吞掉，脚本照样报「✅ 已复位」—— 那正好是要堵的安全回退（库里还是种子弱口令）。
init_log="$(mktemp)"
set +e
"$REPO_DIR/scripts/init-test-db.sh" 2>&1 | tee "$init_log" | grep -E '^▶|^✓|^!|^✅|^已更新'
init_rc="${PIPESTATUS[0]}"
set -e
if [[ "$init_rc" -ne 0 ]]; then
  echo >&2
  echo "✗ init-test-db.sh 退出码 $init_rc：复位没跑完，$DB_NAME 可能还停在种子弱口令上" >&2
  echo "  完整输出：" >&2
  grep -E '^✗|^❌|^!' "$init_log" >&2 || cat "$init_log" >&2
  rm -f "$init_log"
  exit 1
fi
rm -f "$init_log"

echo
echo "▶ 复位后 $DB_NAME（member/order/product/sku/inventory）：$(snapshot)"

# 录片残留自检：这些都是 demo/e2e 才会产生的数据，复位后应当为 0
residue="$(mysql_exec -N -e "SELECT CONCAT(
    (SELECT COUNT(*) FROM $DB_NAME.member WHERE username LIKE 'e2e\\_%'), ',',
    (SELECT COUNT(*) FROM $DB_NAME.admin  WHERE username LIKE 'e2e\\_%'), ',',
    (SELECT COUNT(*) FROM $DB_NAME.product WHERE name LIKE '%E2E%' OR name LIKE '%e2e%'), ',',
    (SELECT COUNT(*) FROM $DB_NAME.warehouse WHERE name LIKE 'E2E%'));" 2>/dev/null)"
IFS=',' read -r r_member r_admin r_product r_wh <<< "$residue"
echo "▶ 残留自检（e2e 会员/管理员/商品/仓库）：$r_member/$r_admin/$r_product/$r_wh"
if [[ "$r_member$r_admin$r_product$r_wh" != "0000" ]]; then
  echo "  ⚠ 仍有残留，可能种子本身带 e2e 命名，或复位没跑完" >&2
else
  echo "  ✓ 干净：只剩种子数据"
fi

echo
echo "▶ 隔离对照：dev 库 qiqi_mall 不受影响"
mysql_exec -t -e "SELECT 'qiqi_mall.product' t, COUNT(*) c FROM qiqi_mall.product
UNION ALL SELECT 'qiqi_mall.order', COUNT(*) FROM qiqi_mall.\`order\`;"

echo
echo "✅ $DB_NAME 已复位回种子。录片后跑一次即可让测试环境恢复干净。"
echo "   提示：若刚重建过 server 容器，docker compose restart gateway（否则网关缓存旧 IP 会 502）。"
