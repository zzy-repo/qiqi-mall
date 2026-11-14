#!/usr/bin/env bash
# txncost.sh —— 「2.73 个事务/单 + 63% 时间不在 SQL 上」的拆解装置
#
#   子命令：
#     ./txncost.sh deploy <jar> <label>          # 只读挂载 jar（不重建镜像），起 server-1/-2
#     ./txncost.sh snap <输出文件>               # 一次取全 P_S / global_status 快照（自启动累计值）
#     ./txncost.sh probe <label> [n] [rps]       # 打开 history_long，短跑，落「事务↔语句」对照原始表
#     ./txncost.sh run <label> <rps> <secs> <warmup> <warmup_rps>
#     ./txncost.sh restore                       # 拆挂载回镜像自带 jar + 关掉 history_long
#
# 为什么这么设计（与 audit-cost 那轮的教训）：
#   events_statements_summary_by_digest 是**自 MySQL 启动以来**的累计值，但它会被回收/清空
#   （上一轮实测：同一份 CSV 里 COMMIT 的 COUNT_STAR 从 5.3M 掉到 1265）——拿「首行 vs 末行」做差
#   会算出 2800 万条语句这种鬼数字。所以本轮**只用窗口紧邻的前后两次快照**做差，
#   且快照本身不带 `use`（采样器自己的语句 SCHEMA_NAME=NULL，不进被测量纲）。
#
# 铁律：不重建镜像、不改表结构、只读挂载 jar；跑完 DB/Redis/容器回干净态；锁由调用方持有。
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH=$(cd "$SELF/.." && pwd)
REPO=$(cd "$BENCH/.." && pwd)
LG=${LG:-/tmp/loadgen}
BASE=${BASE:-http://127.0.0.1:18080}
OUTROOT=${OUTROOT:-$BENCH/results/$(date '+%F')-txn-count}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
MEMBER_PASS=${MEMBER_PASS:-unused-not-needed-for-this-path}
SAMPLER_NO_DB=1      # 采样器一律不 `use` 库：它的语句 SCHEMA_NAME=NULL，不会混进业务口径

# -B = batch 模式：**真正的 tab 分隔**（不加 -B 时 mysql 会把 tab 转义成字面 "\t" 两字符，
# 上一版就是这么被坑的：dump 出来的 10000 行语句全是空字段）。-N 去掉表头。
sql_q() { docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql --default-character-set=utf8mb4 -B -N -uroot -e "$1" 2>/dev/null; }
sql_db() { sql_q "use qiqi_mall_test; $1"; }
say() { echo "[$(date '+%F %T')] $*"; }

# ---------------------------------------------------------------- deploy
cmd_deploy() {
  local jar=${1:?deploy <jar> <label>} label=${2:?deploy <jar> <label>}
  [ -f "$jar" ] || { echo "✗ 找不到 jar：$jar" >&2; exit 2; }
  local dest=$LG/jar/$label
  mkdir -p "$dest"; cp -f "$jar" "$dest/app.jar"; chmod 644 "$dest/app.jar"
  export JAR_HOST_PATH=$dest/app.jar
  cd "$REPO"
  say "=== 部署 jar（label=$label），镜像不重建 ==="
  docker compose -f docker-compose.yml -f bench/jvm-gc.override.yml \
                 -f bench/jar-mount.override.yml \
                 up -d --no-deps --force-recreate server-1 server-2 >/dev/null 2>&1 || {
    echo "✗ compose up 失败" >&2; exit 3; }
  docker compose restart gateway >/dev/null 2>&1 || true
  for _ in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health" || true)" = "200" ] && break
    sleep 1
  done
  for c in qiqi-server-1 qiqi-server-2; do
    echo "  $c jar md5=$(docker exec "$c" sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1)" \
         "mount=$(docker inspect "$c" --format '{{range .Mounts}}{{.Source}}{{end}}')"
  done
  echo "  host jar md5=$(md5sum "$jar" | cut -d' ' -f1)  git HEAD=$(git -C "$REPO" rev-parse --short HEAD)  dirty=$(git -C "$REPO" status --porcelain | wc -l)"
  echo "  镜像（未重建）: $(docker inspect qiqi-mall-server:test --format '{{.Id}}')"
  say "✅ 部署完成"
}

