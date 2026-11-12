#!/usr/bin/env bash
# 初始化一把「本地开发-全权」API Key（全 9 个 scope，永不过期），供 MCP / AI 调用后端接口用。
#
# 设计要点：
#   1) 幂等：按 name 查列表，已经存在就**不新建**（明文只在创建时出现一次，脚本不会再造一个重复 key）；
#      想换一把新的就加 --force：先删同名旧 key 再建。
#   2) 明文 key 只打印到终端（并 600 权限写到 /tmp，方便本机 MCP 读取），
#      **绝不写进仓库里的任何文件** —— 库里也只存 sha256。
#   3) 建 key 用的是 admin 的 JWT（人操作），不是 API Key 自己建自己。
#
# 用法：
#   scripts/init-ai-key.sh                 # 没有就建，有就告知
#   scripts/init-ai-key.sh --force         # 删掉同名 key 再建一把新的
#   BASE_URL=http://127.0.0.1:8081 ADMIN_USER=admin ADMIN_PASS=123456 scripts/init-ai-key.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-123456}"
KEY_NAME="${KEY_NAME:-本地开发-全权}"
OUT_FILE="${OUT_FILE:-/tmp/qiqi-mall-ai-key.txt}"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

SCOPES='["order:read","order:write","catalog:read","catalog:write","member:read","member:write","config:read","config:write","dashboard:read"]'

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖：$1" >&2; exit 1; }; }
need curl
need jq

api() { # api METHOD PATH [BODY]  —— 统一带 Content-Type，JWT 由 $TOKEN 提供
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -X "$method" "$BASE_URL$path" \
      -H 'Content-Type: application/json' \
      ${TOKEN:+-H "Authorization: Bearer $TOKEN"} \
      -d "$body"
  else
    curl -sS -X "$method" "$BASE_URL$path" \
      ${TOKEN:+-H "Authorization: Bearer $TOKEN"}
  fi
}

echo "==> 登录 $BASE_URL (admin=$ADMIN_USER)"
CAPTCHA_REQUIRED=$(curl -sS "$BASE_URL/admin/auth/captcha-required?username=$ADMIN_USER" | jq -r '.data // false')
if [ "$CAPTCHA_REQUIRED" = "true" ]; then
  echo "!! 该账号因连续登录失败已被要求图形验证码，脚本无法自动登录。" >&2
  echo "   请先在管理页面正常登录一次（或等 5 分钟窗口过去）后再跑本脚本。" >&2
  exit 1
fi

LOGIN=$(api POST /admin/auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.accessToken // empty')
if [ -z "$TOKEN" ]; then
  echo "!! 登录失败：$(echo "$LOGIN" | jq -r '.message // .')" >&2
  exit 1
fi
echo "    登录成功（JWT 已获取）"

echo "==> 查同名 key：$KEY_NAME"
EXISTING=$(api GET /admin/api-keys | jq -c --arg n "$KEY_NAME" '[.data[]? | select(.name == $n)]')
EXISTING_ID=$(echo "$EXISTING" | jq -r '.[0].id // empty')
EXISTING_PREFIX=$(echo "$EXISTING" | jq -r '.[0].keyPrefix // empty')

if [ -n "$EXISTING_ID" ] && [ "$FORCE" -eq 0 ]; then
  echo "已存在同名 key（id=$EXISTING_ID, 前缀=$EXISTING_PREFIX...），不重复创建（幂等）。"
  echo "明文只在创建那一次出现过，若已丢失请用 --force 重建：scripts/init-ai-key.sh --force"
  exit 0
fi

if [ -n "$EXISTING_ID" ]; then
  echo "==> --force：删除旧 key id=$EXISTING_ID"
  api DELETE "/admin/api-keys/$EXISTING_ID" >/dev/null
fi

echo "==> 新建 key（9 个 scopes，永不过期）"
CREATED=$(api POST /admin/api-keys "{\"name\":\"$KEY_NAME\",\"scopes\":$SCOPES}")
PLAIN=$(echo "$CREATED" | jq -r '.data.key // empty')
if [ -z "$PLAIN" ]; then
  echo "!! 创建失败：$(echo "$CREATED" | jq -r '.message // .')" >&2
  exit 1
fi

install -m 600 /dev/null "$OUT_FILE"
printf '%s\n' "$PLAIN" > "$OUT_FILE"

echo
echo "创建成功："
echo "$CREATED" | jq -r '.data | "  id        = \(.id)\n  name      = \(.name)\n  keyPrefix = \(.keyPrefix)\n  scopes    = \(.scopes | join(","))\n  expiresAt = \(.expiresAt // "永不过期")"'
echo "  明文 key  = $PLAIN"
echo "              （仅本次可见；已按 600 权限写入 $OUT_FILE，勿提交进仓库）"
echo
echo "MCP / AI 侧使用方式：请求头 X-Api-Key: $PLAIN" >&2
echo "前缀（可用于核对）：${PLAIN:0:8}"
