#!/usr/bin/env bash
# 启动 MCP Inspector，让它有一个**稳定、可点开、且真正能用**的地址，
# 并把带 token 的完整 URL 写进文档站首页「测试环境」表格的「MCP 调试器」行。
#
# 用法：bash scripts/start-inspector.sh
# 幂等：重复执行会先停掉旧实例，不会堆积进程。
#
# ── 为什么对外地址走 tailscale serve 而不是直接 HOST=0.0.0.0 ──────────────
# 1) Inspector 默认拒绝绑 0.0.0.0：需要 DANGEROUSLY_BIND_ALL_INTERFACES=true
#    才能绕过（它自己会警告：backend 能 spawn 本地进程，是 DNS-rebinding 靶子）。
# 2) 即使绕过了也**不可用**：从 http://<局域网IP>:6274 打开是「非安全上下文」，
#    浏览器在非 localhost 的明文 HTTP 下不提供 crypto.randomUUID / crypto.subtle，
#    Inspector 一连接就报 `crypto.randomUUID is not a function`。
#    （已验证：同样实例用 http://localhost:6274 打开，4 个工具能正常列出。）
# 3) 所以这里让 Inspector 只绑回环，对外用 tailscale serve 提供 HTTPS：
#    有合法证书 → 安全上下文 → 页面功能完整，且不把 6274 暴露到整个网络。
set -euo pipefail

REPO_ROOT="/root/qiqi-mall"
DOCS_README="/root/docsify/README.md"
DOCS_BUILDER="/root/docsify/build-material.mjs"
DOCS_LOCK="/tmp/docsify.lock"

UNIT="mcp-inspector"
SLICE="qiqi-svc.slice"
CLIENT_PORT="${CLIENT_PORT:-6274}"
SANDBOX_PORT="${SANDBOX_PORT:-6275}"

# Inspector 自身监听地址（仅回环；对外由 tailscale serve 代理）
BIND_HOST="${BIND_HOST:-127.0.0.1}"

# 对外 HTTPS 入口（tailscale serve）
PUBLIC_HOSTNAME="${PUBLIC_HOSTNAME:-zzy-eq.tailcb871e.ts.net}"
PUBLIC_SCHEME="${PUBLIC_SCHEME:-https}"
PUBLIC_PORT="${PUBLIC_PORT:-8444}"
PUBLIC_ORIGIN="${PUBLIC_SCHEME}://${PUBLIC_HOSTNAME}:${PUBLIC_PORT}"
PUBLIC_URL_BASE="${PUBLIC_ORIGIN}/"

# 文档站 / 其它地址展示用的 tailnet IP
PUBLIC_HOST="${PUBLIC_HOST:-100.113.135.55}"
DOCS_SITE="http://${PUBLIC_HOST}:3027"

MALL_BASE="${MALL_BASE:-http://127.0.0.1:18080}"
# 认证改为 API Key（MCP Server 已不再登录拿 JWT）：优先环境变量，其次读 init-ai-key.sh 产出的文件
MALL_API_KEY="${MALL_API_KEY:-$(cat /tmp/test-env-ai-key.txt 2>/dev/null || true)}"
if [ -z "$MALL_API_KEY" ]; then
  echo "❌ 缺少 MALL_API_KEY：先跑 scripts/init-ai-key.sh，或 export MALL_API_KEY=ak_..." >&2
  exit 1
fi

# token 固定：Inspector 的 authToken 解析顺序是
#   options.authToken || process.env.MCP_INSPECTOR_API_TOKEN || randomBytes(32)
# 显式给 MCP_INSPECTOR_API_TOKEN 后就不再随机生成，静态链接长期有效。
INSPECTOR_TOKEN="${INSPECTOR_TOKEN:-qiqi-mall-mcp-inspector-2026}"