# ---------------------------------------------------------------- snap（累计值快照）
cmd_snap() {
  local out=${1:?snap <输出文件>}
  mkdir -p "$(dirname "$out")"
  {
    echo "#epoch_ms=$(date +%s%3N)"
    sql_q "
      select 'TXN', EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT
        from performance_schema.events_transactions_summary_global_by_event_name;
      select 'STMT_TOTAL', coalesce(SUM(COUNT_STAR),0), coalesce(SUM(SUM_TIMER_WAIT),0),
             coalesce(SUM(SUM_LOCK_TIME),0), coalesce(SUM(SUM_ROWS_AFFECTED),0), coalesce(SUM(SUM_ROWS_EXAMINED),0)
        from performance_schema.events_statements_summary_by_digest where SCHEMA_NAME='qiqi_mall_test';
      select 'WAIT', EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT
        from performance_schema.events_waits_summary_global_by_event_name
        where COUNT_STAR > 0 and (EVENT_NAME like 'wait/io/file/innodb/%' or EVENT_NAME like 'wait/io/socket/%'
              or EVENT_NAME like 'wait/io/table/%');
      select 'DIGEST', DIGEST, COUNT_STAR, SUM_TIMER_WAIT, SUM_LOCK_TIME,
             SUM_ROWS_AFFECTED, SUM_ROWS_EXAMINED, substring(DIGEST_TEXT,1,160)
        from performance_schema.events_statements_summary_by_digest
        where SCHEMA_NAME='qiqi_mall_test' and COUNT_STAR > 0 order by COUNT_STAR desc limit 60;
    "
    # MySQL 8.4 的 performance_schema.global_status 里**没有** Com_*（只剩 1 个），所以计数走 SHOW GLOBAL STATUS
    sql_q "show global status" | awk -F'\t' '$1 ~ /^(Innodb_row_lock_time|Innodb_row_lock_waits|Innodb_row_lock_current_waits|Innodb_os_log_fsyncs|Innodb_data_fsyncs|Innodb_log_waits|Innodb_log_writes|Innodb_log_write_requests|Innodb_data_writes|Com_commit|Com_begin|Com_select|Questions|Queries|Threads_running|Threads_connected|Threads_created|Innodb_rows_inserted|Innodb_rows_updated)$/ {print "STAT\t" $1 "\t" $2}'
  } > "$out" 2>&1
  echo "  快照 → $out（$(grep -c . "$out") 行）"
}

# ---------------------------------------------------------------- 数据前置（与审计那轮同口径）
prep_data() {
  local need=$1
  say "--- reset 订单/台账（prepare.sh reset）"
  MEMBER_PASS="$MEMBER_PASS" MYSQL_PASSWORD="$MYSQL_PASSWORD" BENCH_WORKDIR="$LG" \
    "$SELF/prepare.sh" reset >/dev/null 2>&1 && say "  ✅ reset" || { echo "✗ reset 失败" >&2; return 1; }
  docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql --default-character-set=utf8mb4 -uroot qiqi_mall_test \
    < "$REPO/sql/reset-sku-bucket.sql" >/dev/null 2>&1 && say "  ✅ 桶重置" || { echo "✗ 桶重置失败" >&2; return 1; }
  local chk
  chk=$(sql_db "select concat('bad=',(select count(*) from (select sku_id,count(*) c from sku_bucket where sku_id between 1 and 21 group by sku_id having c<>8) x),
                                ' drift=',(select count(*) from sku s where s.id between 1 and 21 and s.stock<>coalesce((select sum(stock) from sku_bucket b where b.sku_id=s.id),0)));")
  say "  自检 $chk"
  case "$chk" in *bad=0*drift=0*) ;; *) echo "✗ 库存自检不过：$chk" >&2; return 1 ;; esac
  # purge 消化门禁（reset 会一次删掉上一轮几万单，undo 没消化完开跑会把 p50 抬到秒级）
  local lag=0 waited=0
  while [ "$waited" -lt 40 ]; do
    lag=$(sql_q "select count from information_schema.innodb_metrics where name='trx_rseg_history_len';" | tr -d '\r')
    lag=${lag:-0}
    [ "$lag" -lt 400 ] && break
    sleep 5; waited=$(( waited + 5 ))
  done
  say "  purge 消化：lag=$lag（等了 ${waited}s）"
  # 令牌切片：每轮全新一片（GETDEL 一次性消费，复用同一批会成片报「请勿重复提交」）
  local off_file=$LG/.token_offset off total
  off=$(cat "$off_file" 2>/dev/null || echo 0)
  total=$(wc -l < "$LG/tokens.txt")
  [ $(( off + need )) -gt $(( total - 1000 )) ] && off=0
  echo $(( off + need )) > "$off_file"
  say "--- 灌幂等令牌：切片 [$off, $(( off + need ))) 共 $need 条 / 池 $total"
  sed -n "$(( off + 1 )),$(( off + need ))p" "$LG/tokens.txt" > "$LG/tokens_run.txt"
  python3 - "$LG/tokens_run.txt" 1800000 <<'PY' | docker exec -i qiqi-redis redis-cli --pipe >/dev/null
