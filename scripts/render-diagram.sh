#!/usr/bin/env bash
# render-diagram.sh — 把 .mmd 渲染成「宽度精确 2352px」的 PNG（全站图集统一宽度）
#
# 用法:
#   scripts/render-diagram.sh docs/er/1-ER图-商品域.mmd [更多 .mmd ...]
#   scripts/render-diagram.sh docs/flows/03-机制时序/*.mmd
# 输出: 与输入同名同目录的 .png（覆盖原图）
#
# mermaid-cli 工具链坑（实测）：
#   - v12 删除了 -w/--width，只剩 --size（语义是"限制长边"），
#     直接用它会把时序图压成 1418×2352；
#   - v10.9.1 认 -w，但解析不了中文 ER 实体名（Parse error ... got '商'）。
# 结论：v12 --size 按长边算 + PIL 降采样到精确 2352，是本项目唯一可用管线。
#
# 内存纪律（DSH cgroup 有 6G 上限，超了会假死）：
#   - 一次只渲染一张，串行 + sleep，不要放进 for 里连发；
#   - 不要叠加 -s 3 / -w 4000：单个 chrome 进程会吃掉近 1GB；
#   - 本脚本最大帧约 2352×3900，单次峰值远低于降级前的 4000px×3 方案。
#   - 注: systemd-run --scope 在本机容器内不可用（Job failed），无法把 chrome
#     挂到 qiqi-infra.slice，所以只能靠上面的降规格来控内存。
set -euo pipefail

MMDC="npx --yes @mermaid-js/mermaid-cli"
TARGET_W=2352
PUPPETEER_CFG="${PUPPETEER_CFG:-/tmp/puppeteer-cfg.json}"

# headless chrome：优先用环境变量，否则用 puppeteer 缓存里的 headless-shell
if [[ -z "${PUPPETEER_EXECUTABLE_PATH:-}" ]]; then
  CAND=$(find /root/.cache/puppeteer/chrome-headless-shell -type f -name chrome-headless-shell 2>/dev/null | sort | tail -1)
  [[ -n "$CAND" ]] && export PUPPETEER_EXECUTABLE_PATH="$CAND"
fi
[[ -f "$PUPPETEER_CFG" ]] || printf '%s\n' '{"args":["--no-sandbox","--disable-setuid-sandbox","--disable-dev-shm-usage"]}' > "$PUPPETEER_CFG"

[[ $# -ge 1 ]] || { echo "用法: $0 <file.mmd> [更多 .mmd ...]" >&2; exit 2; }

for mmd in "$@"; do
  [[ -f "$mmd" ]] || { echo "跳过（不存在）: $mmd" >&2; continue; }
  png="${mmd%.mmd}.png"
  tmp=$(mktemp -d)

  # 1) 探针：极小尺寸拿到长宽比（轻量，不占内存）
  $MMDC -i "$mmd" -o "$tmp/probe.png" --size 400 -b white -p "$PUPPETEER_CFG" -q >/dev/null 2>&1
  size=$(python3 - "$tmp/probe.png" "$TARGET_W" <<'PY'
import sys
from PIL import Image
w, h = Image.open(sys.argv[1]).size
target = int(sys.argv[2])
# --size 限制的是「长边」，所以按长边把宽度顶到 target
print(round(target * max(w, h) / w))
PY
)

  # 2) 正式渲染：长边 = size，宽度自然落在 2352 附近（多 1~2px），超采样再降采样
  $MMDC -i "$mmd" -o "$tmp/full.png" --size "$size" -b white -p "$PUPPETEER_CFG" -q >/dev/null 2>&1

  # 3) 归一到精确 2352 宽度（只缩放宽度，保持长宽比）
  python3 - "$tmp/full.png" "$png" "$TARGET_W" <<'PY'
import sys
from PIL import Image
src, dst, target = sys.argv[1], sys.argv[2], int(sys.argv[3])
im = Image.open(src).convert('RGB')
w, h = im.size
if w != target:
    im = im.resize((target, round(h * target / w)), Image.LANCZOS)
im.save(dst)
print(f"{dst}  {im.size[0]}x{im.size[1]}")
PY

  rm -rf "$tmp"
  # 串行 + 间隔，避免 chrome 叠加吃内存
  sleep 2
done
