#!/usr/bin/env bash
# auditcost.sh —— 「Redis Stream 异步化」第一步：量审计写入的成本（做/不做的判据）
#
#   子命令：
#     MEMBER_PASS=x ./auditcost.sh deploy on|off      # 用同一份 jar + 同一镜像，只切 qiqi.ledger.audit-enabled
#     MEMBER_PASS=x ./auditcost.sh run <label> [rps] [secs] [warmup_s] [warmup_rps]
#                                                     # 定速下单跑一段，落 digest 快照 + 审计行数 + 事务时长的**前后差**
#     MEMBER_PASS=x ./auditcost.sh ship <label> [n]    # 后台真实审计路径微基准：n 张已付款单逐张发货
#     ./auditcost.sh restore                           # 还原容器（拆挂载、去掉环境变量覆盖）
#
# 为什么需要它：
#   任务书要求「审计写入到底占多少」用 performance_schema DIGEST 量，并按 450 rps 档做「开/关审计」A/B。
#   本脚本只负责**跑**和**取证**，判断留给 auditcost.py / 报告。
#
# 铁律（与本轮任务书一致）：
#   · 不重建镜像、不动业务代码：镜像 qiqi-mall-server:test 不变，只多一个只读 bind mount + 一个环境变量
#   · 两臂唯一差异 = QIQI_LEDGER_AUDIT_ENABLED（audit 分项），status/wms/payment-trace 全开不变
#   · 压测前置：prepare.sh reset（只删 lg_% 名下）+ 桶重置 + 灌令牌；跑完再 reset 一次回干净态
#   · 独占：调用方自己拿 bench-lock.sh（本脚本不抢锁，避免嵌套）
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH=$(cd "$SELF/.." && pwd)
REPO=$(cd "$BENCH/.." && pwd)
LG=${LG:-/tmp/loadgen}
BASE=${BASE:-http://127.0.0.1:18080}
OUTROOT=${OUTROOT:-$BENCH/results/$(date '+%F')-audit-cost}
JAR=${JAR:-$REPO/qiqi-mall-server/target/qiqi-mall-server-0.0.1-SNAPSHOT.jar}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
MEMBER_PASS=${MEMBER_PASS:-unused-not-needed-for-this-path}
SKU_ID=${SKU_ID:-1}

sql_db() { docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql --default-character-set=utf8mb4 -uroot -N -e "use qiqi_mall_test; $1" 2>/dev/null; }
say() { echo "[$(date '+%F %T')] $*"; }

# ---------------------------------------------------------------- deploy
cmd_deploy() {
  local want=${1:?deploy on|off}
  local arm
  case "$want" in
    on)  arm=true ;;
    off) arm=false ;;
    *) echo "deploy on|off" >&2; exit 2 ;;
  esac
  [ -f "$JAR" ] || { echo "✗ 缺 jar：$JAR（先 mvn -o -DskipTests package）" >&2; exit 2; }
  local dest=$LG/jar/auditcost
  mkdir -p "$dest"; cp -f "$JAR" "$dest/app.jar"; chmod 644 "$dest/app.jar"
  export JAR_HOST_PATH=$dest/app.jar
  export BENCH_LEDGER_AUDIT_ENABLED=$arm
  cd "$REPO"
  say "=== 部署 jar（audit-enabled=$arm），镜像不重建 ==="
  docker compose -f docker-compose.yml -f bench/jvm-gc.override.yml \
                 -f bench/jar-mount.override.yml -f bench/ledger-audit.override.yml \
                 up -d --no-deps --force-recreate server-1 server-2 >/dev/null 2>&1 || {
    echo "✗ compose up 失败" >&2; exit 3; }
  docker compose restart gateway >/dev/null 2>&1 || true
  for _ in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health" || true)" = "200" ] && break
    sleep 1
  done
  # 生效证据：只看启动日志那一行（打的是**生效值**，已经与总闸与过）
  local line
  line=$(docker logs qiqi-server-1 2>&1 | grep -o '\[ledger\].*' | tail -1)
  echo "  server-1 $line"
  echo "  server-2 $(docker logs qiqi-server-2 2>&1 | grep -o '\[ledger\].*' | tail -1)"
  echo "  容器内 jar md5: $(docker exec qiqi-server-1 sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1) / host $(md5sum "$JAR" | cut -d' ' -f1)"
  echo "  镜像（未重建）: $(docker inspect qiqi-mall-server:test --format '{{.Id}}')"
  echo "$line" | grep -q "audit=$arm" || { echo "✗ 启动日志的 audit 生效值与期望($arm)不符 —— 不要继续跑" >&2; exit 4; }
  say "✅ 开关已生效并核对"
}

