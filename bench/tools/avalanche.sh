#!/usr/bin/env bash
# avalanche.sh —— 雪崩实验（A 型：内存/线程耗尽）一键基线阶梯
#
#   一条命令复现：
#     MEMBER_PASS='LgBench2026' bench/tools/avalanche.sh
#   自定义阶梯：
#     STEPS=50,100,150,200 STEP_DURATION=75 MEMBER_PASS='...' bench/tools/avalanche.sh
#
# 它做的四件事（顺序不能换）：
#   ① 数据前置：会员/JWT → reset 订单 → **把库存写进桶**（分桶模式下 sku.stock 只是缓存！）
#      → 三自检（桶行数=SKU×N / 每 SKU 桶数=N / 缓存与桶偏差=0）→ 灌幂等令牌池
#   ② 三个采样器同时起：cgroup 200ms（cgwatch.sh）/ load+MemAvailable 5s / docker stats 5s
#   ③ gen.mjs 开环定速阶梯（延迟起点=计划时刻，TPS 报 ok/s，饱和会自己标 saturated）
#   ④ 收尾：抓两个容器的 GC 日志 → gcwatch.py 解析 → avalanche.py 出阶梯表 + 雪崩点判定
#
# 不做什么：不改业务代码、不重建镜像、不改容器内存/CPU 限额、不动数据库结构。
# 观测 JVM 的唯一手段是 JAVA_OPTS 追加 -Xlog:gc*（镜像里是 JRE，没有 jcmd/jstat），
# 需要**重启容器**（不重建镜像），由 tools/jvm-gc-on.sh / jvm-gc-off.sh 负责开关。
set -uo pipefail

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH=$(cd "$SELF/.." && pwd)
REPO=$(cd "$BENCH/.." && pwd)
OUT=${OUT:-$BENCH/results/$(date '+%F')-avalanche-${LABEL:-ladder}}
LG=${LG:-/tmp/loadgen}
STEPS=${STEPS:-"50,100,150,200,250,300,350,400,450,500"}
STEP_DURATION=${STEP_DURATION:-75}
WARMUP=${WARMUP:-60}
WARMUP_RPS=${WARMUP_RPS:-100}
LABEL=${LABEL:-ladder}
MEMBERS=${MEMBERS:-200}
MEMBER_PASS=${MEMBER_PASS:-}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
GUARD_MIN_MB=${GUARD_MIN_MB:-1024}     # 宿主机可用内存低于这个 → 立刻掐掉压测（防止把 mysql 一起 OOM 掉）
LOCK=${LOCK:-/root/.qiqi-standing/bench-lock.sh}
CONTAINERS="qiqi-server-1 qiqi-server-2"

[ -n "$MYSQL_PASSWORD" ] || { echo "缺 MYSQL_PASSWORD（或 $REPO/.env 里没有 MYSQL_ROOT_PASSWORD）" >&2; exit 2; }
[ -n "$MEMBER_PASS" ]    || { echo "缺 MEMBER_PASS（压测会员口令，由调用方给）" >&2; exit 2; }
mkdir -p "$OUT" "$LG"
LOG=$OUT/run.log
: > "$LOG"
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
need_tokens() {
  echo "$STEPS" | awk -v w="$WARMUP" -v wr="$WARMUP_RPS" -v d="$STEP_DURATION" -F, '
    {n = w*wr; for (i=1;i<=NF;i++) n += $i*d; printf "%d\n", int(n*1.15)}'
}

sql_db() { docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql --default-character-set=utf8mb4 -uroot -N -e "use qiqi_mall_test; $1" 2>/dev/null; }

# ---------------------------------------------------------------- 采样器/锁的收尾
CG_PID=""; ENV_PID=""; DS_PID=""; GEN_PID=""; WD_PID=""
cleanup() {
  for p in "$CG_PID" "$ENV_PID" "$DS_PID" "$WD_PID"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
  if [ -n "$GEN_PID" ] && kill -0 "$GEN_PID" 2>/dev/null; then kill "$GEN_PID" 2>/dev/null; fi
  [ -x "$LOCK" ] && "$LOCK" release >>"$LOG" 2>&1 || true
}
trap cleanup EXIT INT TERM

# ================================================================ 0. 前置
log "=== 0. 前置检查 ==="
PW=$(ps -eo pid,cmd | grep -i playwright | grep -v grep | grep -v 'bash -c' || true)
if [ -n "$PW" ]; then log "✗ 有人在跑 e2e（playwright），退出：$PW"; exit 3; fi
log "✅ 无 e2e 在跑"
if ! "$LOCK" acquire "avalanche-$LABEL" >>"$LOG" 2>&1; then
  echo "✗ 压测锁被占：$("$LOCK" status 2>&1 | head -2)" >&2; exit 3
