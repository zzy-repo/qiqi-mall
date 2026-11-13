#!/usr/bin/env bash
# oldmethod.sh —— 复现「现有做法」：每个并发档单独一场，全程 date +%s 真实打点
#   每档：reset → precheck → 重灌令牌池 → JVM 冷启动 → 预热 → 计时 → 收尾 → 间隔
#
# 三把锁（都是被今天早上的污染事故逼出来的）：
#   ① 环境互斥 wait_quiet：连续 QUIET_NEED 秒没有别人的 java LoadGen / 别人 round 日志不动
#   ② 业务污染判定：幂等+传输失败 > 0.5% ⇒ 该档报废重跑
#   ③ 环境扰动判定（跑完统一做）：某档 load1 峰值 > 本 round 各档峰值中位数 × 1.5 ⇒ 重跑一次，仍超标标 DATURBED
# 口径：TPS 用 **ok/s**（有效吞吐），不用 requests/s。
set -uo pipefail
# 路径全部由脚本位置/环境变量决定（细节见 bench.sh 顶部注释）
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=${OUT_OLD:-${OUT:-$SELF/oldout}}
LG=${LG:-/tmp/loadgen}
TIERS=${TIERS:-"1 2 4 8 16 32 64"}
WARMUP=${WARMUP:-60}
DURATION=${DURATION:-60}
GAP=${GAP:-5}
QUIET_NEED=${QUIET_NEED:-90}
QUIET_MAX_WAIT=${QUIET_MAX_WAIT:-1800}
mkdir -p "$OUT"
LOG=${LOG_OLD:-$SELF/oldmethod.log}
: > "$LOG"

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
now() { date +%s; }

foreign_loadgen() { pgrep -af 'java.*LoadGen' 2>/dev/null | grep -v pgrep | grep -v -F "tier-old-" || true; }
round_active() { local f=${RIVAL_ROUND_LOG:-$LG/round-B32b.log}; [ -f "$f" ] || return 1; [ $(( $(date +%s) - $(stat -c %Y "$f") )) -lt 180 ]; }
wait_quiet() {
  local t0 q0 q1
  t0=$(now); q0=$t0
  while :; do
    if [ -n "$(foreign_loadgen)" ] || round_active; then q0=$(now); else
      q1=$(now); [ $((q1-q0)) -ge "$QUIET_NEED" ] && { log "✅ 环境安静 $((q1-q0))s"; return 0; }
    fi
    [ $(( $(now) - t0 )) -ge "$QUIET_MAX_WAIT" ] && { log "⚠ 等安静超时，按现状继续"; return 1; }
    sleep 10
  done
}

# 取 [t_start,t_end] 窗口内的 load1 min/中位/max 与 MemAvail 最小值
envstat() {
  python3 - "$OUT/env.csv" "$1" "$2" <<'PY'
import sys, statistics
p, a, b = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
l, m = [], []
try:
    rows = [x.strip().split(',') for x in open(p) if x.strip()]
    hdr = rows[0]
    ie = hdr.index('epoch') if 'epoch' in hdr else 0
    il = hdr.index('load1') if 'load1' in hdr else 1
    im = hdr.index('MemAvailable_kB') if 'MemAvailable_kB' in hdr else 2
    for f in rows[1:]:
        if len(f) <= max(ie, il, im): continue
        try:
            t = int(f[ie])
        except ValueError:
            continue
        if a <= t <= b:
            l.append(float(f[il])); m.append(float(f[im]))
except FileNotFoundError:
    pass
if not l:
    print("nan,nan,nan,nan")
else:
    print(f"{min(l):.2f},{statistics.median(l):.2f},{max(l):.2f},{min(m)/1024:.0f}")
PY
}

