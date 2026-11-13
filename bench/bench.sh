#!/usr/bin/env bash
# bench.sh —— 一键 harness（开环阶梯压测）
#
#   ./bench.sh                 # 用默认阶梯
#   STEPS=100,178,207,340,364,420,500 STEP_DURATION=45 WARMUP=60 ./bench.sh
#
# 只做一次 reset（开始前）、一次预热（默认 60s，不计入统计）、一次收尾清理。
# 全程 date +%s 打点；provenance（git HEAD / 镜像 digest / cgroup / 脚本 md5）落 out/provenance.txt
# 产出：out/{summary.csv, persecond.csv, samples.csv, curve.png, run.json, timings.txt}
set -uo pipefail
# 所有路径都由脚本自身位置/环境变量决定，脚本内不硬编码任何机器路径：
#   SELF  = 本脚本所在目录（= bench/）；工具在 SELF/tools/
#   OUT   = 本次运行产物目录（默认 SELF/out，可用 OUT= 覆盖）
#   LG    = 数据工作目录：令牌池 tokens.txt / JWT jwts.txt / 下单模板 order.json / 冷门 SKU 清单
#           （必须自己准备，不入库；默认 /tmp/loadgen，可用 LG= 覆盖）
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=${REPO:-$(cd "$SELF/.." && pwd)}
OUT=${OUT:-$SELF/out}
LG=${LG:-/tmp/loadgen}
STEPS=${STEPS:-"100,178,207,340,364,420,500"}
STEP_DURATION=${STEP_DURATION:-45}
WARMUP=${WARMUP:-60}
WARMUP_RPS=${WARMUP_RPS:-200}
LABEL=${LABEL:-step}
LOG=${LOG:-$SELF/bench.log}
mkdir -p "$OUT"
: > "$LOG"

