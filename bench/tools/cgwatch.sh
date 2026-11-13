#!/usr/bin/env bash
# cgwatch.sh —— 200ms 资源水位采样器（雪崩实验用，**只读**，绝不改容器/容器配置）
#
#   用法： ./cgwatch.sh <输出.csv> [容器名...]        # 默认 qiqi-server-1 qiqi-server-2
#          INTERVAL_MS=200 ./cgwatch.sh out/cg.csv     # 采样周期（默认 200ms）
#
# 为什么能用纯 /proc + /sys 读（不 docker exec）：
#   · cgroup v2 的 memory.current / memory.peak / memory.events / cpu.stat / *.pressure
#     在宿主机上直接可读（路径 /sys/fs/cgroup/system.slice/docker-<完整id>.scope）；
#   · 容器内 java 进程在宿主机上也有 pid（PID 命名空间只对容器内隐藏，宿主机看得见），
#     线程名从 /proc/<pid>/task/<tid>/comm 读得到（内核对 comm 截断到 15 字符，
#     Tomcat 工作线程显示为 "http-nio-8082-e"，按前缀计数即可）。
#   → 全程零 docker exec、零网络、零写入容器；200ms×2 容器只有几十次小文件读。
#
# 输出列（一行 = 一个容器的一次采样；host_* 两行重复，方便直接 group by）：
#   epoch_ms,ts,container,mem_current_B,mem_peak_B,mem_high_events,mem_max_events,oom_kill,
#   cpu_usage_usec,psi_mem_some_avg10,psi_cpu_some_avg10,
#   thr_total,thr_http,thr_other_runnable,
#   host_load1,host_load5,host_memavail_kB,host_swapused_kB,host_runnable
#
# 注意：
#   · 容器**没有**内存上限时 memory.max = "max"，memory.events 的 high/max 计数永远是 0
#     —— 这是事实，不是采样失败；此时真正的内存信号是 memory.current/peak 与 PSI。
#   · 采样器自身开销：本脚本每 tick 约读 100 个小文件，4 核机器上 <0.3% CPU。
set -uo pipefail

OUT=${1:?用法: cgwatch.sh <输出.csv> [容器名...]}
shift || true
CONTAINERS=${*:-qiqi-server-1 qiqi-server-2}
INTERVAL_MS=${INTERVAL_MS:-200}
CGROOT=/sys/fs/cgroup

mkdir -p "$(dirname "$OUT")"
echo "epoch_ms,ts,container,mem_current_B,mem_peak_B,mem_high_events,mem_max_events,oom_kill,cpu_usage_usec,psi_mem_some_avg10,psi_cpu_some_avg10,thr_total,thr_http,host_load1,host_load5,host_memavail_kB,host_swapused_kB,host_runnable" > "$OUT"

# ---------------------------------------------------------------- 容器 → (cgroup 目录, java pid)
# 只用 docker inspect 解析一次（不是热路径）；容器重构后 cgroup 变，检测到失效就重解析。
declare -A CG JVMPID

resolve_one() {
  local c=$1 cid dir
  cid=$(docker inspect "$c" --format '{{.Id}}' 2>/dev/null) || return 1
  [ -n "$cid" ] || return 1
  # cgroup v2 + systemd driver：/sys/fs/cgroup/system.slice/docker-<id>.scope
  # cgroup v2 + cgroupfs driver：/sys/fs/cgroup/<id>（兜底两种都找）
  dir="$CGROOT/system.slice/docker-$cid.scope"
  [ -d "$dir" ] || dir="$CGROOT/docker-$cid.scope"
  [ -d "$dir" ] || dir="$CGROOT/$cid"
  [ -d "$dir" ] || { echo "✗ 找不到容器 $c 的 cgroup（只支持 cgroup v2 或需 root）" >&2; return 1; }
  CG[$c]=$dir

  # 该容器里的 java 主进程：/proc/<pid>/cgroup 的第 2 段 == 这个 scope
  local short=${cid:0:12} p
  JVMPID[$c]=""
  for p in /proc/[0-9]*; do
    p=${p#/proc/}
    [ "$(cat "/proc/$p/comm" 2>/dev/null)" = java ] || continue
    case "$(cat "/proc/$p/cgroup" 2>/dev/null)" in
      *"$short"*) JVMPID[$c]=$p; break ;;
    esac
  done
  return 0
}

for c in $CONTAINERS; do resolve_one "$c" || exit 2; done

# 读 cgroup 文件里某个 key 的数字（memory.events / cpu.stat 都是 "key value" 多行）
kv() { awk -v k="$2" '$1==k{print $2; found=1} END{if(!found) print 0}' "$1" 2>/dev/null || echo 0; }
# PSI: "some avg10=1.23 avg60=... total=..." → avg10
psi10() { awk '{for(i=1;i<=NF;i++) if($i ~ /^avg10=/){sub("avg10=","",$i); print $i; exit}}' "$1" 2>/dev/null || echo 0; }

cleanup() { exit 0; }
trap cleanup INT TERM

while :; do
  t_ms=$(date +%s%3N)
  # 宿主机一次性读（所有容器行共用）
  read -r L1 L5 _ < /proc/loadavg
  RUNNABLE=$(awk '{print $4}' /proc/loadavg)                 # 第 4 段 = "running/total"
  MA=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
  SWT=$(awk '/^SwapTotal:/{print $2}' /proc/meminfo)
  SWF=$(awk '/^SwapFree:/{print $2}'  /proc/meminfo)
  SWU=$(( ${SWT:-0} - ${SWF:-0} ))

  for c in $CONTAINERS; do
    d=${CG[$c]:-}
    # 容器被重构 → cgroup 目录消失，重解析一次
    if [ -z "$d" ] || [ ! -d "$d" ]; then
      resolve_one "$c" || continue
      d=${CG[$c]}
    fi
    cur=$(cat "$d/memory.current" 2>/dev/null || echo 0)
    peak=$(cat "$d/memory.peak" 2>/dev/null || echo 0)
    hi=$(kv "$d/memory.events" high)
    mx=$(kv "$d/memory.events" max)
    ok_=$(kv "$d/memory.events" oom_kill)
    cpu=$(kv "$d/cpu.stat" usage_usec)
    pm=$(psi10 "$d/memory.pressure")
    pc=$(psi10 "$d/cpu.pressure")

    pid=${JVMPID[$c]:-}
    thr_total=0; thr_http=0
    if [ -n "$pid" ] && [ -d "/proc/$pid/task" ]; then
      thr_total=$(ls -1 "/proc/$pid/task" 2>/dev/null | wc -l)
      # 线程名前缀计数（Tomcat NIO 工作线程 "http-nio-8082-exec-N" 被内核截成 "http-nio-8082-e"）
      thr_http=$(cat "/proc/$pid"/task/*/comm 2>/dev/null | grep -c '^http-nio' || true)
    fi

    echo "$t_ms,$(date -d "@$((t_ms/1000))" '+%F %T' 2>/dev/null).$(printf '%03d' $((t_ms%1000))),$c,$cur,$peak,$hi,$mx,$ok_,$cpu,$pm,$pc,$thr_total,$thr_http,$L1,$L5,$MA,$SWU,$RUNNABLE" >> "$OUT"
  done

  # 定周期睡眠：用 ms 计算剩余时间，避免 date/docker 调用把周期拉长
  used=$(( $(date +%s%3N) - t_ms ))
  left=$(( INTERVAL_MS - used ))
  [ "$left" -gt 0 ] && sleep "$(awk -v ms="$left" 'BEGIN{printf "%.3f", ms/1000}')"
done
