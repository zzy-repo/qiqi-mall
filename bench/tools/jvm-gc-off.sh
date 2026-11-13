#!/usr/bin/env bash
# jvm-gc-off.sh —— 把 JAVA_OPTS 还原成发布值（-XX:MaxRAMPercentage=75），去掉 -Xlog:gc*
#   会重启容器；镜像不变。
set -euo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SELF/../.." && pwd)
cd "$REPO"
docker compose -f docker-compose.yml up -d --no-deps server-1 server-2
for i in $(seq 1 60); do
  h=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/health || true)
  [ "$h" = "200" ] && { echo "✅ 健康 ($i s)"; break; }
  sleep 1
done
for c in qiqi-server-1 qiqi-server-2; do
  echo "$c: $(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep JAVA_OPTS)"
done

# ⚠️ 必须重启 gateway：nginx 在**启动时**解析 upstream 容器 IP 并缓存（见 nginx/qiqi.conf 的注释）。
#    重建 server-1/-2 会让它们拿到新 IP（甚至互换 8082/8083 的对应关系），不重启 gateway 就会
#    「容器 healthy 但网关 502 / no live upstreams」。这一步实测踩过，写进脚本里避免再踩。
docker compose restart gateway >/dev/null 2>&1 || docker compose up -d --no-deps --force-recreate gateway >/dev/null 2>&1
sleep 3
if [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/health || true)" = "200" ]; then
  echo "✅ 网关 200（upstream 已重新解析）"
else
  echo "⚠ 网关仍不通，检查：docker logs --tail 20 qiqi-gateway" >&2
fi
