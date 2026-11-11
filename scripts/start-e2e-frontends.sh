#!/usr/bin/env bash
#
# 起 / 停 e2e 专用前端（每个 shard 一套 vite：portal + admin），与 dev 5173/5175 隔离。
#
#   ./scripts/start-e2e-frontends.sh           # 起 shard a(5183/5185→8091) + b(5193/5195→8092)
#   ./scripts/start-e2e-frontends.sh stop
#   ./scripts/start-e2e-frontends.sh status
#
# 为什么是一个前端多套而不是用 Host 头分流：
#   vite 的 proxy 底层是 node-http-proxy，**不支持** http-proxy-middleware 的 router（按 Host 选 target），
#   所以只能在「多起几套 vite」和「起 nginx 反代」之间选。实测一套 vite RSS 只有 ~85~120MB，
#   起四套（2 shard × portal/admin）比多起两个 JVM 便宜得多，所以选多套 vite。
#
# vite 只做静态资源 + /portal|/admin 代理，改的是 VITE_PROXY_TARGET / VITE_PORT 两个环境变量，
# 不改任何业务代码。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${E2E_LOG_DIR:-/tmp}"
ACTION="${1:-start}"

# shard → portal端口/admin端口/后端端口
SHARDS=("a:5183:5185:8091" "b:5193:5195:8092")

health() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$1/" 2>/dev/null || true)"
  echo "${code:-000}"
}

pid_on() {
  ss -ltnp 2>/dev/null | grep ":$1 " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true
}

case "$ACTION" in
  stop)
    for s in "${SHARDS[@]}"; do
      IFS=: read -r name pport aport backend <<< "$s"
      for port in "$pport" "$aport"; do
        pid="$(pid_on "$port")"
        [[ -n "${pid:-}" ]] && echo "▶ 停 $name:$port（pid $pid）" && kill "$pid" 2>/dev/null || true
      done
    done
    exit 0
    ;;
  status)
    for s in "${SHARDS[@]}"; do
      IFS=: read -r name pport aport backend <<< "$s"
      printf 'shard %-2s portal %-5s(%s)  admin %-5s(%s)  → 后端 %s\n' \
        "$name" "$pport" "$(health "$pport")" "$aport" "$(health "$aport")" "$backend"
    done
    exit 0
    ;;
  start) ;;
  *) echo "用法：$0 [start|stop|status]" >&2; exit 1 ;;
esac

for s in "${SHARDS[@]}"; do
  IFS=: read -r name pport aport backend <<< "$s"
  for app in portal admin; do
    port="$pport"; [[ "$app" == "admin" ]] && port="$aport"
    if [[ "$(health "$port")" != "000" ]]; then
      echo "· shard $name $app 已在 $port 上跑着，跳过"
      continue
    fi
    LOG="$LOG_DIR/e2e-$app-$name.log"
    echo "▶ 起 shard $name $app → $port（代理到 $backend，日志 $LOG）"
    ( cd "$REPO_DIR/${app}-web" && \
      VITE_PORT="$port" VITE_PROXY_TARGET="http://127.0.0.1:${backend}" \
      setsid nohup ./node_modules/.bin/vite --mode development --strictPort > "$LOG" 2>&1 & )
  done
done

echo "▶ 等前端就绪（最多 90s）"
for i in $(seq 1 45); do
  ok=1
  for s in "${SHARDS[@]}"; do
    IFS=: read -r name pport aport backend <<< "$s"
    [[ "$(health "$pport")" != "000" && "$(health "$aport")" != "000" ]] || ok=0
  done
  [[ "$ok" == "1" ]] && break
  sleep 2
done
"${BASH_SOURCE[0]}" status