# ---------------------------------------------------------------- 数据前置（两臂完全一致）
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
  # reset 会一次删掉上一轮几万单（order/order_item/stock_ledger/台账），InnoDB 的 purge 线程
  # 要消化这批 undo —— 如果压测立刻开跑，purge 会和业务写抢磁盘，整轮 p50 会被抬到秒级
  # （本轮实测：同开关的两轮 p50 相差 200 倍，就是因为一轮撞上了 purge）。所以这里**等它消化完**。
  local lag=0 waited=0
  while [ "$waited" -lt 40 ]; do
    lag=$(sql_db "select count from information_schema.innodb_metrics where name='trx_rseg_history_len';" | tr -d '\r')
    lag=${lag:-0}
    [ "$lag" -lt 400 ] && break
    sleep 5; waited=$(( waited + 5 ))
  done
  say "  purge 消化：lag=$lag（等了 ${waited}s）"
  # 令牌池：**每轮都用一片全新的**。
  #   为什么不能复用 tokens.txt 开头：gen.mjs 的 tokenIdx 每次都从 0 开始，而令牌是
  #   GETDEL **一次性消费**的 —— 第二轮再喂同一批令牌会成片报「请勿重复提交订单」
  #   （本轮实测过：12000/12000 全失败）。所以用一条滚动偏移量切一刀，
  #   保证每轮 seed 的 key 集合与之前完全不重叠。
  local off_file=$LG/.token_offset
  local off
  off=$(cat "$off_file" 2>/dev/null || echo 0)
  [ $(( off + need * 2 )) -gt 470000 ] && off=0
  echo $(( off + need * 2 )) > "$off_file"
  say "--- 灌幂等令牌：切片 [$off, $(( off + need * 2 ))) 共 $(( need * 2 )) 条（TTL 30min）"
  sed -n "$(( off + 1 )),$(( off + need * 2 ))p" "$LG/tokens.txt" > "$LG/tokens_run.txt"
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

# ---------------------------------------------------------------- run（定速下单 + 前后差取证）
cmd_run() {
  local label=${1:?run <label> [rps] [secs] [warmup_s] [warmup_rps]}
  local rps=${2:-450} secs=${3:-60} ws=${4:-20} wrps=${5:-200}
  local out=$OUTROOT/$label
  mkdir -p "$out"
  local log=$out/run.log
  : > "$log"
  local need=$(( rps * (secs + ws) * 12 / 10 ))
  prep_data "$need" |& tee -a "$log" || { echo "✗ 前置失败" >&2; exit 4; }

  # 取证 A：审计表行数、事务聚合、DIGEST 聚合（全部是「自启动以来累计值」，用前后差）
  local a_before a_after
  a_before=$(sql_db "select count(*) from admin_audit_log;")
  sql_db "select concat(COUNT_STAR,',',SUM_TIMER_WAIT) from performance_schema.events_transactions_summary_global_by_event_name;" > "$out/txn_before.txt"
  MYSQL_PASSWORD="$MYSQL_PASSWORD" INTERVAL_S=1 TOPN=200 "$SELF/sqldigest.sh" "$out/sqldigest.csv" >>"$log" 2>&1 &
  local dig=$!
  # 宿主机侧的「环境漂移」取证：这台是共享机，CPU steal 会整段整段地变（实测两轮之间吞吐
  # 能差 45%）。vmstat 的 us/sy/id/wa/st 列 + loadavg 每 5s 一行，用来解释「两臂差异到底是不是环境」。
  ( : > "$out/env.csv"; echo "epoch,us,sy,id,wa,st,load1" > "$out/env.csv"
    while :; do
      read -r us sy id wa st _ <<< "$(vmstat 1 2 | tail -1 | awk '{print $13,$14,$15,$16,$17}')"
      echo "$(date +%s),$us,$sy,$id,$wa,$st,$(cut -d' ' -f1 /proc/loadavg)" >> "$out/env.csv"
      sleep 4
    done ) &
  local envpid=$!
  sleep 3
  say "--- gen.mjs ${rps}rps × ${secs}s（预热 ${ws}s@${wrps}rps）" | tee -a "$log"
  node "$BENCH/gen.mjs" --url "$BASE/portal/orders" --method POST \
    --steps "${STEPS_OVERRIDE:-$rps}" --step-duration "$secs" --warmup "$ws" --warmup-rps "$wrps" \
    --token-file "$LG/tokens_run.txt" --auth-file "$LG/jwts.txt" --body-file "$LG/order.json" \
    --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
    --outdir "$out" --label "$label" --max-lag-ms 3000 >>"$log" 2>&1
  local rc=$?
  sleep 2
  kill "$dig" "$envpid" 2>/dev/null; wait "$dig" 2>/dev/null
  a_after=$(sql_db "select count(*) from admin_audit_log;")
  sql_db "select concat(COUNT_STAR,',',SUM_TIMER_WAIT) from performance_schema.events_transactions_summary_global_by_event_name;" > "$out/txn_after.txt"
  {
    echo "label=$label rps=$rps secs=$secs warmup=${ws}s@${wrps}"
    echo "gen_rc=$rc"
    echo "ledger_line=$(docker logs qiqi-server-1 2>&1 | grep -o '\[ledger\].*' | tail -1)"
    echo "jar_md5=$(docker exec qiqi-server-1 sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1)"
    echo "audit_rows_before=$a_before audit_rows_after=$a_after delta=$((a_after-a_before))"
  } > "$out/evidence.txt"
  cat "$out/evidence.txt" | tee -a "$log"
  python3 "$SELF/auditcost.py" --step "${STEP_INDEX:-0}" "$out" | tee -a "$log"
  say "✅ 完成：$out"
}

