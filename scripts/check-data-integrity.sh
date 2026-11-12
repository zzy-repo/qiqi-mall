#!/usr/bin/env bash
#
# 数据完整性体检（**只读**）—— `scripts/check-data-integrity.sh`
#
# ── 为什么要有它 ────────────────────────────────────────────────────────────
# 有些「坏了」不会让接口报错，只会让数字悄悄对不上：
#   · 订单被硬删了，`stock_ledger` 却还留着它的行（孤儿台账）—— 谁也无法再判断这些进出属于哪单；
#   · `sku.stock` 被写成负数 —— 后续扣减/回补全部继续错下去；
#   · 父订单状态与包裹事实分叉（父单说「已发货」但没有任何包裹真的出库；或货已出库、父单还挂着「待发货」）
#     —— 所有按状态聚合的报表与文档从此都要为它开例外。
# 这三类都得**定期扫**，等到业务方发现「库存对不上」时已经查不出是哪一步坏的。
# 所以把体检做成一条命令：只读、10 秒内跑完、有数字、有样本、退出码能直接当门禁。
#
# ── 用法 ───────────────────────────────────────────────────────────────────
#   ./scripts/check-data-integrity.sh                 # 体检 qiqi_mall + qiqi_mall_test
#   ./scripts/check-data-integrity.sh qiqi_mall_test  # 只体检指定库（可给多个）
#   ./scripts/check-data-integrity.sh --strict        # 历史「分叉-已完成但包裹未签收」也判红（默认只告警）
#   ./scripts/check-data-integrity.sh --samples 10    # 每类分叉最多打几条样本（默认 5，0 = 不打）
#   ./scripts/check-data-integrity.sh --help
#
# ── 退出码 ─────────────────────────────────────────────────────────────────
#   0 = 全部通过（告警不算问题）
#   1 = 有问题（孤儿台账 / 负库存 / 父单超前 / 父单落后；--strict 还包括「已完成但包裹未签收」）
#   2 = 参数或环境错误（docker 不可用、库连不上、库名不存在…）—— **不等于「没问题」**，别当 0 用
#
# ── 只读承诺 ───────────────────────────────────────────────────────────────
# 本脚本只发 SELECT。想清理孤儿台账请用 sql/ 里对得上的运维 SQL（清理前先 dump），
# 别把「体检」和「修复」揉进同一条命令：体检要能被随时、反复、在任何人手上跑。
#
# ── 怎么调度 ───────────────────────────────────────────────────────────────
#   ① 手动：发版前 / 每次全量回归后各跑一次（本轮的收尾节奏就是它）。
#   ② 常驻岗：接进 `scripts/check.sh`（常驻计时脚本）每小时一次即可，只追加 1 行：
#        ./scripts/check-data-integrity.sh >/dev/null || echo "[$(date +%F' '%T)] 数据完整性体检失败"
#      或 systemd timer 每天 04:00：
#        # /etc/systemd/system/qiqi-data-integrity.service
#        #   [Unit] Description=qiqi-mall data integrity
#        #   [Service] Type=oneshot
#        #   [Service] WorkingDirectory=/root/qiqi-mall
#        #   [Service] ExecStart=/root/qiqi-mall/scripts/check-data-integrity.sh
#        # /etc/systemd/system/qiqi-data-integrity.timer
#        #   [Timer] OnCalendar=*-*-* 04:00:00
#        #   [Install] WantedBy=timers.target
#   ③ CI/回归：直接看退出码（1 = 拦下，2 = 环境没准备好，别混为一谈）。
set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 连接参数（与 mcp-server/test/_setup.mjs 同一套环境变量名，口令不回显、不进日志）
MYSQL_CONTAINER="${MCP_TEST_MYSQL_CONTAINER:-qiqi-mysql}"
MYSQL_USER="${MCP_TEST_MYSQL_USER:-root}"
MYSQL_PASSWORD="${MCP_TEST_MYSQL_PASSWORD:-${MYSQL_ROOT_PASSWORD:-qiqi123456}}"

STRICT=0
SAMPLES=5
DBS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)   sed -n '2,58p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --strict)    STRICT=1; shift ;;
    --samples)   SAMPLES="${2:-}"; shift 2 ;;
    --samples=*) SAMPLES="${1#--samples=}"; shift ;;
    -*)          echo "✗ 不认识的参数：$1（用 --help 看用法）" >&2; exit 2 ;;
    *)           DBS+=("$1"); shift ;;
  esac
