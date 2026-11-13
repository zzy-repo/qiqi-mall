#!/usr/bin/env bash
# audit-data.sh —— 压测后数据正确性抽查（跑 tools/audit-data.sql，只读）
#
#   用法： MYSQL_PASSWORD=... ./audit-data.sh [输出文件]
#
# 判据（逐项都应为 0，或按注释说明）：
#   orders_without_exactly_1_item / orphan_items / amount_mismatch / amount_nonpositive
#   orders_without_1_birth_row / birth_row_wrong_status / birth_row_wrong_reason
#   orders_ledger_rows_neq_items / orders_ledger_delta_neq_qty / order_create_delta_not_negative
#   skus_not_conserved / cache_drift_skus / dup_* / negative_buckets
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SELF/../.." && pwd)
MYSQL_PASSWORD=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
OUT=${1:-$REPO/bench/results/$(date '+%F')-avalanche-localsession/data-audit.txt}
mkdir -p "$(dirname "$OUT")"
{
  echo "== 数据正确性抽查 $(date '+%F %T') =="
  echo "   容器 URL: $(docker inspect qiqi-server-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^SPRING_DATASOURCE_URL=' | cut -d= -f2- || echo '(yml 默认)')"
  echo "   jar md5 : $(docker exec qiqi-server-1 sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1)"
  echo
  docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql \
    mysql --default-character-set=utf8mb4 -uroot --table qiqi_mall_test < "$SELF/audit-data.sql" 2>&1
} | tee "$OUT"
echo
echo "→ $OUT"
