#!/bin/bash
B=${B:-http://127.0.0.1:8081}
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

echo "=== 步骤0 前置：分仓库存 ==="
mysql -h127.0.0.1 -uroot -pqiqi123456 -e "USE qiqi_mall; SELECT i.sku_id, w.name warehouse, i.stock FROM inventory i JOIN warehouse w ON w.id=i.warehouse_id WHERE i.sku_id IN (1,3) AND i.stock>0;" 2>/dev/null

echo "=== 步骤1 买家登录 + 下单(SKU1+SKU3) ==="
MT=$(curl -s -m 8 -X POST $B/portal/auth/login -H 'Content-Type: application/json' -d '{"username":"qiqi","password":"123456"}' | J "d['data']['accessToken']")
TK=$(curl -s -m 8 "$B/portal/orders/idempotent-token" -H "Authorization: Bearer $MT" | J "d['data']['tokenId']")
ORDER=$(curl -s -m 15 -X POST $B/portal/orders -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' \
  -d "{\"receiverName\":\"走查买家\",\"receiverPhone\":\"13700001234\",\"receiverAddress\":\"广东省深圳市南山区走查路 5 号\",\"items\":[{\"productSkuId\":1,\"quantity\":1},{\"productSkuId\":3,\"quantity\":1}],\"idempotentToken\":\"$TK\"}")
OID=$(echo "$ORDER" | J "d['data']['order']['id']")
echo "  下单响应: $(echo "$ORDER" | J "'orderId=%s status=%s total=%s' % (d['data']['order']['id'], d['data']['order']['status'], d['data']['order']['totalAmount'])")"
echo "  明细数: $(echo "$ORDER" | J "len(d['data']['items'])")"
echo "  预期(口径②): 下单后 status=0待付款"

echo "=== 步骤2 支付 ==="
PAY=$(curl -s -m 10 -X POST "$B/portal/orders/$OID/pay?payType=1" -H "Authorization: Bearer $MT")
echo "  支付响应: $(echo "$PAY" | J "'status=%s payType=%s paymentTime=%s' % (d['data']['order']['status'], d['data']['order']['payType'], d['data']['order']['paymentTime'])")"
echo "  预期(口径②): 支付后 status=7待审核（0→1→7 同一次调用，1为瞬时态）"

echo "=== 步骤3 管理员审核通过（自动拆单） ==="
AT=$(curl -s -m 8 -X POST $B/admin/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"123456"}' | J "d['data']['accessToken']")
INV_BEFORE=$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT GROUP_CONCAT(CONCAT(sku_id,':',warehouse_id,'=',stock)) FROM inventory WHERE sku_id IN (1,3);" 2>/dev/null)
AUDIT=$(curl -s -m 12 -X POST "$B/admin/orders/$OID/audit-pass" -H "Authorization: Bearer $AT")
echo "  拆单结果: $(echo "$AUDIT" | J "'; '.join('%s(warehouse=%s,status=%s)' % (x['deliverySn'], x['warehouseId'], x['status']) for x in d['data'])")"
echo "  订单状态: $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT CONCAT('status=',status,' split_status=',split_status) FROM \`order\` WHERE id=$OID;" 2>/dev/null)"
INV_AFTER=$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT GROUP_CONCAT(CONCAT(sku_id,':',warehouse_id,'=',stock)) FROM inventory WHERE sku_id IN (1,3);" 2>/dev/null)
echo "  inventory 前: $INV_BEFORE"
echo "  inventory 后: $INV_AFTER  (应完全相同=未扣减)"
D1=$(echo "$AUDIT" | J "d['data'][0]['id']"); D2=$(echo "$AUDIT" | J "d['data'][1]['id']")

echo "=== 步骤4 逐个下发 ==="
for d in $D1 $D2; do
  echo "  delivery $d 下发 → $(curl -s -m 10 -X POST "$B/admin/deliveries/$d/dispatch" -H "Authorization: Bearer $AT" | J "'status=%s' % d['data']['status']")"
done

echo "=== 步骤5 第一个子单：拣货 → 出库，检查订单是否部分发货 ==="
echo "  pick → $(curl -s -m 10 -X POST "$B/admin/deliveries/$D1/wms-callback?event=pick" -H "Authorization: Bearer $AT" | J "'status=%s' % d['data']['status']")"
echo "  outbound → $(curl -s -m 10 -X POST "$B/admin/deliveries/$D1/wms-callback?event=outbound" -H "Authorization: Bearer $AT" | J "'status=%s' % d['data']['status']")"
echo "  运单号: $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT CONCAT(company,' ',track_no) FROM delivery_logistics WHERE delivery_id=$D1;" 2>/dev/null)"
echo "  订单状态(期望 9部分发货): $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)"

echo "=== 步骤6 第二个子单出库 → 订单应变已发货 ==="
echo "  pick → $(curl -s -m 10 -X POST "$B/admin/deliveries/$D2/wms-callback?event=pick" -H "Authorization: Bearer $AT" | J "'status=%s' % d['data']['status']")"
echo "  outbound → $(curl -s -m 10 -X POST "$B/admin/deliveries/$D2/wms-callback?event=outbound" -H "Authorization: Bearer $AT" | J "'status=%s' % d['data']['status']")"
echo "  订单状态(期望 2已发货): $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)"

echo "=== 步骤7 揽收 + 签收（两个子单）→ 订单应变已完成 ==="
for d in $D1 $D2; do
  echo "  $d transit → $(curl -s -m 10 -X POST "$B/admin/deliveries/$d/wms-callback?event=transit" -H "Authorization: Bearer $AT" | J "'status=%s' % d['data']['status']")"
  echo "  $d signed  → $(curl -s -m 10 -X POST "$B/admin/deliveries/$d/wms-callback?event=signed" -H "Authorization: Bearer $AT" | J "'status=%s' % d['data']['status']")"
done
echo "  订单状态(期望 3已完成): $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)"

echo "=== 步骤8 买家查包裹 ==="
curl -s -m 10 "$B/portal/deliveries?orderId=$OID" -H "Authorization: Bearer $MT" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  code=%s 包裹数=%s' % (d['code'], len(d['data']) if d['code']==0 else d['message']))
for x in (d['data'] or []):
    print('   - %s %s status=%s track=%s %s' % (x['deliverySn'], x['warehouseName'], x['status'], x.get('trackNo'), x.get('company') or ''))
"
echo "=== 幂等复核（重复 outbound / 重复 audit-pass）==="
echo "  重复 outbound: $(curl -s -m 8 -X POST "$B/admin/deliveries/$D1/wms-callback?event=outbound" -H "Authorization: Bearer $AT" | J "'code=%s status=%s' % (d['code'], d['data']['status'] if d['code']==0 else '-')")"
echo "  重复 audit-pass: $(curl -s -m 8 -X POST "$B/admin/orders/$OID/audit-pass" -H "Authorization: Bearer $AT" | J "'code=%s msg=%s' % (d['code'], d['message'])")"
echo "OID=$OID"
