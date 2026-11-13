#!/usr/bin/env bash
#
# 起 / 停 e2e 用的「构建产物预览服务」（每个 shard 一套：portal + admin），
# 由 e2e/support/preview-server.mjs 提供静态 + 反代，替代 vite dev server。
#
#   ./scripts/e2e-preview.sh build     # 只在需要时构建两个前端（不带 CDN_BASE，产物引用本地 /assets）
#   ./scripts/e2e-preview.sh start     # 起 4 个 preview（a:5283/5285 → 8091，b:5293/5295 → 8092）
#   ./scripts/e2e-preview.sh stop      # 停掉（systemd unit / 精确 PID，绝不 pkill -f）
#   ./scripts/e2e-preview.sh status    # 看四个端口健康 + 各自 dist 时间戳
#
# 为什么端口要比 dev 那套（5183/5185/5193/5195）+100：
#   两套前端可以同时在跑，切 E2E_FRONT_MODE 时不用先停别人正在用的 dev 栈；
#   对 e2e 而言只是 PORTAL_BASE/ADMIN_BASE 换了个端口号，spec 一行不用改。
#
# 为什么用 systemd-run --unit（和 start-e2e-frontends.sh 同款）：
#   ① 不记进 dsh-web.service 的 6G 账（走 qiqi-svc.slice，兄弟 slice）；
#   ② 停的时候有 unit 名可精确 `systemctl stop`，比按命令行子串 pkill 安全
#      （这台机器上同时有 dev 5173/5175、容器栈、别人的录片，宽匹配会误杀）。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVE="$REPO_DIR/e2e/support/preview-server.mjs"
LOG_DIR="${E2E_LOG_DIR:-/tmp}"
SLICE="${E2E_SVC_SLICE:-qiqi-svc.slice}"
PORT_OFFSET="${E2E_PREVIEW_PORT_OFFSET:-100}"
ACTION="${1:-start}"

# shard → portal端口/admin端口/后端端口（与 e2e-run.sh 的 SHARDS 同源，只多一个 offset）
ALL_SHARDS=("a:5183:5185:8091" "b:5193:5195:8092" "c:5203:5205:8093" "d:5213:5215:8094")
SHARD_N="${E2E_SHARD_N:-2}"
SHARDS=("${ALL_SHARDS[@]:0:$SHARD_N}")

unit_of() { echo "qiqi-e2e-preview-$2-$1"; }   # $1=shard名 $2=portal|admin

health() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$1/" 2>/dev/null || true)"
  echo "${code:-000}"
}

pid_on() {
  ss -ltnpH "sport = :$1" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true
}

# 端口 → 该 shard 的 dist 目录
dist_of() { echo "$REPO_DIR/$1-web/dist"; }   # $1=portal|admin

do_build() {
  local t0 t1
  t0=$(date +%s)
  # ⚠️ 不带 CDN_BASE：产物必须引用本地 /assets（见各 vite.config.js 顶部），否则预览服务上全是 404
  ( cd "$REPO_DIR/portal-web" && unset CDN_BASE && npm run build ) > "$LOG_DIR/e2e-build-portal.log" 2>&1
  ( cd "$REPO_DIR/admin-web"  && unset CDN_BASE && npm run build ) > "$LOG_DIR/e2e-build-admin.log" 2>&1
  t1=$(date +%s)
  echo "▶ 构建完成：portal-web/dist + admin-web/dist，耗时 $((t1-t0))s（日志 $LOG_DIR/e2e-build-{portal,admin}.log）"
  grep -hE 'built in|dist/index.html' "$LOG_DIR/e2e-build-portal.log" "$LOG_DIR/e2e-build-admin.log" | sed 's/^/  /' || true
}

