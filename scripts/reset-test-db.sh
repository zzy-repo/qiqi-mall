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
#     DROP DATABASE + CREATE DATABASE → Flyway 从 V1 全量建结构 → 02-seed.sql 逐表 TRUNCATE
#     再固定 id 重灌，所以重跑 = 整体复位，不会漂移）。
#     ⚠️ 结构已经不在 sql/ 里手抄了：唯一来源是 qiqi-mall-server/…/db/migration/V*.sql。
#   - 字符集坑同样交给 init-test-db.sh 处理：容器里的 mysql 客户端默认 latin1，
#     必须显式 --default-character-set=utf8mb4，否则中文会被二次编码成乱码。
#   - admin 口令也交给 init-test-db.sh 的收尾步骤（scripts/set-admin-password.sh）：
#     种子里的 admin 哈希是演示弱口令，复位后必须用 /root/.qiqi-secrets/admin.txt 覆盖，
#     否则「复位一次就把后台口令打回弱口令」。那一步失败时本脚本会失败退出（不再静默）。
#
# 除 init-test-db.sh 之外，本脚本自己还要**补种四样**（2026-11-14 增 a~c，2026-11-17 增 d，见下）：
#   init-test-db.sh 只灌「种子骨架」（会员/商品/SKU/14 笔种子单），演示必需的审核数据不在种子里，
#   所以光跑它 = 复位后审核队列空、渠道演示缺六单。实测证据（2026-11-14）：只跑 init 后
#   audit_policy=0、QT 单=0。因此复位末尾补齐：
#     a) sql/seed-order-audit-demo.sql  7 条策略 + 全局排除 + 演示六单 QT-1001..1006（幂等：只插不删）
#     b) sql/reset-sku-bucket-N.sql     @n=8 全库重铺库存桶。**必须做**：a 新建的 9 个 SKU 只有
#        1 个桶，而扣减按 bucket_no = hash % 8 找桶 → 7/8 概率找不到桶、明明有货却判「库存不足」
#     c) 三自检（桶行数 = SKU×8 / 每 SKU 8 桶 / drift=0）+ 审核演示数据自检（策略 7 条、QT 六单）
#     d) scripts/prepare-audit-demo-queue.mjs  调运行中服务 POST /admin/order-audit/run **跑一轮真判定**。
#        **必须做**：a 只把六笔单摆成 status=7 的「起点」，决策行与标一行都没有；而人工队列是
#        FROM order_audit_decision 的 ⇒ 少了 d，复位后队列页是空表、「按标筛」0 行，
#        「这单为什么卡」在演示站上根本演不出来（2026-11-17 实测）。手写 SQL 糊决策行不在选项内：
#        那是伪造引擎结论，改一条策略文案抄来的行就成了假证据。
#   ⚠️ 四样都必须幂等（复位本来就会反复跑）；任一失败 → 非 0 退出，绝不留「复位完成但演示数据不全」
#      这种半成品状态（那正是文档里「复位后自动补六单」曾经骗人的地方）。
#   ⚠️ d 需要演示栈在跑（网关 18080）。这是刻意的硬依赖：判定只能由真引擎做，栈不在就跑不了，
#      跑不了就不许报「复位完成」。口令由 d 自己从 ADMIN_PASSWORD / /root/.qiqi-secrets/admin.txt 读。
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

# ============================================================================
# 补种演示必需数据：复位 = 回到「完整演示态」，不是回到空骨架
#   init-test-db.sh 只灌种子骨架，审核演示数据不在种子里（实测：只跑 init 后
#   audit_policy=0 / QT 单=0 → 审核队列页空、演示六单没了）。以下四步缺一不可，
#   且都幂等：脚本可以反复跑，结果一样。
#
#   2026-11-17 补第 3 步（跑一轮真判定）：只做 1、2 两步时，六笔演示单虽然都在 7（待审核），
#   但**一行决策行、一条标都没有**。而人工队列是 FROM order_audit_decision 的 ⇒ 复位完打开队列页
#   是**空表**，「按标筛选」（?tag=NO_POLICY_MATCH）也是 0 行 —— 演示站上「这单为什么卡」根本演不出来。
#   修法不是往 order_audit_decision / order_audit_tag 里手糊行（那是伪造引擎结论，改一条策略文案
#   抄来的行就成了假证据，而且下次复位就没了），而是**让引擎自己判一轮**：
#   scripts/prepare-audit-demo-queue.mjs 调运行中服务的 POST /admin/order-audit/run，
#   决策行、结论标、状态流转全部由真引擎落。它同时是本脚本对「队列有没有单可看」的那道自检。
# ============================================================================
AUDIT_SEED="$REPO_DIR/sql/seed-order-audit-demo.sql"
BUCKET_SQL="$REPO_DIR/sql/reset-sku-bucket-N.sql"
QUEUE_PREP="$REPO_DIR/scripts/prepare-audit-demo-queue.mjs"