done
[[ "$SAMPLES" =~ ^[0-9]+$ ]] || { echo "✗ --samples 需要非负整数（收到「$SAMPLES」）" >&2; exit 2; }
if [[ ${#DBS[@]} -eq 0 ]]; then DBS=("qiqi_mall" "qiqi_mall_test"); fi

# 库名拼进 SQL，先过一遍白名单（只允许字母数字下划线）
for db in "${DBS[@]}"; do
  [[ "$db" =~ ^[A-Za-z0-9_]+$ ]] || { echo "✗ 库名只允许字母数字下划线（收到「$db」）" >&2; exit 2; }
done

# ---------------------------------------------------------------- 只读 SQL 执行
command -v docker >/dev/null || { echo "✗ 找不到 docker —— 本脚本靠它连 MySQL，无法体检" >&2; exit 2; }
docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 \
  || { echo "✗ 容器 $MYSQL_CONTAINER 不在（体检连不上库，绝不能当「没问题」）" >&2; exit 2; }

q() {   # q <sql> → 制表符分隔的无表头文本
  docker exec -e "MYSQL_PWD=$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -N -B -e "$1" 2>/dev/null
}

db_exists() {   # 库必须存在：库名写错的「0 条问题」是假通过
  [[ "$(q "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$1'")" == "1" ]]
}

problems=0
warnings=0
started="$(date +%s)"

echo "════ 数据完整性体检（只读）  $(date '+%F %T')  ════"
echo "  容器 $MYSQL_CONTAINER｜库 ${DBS[*]}｜严格模式 $([[ $STRICT == 1 ]] && echo 开 || echo 关)"
echo

# ================================================================ ① 孤儿库存台账
echo "① 孤儿库存台账（order_id 指向已不存在的订单）"
orphan_total=0
for db in "${DBS[@]}"; do
  db_exists "$db" || { echo "  ✗ 库 $db 不存在（别把「查不到」当「没问题」）" >&2; exit 2; }
  n="$(q "SELECT COUNT(*) FROM \`$db\`.stock_ledger l
           WHERE NOT EXISTS (SELECT 1 FROM \`$db\`.\`order\` o WHERE o.id = l.order_id)")"
  total="$(q "SELECT COUNT(*) FROM \`$db\`.stock_ledger")"
  if [[ "$n" == "0" ]]; then
    printf '  ✓ %-16s 孤儿 0 行（台账共 %s 行）\n' "$db" "$total"
  else
    printf '  ✗ %-16s 孤儿 %s 行（台账共 %s 行）← 订单已删、台账还在，进出无法归属\n' "$db" "$n" "$total"
    q "SELECT CONCAT('      · id=', l.id, ' order_id=', l.order_id, ' sku=', IFNULL(l.sku_id,'-'),
                ' delta=', l.delta, ' reason=', IFNULL(l.reason,'-')), 0
         FROM \`$db\`.stock_ledger l
        WHERE NOT EXISTS (SELECT 1 FROM \`$db\`.\`order\` o WHERE o.id = l.order_id)
        ORDER BY l.id LIMIT $SAMPLES" | cut -f1
    problems=$((problems + 1))
    orphan_total=$((orphan_total + n))
  fi
done
[[ "$orphan_total" == "0" ]] && echo "  → 两库都没有孤儿台账：删单调账与台账清理是同一步（OrderPurgeService#settleStockLedger）"
echo

# ================================================================ ② 负库存
echo "② 负库存（sku.stock < 0）"
neg_total=0
for db in "${DBS[@]}"; do
  n="$(q "SELECT COUNT(*) FROM \`$db\`.sku WHERE stock < 0")"
  if [[ "$n" == "0" ]]; then
    printf '  ✓ %-16s 负库存 0 个（最小库存 %s）\n' "$db" "$(q "SELECT IFNULL(MIN(stock),0) FROM \`$db\`.sku")"
  else
    printf '  ✗ %-16s 负库存 %s 个 ← 扣减闸门被绕过，后续进出都会继续错\n' "$db" "$n"
    q "SELECT CONCAT('      · sku id=', id, ' stock=', stock, ' version=', version), 0
         FROM \`$db\`.sku WHERE stock < 0 ORDER BY stock LIMIT $SAMPLES" | cut -f1
    problems=$((problems + 1))
    neg_total=$((neg_total + n))
  fi
done
[[ "$neg_total" == "0" ]] && echo "  → 库存没有被扣穿（SkuService#deductStock 的原子闸门有效）"
echo

# ================================================================ ③ 订单 ↔ 包裹状态分叉
echo "③ 订单状态 ↔ 包裹状态分叉（抽样；父单状态是子单事实的推导值，不许各说各话）"

# A：父单说「已发货/已完成」，但一张「未取消且已出库」的包裹都没有（货根本没离开仓库）
#    口径：只在**有包裹**的订单上判 —— 1 已付款 → 2 已发货 的直发单本来就没有包裹行，不算分叉
# B：父单还是「待发货(8)」，但已经有包裹出库了（货已走、父单没跟上）
# C：父单「已完成(3)」，但还有包裹没签收（历史遗留：早期父单聚合提前写 3，需回填；默认只告警）
div_a=0; div_b=0; div_c=0
for db in "${DBS[@]}"; do
  a="$(q "SELECT COUNT(*) FROM \`$db\`.\`order\` o
           WHERE o.status IN (2,3)
             AND EXISTS (SELECT 1 FROM \`$db\`.delivery_order d WHERE d.order_id = o.id)
             AND NOT EXISTS (SELECT 1 FROM \`$db\`.delivery_order d
                              WHERE d.order_id = o.id AND d.status <> 9 AND d.status >= 3)")"
  b="$(q "SELECT COUNT(*) FROM \`$db\`.\`order\` o
           WHERE o.status = 8
             AND EXISTS (SELECT 1 FROM \`$db\`.delivery_order d
                          WHERE d.order_id = o.id AND d.status <> 9 AND d.status >= 3)")"
  c="$(q "SELECT COUNT(*) FROM \`$db\`.\`order\` o
           WHERE o.status = 3
             AND EXISTS (SELECT 1 FROM \`$db\`.delivery_order d
                          WHERE d.order_id = o.id AND d.status <> 9 AND d.status < 5)")"
  printf '  %-16s A 父单超前=%s  B 父单落后=%s  C 已完成但未签收=%s\n' "$db" "$a" "$b" "$c"

  if [[ "$a" != "0" ]]; then
    echo "    ✗ A（父单说已发货/已完成，却没有任何包裹真的出库）："
    q "SELECT CONCAT('      · order id=', o.id, ' sn=', o.order_sn, ' status=', o.status,
                ' 包裹状态=', IFNULL((SELECT GROUP_CONCAT(d.status) FROM \`$db\`.delivery_order d WHERE d.order_id=o.id),'无')), 0
         FROM \`$db\`.\`order\` o
        WHERE o.status IN (2,3)
          AND EXISTS (SELECT 1 FROM \`$db\`.delivery_order d WHERE d.order_id = o.id)
          AND NOT EXISTS (SELECT 1 FROM \`$db\`.delivery_order d
                           WHERE d.order_id = o.id AND d.status <> 9 AND d.status >= 3)
        ORDER BY o.id DESC LIMIT $SAMPLES" | cut -f1
    problems=$((problems + 1)); div_a=$((div_a + a))
  fi
  if [[ "$b" != "0" ]]; then
    echo "    ✗ B（货已出库，父单还挂在待发货）："
    q "SELECT CONCAT('      · order id=', o.id, ' sn=', o.order_sn, ' status=', o.status,
                ' 已出库包裹=', (SELECT COUNT(*) FROM \`$db\`.delivery_order d
                                 WHERE d.order_id=o.id AND d.status<>9 AND d.status>=3)), 0
         FROM \`$db\`.\`order\` o
        WHERE o.status = 8
          AND EXISTS (SELECT 1 FROM \`$db\`.delivery_order d
                       WHERE d.order_id = o.id AND d.status <> 9 AND d.status >= 3)
        ORDER BY o.id DESC LIMIT $SAMPLES" | cut -f1
    problems=$((problems + 1)); div_b=$((div_b + b))
  fi
  if [[ "$c" != "0" ]]; then
    if [[ "$STRICT" == "1" ]]; then
      echo "    ✗ C（父单已完成，包裹却没签收）—— --strict 下计为问题："
      problems=$((problems + 1))
    else
      echo "    ⚠ C（父单已完成，包裹却没签收）—— 历史/演示数据的遗留，默认只告警（--strict 才判红）："
      warnings=$((warnings + 1))
    fi
    q "SELECT CONCAT('      · order id=', o.id, ' sn=', o.order_sn, ' 未签收包裹=',
                (SELECT COUNT(*) FROM \`$db\`.delivery_order d
                  WHERE d.order_id=o.id AND d.status<>9 AND d.status<5)), 0
         FROM \`$db\`.\`order\` o
        WHERE o.status = 3
          AND EXISTS (SELECT 1 FROM \`$db\`.delivery_order d
                       WHERE d.order_id = o.id AND d.status <> 9 AND d.status < 5)
        ORDER BY o.id DESC LIMIT $SAMPLES" | cut -f1
    div_c=$((div_c + c))
  fi
done
if [[ $((div_a + div_b + div_c)) == 0 ]]; then
  echo "  → 三库抽样无分叉：父单状态与包裹事实一致"
elif [[ $((div_a + div_b)) == 0 ]]; then
  echo "  → A/B（父单超前/落后）为 0，只有 C 是历史存量：父单已完成而包裹未签收，需按回填脚本处理（本轮不改）"
fi
echo

# ================================================================ 结论
elapsed=$(( $(date +%s) - started ))
echo "────────────────────────────────────────────────────────────"
if [[ "$problems" == "0" ]]; then
  printf '结论：PASS（问题 0，告警 %s 项）｜耗时 %ss\n' "$warnings" "$elapsed"
  exit 0
fi
printf '结论：FAIL（问题 %s 项，告警 %s 项）｜耗时 %ss\n' "$problems" "$warnings" "$elapsed"
echo "处理建议：孤儿台账先 dump 再按 NOT EXISTS 清理；负库存查扣减入口；A/B 分叉用父单状态重算（DeliveryFlowService#recomputeOrderStatus）回填。"
exit 1
