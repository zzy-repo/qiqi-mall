#!/bin/bash
# 死锁复现脚本(FIXME对应): 多SKU订单,并发事务以不同顺序锁行,触发率~53%
# 与 race.sh(单SKU抢库存)不同:本脚本制造"锁顺序交叉"来稳定触发死锁
# 用法: ./deadlock.sh [并发数]  默认100
TOKEN=$(cat benchmark/oversell/.token)
N=${1:-100}

echo "=== 前置:确认两个SKU都有充足库存 ==="
mysql -h127.0.0.1 -uroot -pqiqi123456 2>/dev/null <<SQL
UPDATE qiqi_mall.sku SET stock=1000 WHERE id IN (1,2);
SELECT id,stock FROM qiqi_mall.sku WHERE id IN (1,2);
SQL

echo "=== 发起 $N 个并发下单(一半订单先买SKU1后买SKU2,一半反向) ==="
# 锁顺序交叉的核心:两批请求对相同两行资源以相反顺序加锁
for i in $(seq 1 $N); do
  if [ $((i % 2)) -eq 0 ]; then
    ITEMS='[{"productSkuId":1,"quantity":1},{"productSkuId":2,"quantity":1}]'
  else
    ITEMS='[{"productSkuId":2,"quantity":1},{"productSkuId":1,"quantity":1}]'
  fi
  TK=$(curl -s -m 10 http://127.0.0.1:8081/portal/orders/idempotent-token -H "Authorization: Bearer $TOKEN" | sed -n 's/.*"tokenId":"\([^"]*\)".*/\1/p')
  curl -s -m 10 -X POST http://127.0.0.1:8081/portal/orders \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"items\":$ITEMS,\"receiverName\":\"锁压测$i\",\"receiverAddress\":\"测试\",\"idempotentToken\":\"$TK\"}" \
    -o /tmp/dl_resp_$i.json &
done
wait

SUCCESS=$(grep -l '"code":0' /tmp/dl_resp_*.json 2>/dev/null | wc -l)
# 注意:只能匹配响应体里的报错，不能匹配请求里的 receiverName
DEADLOCK=$(grep -lE "系统繁忙|Deadlock|Lock wait" /tmp/dl_resp_*.json 2>/dev/null | wc -l)
OTHERFAIL=$((N - SUCCESS - DEADLOCK))
echo "结果: 成功=$SUCCESS 死锁报错=$DEADLOCK 其他失败=$OTHERFAIL (总计$N)"
echo "--- 死锁报错样例 ---"
grep -h '"message"' $(grep -lE "系统繁忙|Deadlock|Lock wait" /tmp/dl_resp_*.json 2>/dev/null | head -1) 2>/dev/null | head -c 200
echo
rm -f /tmp/dl_resp_*.json