import sys
path, ttl = sys.argv[1], sys.argv[2]
w = sys.stdout.write
for line in open(path):
    t = line.strip()
    if not t:
        continue
    k = "idem:order:" + t
    w("*5\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$1\r\n1\r\n$2\r\nPX\r\n$%d\r\n%s\r\n"
      % (len(k), k, len(ttl), ttl))
PY
  say "  DBSIZE=$(docker exec qiqi-redis redis-cli DBSIZE | tr -d '\r')"
}

# ---------------------------------------------------------------- probe（事务↔语句 归因）
cmd_probe() {
  local label=${1:?probe <label> [n] [rps]} n=${2:-150} rps=${3:-15}
  local out=$OUTROOT/$label; mkdir -p "$out"
  local log=$out/run.log; : > "$log"
  prep_data "$(( n + 50 ))" |& tee -a "$log" || exit 4
  say "--- 打开 history_long（**只用于本探针**，吞吐轮开跑前会硬门禁并要求关闭）"
  sql_q "update performance_schema.setup_consumers set ENABLED='YES' where NAME in
          ('events_statements_history_long','events_transactions_history_long');"
  # 环形缓冲必须先**清空**：不清空时它保留着上几轮几千条语句，配对会缺行（实测：主事务只配到 8/10 条），
  # 拆出来的「间隙」全是假的。truncate 只清 P_S 的缓冲表，不碰任何业务数据。
  sql_q "truncate table performance_schema.events_statements_history_long;
         truncate table performance_schema.events_transactions_history_long;"
  sql_q "select concat(NAME,'=',ENABLED) from performance_schema.setup_consumers
          where NAME in ('events_statements_history_long','events_transactions_history_long');" | tee -a "$log"
  local secs=$(( n / rps + 4 ))
  say "--- 探针流量 ${rps}rps × ${secs}s（n=$n）"
  node "$BENCH/gen.mjs" --url "$BASE/portal/orders" --method POST \
    --steps "$rps" --step-duration "$secs" --warmup 5 --warmup-rps 5 \
    --token-file "$LG/tokens_run.txt" --auth-file "$LG/jwts.txt" --body-file "$LG/order.json" \
    --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
    --outdir "$out" --label "$label" --max-lag-ms 3000 >>"$log" 2>&1
  say "--- dump 事务↔语句（history_long）"
  cmd_dump "$out"
  say "--- 关掉 history_long（必须！每秒 7.7k 语句时它能把 380 档压垮）"
  sql_q "update performance_schema.setup_consumers set ENABLED='NO' where NAME in
          ('events_statements_history_long','events_transactions_history_long');"
  sql_q "select concat(NAME,'=',ENABLED) from performance_schema.setup_consumers
          where NAME in ('events_statements_history_long','events_transactions_history_long');" | tee -a "$log"
  python3 "$SELF/txncost.py" --probe "$out" | tee -a "$log"
  say "✅ 探针完成：$out"
}

