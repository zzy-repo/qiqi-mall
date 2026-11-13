#!/usr/bin/env bash
# qpscap.sh —— 在「与业务阶梯无关的独立窗口」里量 MySQL 的有效语句上限（QPS_cap）
#
#   ./qpscap.sh <label>            # 例：./qpscap.sh armA
#
# 为什么要它（这一步是本轮对照能不能成立的前提）：
#   不变式是 ok/s ≈ QPS_cap ÷ 每单语句数。如果我既改「每单语句数」又用改后的吞吐当证据，
#   那是自证。所以 QPS_cap 必须**用与业务代码无关的方式**独立量出来，且在改动前后各量一次：
#     · 若两次一致 → 机器/DB 状态没变，整组对照成立；
#     · 若变了 → 整组不可比，必须先查为什么。
#
# 怎么量（两段，缺一不可）：
#   ① 裸能力：10 条连接闭环保压 `SELECT 1` 20s（tools/QpsProbe.java），机器空闲时量
#   ② 有效能力：先开一个与阶梯无关的 450 rps 下单负载（50s），在它跑到第 12s 时
#      用同样 10 条连接量 25s 的 `SELECT 1` —— 这才是「业务饱和时 MySQL 还能跑多少条语句」
#      ①与②的差就是「宿主机 CPU 争抢 + 行锁串行」吃掉的份额
#
# 说明：② 的探针连接会额外给 MySQL 加压（这不是被动测量），所以它**只在这个独立窗口里跑**，
#       绝不插进被计量的阶梯档里（插进去会把那一档的可比性毁掉）。
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH=$(cd "$SELF/.." && pwd)
REPO=$(cd "$BENCH/.." && pwd)
LG=${LG:-/tmp/loadgen}
LABEL=${1:?用法: qpscap.sh <label>}
JAR=${JDBC_JAR:-/root/.m2/repository/com/mysql/mysql-connector-j/9.0.0/mysql-connector-j-9.0.0.jar}
PW=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
OUT=${OUT:-/tmp/loadgen/qpscap/$LABEL}
mkdir -p "$OUT"
URL="jdbc:mysql://127.0.0.1:3306/qiqi_mall_test"
echo "label=$LABEL out=$OUT"

probe() {  # <conns> <seconds> <tag>
  java -cp "$JAR" "$SELF/QpsProbe.java" "$URL" root "$PW" "$1" "$2" "SELECT 1" 2>/dev/null \
    | sed "s/^/[$(date '+%F %T')] $3 /" | tee -a "$OUT/qpsprobe.txt"
}

echo "--- ① 裸能力（机器空闲，10 连接 × 20s）"
probe 10 20 "idle-bare"

echo "--- ② 有效能力（450 rps 下单负载跑着的第 12s 起，10 连接 × 25s）"
node "$BENCH/gen.mjs" --url http://127.0.0.1:18080/portal/orders --method POST \
  --steps 450 --step-duration 55 --warmup 0 --warmup-rps 0 \
  --token-file "$LG/tokens.txt" --auth-file "$LG/jwts.txt" --body-file "$LG/order.json" \
  --sku-hot 1 --sku-ratio 0.8 --sku-cold-file "$LG/skus_cold.txt" \
  --outdir "$OUT/gen" --label "qpscap-$LABEL" --max-lag-ms 3000 > "$OUT/gen.log" 2>&1 &
GEN=$!
sleep 12
probe 10 25 "under-450rps"
wait $GEN
echo "gen 退出码=$?（负载窗口：$(grep -m1 'ok' "$OUT/gen/summary.csv" 2>/dev/null | head -1)）"
echo "--- 清场"
MEMBER_PASS=${MEMBER_PASS:-LgBench2026} MYSQL_PASSWORD="$PW" BENCH_WORKDIR="$LG" \
  "$SELF/prepare.sh" reset >/dev/null 2>&1 && echo "  订单已清"
docker exec -i -e MYSQL_PWD="$PW" qiqi-mysql mysql --default-character-set=utf8mb4 -uroot qiqi_mall_test \
  < "$REPO/sql/reset-sku-bucket.sql" >/dev/null 2>&1 && echo "  库存桶已复位"
echo "=== 结果（$OUT/qpsprobe.txt）"; cat "$OUT/qpsprobe.txt"
