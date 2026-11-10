#!/bin/bash
# 图片上传链路自检：登录 → 上传一张 PNG → 校验返回 URL 可取回
set -e
BASE=${1:-http://127.0.0.1:8081}
TOKEN=$(curl -s -m 8 -X POST "$BASE/admin/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "登录失败（检查后端是否启动、admin/123456 是否可用）"; exit 1; }

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
