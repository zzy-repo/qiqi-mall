#!/bin/bash
# 滚动发布下的「进程内存态」失效实验
# 见 docs/multi-instance-plan.md、docs/FIX-multi-instance.md、benchmark/3-滚动发布实验/README.md
# 用法: ./run.sh memory|redis
# 依赖: docker compose 栈（server-1/server-2/gateway/redis/mysql）
set -u
MODE=${1:-memory}
GW=${GW:-http://127.0.0.1:18080}
# admin 密码不再硬编码：ADMIN_PASSWORD 环境变量优先，其次读 /root/.qiqi-secrets/admin.txt
ADMIN_PASS="${ADMIN_PASSWORD:-$(cat /root/.qiqi-secrets/admin.txt 2>/dev/null || true)}"
if [ -z "${ADMIN_PASS:-}" ]; then
  echo "❌ 缺少 admin 密码" >&2
  exit 1
fi
STAMP=$(date +%s)
MARK="exp_${MODE}_${STAMP}"

wait_healthy() {
  for _ in $(seq 1 90); do
    h1=$(docker inspect --format '{{.State.Health.Status}}' qiqi-server-1 2>/dev/null)
    h2=$(docker inspect --format '{{.State.Health.Status}}' qiqi-server-2 2>/dev/null)
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$GW/api/health")
    [ "$h1" = "healthy" ] && [ "$h2" = "healthy" ] && [ "$code" = "200" ] && return 0
    sleep 2
  done
  echo "!! 健康检查超时（server-1=$h1 server-2=$h2 gateway=$code）"
  return 1
}

echo "===== 模式: KV_STORE=$MODE ====="
KV_STORE=$MODE docker compose up -d --force-recreate server-1 server-2 >/dev/null 2>&1
wait_healthy || exit 1
echo "实例状态: server-1=healthy server-2=healthy, gateway=200"

# 会员 token（JWT 无状态，任一实例签发的都能用）
MT=$(curl -s -m 8 -X POST "$GW/portal/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"qiqi","password":"123456"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$MT" ] || { echo "!! 会员登录失败"; exit 1; }

# ---------- 指标1: 失败计数是否跨实例可见 ----------
echo "--- 指标1: 5 次失败登录（经网关轮询）后 captcha-required"
for _ in $(seq 1 5); do
  curl -s -m 8 -X POST "$GW/admin/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"wrong"}' >/dev/null
done
NEED=$(curl -s -m 8 "$GW/admin/auth/captcha-required?username=admin" | grep -o 'true\|false')
echo "captcha-required = $NEED（redis 期望 true / memory 期望 false）"

# ---------- 指标2: 验证码跨实例可用 ----------
echo "--- 指标2: 验证码跨实例使用"
CAP=$(curl -s -m 8 "$GW/admin/auth/captcha")
CID=$(echo "$CAP" | sed -n 's/.*"captchaId":"\([^"]*\)".*/\1/p')
if [ "$MODE" = "redis" ]; then
  CODE=$(docker exec qiqi-redis redis-cli get "captcha:$CID" 2>/dev/null | tr -d '\r')
  if [ -n "$CODE" ]; then
    RES=$(curl -s -m 8 -X POST "$GW/admin/auth/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CODE\"}")
    echo "跨实例验证码登录: $(echo "$RES" | grep -o '"code":[0-9]*')（code=0 表示通过）"
  else
    echo "跨实例验证码登录: 从 Redis 取不到明文（异常）"
  fi
else
  echo "跨实例验证码登录: memory 档明文只存在于某实例内存，跨实例不可用（失效点，跳过）"
fi

# ---------- 指标3a: 令牌由实例1发放、经网关提交 → 误拒率 ----------
echo "--- 指标3a: 令牌由「实例1」发放，提交走网关（跨实例），6 次试验"
FALSE_REJECT=0
TRIALS=6
for _ in $(seq 1 $TRIALS); do
  TK=$(docker exec qiqi-server-1 wget -qO- --header="Authorization: Bearer $MT" \
        http://127.0.0.1:8082/portal/orders/idempotent-token 2>/dev/null \
        | sed -n 's/.*"tokenId":"\([^"]*\)".*/\1/p')
  R=$(curl -s -m 15 -X POST "$GW/portal/orders" -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' \
    -d "{\"receiverName\":\"$MARK\",\"receiverPhone\":\"1\",\"receiverAddress\":\"x\",\"items\":[{\"productSkuId\":9,\"quantity\":1}],\"idempotentToken\":\"$TK\"}")
  echo "$R" | grep -q '请勿重复提交' && FALSE_REJECT=$((FALSE_REJECT+1))
done
echo "误拒次数 = $FALSE_REJECT/$TRIALS（memory 期望约半数被误拒；redis 期望 0）"

# ---------- 指标3b: 同一令牌连续提交 4 次 ----------
echo "--- 指标3b: 同一幂等令牌提交 4 次（经网关轮询）"
TK2=$(curl -s -m 8 "$GW/portal/orders/idempotent-token" -H "Authorization: Bearer $MT" \
  | sed -n 's/.*"tokenId":"\([^"]*\)".*/\1/p')
OK=0
for _ in 1 2 3 4; do
  R=$(curl -s -m 15 -X POST "$GW/portal/orders" -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' \
    -d "{\"receiverName\":\"$MARK\",\"receiverPhone\":\"1\",\"receiverAddress\":\"x\",\"items\":[{\"productSkuId\":9,\"quantity\":1}],\"idempotentToken\":\"$TK2\"}")
  echo "$R" | grep -q '"code":0' && OK=$((OK+1))
done
echo "同一令牌成功下单次数 = $OK（redis 期望 1）"

# ---------- 指标4: 滚动发布窗口可用性 ----------
echo "--- 指标4: 停掉 server-1 后经网关发 10 次请求"
docker compose stop server-1 >/dev/null 2>&1
sleep 8
SUCC=0
for _ in $(seq 1 10); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 8 "$GW/api/health")
  [ "$code" = "200" ] && SUCC=$((SUCC+1))
done
echo "单实例在线成功率 = $SUCC/10"
docker compose start server-1 >/dev/null 2>&1
wait_healthy >/dev/null 2>&1

echo "===== 汇总（KV_STORE=$MODE）====="
echo "captcha-required=$NEED | 误拒=$FALSE_REJECT/$TRIALS | 同令牌成功=$OK | 单实例在线=$SUCC/10 | 标记=$MARK"
