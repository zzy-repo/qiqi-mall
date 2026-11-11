#!/usr/bin/env bash
#
# 起 / 停 e2e 专用的两个后端（每个 Playwright shard 一个），与 dev 8081、容器栈 18080 完全隔离。
#
#   ./scripts/start-e2e-backends.sh           # 起 a(8091) + b(8092)
#   ./scripts/start-e2e-backends.sh stop      # 停掉
#   ./scripts/start-e2e-backends.sh status    # 看端口与健康
#
# 为什么用本地 java 进程而不是 docker service：
#   1) 不碰容器栈（server-1/2 的库配置归别的任务，动了会影响 demo 档和预览服务）；
#   2) 不用重新构建镜像（改后端源码时只要 mvn compile，进程重启即可）；
#   3) 内存可控（每个 JVM -Xmx384m，实测 RSS ~330MB；docker 还要多一层容器开销）。
#
# 隔离点：库不同（qiqi_mall_e2e_a / _b）+ Redis database 不同（1 / 2）。
#   Redis 也要分开，否则 captcha:fail:{username} 这类全局计数会跨 shard 互相污染
#   ——这正是「worker A 把 admin 密码错打满、worker B 登录被卡死」的根因。
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$REPO_DIR/qiqi-mall-server"
LOG_DIR="${E2E_LOG_DIR:-/tmp}"
ACTION="${1:-start}"

# shard → 端口 / 库 / redis db / 日志
SHARDS=("a:8091:qiqi_mall_e2e_a:1" "b:8092:qiqi_mall_e2e_b:2")

port_of()   { echo "$1" | cut -d: -f2; }
db_of()     { echo "$1" | cut -d: -f3; }
redis_of()  { echo "$1" | cut -d: -f4; }
name_of()   { echo "$1" | cut -d: -f1; }

health() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${1}/portal/products/1" 2>/dev/null || true)"
  echo "${code:-000}"
}

case "$ACTION" in
  stop)
    for s in "${SHARDS[@]}"; do
      port="$(port_of "$s")"
      pid="$(ss -ltnp 2>/dev/null | grep ":${port} " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
      if [[ -n "${pid:-}" ]]; then
        echo "▶ 停 shard $(name_of "$s")（pid $pid, 端口 $port）"
        kill "$pid" 2>/dev/null || true
      else
        echo "· shard $(name_of "$s") 没在跑"
      fi
    done
    exit 0
    ;;
  status)
    for s in "${SHARDS[@]}"; do
      port="$(port_of "$s")"
      printf 'shard %-2s 端口 %-5s 库 %-18s redis-db %-2s 健康 %s\n' \
        "$(name_of "$s")" "$port" "$(db_of "$s")" "$(redis_of "$s")" "$(health "$port")"
    done
    exit 0
    ;;
  start) ;;
  *)
    echo "用法：$0 [start|stop|status]" >&2
    exit 1
    ;;
esac

# 1) 后端跑的是 target/classes，先把最新源码编出来
echo "▶ 编译后端（mvn -o -q compile）"
(cd "$SERVER_DIR" && mvn -o -q compile)
CP_FILE="$SERVER_DIR/target/e2e-cp.txt"
if [[ ! -f "$CP_FILE" ]]; then
  echo "✗ 缺 $CP_FILE：请先在 dev 后端（8081）运行时执行 scripts/e2e-classpath.sh 生成" >&2
  exit 1
fi
CP="$(cat "$CP_FILE")"

for s in "${SHARDS[@]}"; do
  name="$(name_of "$s")"; port="$(port_of "$s")"; db="$(db_of "$s")"; rdb="$(redis_of "$s")"
  if [[ "$(health "$port")" == "200" ]]; then
    echo "· shard $name 已在 $port 上跑着，跳过"
    continue
  fi
  LOG="$LOG_DIR/e2e-server-$name.log"
  echo "▶ 启动 shard $name → 端口 $port / 库 $db / redis-db $rdb（日志 $LOG）"
  setsid nohup java -XX:TieredStopAtLevel=1 -Xmx384m -cp "$CP" com.qiqi.mall.QiQiMallApplication \
    --spring.profiles.active=dev \
    --server.port="$port" \
    --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/${db}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
    --spring.data.redis.database="$rdb" \
    --spring.devtools.restart.enabled=false \
    > "$LOG" 2>&1 &
done

echo "▶ 等健康检查（最多 90s）"
for i in $(seq 1 45); do
  ok=1
  for s in "${SHARDS[@]}"; do
    [[ "$(health "$(port_of "$s")")" == "200" ]] || ok=0
  done
  [[ "$ok" == "1" ]] && break
  sleep 2
done

for s in "${SHARDS[@]}"; do
  printf '  shard %-2s 端口 %-5s 健康 %s\n' "$(name_of "$s")" "$(port_of "$s")" "$(health "$(port_of "$s")")"
done
