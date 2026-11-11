#!/usr/bin/env bash
#
# 生成 e2e 后端用的 classpath 文件：qiqi-mall-server/target/e2e-cp.txt
#
#   ./scripts/e2e-classpath.sh
#
# 做法：从正在运行的 dev 后端（默认 8081）的 /proc/<pid>/cmdline 里把 -cp 原样抄出来。
# 这样 e2e 后端与 dev 后端的依赖版本完全一致，也不用联网拉依赖（机器上 maven 离线可用）。
# 改了 pom 依赖后重跑本脚本即可。
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${DEV_PORT:-8081}"
OUT="$REPO_DIR/qiqi-mall-server/target/e2e-cp.txt"

PID="$(ss -ltnp 2>/dev/null | grep ":${PORT} " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
if [[ -z "${PID:-}" ]]; then
  echo "✗ 端口 $PORT 上没有进程；先启动 dev 后端（mvn spring-boot:run -Dspring-boot.run.profiles=dev）" >&2
  exit 1
fi

python3 - "$PID" "$OUT" <<'PY'
import sys
pid, out = sys.argv[1], sys.argv[2]
args = [a.decode() for a in open(f'/proc/{pid}/cmdline','rb').read().split(b'\0') if a]
cp = args[args.index('-cp') + 1]
open(out, 'w').write(cp)
print(f'✅ classpath 已写入 {out}（{cp.count(":")+1} 个条目）')
PY