echo
echo "▶ [补种 1/4] 审核演示数据：sql/seed-order-audit-demo.sql（7 条策略 + 全局排除 + QT-1001..1006）"
if [[ ! -f "$AUDIT_SEED" ]]; then
  echo "✗ 找不到 $AUDIT_SEED —— 复位后审核队列会是空的，拒绝把半成品当完成" >&2
  exit 1
fi
mysql_exec "$DB_NAME" < "$AUDIT_SEED" > /dev/null \
  || { echo "✗ 补种审核演示数据失败（$AUDIT_SEED）" >&2; exit 1; }
echo "  ✓ 审核策略与六笔演示单已补齐（幂等：已存在则整条跳过）"

echo
echo "▶ [补种 2/4] 库存桶重铺：sql/reset-sku-bucket-N.sql（@n=8 全库，按各 SKU 自己的 sku.stock）"
if [[ ! -f "$BUCKET_SQL" ]]; then
  echo "✗ 找不到 $BUCKET_SQL —— 补种 1 新建的 SKU 只有 1 个桶，扣减会假缺货，拒绝继续" >&2
  exit 1
fi
# 不传参：文件默认就是 @n=8 / @total=0（各 SKU 按自己 sku.stock）/ @from=@to=0（全库），
# 正是「全库重铺成 8 桶」这一档，先把参数行打印出来留证再执行其余部分。
mysql_exec "$DB_NAME" < "$BUCKET_SQL" | grep -E '^参数 OK|^✗ 参数' || {
  echo "✗ 库存桶重铺失败（$BUCKET_SQL）" >&2; exit 1; }
echo "  ✓ 全库每 SKU 重铺 8 个桶（幂等：先删该 SKU 所有桶再均分，重跑结果相同）"

echo
echo "▶ [补种 3/4] 跑一轮真判定：scripts/prepare-audit-demo-queue.mjs（让队列里出现带标的待审核单）"
if [[ ! -f "$QUEUE_PREP" ]]; then
  echo "✗ 找不到 $QUEUE_PREP —— 复位后审核队列会是空表（按标筛 0 行），拒绝把半成品当完成" >&2
  exit 1
fi
# 它需要演示栈（网关 18080）在跑：判定必须由真引擎做，不能在 SQL 里手抄一份决策行。
# 口令由它自己从 ADMIN_PASSWORD / /root/.qiqi-secrets/admin.txt 读，取不到就失败 —— 不静默跳过。
node "$QUEUE_PREP" || {
  echo "✗ 演示队列没跑起来（$QUEUE_PREP）—— 复位后「按标筛」会是 0 行，不接受" >&2; exit 1; }

