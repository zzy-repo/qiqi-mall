#!/usr/bin/env bash
#
# 一眼看清「谁在吃内存、e2e 有没有真的跑到兄弟 slice 里」。
#
#   scripts/mem-status.sh
#
# 只读：不改任何 cgroup 属性、不动任何进程。
#
# 背景（为什么需要它）：
#   DSH 被 systemd 限在 MemoryHigh=6G / MemoryMax=8G。agent 直接跑 playwright 时，
#   chrome / node / java 都是 DSH 的子进程，全记在 DSH 头上 → 一跑 e2e 就把 DSH 挤到假死。
#   解决办法是把这些进程关进兄弟 slice（scripts/e2e-run.sh / scripts/detach-infra.sh）。
#
# 判读要点：
#   · dsh-web.service 的 current 才是「DSH 的账」；qiqi-e2e/infra 是兄弟，不占它的额度。
#   · chrome 那几行必须显示 qiqi-e2e.slice。若显示 dsh-web.service，说明有人绕过
#     scripts/e2e-run.sh 直接跑了 playwright —— 这就是 DSH 被挤爆的原因。
#   · cgroup v2 不迁移已记账的内存页：把进程搬出 DSH 后，它「之前」占的页仍算在 DSH 账上，
#     只有进程重启/退出才真正释放。所以迁移后 current 可能不马上降，别误判为失败。
set -euo pipefail

CG=/sys/fs/cgroup
DSH="$CG/system.slice/dsh-web.service"
E2E_SLICE="$(systemctl show qiqi-e2e.slice -p ControlGroup --value 2>/dev/null || true)"
INFRA_SLICE="$(systemctl show qiqi-infra.slice -p ControlGroup --value 2>/dev/null || true)"
E2E="$CG${E2E_SLICE:-/qiqi.slice/qiqi-e2e.slice}"
INFRA="$CG${INFRA_SLICE:-/qiqi.slice/qiqi-infra.slice}"

# 字节 → 人眼（1 位小数，带单位）
hr() {
  local b="${1:-0}"
  [[ "$b" == "max" || -z "$b" ]] && { echo "${b:-?}"; return; }
  awk -v b="$b" 'BEGIN{
    split("B KB MB GB TB",u," ");
    i=1; while (b>=1024 && i<5) { b/=1024; i++ }
    printf "%.1f%s", b, u[i]
  }'
}
# 读 cgroup 文件（读不到给 ?）
rf() { local v; v="$(cat "$1" 2>/dev/null || true)"; echo "${v:-?}"; }
# 某 cgroup 里进程数 + 进程 RSS 合计(MB)
cgroup_procs_info() {
  local dir="$1" n=0 tot=0 pid r
  [[ -r "$dir/cgroup.procs" ]] || { echo "0 procs / 0MB"; return; }
  while read -r pid; do
    [[ -z "$pid" ]] && continue
    n=$((n+1))
    r="$(awk '/VmRSS/{print $2}' "/proc/$pid/status" 2>/dev/null || echo 0)"
    tot=$((tot + ${r:-0}))
  done < "$dir/cgroup.procs"
  echo "$n procs / $((tot/1024))MB rss"
}

printf '%-14s %s\n' "时间" "$(date '+%F %T %Z')"

# ── DSH 本体 ───────────────────────────────────────────────────────────────
printf '%-14s current=%s  high=%s  max=%s  min=%s  (%s)\n' \
  "dsh-web" \
  "$(hr "$(rf "$DSH/memory.current")")" \
  "$(hr "$(rf "$DSH/memory.high")")" \
  "$(hr "$(rf "$DSH/memory.max")")" \
  "$(hr "$(rf "$DSH/memory.min")")" \
  "$(cgroup_procs_info "$DSH")"
printf '%-14s peak=%s\n' "dsh-web" "$(hr "$(rf "$DSH/memory.peak")")"

# ── 兄弟 slice ────────────────────────────────────────────────────────────
printf '%-14s current=%s  high=%s  max=%s  (%s)\n' \
  "qiqi-e2e" \
  "$(hr "$(rf "$E2E/memory.current")")" \
  "$(hr "$(rf "$E2E/memory.high")")" \
  "$(hr "$(rf "$E2E/memory.max")")" \
  "$(cgroup_procs_info "$E2E")"
printf '%-14s peak=%s\n' "qiqi-e2e" "$(hr "$(rf "$E2E/memory.peak")")"
printf '%-14s current=%s  high=%s  max=%s  (%s)\n' \
  "qiqi-infra" \
  "$(hr "$(rf "$INFRA/memory.current")")" \
  "$(hr "$(rf "$INFRA/memory.high")")" \
  "$(hr "$(rf "$INFRA/memory.max")")" \
  "$(cgroup_procs_info "$INFRA")"
printf '%-14s peak=%s\n' "qiqi-infra" "$(hr "$(rf "$INFRA/memory.peak")")"

# ── 整机 ─────────────────────────────────────────────────────────────────
printf '%-14s %s\n' "整机内存" "$(free -h | awk '/^Mem:/{printf "总 %s / 用 %s / 可用 %s / 缓存 %s", $2,$3,$7,$6}')"
printf '%-14s %s\n' "整机swap" "$(free -h | awk '/^Swap:/{printf "总 %s / 用 %s / 可用 %s", $2,$3,$4}')"

# ── chrome 落在哪个 slice（这是「e2e 真的搬出去了」的铁证）────────────────
chrome_pids="$(pgrep -f 'chrome-linux64/chrome' 2>/dev/null || true)"
if [[ -z "$chrome_pids" ]]; then
  printf '%-14s %s\n' "chrome" "（当前没有 chrome 进程）"
else
  # 按 cgroup 归并，避免刷屏
  while read -r cnt cg; do
    printf '%-14s %s 个进程  →  %s\n' "chrome" "$cnt" "$cg"
  done < <(for pid in $chrome_pids; do
             sed -n 's/^0:://p' "/proc/$pid/cgroup" 2>/dev/null | head -1
           done | sort | uniq -c | awk '{print $1, $2}')
  # 只把「跑在 DSH 里」的单独点出来，这是要修的异常
  dsh_chrome=0
  for pid in $chrome_pids; do
    case "$(sed -n 's/^0:://p' "/proc/$pid/cgroup" 2>/dev/null | head -1)" in
      *dsh-web.service*) dsh_chrome=$((dsh_chrome+1));;
    esac
  done
  if [[ "$dsh_chrome" != "0" ]]; then
    printf '%-14s ⚠ %s 个 chrome 在 dsh-web.service 里（绕过了 scripts/e2e-run.sh）\n' "chrome" "$dsh_chrome"
  fi
fi

# ── 谁在 DSH 里吃内存（top 5）────────────────────────────────────────────
echo "DSH 内 top5："
for pid in $(cat "$DSH/cgroup.procs" 2>/dev/null || true); do
  [[ -r "/proc/$pid/status" ]] || continue
  r="$(awk '/VmRSS/{print $2}' "/proc/$pid/status" 2>/dev/null || echo 0)"
  c="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | cut -c1-64)" || continue
  [[ -z "$c" ]] && continue
  printf '%s\t%s\t%s\n' "$(( ${r:-0}/1024 ))" "$pid" "$c"
done | sort -k1,1 -rn | head -5 | awk -F'\t' '{printf "%-14s %6sMB  pid %-9s %s\n", "", $1, $2, $3}'
