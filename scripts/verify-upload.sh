#!/bin/bash
# 图片上传链路自检：登录 → 上传一张 PNG → 校验返回 URL 可取回
set -e
BASE=${1:-http://127.0.0.1:8081}
# admin 密码不再硬编码：ADMIN_PASSWORD 优先，其次读密钥文件 /root/.qiqi-secrets/admin.txt
ADMIN_PASS="${ADMIN_PASSWORD:-$(cat /root/.qiqi-secrets/admin.txt 2>/dev/null || true)}"
if [ -z "${ADMIN_PASS:-}" ]; then
  echo "❌ 缺少 admin 密码" >&2
  exit 1
fi
LOGIN_BODY=$(ADMIN_PASS="$ADMIN_PASS" python3 -c 'import json,os;print(json.dumps({"username":"admin","password":os.environ["ADMIN_PASS"]}))')
TOKEN=$(curl -s -m 8 -X POST "$BASE/admin/auth/login" -H 'Content-Type: application/json' \
  --data "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败（检查后端是否启动、admin 密码是否可用）"; exit 1; }

python3 - <<'PY'
import struct, zlib
def chunk(t, d):
    c = t + d
    return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00'))
       + chunk(b'IEND', b''))
open('/tmp/verify-upload.png', 'wb').write(png)
PY

RESP=$(curl -s -m 20 -X POST "$BASE/admin/upload/image" -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/verify-upload.png;type=image/png")
echo "上传响应: $RESP"
URL=$(echo "$RESP" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
[ -n "$URL" ] || { echo "❌ 上传失败（MinIO 未启动？见 docs/docker.md）"; exit 1; }

CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 8 "$URL")
echo "取图 HTTP=$CODE URL=$URL"
[ "$CODE" = "200" ] && echo "✅ 上传链路正常" || { echo "❌ URL 不可访问"; exit 1; }
