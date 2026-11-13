#!/usr/bin/env bash
# probecheck.sh —— 「驱动探针条数」的定量验证（改动的**硬证据**，不看吞吐）
#
#   用法： MEMBER_PASS=... ./probecheck.sh <label> [rps] [seconds] [warmup_rps] [warmup_s]
#   例：   MEMBER_PASS=... ./probecheck.sh armA 300 40
#
# 它在做什么：跑一小段定速下单（默认 300 rps × 40s），同时每 1s 快照
# performance_schema.events_statements_summary_by_digest，跑完用 sqlprofile.py 做差，输出
#   · 每单语句数
#   · `SELECT @@SESSION.transaction_read_only`（Connector/J 在每条 DML 前的探针）的条数/单
#   · `SET autocommit = ?` 的条数/单
# 这三个数就是「JDBC URL 参数真的生效了吗」的判据：
#   useLocalSessionState=false（默认）→ 探针 ≈ 6~7 条/单
#   useLocalSessionState=true          → 探针 ≈ 0 条/单
#
# 为什么不能只看吞吐：吞吐会同时被 JIT/热机/GC/锁竞争影响，探针条数是**语句级**的直接观察，
# 它变了才说明驱动行为变了。
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH=$(cd "$SELF/.." && pwd)
REPO=$(cd "$BENCH/.." && pwd)
LG=${LG:-/tmp/loadgen}
LABEL=${1:?用法: probecheck.sh <label> [rps] [seconds] [warmup_rps] [warmup_s]}
RPS=${2:-300}
SECS=${3:-40}
WRPS=${4:-$((RPS / 2))}
WSECS=${5:-20}
MEMBER_PASS=${MEMBER_PASS:-}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
LOCK=${LOCK:-/root/.qiqi-standing/bench-lock.sh}
OUT=${OUT:-$BENCH/results/$(date '+%F')-avalanche-localsession/probecheck-$LABEL}
mkdir -p "$OUT"
LOG=$OUT/run.log
: > "$LOG"
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

sql_db() { docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql --default-character-set=utf8mb4 -uroot -N -e "use qiqi_mall_test; $1" 2>/dev/null; }

DIG_PID=""; GEN_PID=""
cleanup() {
  [ -n "$DIG_PID" ] && kill "$DIG_PID" 2>/dev/null
  [ -n "$GEN_PID" ] && kill -0 "$GEN_PID" 2>/dev/null && kill "$GEN_PID" 2>/dev/null
  [ -x "$LOCK" ] && "$LOCK" release >>"$LOG" 2>&1 || true
}
trap cleanup EXIT INT TERM

[ -n "$MYSQL_PASSWORD" ] || { echo "缺 MYSQL_PASSWORD" >&2; exit 2; }
[ -n "$MEMBER_PASS" ]    || { echo "缺 MEMBER_PASS" >&2; exit 2; }

log "=== 0. 前置 ==="
if ! "$LOCK" acquire "probecheck-$LABEL" >>"$LOG" 2>&1; then
  echo "✗ 压测锁被占：$("$LOCK" status 2>&1 | head -2)" >&2; exit 3
fi
log "✅ 已持压测独占锁"
HIST=$(docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql -uroot -N -e \
  "select ENABLED from performance_schema.setup_consumers where NAME='events_statements_history_long'" 2>/dev/null | tr -d '\r')
[ "${HIST:-NO}" = "YES" ] && { log "✗ events_statements_history_long 开着，先关掉"; exit 3; }
# 生效证据的另一半：容器里真的注入了什么 URL
for c in qiqi-server-1 qiqi-server-2; do
  log "  $c URL=$(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^SPRING_DATASOURCE_URL=' | cut -d= -f2- || echo '(yml 默认，无 useLocalSessionState)')"
  log "  $c jar_md5=$(docker exec "$c" sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1)"
done

log "=== 1. 数据前置（reset 订单 + 写桶 + 三自检 + 灌令牌）==="
NEED=$(( RPS * (SECS + WSECS) * 115 / 100 ))
HAVE=$(wc -l < "$LG/tokens.txt" 2>/dev/null || echo 0)
log "令牌需求=$NEED 现有=$HAVE"
[ "$HAVE" -ge "$NEED" ] || { log "✗ 令牌不够，先跑 prepare.sh tokens"; exit 4; }
MEMBER_PASS="$MEMBER_PASS" MYSQL_PASSWORD="$MYSQL_PASSWORD" BENCH_WORKDIR="$LG" \
  "$SELF/prepare.sh" reset >>"$LOG" 2>&1 && log "✅ reset" || { log "✗ reset 失败"; exit 4; }
docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql --default-character-set=utf8mb4 -uroot qiqi_mall_test \
  < "$REPO/sql/reset-sku-bucket.sql" >>"$LOG" 2>&1 && log "✅ 桶已重置"
CHK=$(sql_db "select concat('rows=',(select count(*) from sku_bucket where sku_id between 1 and 21),
  ' bad=',(select count(*) from (select sku_id,count(*) c from sku_bucket where sku_id between 1 and 21
  group by sku_id having c<>8) x), ' drift=',(select count(*) from sku s where s.id between 1 and 21
  and s.stock <> coalesce((select sum(stock) from sku_bucket b where b.sku_id=s.id),0)));")
log "  自检 $CHK"
echo "$CHK" | grep -q 'bad=0 drift=0' || { log "✗ 自检不过"; exit 5; }
MYSQL_PASSWORD="$MYSQL_PASSWORD" BENCH_WORKDIR="$LG" "$SELF/prepare.sh" seed 2>&1 | tail -2 | tee -a "$LOG"

log "=== 2. 起 SQL 画像采样（每 1s 一次 DIGEST 快照）==="
MYSQL_PASSWORD="$MYSQL_PASSWORD" INTERVAL_S=1 TOPN=120 "$SELF/sqldigest.sh" "$OUT/sqldigest.csv" >>"$LOG" 2>&1 &
DIG_PID=$!
sleep 2

log "=== 3. 定速下单 ${RPS}rps × ${SECS}s（预热 ${WSECS}s@${WRPS}rps）==="
node "$BENCH/gen.mjs" --url http://127.0.0.1:18080/portal/orders --method POST \
  --steps "$RPS" --step-duration "$SECS" --warmup "$WSECS" --warmup-rps "$WRPS" \
  --token-file "$LG/tokens.txt" --auth-file "$LG/jwts.txt" --body-file "$LG/order.json" \
  --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
  --outdir "$OUT" --label "$LABEL" --max-lag-ms 3000 >>"$LOG" 2>&1
GEN_PID=""
sleep 2
kill "$DIG_PID" 2>/dev/null; DIG_PID=""

log "=== 4. SQL 画像做差（sqlprofile.py）==="
python3 "$SELF/sqlprofile.py" "$OUT" > "$OUT/sqlprofile.txt" 2>&1
sed -n '1,40p' "$OUT/sqlprofile.txt" | tee -a "$LOG"
echo >> "$OUT/probecheck.txt"
{
  echo "== probecheck $LABEL =="
  echo "url=$(docker inspect qiqi-server-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^SPRING_DATASOURCE_URL=' | cut -d= -f2- || echo '(yml 默认)')"
  echo "jar_md5=$(docker exec qiqi-server-1 sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1)"
  echo "rps=$RPS secs=$SECS"
  grep -E "每单 |SELECT @@SESSION.transaction_read_only|SET autocommit|语句/单" "$OUT/sqlprofile.txt" | head -20
} > "$OUT/probecheck.txt"
echo "--- probecheck 摘要 ---"
cat "$OUT/probecheck.txt"
log "=== 完成：$OUT ==="
