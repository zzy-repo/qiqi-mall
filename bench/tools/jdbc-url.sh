#!/usr/bin/env bash
# jdbc-url.sh —— 只改 JDBC URL 参数（useLocalSessionState）的注入口：不重建镜像、不改代码、不改池
#
#   ./jdbc-url.sh apply true  /tmp/loadgen/jar/b/app.jar    # useLocalSessionState=true（实验臂 B）
#   ./jdbc-url.sh apply false /tmp/loadgen/jar/b/app.jar    # 同 jar、同 URL 其他参数，只是不带该参数（臂 A）
#   ./jdbc-url.sh off                                       # 还原发行态：拆掉 URL 注入 + jar 挂载 + GC 日志
#
# 为什么 A 臂也用这个脚本注入：两个臂除了 URL 里那一个参数，连「注入路径」都必须一致，
# 否则「A 少了参数会不会是注入方式带来的差异」就说不清。A 臂注入的是**与 application-test.yml
# 完全等价**的 URL（只是占位符已展开）。
#
# 生效的硬证据不是日志，而是 performance_schema：注入 true 后
# `SELECT @@SESSION.transaction_read_only`（Connector/J 每条 DML 前的探针）应从 7.04 条/单 → ~0。
# 见 tools/probecheck.sh。
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SELF/../.." && pwd)
cd "$REPO"

DB=${MYSQL_DATABASE:-qiqi_mall_test}
DBHOST=${BENCH_DB_HOST:-mysql}
BASEPART="jdbc:mysql://${DBHOST}:3306/${DB}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"

wait_health() {
  for _ in $(seq 1 90); do
    h=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/health || true)
    [ "$h" = "200" ] && { echo "✅ 网关健康"; return 0; }
    sleep 1
  done
  echo "⚠ 90s 内网关没 200，检查 docker logs --tail 30 qiqi-server-1 / qiqi-gateway" >&2
  return 1
}

show_state() {
  echo "=== 生效状态（容器内实际环境变量，不是我以为的）==="
  for c in qiqi-server-1 qiqi-server-2; do
    echo "  $c SPRING_DATASOURCE_URL = $(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^SPRING_DATASOURCE_URL=' | cut -d= -f2- || echo '(未注入 → 走 yml)')"
    echo "  $c JAVA_OPTS              = $(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^JAVA_OPTS=' | cut -d= -f2-)"
    echo "  $c jar md5                = $(docker exec "$c" sh -c 'md5sum /app/app.jar' 2>/dev/null | cut -d' ' -f1)  mount=$(docker inspect "$c" --format '{{range .Mounts}}{{.Destination}}{{println}}{{end}}' | tr -d '\n')"
  done
  echo "  镜像（**未重建**）           = $(docker inspect qiqi-mall-server:test --format '{{.Id}}')"
  echo "  MySQL 侧每客户端连接数       ="
  docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql -uroot -N -e \
    "select concat('    ',substring_index(host,':',1),' -> ',count(*),' 条') from information_schema.processlist group by substring_index(host,':',1)" 2>/dev/null
}

case "${1:-}" in
  apply)
    WANT=${2:?用法: jdbc-url.sh apply <true|false> <jar 宿主机绝对路径>}
    JAR=${3:?用法: jdbc-url.sh apply <true|false> <jar 宿主机绝对路径>}
    [ -f "$JAR" ] || { echo "✗ 找不到 $JAR" >&2; exit 2; }
    chmod 644 "$JAR"
    export JAR_HOST_PATH=$JAR
    if [ "$WANT" = "true" ]; then
      export BENCH_JDBC_URL="${BASEPART}&useLocalSessionState=true"
    else
      export BENCH_JDBC_URL="${BASEPART}"
    fi
    echo "=== 注入 URL（useLocalSessionState=$WANT）==="
    echo "  $BENCH_JDBC_URL"
    echo "  jar: $JAR md5=$(md5sum "$JAR" | cut -d' ' -f1)"
    docker compose -f docker-compose.yml -f bench/jvm-gc.override.yml \
                   -f bench/jar-mount.override.yml -f bench/jdbc-url.override.yml \
                   up -d --no-deps --force-recreate server-1 server-2 >/dev/null || exit 3
    docker compose restart gateway >/dev/null 2>&1 || true
    sleep 3
    wait_health || true
    show_state
    ;;
  off)
    echo "=== 还原发行态（拆 URL 注入 + jar 挂载 + GC 日志）==="
    docker compose -f docker-compose.yml up -d --no-deps --force-recreate server-1 server-2 >/dev/null || exit 3
    docker compose restart gateway >/dev/null 2>&1 || true
    sleep 3
    wait_health || true
    show_state
    ;;
  *)
    sed -n '2,20p' "$0"
    exit 2
    ;;
esac