case "$ACTION" in
  build)
    do_build
    exit 0
    ;;

  stop)
    for s in "${SHARDS[@]}"; do
      IFS=: read -r name pport aport backend <<< "$s"
      for app in portal admin; do
        unit="$(unit_of "$name" "$app")"
        if systemctl is-active --quiet "$unit" 2>/dev/null; then
          echo "▶ 停 preview shard $name $app（单元 $unit）"
          systemctl stop "$unit" 2>/dev/null || true
        fi
        systemctl reset-failed "$unit" 2>/dev/null || true
        # 兜底：万一是散进程（不是单元起的），按端口精确反查 PID 再杀单个
        if [[ "$app" == "portal" ]]; then port=$((pport+PORT_OFFSET)); else port=$((aport+PORT_OFFSET)); fi
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
      pp=$((pport+PORT_OFFSET)); ap=$((aport+PORT_OFFSET))
      printf 'shard %-2s portal %-5s(%s)  admin %-5s(%s)  → 后端 %s  dist: portal %s / admin %s\n' \
        "$name" "$pp" "$(health "$pp")" "$ap" "$(health "$ap")" "$backend" \
        "$(stat -c %y "$REPO_DIR/portal-web/dist/index.html" 2>/dev/null | cut -d. -f1 || echo 未构建)" \
        "$(stat -c %y "$REPO_DIR/admin-web/dist/index.html" 2>/dev/null | cut -d. -f1 || echo 未构建)"
    done
    pid_count="$(pgrep -fc 'e2e/support/preview-server.mjs' 2>/dev/null || true)"
    echo "preview 进程数：${pid_count:-0}"
    exit 0
    ;;

  start) ;;
  *) echo "用法：$0 [build|start|stop|status]" >&2; exit 1 ;;
esac

# ── start ──────────────────────────────────────────────────────────────────
# 先硬校验 dist：没构建就别起，否则每个页面都是 404，跑出来一片假红还查不出原因
for app in portal admin; do
  idx="$(dist_of "$app")/index.html"
  if [[ ! -f "$idx" ]]; then
    echo "✗ 缺少 $idx —— 先构建：$0 build" >&2
    exit 2
  fi
  if ! grep -q '/assets/' "$idx"; then
    echo "✗ $idx 里没有 /assets 引用（可能是带 CDN_BASE 构建的产物）→ 用 $0 build 重建" >&2
    exit 2
  fi
done

for s in "${SHARDS[@]}"; do
  IFS=: read -r name pport aport backend <<< "$s"
  for app in portal admin; do
    if [[ "$app" == "portal" ]]; then port=$((pport+PORT_OFFSET)); else port=$((aport+PORT_OFFSET)); fi
    if [[ "$(health "$port")" != "000" ]]; then
      echo "· shard $name $app 已在 $port 上跑着，跳过"
      continue
    fi
    unit="$(unit_of "$name" "$app")"
    LOG="$LOG_DIR/e2e-preview-$app-$name.log"
    : > "$LOG"
    systemctl reset-failed "$unit" 2>/dev/null || true
    echo "▶ 起 preview shard $name $app → $port（dist=$(dist_of "$app")，api→$backend，单元 $unit）"
    systemd-run --unit="$unit" --slice="$SLICE" --collect --quiet \
      --property=WorkingDirectory="$REPO_DIR" \
      --property=StandardOutput=file:"$LOG" \
      --property=StandardError=file:"$LOG" \
      -- "$(command -v node)" "$SERVE" \
         --dist="$(dist_of "$app")" --port="$port" --api="http://127.0.0.1:$backend" \
         --label="$name-$app" --quiet
  done
done

echo "▶ 等 preview 就绪（最多 60s）"
for i in $(seq 1 30); do
  ok=1
  for s in "${SHARDS[@]}"; do
    IFS=: read -r name pport aport backend <<< "$s"
    for port in $((pport+PORT_OFFSET)) $((aport+PORT_OFFSET)); do
      [[ "$(health "$port")" != "000" ]] || ok=0
    done
  done
  [[ "$ok" == "1" ]] && break
  sleep 2
done
"${BASH_SOURCE[0]}" status
