#!/usr/bin/env bash
# mycpu.sh —— **MySQL 自己的** cgroup CPU 采样器（雪崩实验第三步用，**只读**）
#
#   用法： ./mycpu.sh <输出.csv> [容器名]           # 默认 qiqi-mysql
#          INTERVAL_MS=1000 ./mycpu.sh out/mycpu.csv
#
# 为什么需要它：上一轮的不变式「每单 19 条 SQL × 这台机 7.7k QPS」只是**强相关**，
#   要变成因果必须采 MySQL 侧的算力占用。但 cgwatch.sh 只采 server-1/-2，没有 DB。
#
# 采什么（每行 = 一次采样；全部是宿主机直读 cgroup v2 / proc，零 docker exec）：
#   cpu.stat 的 usage_usec（→ 用了几个核，按档窗口求增量）
#   cpu.stat 的 nr_periods / nr_throttled / throttled_usec（→ cfs 配额节流）
#   cpu.max（→ 有没有配额；"max 100000" = 无上限 = 物理上不可能 throttle）
#   memory.current / memory.peak / pids.current
#   memory.stat 的 workingset 里 pgmajfault?（不要，太重）—— 只留上面的
#   宿主机：root cgroup cpu.pressure（some/full avg10）与 /proc/stat 的总忙占比
#          （4 核机器上「MySQL 2.6 核 + 两个 JVM 1.4 核」这种账只能这么算）
#
# 说明：容器**没有** CPU 配额时 nr_periods 恒为 0 —— 这是事实，不是采样失败。
#   此时「MySQL 是否打满算力」只能看 usage_usec（核数）+ 宿主机 cpu.pressure。
set -uo pipefail

OUT=${1:?用法: mycpu.sh <输出.csv> [容器名]}
shift || true
C=${*:-qiqi-mysql}
INTERVAL_MS=${INTERVAL_MS:-1000}
CGROOT=/sys/fs/cgroup
mkdir -p "$(dirname "$OUT")"

echo "epoch_ms,ts,container,cpu_max,usage_usec,nr_periods,nr_throttled,throttled_usec,mem_current_B,mem_peak_B,pids_current,host_cpu_psi_some10,host_cpu_psi_full10,host_cpu_busy_pct,host_cores_busy,host_load1,host_ncpu" > "$OUT"

resolve() {
  local c=$1 cid dir
  cid=$(docker inspect "$c" --format '{{.Id}}' 2>/dev/null) || return 1
  [ -n "$cid" ] || return 1
  dir="$CGROOT/system.slice/docker-$cid.scope"
  [ -d "$dir" ] || dir="$CGROOT/docker-$cid.scope"
  [ -d "$dir" ] || dir="$CGROOT/$cid"
  [ -d "$dir" ] && echo "$dir"
}

DIR=$(resolve "$C") || { echo "✗ 找不到容器 $C 的 cgroup" >&2; exit 1; }

NCPU=$(nproc)

# /proc/stat 的 cpu 行总忙占比（两次采样之间）
stat_busy() {  # → "total idle"（jiffies）
  awk '/^cpu /{t=0; for(i=2;i<=NF;i++) t+=$i; printf "%d %d\n", t, $5+$6}' /proc/stat
}
read -r T0 I0 <<<"$(stat_busy)"

while :; do
  mapfile -t CST < <(awk '{print $1" "$2}' "$DIR/cpu.stat" 2>/dev/null)
  usage=0; nper=0; nthr=0; tthr=0
  for kv in "${CST[@]}"; do
    k=${kv%% *}; v=${kv##* }
    case "$k" in
      usage_usec) usage=$v ;;
      nr_periods) nper=$v ;;
      nr_throttled) nthr=$v ;;
      throttled_usec) tthr=$v ;;
    esac
  done
  cmax=$(cat "$DIR/cpu.max" 2>/dev/null || echo "n/a")
  mcur=$(cat "$DIR/memory.current" 2>/dev/null || echo 0)
  mpeak=$(cat "$DIR/memory.peak" 2>/dev/null || echo 0)
  pids=$(cat "$DIR/pids.current" 2>/dev/null || echo 0)

  # 宿主机 CPU 压力（root cgroup）
  rp=$(awk '/^some/{for(i=1;i<=NF;i++) if($i ~ /^avg10=/){split($i,a,"="); print a[2]}}
          /^full/{for(i=1;i<=NF;i++) if($i ~ /^avg10=/){split($i,a,"="); print a[2]}}' \
       "$CGROOT/cpu.pressure" 2>/dev/null | tr '\n' ' ')
  set -- $rp; psome=${1:-0}; pfull=${2:-0}

  read -r T1 I1 <<<"$(stat_busy)"
  dt=$(( T1 - T0 )); di=$(( I1 - I0 ))
  busy=0
  if [ "$dt" -gt 0 ]; then busy=$(awk -v d="$dt" -v i="$di" 'BEGIN{printf "%.1f", 100.0*(d-i)/d}'); fi
  cores=$(awk -v b="$busy" -v n="$NCPU" 'BEGIN{printf "%.2f", b*n/100.0}')
  load1=$(awk '{print $1}' /proc/loadavg)
  T0=$T1; I0=$I1

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$(date +%s%3N)" "$(date '+%F %T.%3N')" "$C" "$cmax" "$usage" "$nper" "$nthr" "$tthr" \
    "$mcur" "$mpeak" "$pids" "$psome" "$pfull" "$busy" "$cores" "$load1" "$NCPU" >> "$OUT"

  sleep "$(awk -v ms="$INTERVAL_MS" 'BEGIN{printf "%.3f", ms/1000.0}')"
done
