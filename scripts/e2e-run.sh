#!/usr/bin/env bash
#
# 跑 e2e 的唯一入口：把 playwright + chrome 关进 qiqi-e2e.slice（cgroup）。
#
#   scripts/e2e-run.sh scenarios/03-商家-履约/1-订单审核与发货.spec.js --output=/tmp/pwout-x
#   scripts/e2e-run.sh --list                 # 只看不跑
#
# 为什么必须走它：
#   agent 直接跑 `npx playwright test` 时，chrome / node / java 都是 DSH 自己的子进程，
#   全都记在 dsh-web.service 的 MemoryHigh=6G 头上 → e2e 一跑就把 DSH 挤到 OOM 边缘。
#   `systemd-run --scope --slice=qiqi-e2e.slice` 起的进程在**兄弟 slice**里，
#   不再计入 DSH；超限只杀 e2e 自己（MemoryMax=4G），DSH 和其他服务不受影响。
#
# 注意：这套隔离不"增加内存"，只限制爆炸半径。真内存大头是「agent 数 × 每会话 ~390MB」。
#
# flock 仍保留：现在大家还在共用 dev 库（8081），串行是库层面的要求；
# 等 e2e 独立库（sql/e2edb）铺开后，可以用 E2E_UNLOCKED=1 关掉。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR/e2e"

SLICE="qiqi-e2e.slice"
LIMITS=(--property=MemoryMax=4G --property=MemoryHigh=3G --property=MemorySwapMax=1G)

if [[ "${1:-}" == "--list" ]]; then
  echo "slice: $SLICE  ${LIMITS[*]}"
  echo "flock: $([[ "${E2E_UNLOCKED:-0}" == "1" ]] && echo 关闭 || echo /tmp/e2e.lock)"
  exit 0
fi

if [[ "${E2E_UNLOCKED:-0}" == "1" ]]; then
  exec systemd-run --scope --quiet --slice="$SLICE" "${LIMITS[@]}" -- \
    npx playwright test "$@"
else
  exec systemd-run --scope --quiet --slice="$SLICE" "${LIMITS[@]}" -- \
    flock /tmp/e2e.lock npx playwright test "$@"
fi