run_tier() {   # $1=并发 $2=attempt -> 写一行 timings，返回 verdict 到全局 VERDICT
  local C=$1 attempt=$2 T0 T1 T2 J0 J1 T3 TPS P50 P95 P99 OK FAIL IDEM TR REQ MEAS
  T0=$(now)
  log "--- tier c$C 第${attempt}次开始 $T0 ---"
  "$SELF/tools/prepare.sh" reset >> "$LOG" 2>&1
  T1=$(now)
  "$SELF/tools/prepare.sh" precheck >> "$LOG" 2>&1
  "$SELF/tools/prepare.sh" seed 2>&1 | tail -2 >> "$LOG"
  T2=$(now)
  J0=$(now)
  java -Xmx1g "$SELF/legacy/LoadGen.java" --url http://127.0.0.1:18080/portal/orders --method POST \
    --concurrency "$C" --duration "$DURATION" --warmup "$WARMUP" \
    --token-file "$LG/tokens.txt" --auth-file "$LG/jwts.txt" \
    --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
    --body-file "$LG/order.json" --out "$OUT/tier-old-c$C.csv" --label "old-c$C" \
    2>&1 | grep -E "SUMMARY|TOP ERRORS|^ +[0-9]+ " | tee -a "$LOG"
  J1=$(now); T3=$(now)

  val() { awk -F, -v k="$1" '$1==k{print $2}' "$OUT/tier-old-c$C.csv" 2>/dev/null; }
  TPS=$(val tps); P50=$(val p50_ms); P95=$(val p95_ms); P99=$(val p99_ms)
  OK=$(val ok); FAIL=$(val fail); IDEM=$(val biz_fail_2>/dev/null); IDEM=$(val biz_fail_idempotent); TR=$(val transport_err)
  MEAS=$(val elapsed_s)
  REQ=$(( ${OK:-0} + ${FAIL:-0} ))
  local BAD=$(( ${IDEM:-0} + ${TR:-0} ))
  local OKRPS; OKRPS=$(awk -v o="${OK:-0}" -v s="${MEAS:-1}" 'BEGIN{printf "%.1f", (s>0?o/s:0)}')
  local REQRPS; REQRPS=$(awk -v r="$REQ" -v s="${MEAS:-1}" 'BEGIN{printf "%.1f", (s>0?r/s:0)}')
  local ENV; ENV=$(envstat "$T0" "$T3")
  local L1MIN L1MED L1MAX MEMMIN
  IFS=, read -r L1MIN L1MED L1MAX MEMMIN <<< "$ENV"

  VERDICT="OK"
  if [ "$REQ" -gt 0 ] && [ $(( BAD * 1000 / REQ )) -gt 5 ]; then
    VERDICT="CONTAMINATED"
    log "❌ c$C 第${attempt}次污染：idem=${IDEM:-0} transport=${TR:-0} / req=$REQ（$(( BAD*100/REQ ))%）→ 重跑"
  fi
  echo "c$C,$C,$attempt,$T0,$T3,$((T3-T0)),$((T1-T0)),$((T2-T1)),$((J1-J0)),$MEAS,$REQ,$OK,$FAIL,$OKRPS,$REQRPS,$P50,$P95,$P99,${IDEM:-0},$L1MIN,$L1MED,$L1MAX,$MEMMIN,$VERDICT,$OUT/tier-old-c$C.csv" >> "$OUT/timings.csv"
  echo "c$C,$attempt,$VERDICT,$((T3-T0)),ok_rps=$OKRPS,req=$REQ,idem=${IDEM:-0},load1_max=$L1MAX" >> "$OUT/attempts.csv"
  log "--- tier c$C 第${attempt}次结束 $T3 墙上 $((T3-T0))s (reset $((T1-T0))s / seed $((T2-T1))s / JVM+跑 $((J1-J0))s) ok_rps=$OKRPS p99=$P99 load1=$L1MIN/$L1MED/$L1MAX verdict=$VERDICT ---"
}

T_ALL0=$(now)
echo "tier,concurrency,attempt,t_start,t_end,wall_s,reset_s,seed_s,jvm_start_s,measure_s,requests,ok,fail,ok_rps,req_rps,p50_ms,p95_ms,p99_ms,biz_idem_fail,load1_min,load1_med,load1_max,mem_min_MB,verdict,csv" > "$OUT/timings.csv"
echo "tier,attempt,verdict,wall_s,detail" > "$OUT/attempts.csv"