# ---------------------------------------------------------------- run（定速 + 窗口前后差）
cmd_run() {
  local label=${1:?run <label> <rps> <secs> <warmup> <warmup_rps>}
  local rps=${2:-300} secs=${3:-75} ws=${4:-90} wrps=${5:-100}
  local out=$OUTROOT/$label; mkdir -p "$out"
  local log=$out/run.log; : > "$log"
  # history_long 必须是关的（硬门禁，见 avalanche.sh 的教训）
  local hist; hist=$(sql_q "select ENABLED from performance_schema.setup_consumers where NAME='events_statements_history_long'" | tr -d '\r')
  [ "${hist:-NO}" = "NO" ] || { echo "✗ events_statements_history_long 还开着，拒绝开跑" >&2; exit 3; }
  local need=$(( rps * secs + ws * wrps ))
  need=$(( need * 12 / 10 ))
  prep_data "$need" |& tee -a "$log" || { echo "✗ 前置失败" >&2; exit 4; }

  # 采样器：cgroup 200ms / 宿主机 5s / MySQL 计数器 2s / MySQL cgroup CPU 1s
  INTERVAL_MS=200 "$SELF/cgwatch.sh" "$out/cg.csv" qiqi-server-1 qiqi-server-2 >>"$log" 2>&1 &
  local cg=$!
  "$SELF/envsample2.sh" "$out/env.csv" >>"$log" 2>&1 &
  local env=$!
  INTERVAL_MS=1000 "$SELF/mycpu.sh" "$out/mycpu.csv" qiqi-mysql >>"$log" 2>&1 &
  local mycpu=$!
  ( echo "epoch,threads_run,threads_conn,row_lock_waits,row_lock_time_ms,com_commit,com_begin,questions,log_fsyncs,data_fsyncs,log_waits,rows_ins,rows_upd"
    while :; do
      k=$(date +%s)
      sql_q "select concat(
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Threads_running'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Threads_connected'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Innodb_row_lock_waits'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Innodb_row_lock_time'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Com_commit'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Com_begin'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Questions'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Innodb_os_log_fsyncs'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Innodb_data_fsyncs'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Innodb_log_waits'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Innodb_rows_inserted'),',',
        (select VARIABLE_VALUE from performance_schema.global_status where VARIABLE_NAME='Innodb_rows_updated'))" \
        | sed "s/^/$k,/" >> "$out/mysql.csv"
      sleep 2
    done ) &
  local my=$!
  sleep 2

  say "--- gen.mjs ${rps}rps × ${secs}s（预热 ${ws}s@${wrps}rps）"
  node "$BENCH/gen.mjs" --url "$BASE/portal/orders" --method POST \
    --steps "$rps" --step-duration "$secs" --warmup "$ws" --warmup-rps "$wrps" \
    --token-file "$LG/tokens_run.txt" --auth-file "$LG/jwts.txt" --body-file "$LG/order.json" \
    --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
    --outdir "$out" --label "$label" --max-lag-ms 3000 >>"$log" 2>&1 &
  local gen=$!
  # 窗口 = 测量档中段（避开升档瞬间与收尾排空）：预热结束后 3s 取 before，测量档结束前 4s 取 after
  sleep $(( ws + 3 ))
  cmd_snap "$out/snap_before.txt"
  sleep $(( secs - 7 ))
  cmd_snap "$out/snap_after.txt"
  wait "$gen"; local rc=$?

  sleep 1
  kill "$cg" "$env" "$mycpu" "$my" 2>/dev/null; wait 2>/dev/null
  {
    echo "label=$label rps=$rps secs=$secs warmup=${ws}s@${wrps}"
    echo "gen_rc=$rc"
    echo "jar_md5=$(docker exec qiqi-server-1 sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1)"
    echo "image=$(docker inspect qiqi-mall-server:test --format '{{.Id}}')"
    echo "git_HEAD=$(git -C "$REPO" rev-parse --short HEAD) dirty=$(git -C "$REPO" status --porcelain | wc -l)"
    echo "p_s_history_long=$(sql_q "select ENABLED from performance_schema.setup_consumers where NAME='events_statements_history_long'" | tr -d '\r')"
  } > "$out/evidence.txt"
  cat "$out/evidence.txt" | tee -a "$log"
  python3 "$SELF/txncost.py" "$out" | tee -a "$log"
  say "✅ 完成：$out"
}

# ---------------------------------------------------------------- dump（只把环形缓冲倒出来，不跑流量）
cmd_dump() {
  local out=${1:?dump <dir>}
  mkdir -p "$out"
  sql_q "
    select 'TXN', t.THREAD_ID, t.EVENT_ID, t.STATE,
        t.TIMER_WAIT, t.TIMER_START, t.TIMER_END, t.ISOLATION_LEVEL, coalesce(t.ACCESS_MODE,'-')
      from performance_schema.events_transactions_history_long t
      order by t.THREAD_ID, t.EVENT_ID;" > "$out/txn.txt"
  sql_q "
    select 'STMT', s.THREAD_ID, s.EVENT_ID, s.NESTING_EVENT_ID, coalesce(s.NESTING_EVENT_TYPE,'-'),
        s.TIMER_WAIT, s.TIMER_START, s.TIMER_END, s.LOCK_TIME, s.ROWS_AFFECTED, s.ROWS_SENT,
        coalesce(s.CURRENT_SCHEMA,'-'), substring(s.SQL_TEXT,1,150)
      from performance_schema.events_statements_history_long s
      order by s.THREAD_ID, s.EVENT_ID;" > "$out/stmt.txt"
  echo "  事务行=$(grep -c '^TXN' "$out/txn.txt")  语句行=$(grep -c '^STMT' "$out/stmt.txt")"
  python3 "$SELF/txncost.py" --probe "$out"
}

cmd_restore() {  sql_q "update performance_schema.setup_consumers set ENABLED='NO' where NAME in
          ('events_statements_history_long','events_transactions_history_long');" >/dev/null 2>&1
  cd "$REPO" && "$SELF/jar-deploy.sh" --restore
}

case "${1:-}" in
  deploy)  shift; cmd_deploy "$@" ;;
  snap)    shift; cmd_snap "$@" ;;
  probe)   shift; cmd_probe "$@" ;;
  run)     shift; cmd_run "$@" ;;
  dump)    shift; cmd_dump "$@" ;;
  restore) cmd_restore ;;
  *) sed -n '2,26p' "$0" >&2; exit 2 ;;
esac
