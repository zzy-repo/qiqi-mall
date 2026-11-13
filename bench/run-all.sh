#!/usr/bin/env bash
# run-all.sh —— 一个独占窗口内跑完「实验 A + B + C」
#
# 顺序（关键：旧法与新法必须**相邻**测量，否则比的是机器不是方法）：
#   0) 取 /root/.qiqi-standing/bench-lock.sh 独占锁
#   1) 等 load1 < 2 并稳定（最多等 10 分钟）
#   2) 旧法：7 档 c1..c64，每档单独一场（reset → seed → JVM 冷启动 → 预热60 → 计时60）
#   3) 新法：开环阶梯一次跑完（一次 reset / 一次预热 / 一个进程）
#   4) 切档聚合 + 出图 + 新旧对比
#   5) 释放锁
#
# 全程把 load1 每 15s 采样一次落 load1.csv，锁窗口落 lock-window.csv，供报告「可判真伪」用。
set -uo pipefail
# 路径全部由脚本位置/环境变量决定（细节见 bench.sh 顶部注释）
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LOCK=${LOCK:-/root/.qiqi-standing/bench-lock.sh}
LOG=${LOG_RUNALL:-$SELF/run-all.log}
: > "$LOG"

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
now() { date +%s; }

# ---------------------------------------------------------------- 0. 独占锁
log "=== 0. 取独占锁 ==="
if ! "$LOCK" status >/dev/null 2>&1; then
  log "⚠ 锁当前被占：$("$LOCK" status 2>&1 | head -2 | tr '\n' ' ')"
fi
if ! "$LOCK" acquire "bench2-prototype"; then
  log "❌ 锁被别人占着，退出。请先等对方 release。"
  "$LOCK" status | tee -a "$LOG"
  exit 3
fi
trap '"$LOCK" release >/dev/null 2>&1; echo "[$(date +%F\ %T)] released" >> '$LOG EXIT
T_LOCK=$(now)
echo "phase,epoch,iso,note" > "$SELF/lock-window.csv"
echo "acquire,$T_LOCK,$(date '+%F %T'),bench2-prototype" >> "$SELF/lock-window.csv"

# load1 采样器（15s）
( while :; do echo "$(now),$(cut -d' ' -f1-3 /proc/loadavg),$(nproc)"; sleep 15; done ) > "$SELF/load1.csv" 2>/dev/null &
SAMPLER=$!

# ---------------------------------------------------------------- 1. 等 load1 落下来
log "=== 1. 等 load1 < 2 并稳定 60s（最多 600s）==="
WAIT0=$(now); STABLE_SINCE=$(now)
while :; do
  L=$(cut -d' ' -f1 /proc/loadavg)
  if awk -v l="$L" 'BEGIN{exit !(l<2)}'; then
    [ $(( $(now) - STABLE_SINCE )) -ge 60 ] && { log "✅ load1=$L 已稳定，开跑"; break; }
  else
    STABLE_SINCE=$(now)
  fi
  if [ $(( $(now) - WAIT0 )) -ge 600 ]; then log "⚠ 等 load1<2 超时 600s，当前 load1=$(cut -d' ' -f1-3 /proc/loadavg)，按现状开跑并记录"; break; fi
  sleep 10
done
echo "load1_ok,$(now),$(date '+%F %T'),load1=$(cut -d' ' -f1-3 /proc/loadavg)" >> "$SELF/lock-window.csv"

# ---------------------------------------------------------------- 2. 旧法 7 档
T_OLD0=$(now)
log "=== 2. 旧法 7 档（每档独立一场）开始 $T_OLD0 ==="
TIERS="${TIERS:-1 2 4 8 16 32 64}" WARMUP="${WARMUP:-60}" DURATION="${DURATION:-60}" "$SELF/oldmethod.sh"
T_OLD1=$(now)
echo "oldmethod,$T_OLD0,$(date -d @$T_OLD0 '+%F %T'),start" >> "$SELF/lock-window.csv"
echo "oldmethod_end,$T_OLD1,$(date -d @$T_OLD1 '+%F %T'),wall=$((T_OLD1-T_OLD0))s" >> "$SELF/lock-window.csv"
log "旧法完成，墙上 $((T_OLD1-T_OLD0))s = $(( (T_OLD1-T_OLD0)/60 ))min$(( (T_OLD1-T_OLD0)%60 ))s"

# ---------------------------------------------------------------- 2.5 由旧法实测结果确定阶梯
log "=== 2.5 由旧法实测 ok/s 生成阶梯（逐档对位 + 追加 1 个超载级找饱和点）==="
STEPS=$(python3 "$SELF/tools/mkladder.py" "$SELF/oldout/timings.csv")
log "阶梯 STEPS=$STEPS（前 N 级 = 旧法各档实测 ok/s；末级 = 1.35× 最高档，用于暴露饱和点）"

# ---------------------------------------------------------------- 3. 新法阶梯
T_NEW0=$(now)
log "=== 3. 新法开环阶梯开始 $T_NEW0 STEPS=$STEPS ==="
STEPS="$STEPS" "$SELF/bench.sh"
T_NEW1=$(now)
echo "newmethod,$T_NEW0,$(date -d @$T_NEW0 '+%F %T'),start" >> "$SELF/lock-window.csv"
echo "newmethod_end,$T_NEW1,$(date -d @$T_NEW1 '+%F %T'),wall=$((T_NEW1-T_NEW0))s" >> "$SELF/lock-window.csv"
log "新法完成，墙上 $((T_NEW1-T_NEW0))s"

kill "$SAMPLER" 2>/dev/null

# ---------------------------------------------------------------- 4. 对比
log "=== 4. 新旧对比 ==="
python3 "$SELF/tools/compare.py" "$SELF/oldout/timings.csv" "$SELF/out/summary.csv" 2>&1 | tee -a "$LOG" | tee "$SELF/compare.txt"

log "=== 5. load1 区间 ==="
awk -F, 'NR>1{l=$2+0; if(NR==2||l<mn)mn=l; if(l>mx)mx=l; n++} END{printf "load1 全程 %d 个采样，min=%.2f max=%.2f\n", n, mn, mx}' "$SELF/load1.csv" | tee -a "$LOG"
echo "release,$(now),$(date '+%F %T'),total_wall=$(( $(now)-T_LOCK ))s" >> "$SELF/lock-window.csv"
log "=== 全部结束，总窗口 $(( $(now)-T_LOCK ))s ==="
