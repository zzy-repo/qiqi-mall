#!/usr/bin/env bash
#
# 把「已经在跑」的重进程（java 后端 / vite dev / esbuild）从 DSH 的 cgroup 搬到 qiqi-infra.slice。
#
#   scripts/detach-infra.sh            # 默认 dry-run：只打印清单，不动任何进程
#   scripts/detach-infra.sh --apply    # 真正迁移（迁移前务必先给清单确认）
#
# 为什么需要：这些进程如果是 agent 的子孙进程，就记在 dsh-web.service 的 MemoryHigh=6G 里，
# 搬出去之后 DSH 的 MemoryCurrent 才会真的降下来（新起的进程用 systemd-run 即可，无需迁移）。
#
# 红线：绝不移动 DSH 自身进程（/opt/dsh-app 下的 node），移了 DSH 会挂。
#       也绝不移动 systemd/容器运行时等系统进程——只按下面的白名单匹配。
#
# slice 配置（不入库，只在这里留档；由 root 手工创建/设置）：
#   systemd-run --slice 或 systemctl 创建 /qiqi.slice /qiqi-infra.slice /qiqi-e2e.slice
#   qiqi-infra.slice : MemoryHigh=5G MemoryMax=6G
#   qiqi-e2e.slice   : MemoryHigh=3G MemoryMax=4G MemorySwapMax=1G
#   注意 cgroup v2 下 slice 是**嵌套**的，真实路径是
#   /sys/fs/cgroup/qiqi.slice/qiqi-infra.slice/cgroup.procs
#   （不是 /sys/fs/cgroup/qiqi-infra.slice/…，本脚本因此动态解析，不再写死扁平路径）
set -euo pipefail

SLICE_UNIT="qiqi-infra.slice"
APPLY=0
[[ "${1:-}" == "--apply" ]] && APPLY=1

# 白名单：只认这些特征（qiqi-mall 的后端与前端 dev server / esbuild service）
# 注意 vite 有两种写法：绝对路径 `.../portal-web/node_modules/.bin/vite` 和相对路径
# `node ./node_modules/.bin/vite --strictPort`（scripts/start-e2e-frontends.sh 起的），
# 所以不能只靠命令行前缀，还要看 cwd 是否在 qiqi-mall 下（见 in_scope_cwd）。
PATTERN='spring-boot:run|QiQiMallApplication|-cp .*qiqi-mall-server|node_modules/\.bin/vite|esbuild'
# 黑名单：命中就跳过（DSH 本体、systemd、containerd 等）
DENY='dsh|/opt/dsh-app|systemd|containerd|dockerd|chrome-linux64/chrome'

# 归属校验：进程 cwd 必须在这个仓库下（防止误搬别的项目的 vite/esbuild）
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
in_scope_cwd() {
  local pid="$1" cwd
  cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" || return 1
  [[ "$cwd" == "$REPO_ROOT" || "$cwd" == "$REPO_ROOT"/* ]]
}

# ── 解析目标 cgroup 路径（嵌套 slice，别写死）──────────────────────────────
resolve_target() {
  local cg
  cg="$(systemctl show "$SLICE_UNIT" -p ControlGroup --value 2>/dev/null || true)"
  if [[ -n "$cg" ]]; then
    echo "/sys/fs/cgroup${cg}/cgroup.procs"
    return 0
  fi
  # 兜底：在 cgroupfs 里找一层嵌套
  local hit
  hit="$(find /sys/fs/cgroup -maxdepth 3 -type d -name "$SLICE_UNIT" 2>/dev/null | head -1)"
  [[ -n "$hit" ]] && echo "$hit/cgroup.procs"
}

TARGET="$(resolve_target || true)"
if [[ -z "${TARGET:-}" || ! -w "$TARGET" ]]; then
  echo "✗ 找不到可写的 $SLICE_UNIT cgroup.procs（解析结果：${TARGET:-<空>}）" >&2
  echo "  先确认 slice 存在：systemctl status $SLICE_UNIT" >&2
  exit 1
fi

dsh_mem() { awk '{printf "%.2f GB", $1/1024/1024/1024}' /sys/fs/cgroup/system.slice/dsh-web.service/memory.current 2>/dev/null; }

mapfile -t PIDS < <(pgrep -f "$PATTERN" || true)

echo "▶ 候选进程（$(date '+%F %T')）  目标：$TARGET"
printf '%-9s %-8s %s\n' PID RSS_MB CGROUP
for pid in "${PIDS[@]}"; do
  cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
  [[ -z "$cmd" ]] && continue
  echo "$cmd" | grep -Eq "$DENY" && continue
  in_scope_cwd "$pid" || { echo "  · 跳过 $pid（cwd 不在仓库内）"; continue; }
  cg="$(sed -n 's/^0:://p' "/proc/$pid/cgroup" 2>/dev/null | head -1)"
  rss=$(( $(awk '/VmRSS/{print $2}' /proc/$pid/status 2>/dev/null) / 1024 ))
  printf '%-9s %-8s %s\n' "$pid" "$rss" "${cg:-?}"
done

if [[ "$APPLY" != "1" ]]; then
  echo
  echo "（dry-run，未改动任何进程；确认清单后加 --apply 执行）"
  exit 0
fi

move_tree() {
  local pid="$1" rc=0
  for c in $(pgrep -P "$pid" 2>/dev/null); do move_tree "$c" || rc=1; done
  if echo "$pid" > "$TARGET" 2>/dev/null; then
    return 0
  fi
  echo "  ✗ 移动失败 $pid: $(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | cut -c1-60)" >&2
  return 1
}

echo
echo "▶ 迁移到 $SLICE_UNIT"
echo "  DSH 内存（迁移前）：$(dsh_mem)"
ok=0; fail=0
for pid in "${PIDS[@]}"; do
  cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
  [[ -z "$cmd" ]] && continue
  echo "$cmd" | grep -Eq "$DENY" && continue
  in_scope_cwd "$pid" || continue
  if move_tree "$pid"; then ok=$((ok+1)); else fail=$((fail+1)); fi
done
echo "  成功 $ok 个，失败 $fail 个"

echo
echo "▶ 迁移后核对"
echo "  DSH 内存（迁移后）：$(dsh_mem)"
echo "  $SLICE_UNIT 进程数：$(wc -l < "$TARGET")"
echo "  仍在 dsh-web.service 的相关进程："
found=0
for pid in "${PIDS[@]}"; do
  cg="$(sed -n 's/^0:://p' "/proc/$pid/cgroup" 2>/dev/null | head -1)"
  [[ "$cg" == *dsh-web.service* ]] || continue
  found=1
  printf '    %-9s %s\n' "$pid" "$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | cut -c1-60)"
done
[[ "$found" == "0" ]] && echo "    （无）"

if [[ "$fail" != "0" ]]; then
  echo "✗ 有 $fail 个进程迁移失败，见上面的错误" >&2
  exit 1
fi
echo "✓ 迁移完成"