# ---------------------------------------------------------------- ship（后台真实审计路径微基准）
cmd_ship() {
  local label=${1:?ship <label> [n]}
  local n=${2:-300}
  local out=$OUTROOT/$label
  mkdir -p "$out"; local log=$out/run.log; : > "$log"
  local admin_pass; admin_pass=$(cat /root/.qiqi-secrets/admin.txt 2>/dev/null)
  [ -n "$admin_pass" ] || { echo "✗ 缺 admin 口令" >&2; exit 2; }
  local atok
  atok=$(curl -s -X POST "$BASE/admin/auth/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"admin\",\"password\":\"$admin_pass\"}" \
      | python3 -c 'import sys,json; print((json.load(sys.stdin).get("data") or {}).get("accessToken",""))')
  [ -n "$atok" ] || { echo "✗ admin 登录失败" >&2; exit 2; }
  prep_data "$n" |& tee -a "$log" || exit 4

  say "--- 造 $n 张 status=1 已付款单（后台建单接口，与发货同一条审计链）"
  : > "$out/ids.txt"
  local now; now=$(date '+%Y%m%d%H%M%S')
  for i in $(seq 1 "$n"); do
    curl -s -X POST "$BASE/admin/orders" -H "Authorization: Bearer $atok" -H 'Content-Type: application/json' \
      -d "{\"order\":{\"orderSn\":\"AC$now$(printf '%05d' "$i")\",\"memberId\":107,\"status\":1,\"payType\":1,\"totalAmount\":1.00,\"receiverName\":\"AC\",\"receiverPhone\":\"13800000000\",\"receiverAddress\":\"ac\"},\"items\":[{\"productId\":1,\"productSkuId\":$SKU_ID,\"productQuantity\":1,\"productPrice\":1.00,\"productName\":\"ac\"}]}" \
      | python3 -c 'import sys,json
try: print((json.load(sys.stdin).get("data") or {}).get("id",""))
except Exception: print("")' >> "$out/ids.txt"
  done
  local made; made=$(grep -c '[0-9]' "$out/ids.txt" || true)
  say "  建成 $made 张"
  [ "$made" -ge "$n" ] || { echo "✗ 建单不足" >&2; exit 5; }

  local abefore; abefore=$(sql_db "select count(*) from admin_audit_log;")
  # 不看 CSV 采样（DIGEST 行会被 P_S 回收，长窗口做差会出负数），只在发货循环**前后**各取一次
  # P_S 聚合值，且同时打印条数差 —— 条数必须正好等于 n，那才说明窗口里只有我这一批审计。
  ps_audit() {
    sql_db "select concat(coalesce(sum(COUNT_STAR),0),',',round(coalesce(sum(SUM_TIMER_WAIT),0)/1e9,3)) from
      performance_schema.events_statements_summary_by_digest
      where SCHEMA_NAME='qiqi_mall_test' and DIGEST_TEXT like 'INSERT INTO \`admin_audit_log\`%';"
  }
  ps_all() {
    sql_db "select concat(coalesce(sum(COUNT_STAR),0),',',round(coalesce(sum(SUM_TIMER_WAIT),0)/1e9,3)) from
      performance_schema.events_statements_summary_by_digest where SCHEMA_NAME='qiqi_mall_test';"
  }
  ps_audit > "$out/ps_audit_before.txt"; ps_all > "$out/ps_all_before.txt"
  say "--- 逐张发货（串行，量单次操作墙钟 + 审计写入占比）"
  while read -r id; do
    [ -n "$id" ] || continue
    local t
    t=$(curl -s -o /dev/null -w '%{time_total}' -X POST "$BASE/admin/orders/$id/ship" -H "Authorization: Bearer $atok")
    echo "$t"
  done < "$out/ids.txt" > "$out/ship_s.txt"
  ps_audit > "$out/ps_audit_after.txt"; ps_all > "$out/ps_all_after.txt"
  local aafter; aafter=$(sql_db "select count(*) from admin_audit_log;")
  {
    echo "label=$label n=$n"
    echo "ledger_line=$(docker logs qiqi-server-1 2>&1 | grep -o '\[ledger\].*' | tail -1)"
    echo "audit_rows_before=$abefore audit_rows_after=$aafter delta=$((aafter-abefore))"
  } > "$out/evidence.txt"
  cat "$out/evidence.txt" | tee -a "$log"
  python3 "$SELF/auditcost.py" --ship "$out" | tee -a "$log"
  say "✅ 完成：$out"
}

cmd_restore() {
  cd "$REPO"
  unset BENCH_LEDGER_AUDIT_ENABLED
  "$SELF/jar-deploy.sh" --restore
}

case "${1:-}" in
  deploy)  shift; cmd_deploy "$@" ;;
  run)     shift; cmd_run "$@" ;;
  ship)    shift; cmd_ship "$@" ;;
  restore) cmd_restore ;;
  *) sed -n '2,25p' "$0" >&2; exit 2 ;;
esac
