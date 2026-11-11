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
#
# 为什么用 systemd-run --unit 而不是 setsid nohup：
#   ① 不记在 dsh-web.service 的 6G 账上（走 qiqi-svc.slice，兄弟 slice）；
#   ② `systemd-run --scope` 的命令退出会按 KillMode=control-group 杀掉里面所有进程，
#      setsid nohup 起的 vite 也活不过脚本退出——常驻服务必须用瞬态 service 单元。
#   注意 slice 必须是**没有直接子进程**的（cgroup v2 no-internal-process）：
#   qiqi-infra.slice 里有 detach-infra.sh 搬来的散进程，无法再跑子单元（systemd-run 报 219/CGROUP），
#   所以这里用 qiqi-svc.slice。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${E2E_LOG_DIR:-/tmp}"
ACTION="${1:-start}"
SLICE="${E2E_SVC_SLICE:-qiqi-svc.slice}"

# shard → portal端口/admin端口/后端端口
ALL_SHARDS=("a:5183:5185:8091" "b:5193:5195:8092" "c:5203:5205:8093" "d:5213:5215:8094")
SHARD_N="${E2E_SHARD_N:-2}"
SHARDS=("${ALL_SHARDS[@]:0:$SHARD_N}")

health() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$1/" 2>/dev/null || true)"
  echo "${code:-000}"
}

unit_of() { echo "qiqi-e2e-$2-$1"; }   # $1=shard名 $2=portal|admin

pid_on() {
  ss -ltnp 2>/dev/null | grep ":$1 " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true
}

case "$ACTION" in
  stop)
    for s in "${SHARDS[@]}"; do
      IFS=: read -r name pport aport backend <<< "$s"
      for app in portal admin; do
        unit="$(unit_of "$name" "$app")"
        if systemctl is-active --quiet "$unit" 2>/dev/null; then
          echo "▶ 停 shard $name $app（单元 $unit）"
          systemctl stop "$unit" 2>/dev/null || true
          systemctl reset-failed "$unit" 2>/dev/null || true
        fi
        # 兜底：早先用 setsid 散起的实例不在单元里，按端口收
        port="$pport"; [[ "$app" == "admin" ]] && port="$aport"
        pid="$(pid_on "$port")"
        if [[ -n "${pid:-}" ]]; then
          echo "  · $port 上还有散进程 pid $pid，一并停掉"
          kill "$pid" 2>/dev/null || true
        fi
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
    unit="$(unit_of "$name" "$app")"
    systemctl reset-failed "$unit" 2>/dev/null || true
    echo "▶ 起 shard $name $app → $port（代理到 $backend，单元 $unit，日志 $LOG）"
    systemd-run --unit="$unit" --slice="$SLICE" --collect --quiet \
      --property=WorkingDirectory="$REPO_DIR/${app}-web" \
      --property=Environment="VITE_PORT=$port" \
      --property=Environment="VITE_PROXY_TARGET=http://127.0.0.1:${backend}" \
      --property=StandardOutput=file:"$LOG" \
      --property=StandardError=file:"$LOG" \
      -- "$REPO_DIR/${app}-web/node_modules/.bin/vite" --mode development --strictPort
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