echo
echo "▶ [补种 4/4] 自检（复位必须真的等于「回到完整演示态」，任一不过即失败）"
selftest="$(mysql_exec -N -e "SELECT CONCAT(
    (SELECT COUNT(*) FROM $DB_NAME.sku), ',',
    (SELECT COUNT(*) FROM $DB_NAME.sku_bucket), ',',
    (SELECT COUNT(*) FROM (SELECT sku_id FROM $DB_NAME.sku_bucket GROUP BY sku_id HAVING COUNT(*) <> 8) x), ',',
    (SELECT COUNT(*) FROM $DB_NAME.sku s LEFT JOIN (SELECT sku_id, SUM(stock) t FROM $DB_NAME.sku_bucket GROUP BY sku_id) b
       ON b.sku_id = s.id WHERE s.stock <> COALESCE(b.t, 0)), ',',
    (SELECT COUNT(*) FROM $DB_NAME.audit_policy), ',',
    (SELECT COUNT(*) FROM $DB_NAME.\`order\` WHERE order_sn LIKE 'QT-100%'), ',',
    (SELECT COUNT(*) FROM $DB_NAME.channel_shop), ',',
    (SELECT COUNT(*) FROM $DB_NAME.\`order\` o WHERE o.status = 7 AND EXISTS (
        SELECT 1 FROM $DB_NAME.order_audit_tag t WHERE t.order_id = o.id AND t.active = 1
          AND (t.tag_code IN ('NO_POLICY_MATCH','AUDIT_PENDING','BLOCKED','STOCK_SHORT','COST_MISSING')
               OR t.tag_code LIKE 'FROZEN%'))), ',',
    (SELECT COUNT(DISTINCT t.tag_code) FROM $DB_NAME.\`order\` o
       JOIN $DB_NAME.order_audit_tag t ON t.order_id = o.id AND t.active = 1
      WHERE o.status = 7
        AND (t.tag_code IN ('NO_POLICY_MATCH','AUDIT_PENDING','BLOCKED','STOCK_SHORT','COST_MISSING')
             OR t.tag_code LIKE 'FROZEN%')), ',',
    (SELECT COUNT(DISTINCT o.id) FROM $DB_NAME.\`order\` o
       JOIN $DB_NAME.order_audit_tag t ON t.order_id = o.id AND t.active = 1
      WHERE o.status = 7 AND t.tag_code = 'NO_POLICY_MATCH'), ',',
    (SELECT COUNT(DISTINCT order_id) FROM $DB_NAME.order_audit_decision));")"
IFS=',' read -r t_sku t_bucket t_bad t_drift t_policy t_qt t_shop t_tagged t_tagkinds t_nomatch t_decided <<< "$selftest"
expected_bucket=$(( t_sku * 8 ))
echo "  ① 桶行数 = $t_bucket（应为 SKU×8 = $t_sku×8 = $expected_bucket）"
echo "  ② 非 8 桶的 SKU = $t_bad（应为 0）"
echo "  ③ 桶总量 ≠ sku.stock 的 SKU（drift）= $t_drift（应为 0）"
echo "  ④ 审核策略 = $t_policy 条（应 7）｜演示六单 QT-1001..1006 = $t_qt 笔（应 6）｜渠道店铺 = $t_shop 家（应 3）"
echo "  ⑤ 演示队列：待审核且带结论标的单 = $t_tagged 张（应 ≥3）｜这些单身上的结论标 = $t_tagkinds 种（应 ≥3）｜其中挂 NO_POLICY_MATCH = $t_nomatch 张（应 ≥1）｜有决策行的单 = $t_decided 张（队列就靠它，应 ≥5）"
selftest_bad=0
[[ "$t_bucket" == "$expected_bucket" ]] || { echo "  ✗ 桶行数不等于 SKU×8" >&2; selftest_bad=1; }
[[ "$t_bad" == "0" ]]                   || { echo "  ✗ 存在不是 8 个桶的 SKU（扣减会按 hash%8 假缺货）" >&2; selftest_bad=1; }
[[ "$t_drift" == "0" ]]                 || { echo "  ✗ 存在缓存偏差：sku.stock 与桶总量不一致" >&2; selftest_bad=1; }
[[ "$t_policy" == "7" ]]                || { echo "  ✗ 审核策略不是 7 条（补种 1 没生效）" >&2; selftest_bad=1; }
[[ "$t_qt" == "6" ]]                    || { echo "  ✗ 演示六单不齐（QT-1001..1006 应为 6 笔）" >&2; selftest_bad=1; }
[[ "$t_shop" == "3" ]]                  || { echo "  ✗ 渠道店铺不是 3 家（V1 基线应自带）" >&2; selftest_bad=1; }
[[ "$t_tagged" -ge 3 ]]                 || { echo "  ✗ 带结论标的待审核单不足 3 张（补种 3 的判定没生效 / 判定后单子都离开 7 了）" >&2; selftest_bad=1; }
[[ "$t_tagkinds" -ge 3 ]]               || { echo "  ✗ 待审核单身上的结论标不足 3 种（演示要求 NO_POLICY_MATCH / AUDIT_PENDING / BLOCKED 至少各一）" >&2; selftest_bad=1; }
[[ "$t_nomatch" -ge 1 ]]                || { echo "  ✗ 没有一张待审核单挂 NO_POLICY_MATCH ⇒ 按标筛筛不出单，第 5 步演示做不了" >&2; selftest_bad=1; }
[[ "$t_decided" -ge 5 ]]                || { echo "  ✗ 有决策行的单不足 5 张 ⇒ 人工队列是空表" >&2; selftest_bad=1; }
if [[ "$selftest_bad" -ne 0 ]]; then
  echo "✗ 复位自检未通过：$DB_NAME 不是「完整演示态」，别拿它去演示/对外" >&2
  exit 1
fi
echo "  ✓ 自检全过：桶 = SKU×8、每 SKU 8 桶、drift=0、策略 7 条、六单齐、三店铺在、队列里有 $t_tagged 张带标的待审核单（$t_tagkinds 种标，按标筛 NO_POLICY_MATCH 能筛出 $t_nomatch 张）"

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
echo "✅ $DB_NAME 已复位为**完整演示态**（种子骨架 + 审核演示六单 QT-1001..1006 + 每 SKU 8 桶 + 一轮真判定落下的决策行与结论标）。"
echo "   录片/演示后跑一次即可让测试环境恢复干净，演示数据不会缺。"
echo "   提示：若刚重建过 server 容器，docker compose restart gateway（否则网关缓存旧 IP 会 502）。"