# Origin 白名单：浏览器 Origin 必须登记，否则点「连接」会 403 Invalid origin。
# 注意 ALLOWED_ORIGINS 一旦设置就整体覆盖默认值，本机地址要一并列上。
ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-${PUBLIC_ORIGIN},http://localhost:${CLIENT_PORT},http://127.0.0.1:${CLIENT_PORT}}"

log() { printf '[start-inspector] %s\n' "$*"; }
die() { printf '[start-inspector] ERROR: %s\n' "$*" >&2; exit 1; }

command -v systemd-run >/dev/null || die "systemd-run 不可用"
[ -f "$DOCS_README" ] || die "找不到 $DOCS_README"

# ---------------------------------------------------------------- 1) 停旧实例
# 只按 systemd 单元名 + 端口上的确切 PID 清理，绝不使用 pkill -f 宽匹配
# （宽匹配会把执行脚本的 shell 自身一起杀掉）。
log "停止旧的 $UNIT"
systemctl stop "$UNIT" >/dev/null 2>&1 || true
systemctl reset-failed "$UNIT" >/dev/null 2>&1 || true

for port in "$CLIENT_PORT" "$SANDBOX_PORT"; do
  while read -r pid; do
    [ -n "${pid:-}" ] || continue
    log "端口 $port 仍被 pid=$pid 占用，发送 TERM"
    kill "$pid" 2>/dev/null || true
  done < <(ss -lntpH "sport = :${port}" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)
done

# 等端口真正释放（最多 10s）
for _ in $(seq 1 20); do
  ss -lntH "sport = :${CLIENT_PORT}" 2>/dev/null | grep -q . || break
  sleep 0.5
done

# ---------------------------------------------------------------- 2) 对外入口
if command -v tailscale >/dev/null 2>&1; then
  log "确保 tailscale serve 入口 ${PUBLIC_ORIGIN} → http://127.0.0.1:${CLIENT_PORT}"
  # 重复设置同一映射是幂等的；已有配置不会被动
  tailscale serve --bg "--https=${PUBLIC_PORT}" "http://127.0.0.1:${CLIENT_PORT}" >/dev/null 2>&1 \
    || log "警告：tailscale serve 配置失败（对外 HTTPS 入口可能不可用）"
else
  log "警告：未找到 tailscale 命令，跳过对外 HTTPS 入口"
fi

# ---------------------------------------------------------------- 3) 后台启动
log "以 systemd 单元 $UNIT 启动（slice=$SLICE，退出 DSH 也不受影响）"
JOURNAL_SINCE="$(date '+%Y-%m-%d %H:%M:%S')"

BIND_ENV=()
if [ "$BIND_HOST" = "0.0.0.0" ] || [ "$BIND_HOST" = "::" ]; then
  BIND_ENV+=(--setenv=DANGEROUSLY_BIND_ALL_INTERFACES=true)
fi

systemd-run \
  --unit="$UNIT" \
  --slice="$SLICE" \
  --collect \
  --working-directory="$REPO_ROOT" \
  --description="MCP Inspector (qiqi-mall)" \
  --setenv=HOME=/root \
  --setenv=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  --setenv=HOST="$BIND_HOST" \
  "${BIND_ENV[@]}" \
  --setenv=MCP_AUTO_OPEN_ENABLED=false \
  --setenv=MCP_INSPECTOR_API_TOKEN="$INSPECTOR_TOKEN" \
  --setenv=ALLOWED_ORIGINS="$ALLOWED_ORIGINS" \
  --setenv=CLIENT_PORT="$CLIENT_PORT" \
  --setenv=SERVER_PORT="$SANDBOX_PORT" \
  --setenv=MCP_ENABLE_WRITE=1 \
  --setenv=MALL_BASE="$MALL_BASE" \
  --setenv=MALL_API_KEY="$MALL_API_KEY" \
  /usr/bin/npx -y @modelcontextprotocol/inspector \
    -e "MALL_BASE=$MALL_BASE" \
    -e "MALL_API_KEY=$MALL_API_KEY" \
    -e "MCP_ENABLE_WRITE=1" \
    node mcp-server/src/index.mjs \
  >/dev/null