"$SELF/tools/envsample2.sh" "$OUT/env.csv" &
ENVPID=$!
trap 'kill $ENVPID 2>/dev/null' EXIT

log "=== 旧法开始（每档独立一场）TIERS=[$TIERS] warmup=${WARMUP}s measure=${DURATION}s ==="
log "T_ALL_START=$T_ALL0 ($(date '+%F %T'))"
wait_quiet || true

for C in $TIERS; do
  attempt=0
  while :; do
    attempt=$((attempt+1))
    run_tier "$C" "$attempt"
    [ "$VERDICT" = "OK" ] && break
    [ "$attempt" -ge 2 ] && { log "⚠ c$C 连续 2 次污染，标注 CONTAMINATED 继续"; break; }
    log "等 30s 重试 c$C"; sleep 30; wait_quiet || true
  done
  [ "$C" != "$(echo $TIERS | awk '{print $NF}')" ] && sleep "$GAP"
done

# ---------------------------------------------------------------- ③ 环境扰动后置检查
log "=== 后置：load1 扰动检查（某档峰值 > 各档峰值中位数 × 1.5 ⇒ 重跑一次）==="
python3 - "$OUT/timings.csv" <<'PY' > "$OUT/_disturb.txt"
import csv, statistics, sys
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r['verdict'] == 'OK']
peaks = []
for r in rows:
    try: peaks.append(float(r['load1_max']))
    except Exception: pass
if not peaks:
    print("NODATA")
else:
    m = statistics.median(peaks)
    print(f"MEDIAN={m:.2f}")
    for r in rows:
        try: p = float(r['load1_max'])
        except Exception: continue
        if p > 1.5 * m:
            print(f"FLAG {r['tier']} peak={p:.2f} median={m:.2f}")
PY
cat "$OUT/_disturb.txt" | tee -a "$LOG"
if grep -q '^FLAG' "$OUT/_disturb.txt"; then
  for T in $(grep '^FLAG' "$OUT/_disturb.txt" | awk '{print $2}' | sed 's/^c//'); do
    log "重跑受扰档 c$T（第 3 次 = 扰动复测）"
    run_tier "$T" 3
    if [ "$VERDICT" = "OK" ]; then
      MED=$(sed -n 's/MEDIAN=//p' "$OUT/_disturb.txt")
      NEW=$(awk -F, -v t="c$T" -v a="3" '$1==t && $3==a {print $22}' "$OUT/timings.csv" | tail -1)
      if awk -v n="$NEW" -v m="$MED" 'BEGIN{exit !(n+0 > 1.5*m+0)}'; then
        log "⚠ c$T 复测 load1 峰值 $NEW 仍 > 1.5×中位 $MED → 标 DATURBED（数字打折看）"
        sed -i "s/^\(c$T,[0-9]*\),3,\(.*\),OK,/\1,3,\2,DATURBED,/" "$OUT/timings.csv"
      else
        log "✅ c$T 复测 load1 峰值 $NEW ≤ 1.5×中位 $MED，采用第 3 次"
      fi
    fi
    sleep "$GAP"
  done
fi

T_ALL1=$(now)
TOTAL=$((T_ALL1-T_ALL0))
log "T_ALL_END=$T_ALL1 旧法总墙上时间=${TOTAL}s = $((TOTAL/60))min$((TOTAL%60))s"
echo "TOTAL_WALL_S,$TOTAL" >> "$OUT/timings.csv"
echo "TOTAL_WALL_S $TOTAL"
awk -F, 'NR>1 && ($24=="OK"||$24=="DATURBED") && NF>5 {s+=$6; n++} END{printf "采用档 %d 个，墙上合计 %d s = %.1f min\n", n, s, s/60}' "$OUT/timings.csv" | tee -a "$LOG"
