#!/usr/bin/env bash
# sqldigest.sh —— 按 DIGEST 快照 performance_schema 语句聚合表（雪崩实验第三步「SQL 画像」用）
#
#   用法： ./sqldigest.sh <输出.csv>            # 默认每 2s 快照一次
#          INTERVAL_S=2 TOPN=80 ./sqldigest.sh out/sqldigest.csv
#          MYSQL_PASSWORD=... （不给就读 <repo>/.env 的 MYSQL_ROOT_PASSWORD）
#
# 为什么这么采：events_statements_summary_by_digest 是**自 MySQL 启动以来**的累计值，
#   所以两次快照相减 = 该时间窗内的「每条 DIGEST 执行了几次、总共花了多少时间」。
#   比 slow log 强的地方：没有 long_query_time 阈值、不需要改配置、不需要重启、
#   而且 DIGEST 天然把「同一形状不同参数」聚成一条 —— 正好回答「每单那 N 条 SQL 是哪几条」。
#
# 列的语义（MICROSECOND 已在 SQL 里换算成毫秒）：
#   epoch_ms,digest,schema,count_star,sum_timer_wait_ms,sum_rows_affected,sum_rows_examined,digest_text
#   注意 count_star 是**服务端执行的语句数**（含 COM_QUERY 的 BEGIN/COMMIT、以及本采样器自己的查询）。
#
# 采样成本：一次聚合查询（表里几十~几千行）≈ 2~5ms，每 2s 一次，可忽略；
#   它自己也会出现在结果里（digest_text 含 events_statements_summary_by_digest），解析时剔掉。
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SELF/../.." && pwd)

OUT=${1:?用法: sqldigest.sh <输出.csv>}
INTERVAL_S=${INTERVAL_S:-2}
TOPN=${TOPN:-80}
CONT=${MYSQL_CONT:-qiqi-mysql}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
[ -n "$MYSQL_PASSWORD" ] || { echo "缺 MYSQL_PASSWORD" >&2; exit 2; }

mkdir -p "$(dirname "$OUT")"
echo "epoch_ms,digest,schema,count_star,sum_timer_wait_ms,sum_rows_affected,sum_rows_examined,digest_text" > "$OUT"

SQL="select concat(unix_timestamp(now(3))*1000, ',', DIGEST, ',', coalesce(SCHEMA_NAME,'-'), ',',
  COUNT_STAR, ',', round(SUM_TIMER_WAIT/1e9, 3), ',', SUM_ROWS_AFFECTED, ',', SUM_ROWS_EXAMINED, ',',
  replace(replace(substring(DIGEST_TEXT,1,180), ',', ';'), char(10), ' '))
 from performance_schema.events_statements_summary_by_digest
 where COUNT_STAR > 0 order by COUNT_STAR desc limit $TOPN"

while :; do
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$CONT" \
    mysql --default-character-set=utf8mb4 -uroot -N -e "$SQL" 2>/dev/null >> "$OUT"
  sleep "$INTERVAL_S"
done