fi
log "✅ 已持压测独占锁"
for c in $CONTAINERS; do
  st=$(docker inspect "$c" --format '{{.State.Health.Status}}' 2>/dev/null)
  log "  $c health=$st java_opts=$(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -o 'Xlog:gc[^ ]*' || echo '(无 GC 日志!)')"
done
curl -s -o /dev/null -w "  gateway /api/health -> %{http_code}\n" http://127.0.0.1:18080/api/health | tee -a "$LOG"

# ================================================================ 1. 数据前置
log "=== 1. 数据前置 ==="
NEED=$(need_tokens)
HAVE=$(wc -l < "$LG/tokens.txt" 2>/dev/null || echo 0)
log "幂等令牌需求（含 15% 余量）=$NEED，现有=$HAVE"
if [ "$HAVE" -lt "$NEED" ]; then
  TOKENS=$(( NEED * 2 )) "$SELF/prepare.sh" tokens 2>&1 | tail -2 | tee -a "$LOG"
fi

MEM_DB=$(sql_db "select count(*) from member where username like 'lg\\_%';")
JWT_N=$(wc -l < "$LG/jwts.txt" 2>/dev/null || echo 0)
log "库里压测会员=$MEM_DB，jwts.txt=$JWT_N（要求都是 $MEMBERS）"
if [ "${MEM_DB:-0}" -ne "$MEMBERS" ] || [ "$JWT_N" -ne "$MEMBERS" ]; then
  log "→ 重建 $MEMBERS 个压测会员与 JWT（prepare.sh members 幂等：先注册，已存在则登录）"
  MEMBER_PASS="$MEMBER_PASS" MYSQL_PASSWORD="$MYSQL_PASSWORD" BENCH_WORKDIR="$LG" \
    "$SELF/prepare.sh" members 2>&1 | tail -3 | tee -a "$LOG"
  JWT_N=$(wc -l < "$LG/jwts.txt" 2>/dev/null || echo 0)
  [ "$JWT_N" -eq "$MEMBERS" ] || { log "✗ JWT 只有 $JWT_N 条，退出"; exit 4; }
fi

log "--- reset 订单/台账（prepare.sh reset：只删 lg_% 名下的单，不碰别人的）"
MEMBER_PASS="$MEMBER_PASS" MYSQL_PASSWORD="$MYSQL_PASSWORD" BENCH_WORKDIR="$LG" \
  "$SELF/prepare.sh" reset >>"$LOG" 2>&1 && log "✅ reset 完成" || { log "✗ reset 失败"; exit 4; }

log "--- 把库存写进桶（sql/reset-sku-bucket.sql：分桶模式下 sku.stock 只是展示缓存，只改它不生效）"
docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql \
  mysql --default-character-set=utf8mb4 -uroot qiqi_mall_test \
  < "$REPO/sql/reset-sku-bucket.sql" >>"$LOG" 2>&1 \
  && log "✅ 桶已重置（sku 1..21 各 8 桶 × 500000）" || { log "✗ 桶重置失败"; exit 4; }

log "--- 三自检（桶行数 = SKU×N / 每 SKU 桶数 = N / 缓存与桶偏差 = 0）"
CHK=$(sql_db "
select concat('rows=', (select count(*) from sku_bucket where sku_id between 1 and 21),
  ' want=', (select count(*) from sku where id between 1 and 21) * 8) as c1;
select concat('bad_count=', (select count(*) from (select sku_id,count(*) c from sku_bucket
  where sku_id between 1 and 21 group by sku_id having c <> 8) x)) as c2;
select concat('drift=', (select count(*) from sku s where s.id between 1 and 21
  and s.stock <> coalesce((select sum(stock) from sku_bucket b where b.sku_id=s.id),0))) as c3;
select concat('sku1_bucket_sum=', coalesce((select sum(stock) from sku_bucket where sku_id=1),0),
  ' bucket_min=', coalesce((select min(stock) from sku_bucket where sku_id=1),0));")
echo "$CHK" | tee -a "$LOG"
C1=$(echo "$CHK" | sed -n 's/^rows=\([0-9]*\) want=\([0-9]*\)$/\1 \2/p')
set -- $C1
if [ "${1:-x}" != "${2:-y}" ]; then log "✗ 自检①不过（桶行数 $1 ≠ $2），退出"; exit 5; fi
echo "$CHK" | grep -q '^bad_count=0$'  || { log "✗ 自检②不过（有 SKU 桶数 ≠ 8），退出"; exit 5; }
echo "$CHK" | grep -q '^drift=0$'      || { log "✗ 自检③不过（缓存与桶有偏差），退出"; exit 5; }
log "✅ 三自检全绿"

log "--- 灌幂等令牌池（TTL 1800s，必须在开跑前 30 分钟内）"
MYSQL_PASSWORD="$MYSQL_PASSWORD" BENCH_WORKDIR="$LG" "$SELF/prepare.sh" seed 2>&1 | tail -3 | tee -a "$LOG"

# ================================================================ 2. 采样器
log "=== 2. 起采样器 ==="
INTERVAL_MS=200 "$SELF/cgwatch.sh" "$OUT/cg.csv" $CONTAINERS >>"$LOG" 2>&1 &
CG_PID=$!
"$SELF/envsample2.sh" "$OUT/env.csv" >>"$LOG" 2>&1 &
ENV_PID=$!
( while :; do
    echo "$(date +%s),$(docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
      qiqi-server-1 qiqi-server-2 2>/dev/null | tr '\n' ' ')"
    sleep 5
  done ) > "$OUT/docker-stats.csv" 2>/dev/null &
