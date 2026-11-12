#!/usr/bin/env bash
#
# 补齐分仓库存（inventory）—— 幂等脚本，可反复重跑
#
# 背景（缺陷：分仓库存只有少数规格有记录 → 部分商品一下单就报「无法确定发货仓」）：
#   拆单要把每个 SKU 归到一个发货仓，归属依据是 inventory 里的分仓记录。
#   只有一部分规格配了记录时，其余规格的订单一审核通过就拆不出包裹，订单卡死在待审核。
#
# 修法分两层，本脚本是第二层（补数据）：
#   ① 代码兜底：SplitOrderService 在没有分仓记录时归到「默认仓」（启用仓里 id 最小的那个），
#      并在日志 / 拆单方案 note / willDo 文案里写明「该规格未配置分仓库存，已按默认仓 XX 处理」。
#   ② 补数据：本脚本把每个「一条分仓记录都没有」的 SKU，按它的**总库存 sku.stock**
#      在默认仓补一行 inventory(stock = sku.stock)。
#
# 幂等口径（重要）：
#   - 只处理「没有任何分仓记录」的 SKU（NOT EXISTS + inventory 的 UNIQUE(sku_id, warehouse_id) 双保险），
#     已经有分仓记录的 SKU 一律跳过、**绝不覆盖**（人工配过的分仓数据优先）；
#   - 默认仓 = warehouse 里 status=1 中 id 最小的那个仓（与代码兜底同一口径）；
#   - 重跑一次 = 再查一遍「还缺谁」，缺的就补，不缺就报 0 行 —— 所以可以放心跑第二遍。
#
# 用法：
#   ./scripts/backfill-inventory.sh --dry-run    # 只看会插入多少行、哪些 SKU
#   ./scripts/backfill-inventory.sh              # 真跑，打印插入行数与最终覆盖率
#
# 环境变量（默认值即测试容器栈）：
#   MYSQL_CONTAINER=qiqi-mysql  MYSQL_DATABASE_TEST=qiqi_mall_test
#   MYSQL_USER=root             MYSQL_PASSWORD=qiqi123456
#
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB="${MYSQL_DATABASE_TEST:-qiqi_mall_test}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help)
      sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "✗ 未知参数：$arg（支持 --dry-run / --help）" >&2
      exit 2
      ;;
  esac
done

# mysql 客户端放容器里跑；密码走 MYSQL_PWD，避免命令行明文告警。
# --default-character-set=utf8mb4 必须显式指定，否则中文会被二次编码（本项目踩过）。
mysql_q() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" -N -B "$DB" -e "$1"
}

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi

# ---------------------------------------------------------------- 默认仓
DEFAULT_WH_ID="$(mysql_q "SELECT id FROM warehouse WHERE status = 1 ORDER BY id ASC LIMIT 1;")"
if [[ -z "$DEFAULT_WH_ID" ]]; then
  echo "✗ $DB 里没有启用的仓库（warehouse.status=1），无法确定默认仓" >&2
  exit 1
fi
DEFAULT_WH_NAME="$(mysql_q "SELECT name FROM warehouse WHERE id = ${DEFAULT_WH_ID};")"

# ---------------------------------------------------------------- 缺口统计（同一个 NOT EXISTS 口径）
MISSING_WHERE="FROM sku s WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.sku_id = s.id)"
MISSING="$(mysql_q "SELECT COUNT(*) ${MISSING_WHERE};")"

echo "▶ 库：$DB（容器 $CONTAINER）"
echo "▶ 默认仓：${DEFAULT_WH_NAME}（id=${DEFAULT_WH_ID}，启用仓里 id 最小）"
echo "▶ 缺口：${MISSING} 个规格没有任何分仓记录"

if [[ "$MISSING" -gt 0 ]]; then
  echo "  将被补齐的规格（最多列 20 条，格式 规格id:总库存）："
  mysql_q "SELECT CONCAT(s.id, ':', s.stock) ${MISSING_WHERE} ORDER BY s.id ASC LIMIT 20;" | sed 's/^/    /'
  if [[ "$MISSING" -gt 20 ]]; then
    echo "    …（其余 $((MISSING - 20)) 条略）"
  fi
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "■ --dry-run：不会写库。真跑（去掉 --dry-run）将插入 ${MISSING} 行 inventory。"
else
  # ------------------------------------------------------------ 真跑（幂等：只补缺口）
  INSERTED="$(mysql_q "INSERT INTO inventory (sku_id, warehouse_id, stock)
    SELECT s.id, ${DEFAULT_WH_ID}, s.stock ${MISSING_WHERE};
    SELECT ROW_COUNT();" | tail -1)"
  echo "■ 已插入 ${INSERTED} 行 inventory（warehouse_id=${DEFAULT_WH_ID}，stock = sku.stock）"

  # 再跑一遍确认幂等：第二遍必须补 0 行
  LEFT="$(mysql_q "SELECT COUNT(*) ${MISSING_WHERE};")"
  if [[ "$LEFT" -ne 0 ]]; then
    echo "✗ 幂等自检失败：补完还剩 ${LEFT} 个规格没有分仓记录" >&2
    exit 1
  fi
  echo "■ 幂等自检：再查一遍缺口 = 0（重跑不会重复插入）"
fi

# ---------------------------------------------------------------- 覆盖率
read -r SKUS COVERED ROWS <<<"$(mysql_q "SELECT (SELECT COUNT(*) FROM sku),
      (SELECT COUNT(DISTINCT i.sku_id) FROM inventory i JOIN sku s ON s.id = i.sku_id),
      (SELECT COUNT(*) FROM inventory);" | tr '\t' ' ')"
echo "■ 覆盖率：${COVERED}/${SKUS} 规格有分仓记录（inventory 共 ${ROWS} 行）"
if [[ "$COVERED" -eq "$SKUS" ]]; then
  echo "✓ 达标：所有规格都有分仓记录，拆单不会再报「无法确定发货仓」"
else
  echo "! 仍有 $((SKUS - COVERED)) 个规格没有分仓记录（代码侧已按默认仓兜底，不会再卡单）"
fi