log()  { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
now()  { date +%s; }
span() { echo "$1 -> $2  ($(( $2 - $1 ))s = $(( ($2-$1)/60 ))min$(( ($2-$1)%60 ))s)"; }

# ---------------------------------------------------------------- 0. 前置：环境是否真的空着
log "=== 0. 前置检查（环境互斥）==="
# 本机还有别的 agent 在压同一套环境（同一 redis 令牌池 + 同一个库）：
# 别人的 reset 会删掉我的订单、别人的压测会抢走我的幂等令牌 → 必须等它彻底安静。
QUIET_NEED=${QUIET_NEED:-90}
QUIET_MAX_WAIT=${QUIET_MAX_WAIT:-1800}
foreign_loadgen() { pgrep -af 'java.*LoadGen' 2>/dev/null | grep -v pgrep | grep -v -F "bench2" || true; }
round_active() { local f=${RIVAL_ROUND_LOG:-$LG/round-B32b.log}; [ -f "$f" ] || return 1; [ $(( $(date +%s) - $(stat -c %Y "$f") )) -lt 180 ]; }
Q0=$(now); QT=$(now)
while :; do
  if [ -n "$(foreign_loadgen)" ] || round_active; then Q0=$(now); else
    QT=$(now); [ $((QT-Q0)) -ge "$QUIET_NEED" ] && { log "✅ 环境安静 $((QT-Q0))s"; break; }
  fi
  [ $(( $(now) - Q0 )) -ge "$QUIET_MAX_WAIT" ] && { log "⚠ 等安静超时，继续（结果里标污染风险）"; break; }
  sleep 10
done
curl -s -o /dev/null -w "gateway /api/health -> %{http_code}\n" http://127.0.0.1:18080/api/health | tee -a "$LOG"

# ---------------------------------------------------------------- provenance
{
  echo "== provenance =="
  echo "collected_at=$(date '+%F %T %z') epoch=$(date +%s)"
  echo "host=$(hostname) cores=$(nproc) loadavg=$(cat /proc/loadavg)"
  # git HEAD 直接从 .git 文件读（不执行 git 命令，避免任何写仓库的可能）
  H=$(cat "$REPO/.git/HEAD" 2>/dev/null | awk '{print $2}')
  if [ -n "${H:-}" ] && [ -f "$REPO/.git/$H" ]; then
    echo "git_HEAD=$(cat "$REPO/.git/$H") ($H)"
  else
    echo "git_HEAD=$(cat "$REPO/.git/HEAD" 2>/dev/null)"
  fi
  echo "server_image=$(docker inspect qiqi-mall-server:test --format '{{.Id}}' 2>/dev/null)"
  echo "server_container_image=$(docker inspect qiqi-server-1 --format '{{.Image}}' 2>/dev/null)"
  echo "server_started_at=$(docker inspect qiqi-server-1 --format '{{.State.StartedAt}}' 2>/dev/null)"
  for c in qiqi-server-1 qiqi-server-2 qiqi-mysql qiqi-gateway; do
    echo "cgroup $c: cpu_quota=$(docker inspect $c --format '{{.HostConfig.CpuQuota}}' 2>/dev/null) " \
         "nano_cpus=$(docker inspect $c --format '{{.HostConfig.NanoCpus}}' 2>/dev/null) " \
         "cpu_shares=$(docker inspect $c --format '{{.HostConfig.CpuShares}}' 2>/dev/null) " \
         "mem_limit=$(docker inspect $c --format '{{.HostConfig.Memory}}' 2>/dev/null)"
  done
  echo "script_md5 $(md5sum "$SELF/gen.mjs" "$SELF/bench.sh" "$SELF/tools/analyze.py" "$SELF/oldmethod.sh" 2>/dev/null | awk '{print $2"="$1}' | tr '\n' ' ')"
  echo "loadgen_reused $(md5sum "$SELF/legacy/LoadGen.java" "$SELF/tools/prepare.sh" 2>/dev/null | awk '{print $2"="$1}' | tr '\n' ' ')"
  echo "steps=$STEPS step_duration=${STEP_DURATION}s warmup=${WARMUP}s@${WARMUP_RPS}rps"
  echo "tokens=$(wc -l < "$LG/tokens.txt") jwts=$(wc -l < "$LG/jwts.txt") redis_dbsize=$(docker exec -i qiqi-redis redis-cli DBSIZE 2>/dev/null)"
} | tee "$OUT/provenance.txt" >> "$LOG"

# ---------------------------------------------------------------- 1. 一次性 reset
T0=$(now); log "=== 1. reset（全程唯一一次）T0=$T0 ==="
"$SELF/tools/prepare.sh" reset >> "$LOG" 2>&1
"$SELF/tools/prepare.sh" precheck >> "$LOG" 2>&1
T_RESET=$(now)
log "reset+precheck 完成，耗时 $((T_RESET-T0))s"

# ---------------------------------------------------------------- 2. 一次性灌令牌（TTL 30min，必须开跑前 30 分钟内）
T1=$(now); log "=== 2. seed 令牌池（唯一一次）==="
"$SELF/tools/prepare.sh" seed 2>&1 | tail -2 | tee -a "$LOG"
T_SEED=$(now)
log "seed 完成，耗时 $((T_SEED-T1))s"

# ---------------------------------------------------------------- 3. 资源采样器（5s 一次，落 bench2 自己的文件）
"$SELF/tools/envsample2.sh" "$OUT/env.csv" &
ENVPID=$!
# 令牌池剩余量采样（10s）：幂等令牌是 GETDEL 原子消费的硬资源，谁抢到谁下单
( while :; do echo "$(date +%s),$(docker exec -i qiqi-redis redis-cli DBSIZE 2>/dev/null | tr -d '\r')"; sleep 10; done ) > "$OUT/redis.csv" 2>/dev/null &
REDISPID=$!
( while :; do
    echo "$(date +%s),$(docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' qiqi-server-1 qiqi-server-2 qiqi-mysql 2>/dev/null | tr '\n' ' ')"
    sleep 5
  done ) > "$OUT/docker-stats.csv" 2>/dev/null &
SAMPLER=$!
cleanup_sampler() { kill "$SAMPLER" "$ENVPID" "$REDISPID" 2>/dev/null; wait "$SAMPLER" 2>/dev/null; }
trap cleanup_sampler EXIT

# ---------------------------------------------------------------- 4. 开环阶梯压测（一个进程跑完全部档）
T2=$(now); log "=== 3. 开环阶梯压测开始 T2=$T2（含 ${WARMUP}s 预热）==="
RS_BEFORE=$(docker exec -i qiqi-redis redis-cli DBSIZE 2>/dev/null | tr -d '\r')
log "令牌池 DBSIZE(开跑前)=$RS_BEFORE"
node "$SELF/gen.mjs" --url http://127.0.0.1:18080/portal/orders --method POST \
  --steps "$STEPS" --step-duration "$STEP_DURATION" \
  --warmup "$WARMUP" --warmup-rps "$WARMUP_RPS" \
  --token-file "$LG/tokens.txt" --auth-file "$LG/jwts.txt" --body-file "$LG/order.json" \
  --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
  --outdir "$OUT" --label "$LABEL" --max-lag-ms 3000 2>&1 | tee -a "$LOG"
GEN_RC=${PIPESTATUS[0]}
T3=$(now)
cleanup_sampler
trap - EXIT
RS_AFTER=$(docker exec -i qiqi-redis redis-cli DBSIZE 2>/dev/null | tr -d '\r')
log "gen.mjs 退出码=$GEN_RC，压测段墙上时间 $((T3-T2))s；令牌池 DBSIZE $RS_BEFORE -> $RS_AFTER（消耗 $((RS_BEFORE-RS_AFTER))）"

# ---------------------------------------------------------------- 5. 收尾清理（唯一一次）
T4=$(now); log "=== 4. 收尾清理 ==="
"$SELF/tools/prepare.sh" reset >> "$LOG" 2>&1
T5=$(now)
log "收尾清理耗时 $((T5-T4))s"

# ---------------------------------------------------------------- 6. 切片 + 出表出图
log "=== 5. 切档聚合 + 出图 ==="
python3 "$SELF/tools/analyze.py" "$OUT" 2>&1 | tee -a "$LOG"

# ---------------------------------------------------------------- 7. 打点汇总
{
  echo "== timings (date +%s 真实打点) =="
  echo "T0_start              = $T0   $(date -d @$T0 '+%F %T')"
  echo "T_reset_done          = $T_RESET  (+$((T_RESET-T0))s)"
  echo "T_seed_done           = $T_SEED   (+$((T_SEED-T_RESET))s)"
  echo "T_bench_start         = $T2   (+$((T2-T_SEED))s)"
  echo "T_bench_end           = $T3"
  echo "  压测段（含预热）     = $((T3-T2))s = $(( (T3-T2)/60 ))min$(( (T3-T2)%60 ))s"
  echo "  其中预热 $WARMUP s（不计入统计）/ 阶梯 $(( ${#STEPS} * 0 + $(echo $STEPS | awk -F, '{print NF}') * STEP_DURATION ))s"
  echo "T_cleanup_done        = $T5  (+$((T5-T4))s)"
  echo "T_total_wall          = $((T5-T0))s = $(( (T5-T0)/60 ))min$(( (T5-T0)%60 ))s   (reset+seed+压测+清理+分析)"
  echo "  新法纯测量段(不含预热) = $((T3-T2-WARMUP))s"
} | tee "$OUT/timings.txt" | tee -a "$LOG"

log "=== 全部完成 wall=$((T5-T0))s ==="
