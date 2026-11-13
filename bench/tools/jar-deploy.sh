#!/usr/bin/env bash
# jar-deploy.sh —— 把「刚 mvn 出来的 jar」挂进 server-1/-2（**不重建镜像**），并核对生效证据
#
#   ./jar-deploy.sh <fat.jar> <label>        # label 只是归档名：a / b / ...
#   ./jar-deploy.sh --restore                # 还原：容器回到镜像自带的 jar（拆掉挂载）
#
# 做的事（顺序不能换）：
#   ① 把 jar 复制到 /tmp/loadgen/jar/<label>/app.jar 并 chmod 644
#      （容器以 USER app 运行，target/ 里的 jar 是 0600 root:root —— 直接挂上去 JVM 读不了）
#   ② docker compose -f docker-compose.yml -f bench/jvm-gc.override.yml -f bench/jar-mount.override.yml
#      up -d --no-deps --force-recreate server-1 server-2
#      （GC 日志两臂**都开**，保证 A/B 同构；镜像不变，只多一个只读 bind mount）
#   ③ 重启 gateway（nginx 启动时缓存 upstream IP，重建 server 后不重启会 502 —— 已踩过）
#   ④ 打印生效证据：容器里 /app/app.jar 的 md5 必须等于宿主机那份；镜像 digest 必须没变
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SELF/../.." && pwd)
LG=${LG:-/tmp/loadgen}
cd "$REPO"

compose_recreate() {
  docker compose -f docker-compose.yml -f bench/jvm-gc.override.yml "$@" \
    up -d --no-deps --force-recreate server-1 server-2
}

wait_health() {
  for _ in $(seq 1 90); do
    h=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/health || true)
    [ "$h" = "200" ] && { echo "✅ 健康"; return 0; }
    sleep 1
  done
  echo "⚠ 90s 内网关没 200，检查 docker logs --tail 30 qiqi-server-1 / qiqi-gateway" >&2
  return 1
}

restart_gateway() {
  docker compose restart gateway >/dev/null 2>&1 || true
  sleep 3
}

if [ "${1:-}" = "--restore" ]; then
  echo "=== 还原：拆掉 jar 挂载，容器回到镜像自带 jar（镜像本来就等于发布态）==="
  compose_recreate
  restart_gateway
  wait_health || true
  for c in qiqi-server-1 qiqi-server-2; do
    echo "$c mounts: $(docker inspect "$c" --format '{{range .Mounts}}{{.Destination}} {{end}}' | tr -d '\n' || true)"
    echo "$c /app/app.jar md5: $(docker exec "$c" sh -c 'md5sum /app/app.jar' 2>/dev/null)"
  done
  echo "镜像（未重建）: $(docker inspect qiqi-mall-server:test --format '{{.Id}}')"
  exit 0
fi

JAR=${1:?用法: jar-deploy.sh <fat.jar> <label> | --restore}
LABEL=${2:?用法: jar-deploy.sh <fat.jar> <label>}
[ -f "$JAR" ] || { echo "✗ 找不到 $JAR" >&2; exit 2; }

DEST=$LG/jar/$LABEL
mkdir -p "$DEST"
cp -f "$JAR" "$DEST/app.jar"
chmod 644 "$DEST/app.jar"
export JAR_HOST_PATH=$DEST/app.jar
echo "=== 部署 jar（label=$LABEL）==="
echo "  host jar: $JAR_HOST_PATH  md5=$(md5sum "$JAR_HOST_PATH" | cut -d' ' -f1)  size=$(stat -c%s "$JAR_HOST_PATH")"

docker compose -f docker-compose.yml -f bench/jvm-gc.override.yml -f bench/jar-mount.override.yml \
  up -d --no-deps --force-recreate server-1 server-2
restart_gateway
wait_health

echo "=== 生效证据 ==="
for c in qiqi-server-1 qiqi-server-2; do
  echo "  $c mounts: $(docker inspect "$c" --format '{{range .Mounts}}{{.Source}}->{{.Destination}}{{end}}')"
  echo "  $c /app/app.jar md5: $(docker exec "$c" sh -c 'md5sum /app/app.jar' 2>/dev/null)"
  echo "  $c JAVA_OPTS: $(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^JAVA_OPTS=' | tr -d '\n')"
done
echo "  镜像（**未重建**）: $(docker inspect qiqi-mall-server:test --format '{{.Id}}')"
echo "  git HEAD: $(git -C "$REPO" rev-parse HEAD)  git dirty: $(git -C "$REPO" status --porcelain | wc -l) 行"