# ---------------------------------------------------------------- 4) 抓 token
# 从 journal 里正则抓带 token 的完整 URL，再换成对外 HTTPS 地址。
INTERNAL_URL=""
for _ in $(seq 1 60); do
  INTERNAL_URL="$(journalctl -u "$UNIT" --since "$JOURNAL_SINCE" --no-pager 2>/dev/null \
          | grep -oE 'http://[^[:space:]]*MCP_INSPECTOR_API_TOKEN=[A-Za-z0-9._~-]+' \
          | head -1 || true)"
  if [ -n "$INTERNAL_URL" ] && ss -lntH "sport = :${CLIENT_PORT}" 2>/dev/null | grep -q .; then
    break
  fi
  sleep 1
done

if [ -n "$INTERNAL_URL" ]; then
  TOKEN_PARAM="$(printf '%s' "$INTERNAL_URL" | grep -oE 'MCP_INSPECTOR_API_TOKEN=[A-Za-z0-9._~-]+' | head -1)"
else
  log "未从日志抓到 URL，用已知 token 构造兜底链接"
  TOKEN_PARAM="MCP_INSPECTOR_API_TOKEN=${INSPECTOR_TOKEN}"
fi

PUBLIC_URL="${PUBLIC_URL_BASE}?${TOKEN_PARAM}"
log "Inspector 内部地址 : ${INTERNAL_URL:-(未抓到)}"
log "对外链接           : $PUBLIC_URL"

# ---------------------------------------------------------------- 5) 写首页
ROW="| MCP 调试器 | ${PUBLIC_URL} | 调 MCP 工具（由 scripts/start-inspector.sh 启动，重启会刷新地址） |"
ROW_ESCAPED="${ROW//&/\\&}"

if grep -q '^| MCP 调试器 |' "$DOCS_README"; then
  log "首页已有「MCP 调试器」行，替换"
  sed -i "s#^| MCP 调试器 |.*\$#${ROW_ESCAPED}#" "$DOCS_README"
else
  log "首页无「MCP 调试器」行，插入到「接口文档」行之后"
  # 「接口文档」在「测试环境」和「开发环境」两张表里都有，锚定第一次出现的那行
  sed -i "0,/^| 接口文档 |/s#^\(| 接口文档 |.*\)\$#\1\n${ROW_ESCAPED}#" "$DOCS_README"
fi

grep -q '^| MCP 调试器 |' "$DOCS_README" || die "写入 $DOCS_README 失败"

# ---------------------------------------------------------------- 6) 重建站点
log "重建文档站"
flock "$DOCS_LOCK" node "$DOCS_BUILDER" >/dev/null 2>&1 || die "文档站重建失败（$DOCS_BUILDER）"

# ---------------------------------------------------------------- 7) 报告
echo
echo "==================== 启动完成 ===================="
echo "URL        : $PUBLIC_URL"
echo "单元状态   : $(systemctl is-active "$UNIT" 2>/dev/null || echo unknown) / $(systemctl show -p MainPID --value "$UNIT" 2>/dev/null | sed 's/^/pid=/')"
echo -n "端口监听   : "
ss -lntH "sport = :${CLIENT_PORT}" 2>/dev/null | awk '{print $4}' | paste -sd' ' - || echo "(无)"
echo -n "带 token 访问: "
curl -s -o /dev/null -m 10 -w "%{http_code}\n" "$PUBLIC_URL" || echo "curl 失败"
echo -n "首页那一行 : "
curl -s -m 10 "${DOCS_SITE}/README.md" | grep -c 'MCP 调试器' | sed 's/^/匹配行数=/' || echo "匹配行数=0"
echo "文档首页   : ${DOCS_SITE}/"
echo "停止       : bash scripts/stop-inspector.sh"
echo "================================================="