DS_PID=$!
log "  cgwatch(200ms)=$CG_PID envsample(5s)=$ENV_PID dockerstats(5s)=$DS_PID"

# 宿主机内存看门狗：可用内存掉到 GUARD_MIN_MB 以下就掐掉压测（按 pid 精确 kill，不做宽匹配）
( while :; do
    ma=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)
    if [ "$ma" -lt "$GUARD_MIN_MB" ]; then
      echo "[$(date '+%F %T')] ⚠⚠ 宿主机可用内存 ${ma}MB < ${GUARD_MIN_MB}MB，掐掉压测（保护 mysql/宿主机）" | tee -a "$LOG"
      [ -n "${GEN_PID:-}" ] && kill -TERM "$GEN_PID" 2>/dev/null
      break
    fi
    sleep 2
  done ) &
WD_PID=$!

# ================================================================ 3. 压测
log "=== 3. 开环阶梯开始（steps=$STEPS × ${STEP_DURATION}s，预热 ${WARMUP}s@${WARMUP_RPS}rps）==="
node "$BENCH/gen.mjs" --url http://127.0.0.1:18080/portal/orders --method POST \
  --steps "$STEPS" --step-duration "$STEP_DURATION" \
  --warmup "$WARMUP" --warmup-rps "$WARMUP_RPS" \
  --token-file "$LG/tokens.txt" --auth-file "$LG/jwts.txt" --body-file "$LG/order.json" \
  --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
  --outdir "$OUT" --label "$LABEL" --max-lag-ms 3000 >>"$LOG" 2>&1 &
GEN_PID=$!
wait "$GEN_PID"; GEN_RC=$?
GEN_PID=""
log "gen.mjs 退出码=$GEN_RC"

# ================================================================ 4. 收尾分析
log "=== 4. 抓 GC 日志 + 解析 ==="
i=1
for c in $CONTAINERS; do
  docker exec "$c" sh -c 'cat /tmp/gc.log' > "$OUT/gc-$c.log" 2>/dev/null \
    && log "  $c GC 日志 $(wc -l < "$OUT/gc-$c.log") 行" || log "  ⚠ $c 没抓到 GC 日志（JAVA_OPTS 里没有 -Xlog:gc*？）"
  i=$((i+1))
done
python3 "$SELF/gcwatch.py" "$OUT" \
  $(for c in $CONTAINERS; do printf '%s=%s ' "$c" "$OUT/gc-$c.log"; done) 2>&1 | tee -a "$LOG"

log "=== 5. 出阶梯表 + 雪崩点判定 ==="
python3 "$SELF/avalanche.py" "$OUT" 2>&1 | tee -a "$LOG"

log "=== 6. 清场（reset 掉本轮造的数据，别把库留给下一个人）==="
MEMBER_PASS="$MEMBER_PASS" MYSQL_PASSWORD="$MYSQL_PASSWORD" BENCH_WORKDIR="$LG" \
  "$SELF/prepare.sh" reset >>"$LOG" 2>&1 && log "✅ 清场完成"
docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql \
  mysql --default-character-set=utf8mb4 -uroot qiqi_mall_test \
  < "$REPO/sql/reset-sku-bucket.sql" >>"$LOG" 2>&1 && log "✅ 库存桶复位"

log "=== 全部完成；产物在 $OUT ==="
