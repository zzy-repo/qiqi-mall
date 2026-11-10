#!/bin/bash
# 超卖复现: 50并发同时下单,每单买1件SKU(id=1,初始stock=10)
# 期望正确行为: 最多成功10单; 若>10单成功则为超卖
TOKEN=$(cat benchmark/oversell/.token)
CONCURRENCY=200
QTY_PER_ORDER=1
echo "发起 $CONCURRENCY 个并发下单请求(每单qty=$QTY_PER_ORDER)..."
# 每个请求单独领一个幂等令牌（模拟 N 个用户各自点一次结算）
for i in $(seq 1 $CONCURRENCY); do
  TK=$(curl -s -m 10 http://127.0.0.1:8081/portal/orders/idempotent-token -H "Authorization: Bearer $TOKEN" | sed -n 's/.*"tokenId":"\([^"]*\)".*/\1/p')
  curl -s -m 10 -X POST http://127.0.0.1:8081/portal/orders \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"items\":[{\"productSkuId\":1,\"quantity\":$QTY_PER_ORDER}],\"receiverName\":\"压测$i\",\"receiverAddress\":\"测试地址\",\"idempotentToken\":\"$TK\"}" \
    -o /tmp/order_resp_$i.json &
done
wait
SUCCESS=$(grep -l '"code":0' /tmp/order_resp_*.json 2>/dev/null | wc -l)
# 后端报错文案为「库存不足：<商品名>」，这里按不含结尾引号的关键词匹配
FAIL=$(grep -l '库存不足' /tmp/order_resp_*.json 2>/dev/null | wc -l)
echo "结果: 成功=$SUCCESS 失败=$FAIL (总计$CONCURRENCY)"
