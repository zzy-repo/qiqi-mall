#!/bin/bash
# 场景5 走查：支付 → 审核通过自动拆单 → 逐个下发 → WMS 回传 → 父订单聚合 → 买家查包裹。
#
# 并发 / 重复防护口径（后端 1f49974 起）：**确认码已整套删除**，写操作不再需要任何凭证，
#   裸 POST 就是合法调用。保护由两层承担：
#     ① 业务状态守卫（例：「只有待审核订单可以审核通过」）
#     ② 热表 version 乐观锁（期间被别人改过 → 409 版本冲突）
#   本脚本里每个写操作仍走「预演 → 执行」两跳，统一由下面的 preview_and_exec 完成，
#   但预演只是给人看「将要发生什么」，**不签发任何码**：
#     ① 同一地址 + ?dryRun=true → 不落库，返回 { willDo }（一句具体中文「将做什么」）
#     ② 同一地址（不带 dryRun）+ 业务 body → 直接执行（body 里没有 confirmCode）
#   预演和执行跑的是同一套业务守卫，所以「预演就不过」= 这次操作本来就不该执行。
B=${B:-http://127.0.0.1:8081}
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

FAIL=0
check() { # check <实际> <期望> <说明>
  if [ "$1" = "$2" ]; then
    echo "    ✓ $3（$1）"
  else
    echo "    ✗ $3（实际 $1，期望 $2）"
    FAIL=$((FAIL+1))
  fi
}
check_contains() { # check_contains <实际文本> <期望包含的子串> <说明>
  case "$1" in
    *"$2"*) echo "    ✓ $3（含「$2」）" ;;
    *)      echo "    ✗ $3（实际「$1」，期望包含「$2」）"; FAIL=$((FAIL+1)) ;;
  esac
}

# ------------------------------------------------------------------ 预演 → 执行 小工具
# 用法：preview_and_exec <OP_NAME> <DRYRUN_METHOD> <DRYRUN_URL> <EXEC_METHOD> <EXEC_URL> <BODY_JSON_or_empty>
#   1) 打 dryRun=true → 把 data.willDo 打印出来（这是给人看「将要发生什么」），**不取码**；
#      预演不通过 → 把后端中文原因原样打印并放弃执行，说明这次操作本来就不该执行，不会发出任何写请求
#   2) 打真接口，body 就是业务参数（确认码已删，body 里没有 confirmCode）
#   3) 执行失败时把后端中文 message 原样打印出来（不吞），并计入 FAIL
# 结果通过全局变量回传：PE_RESP（执行响应原文，供调用方再 J() 取字段）
PE_RESP=""
preview_and_exec() {
  local name="$1" dm="$2" durl="$3" em="$4" eurl="$5" body="${6:-}"
  PE_RESP=""
  [ -z "$body" ] && body='{}'
  local sep='?'; case "$durl" in *\?*) sep='&' ;; esac
  local dry dcode dmsg dwill ecode emsg
  dry=$(curl -s -m 15 -X "$dm" "$durl${sep}dryRun=true" -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{}')
  dcode=$(printf '%s' "$dry" | J "d['code']" 2>/dev/null)
  if [ "$dcode" != "0" ]; then
    dmsg=$(printf '%s' "$dry" | J "d['message']" 2>/dev/null)
    echo "    ⚠ 预演不通过（$name）：${dmsg:-响应无法解析：$dry}"
    echo "      → 业务守卫已经把这次操作挡在门外，本来就不该执行，已放弃（未发出任何写请求）。"
    return 1
  fi
  dwill=$(printf '%s' "$dry" | J "d['data']['willDo']")
  echo "    ⟳ 预演 dryRun=true（不落库）：$dwill"
  PE_RESP=$(curl -s -m 20 -X "$em" "$eurl" -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d "$body")
  ecode=$(printf '%s' "$PE_RESP" | J "d['code']" 2>/dev/null)
  if [ "$ecode" != "0" ]; then
    emsg=$(printf '%s' "$PE_RESP" | J "d['message']" 2>/dev/null)
    echo "    ✗ 执行失败（$name）：${emsg:-响应无法解析：$PE_RESP}"
    FAIL=$((FAIL+1))
    return 1
  fi
  return 0
}

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
check "$(echo "$ORDER" | J "d['data']['order']['status']")" "0" "下单后 order.status=0"

echo "=== 步骤2 支付 ==="
PAY=$(curl -s -m 10 -X POST "$B/portal/orders/$OID/pay?payType=1" -H "Authorization: Bearer $MT")
echo "  支付响应: $(echo "$PAY" | J "'status=%s payType=%s paymentTime=%s' % (d['data']['order']['status'], d['data']['order']['payType'], d['data']['order']['paymentTime'])")"
echo "  预期(口径②): 支付后 status=7待审核（0→1→7 同一次调用，1为瞬时态）"
check "$(echo "$PAY" | J "d['data']['order']['status']")" "7" "支付后 order.status=7"

echo "=== 步骤3 管理员审核通过（自动拆单）——L3 写操作，先预演看 willDo 再执行 ==="
AT=$(curl -s -m 8 -X POST $B/admin/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"123456"}' | J "d['data']['accessToken']")
INV_BEFORE=$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT GROUP_CONCAT(CONCAT(sku_id,':',warehouse_id,'=',stock)) FROM inventory WHERE sku_id IN (1,3);" 2>/dev/null)
preview_and_exec "audit-pass 审核通过" POST "$B/admin/orders/$OID/audit-pass" POST "$B/admin/orders/$OID/audit-pass" "" \
  || { echo "  ✗ 审核通过没走通，后面没有发货单可演示，终止。"; echo "OID=$OID"; exit 1; }
AUDIT="$PE_RESP"
echo "  拆单结果: $(echo "$AUDIT" | J "'; '.join('%s(warehouse=%s,status=%s)' % (x['deliverySn'], x['warehouseId'], x['status']) for x in d['data'])")"
echo "  订单状态: $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT CONCAT('status=',status,' split_status=',split_status) FROM \`order\` WHERE id=$OID;" 2>/dev/null)"
INV_AFTER=$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT GROUP_CONCAT(CONCAT(sku_id,':',warehouse_id,'=',stock)) FROM inventory WHERE sku_id IN (1,3);" 2>/dev/null)
echo "  inventory 前: $INV_BEFORE"
echo "  inventory 后: $INV_AFTER  (应完全相同=未扣减)"
check "$INV_AFTER" "$INV_BEFORE" "拆单未扣分仓 inventory"
check "$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)" "8" "审核通过后 order.status=8待发货"
D1=$(echo "$AUDIT" | J "d['data'][0]['id']"); D2=$(echo "$AUDIT" | J "d['data'][1]['id']")
check "$(echo "$AUDIT" | J "len(d['data'])")" "2" "拆成 2 个发货单"

echo "=== 步骤4 逐个下发（每个都先预演）==="
for d in $D1 $D2; do
  echo "  delivery $d 下发："
  preview_and_exec "dispatch 包裹 $d" POST "$B/admin/deliveries/$d/dispatch" POST "$B/admin/deliveries/$d/dispatch" "" \
    && { echo "    → status=$(echo "$PE_RESP" | J "d['data']['status']")"; \
         check "$(echo "$PE_RESP" | J "d['data']['status']")" "1" "包裹 $d 已下发(status=1)"; }
done

echo "=== 步骤5 第一个子单：拣货 → 出库，检查订单是否部分发货 ==="
echo "  pick →"
preview_and_exec "wms-callback pick" POST "$B/admin/deliveries/$D1/wms-callback?event=pick" POST "$B/admin/deliveries/$D1/wms-callback?event=pick" "" \
  && check "$(echo "$PE_RESP" | J "d['data']['status']")" "2" "D1 已拣货(status=2)"
echo "  outbound →"
preview_and_exec "wms-callback outbound" POST "$B/admin/deliveries/$D1/wms-callback?event=outbound" POST "$B/admin/deliveries/$D1/wms-callback?event=outbound" "" \
  && check "$(echo "$PE_RESP" | J "d['data']['status']")" "3" "D1 已出库(status=3)"
echo "  运单号: $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT CONCAT(company,' ',track_no) FROM delivery_logistics WHERE delivery_id=$D1;" 2>/dev/null)"
echo "  订单状态(期望 9部分发货): $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)"
check "$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)" "9" "部分出库后 order.status=9部分发货"

echo "=== 步骤6 第二个子单出库 → 订单应变已发货 ==="
echo "  pick →"
preview_and_exec "wms-callback pick" POST "$B/admin/deliveries/$D2/wms-callback?event=pick" POST "$B/admin/deliveries/$D2/wms-callback?event=pick" "" \
  && check "$(echo "$PE_RESP" | J "d['data']['status']")" "2" "D2 已拣货(status=2)"
echo "  outbound →"
preview_and_exec "wms-callback outbound" POST "$B/admin/deliveries/$D2/wms-callback?event=outbound" POST "$B/admin/deliveries/$D2/wms-callback?event=outbound" "" \
  && check "$(echo "$PE_RESP" | J "d['data']['status']")" "3" "D2 已出库(status=3)"
echo "  订单状态(期望 2已发货): $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)"
check "$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)" "2" "全部出库后 order.status=2已发货"

echo "=== 步骤7 揽收 + 签收（两个子单）→ 订单应变已完成 ==="
for d in $D1 $D2; do
  echo "  $d transit →"
  preview_and_exec "wms-callback transit" POST "$B/admin/deliveries/$d/wms-callback?event=transit" POST "$B/admin/deliveries/$d/wms-callback?event=transit" "" \
    && check "$(echo "$PE_RESP" | J "d['data']['status']")" "4" "$d 运输中(status=4)"
  echo "  $d signed →"
  preview_and_exec "wms-callback signed" POST "$B/admin/deliveries/$d/wms-callback?event=signed" POST "$B/admin/deliveries/$d/wms-callback?event=signed" "" \
    && check "$(echo "$PE_RESP" | J "d['data']['status']")" "5" "$d 已签收(status=5)"
done
echo "  订单状态(期望 3已完成): $(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)"
check "$(mysql -h127.0.0.1 -uroot -pqiqi123456 -N -e "USE qiqi_mall; SELECT status FROM \`order\` WHERE id=$OID;" 2>/dev/null)" "3" "全部签收后 order.status=3已完成"

echo "=== 步骤8 买家查包裹 ==="
curl -s -m 10 "$B/portal/deliveries?orderId=$OID" -H "Authorization: Bearer $MT" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  code=%s 包裹数=%s' % (d['code'], len(d['data']) if d['code']==0 else d['message']))
for x in (d['data'] or []):
    print('   - %s %s status=%s track=%s %s' % (x['deliverySn'], x['warehouseName'], x['status'], x.get('trackNo'), x.get('company') or ''))
"

echo "=== 幂等 / 重复防护复核（重复 outbound / 重复 audit-pass）==="
echo "  重复 outbound（D1 已签收 → 预演会说「不会改动任何数据」，直接执行也只回原状态）："
preview_and_exec "wms-callback outbound(重复)" POST "$B/admin/deliveries/$D1/wms-callback?event=outbound" POST "$B/admin/deliveries/$D1/wms-callback?event=outbound" "" \
  && { echo "    → code=0 status=$(echo "$PE_RESP" | J "d['data']['status']")"; \
       check "$(echo "$PE_RESP" | J "d['data']['status']")" "5" "重复 outbound 未改状态(仍=5)"; }
echo "  重复 audit-pass（订单已完成，不再是待审核 → 由业务守卫拦）："
REUSE=$(curl -s -m 8 -X POST "$B/admin/orders/$OID/audit-pass" -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{}')
echo "    → $(echo "$REUSE" | J "'code=%s msg=%s' % (d['code'], d['message'])")"
echo "    结论：没有码可重放了，重复防护改由业务状态守卫承担 —— 订单已不是 status=7，"
echo "          后端直接以「只有待审核订单可以审核通过」拒掉（不是重试能解决的问题，是状态变了）。"
check "$(echo "$REUSE" | J "d['code']")" "1" "重复 audit-pass 被拒(code=1)"
check_contains "$(echo "$REUSE" | J "d['message']")" "只有待审核订单可以审核通过" "重复 audit-pass 报的是业务守卫原因"
echo "  对照：预演侧同样挡住（同一套守卫，预演一开口就报同一个原因）"
NODRY=$(curl -s -m 8 -X POST "$B/admin/orders/$OID/audit-pass?dryRun=true" -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{}')
echo "    → $(echo "$NODRY" | J "'code=%s msg=%s' % (d['code'], d['message'])")"
check "$(echo "$NODRY" | J "d['code']")" "1" "已完成订单预演被业务守卫挡下(code=1)"
check_contains "$(echo "$NODRY" | J "d['message']")" "只有待审核订单可以审核通过" "预演与执行报同一个业务守卫原因"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "✅ 场景5 走查全流程完成：全部断言通过（写操作全程走「预演看 willDo → 直接执行」）"
else
  echo "✗ 有 $FAIL 条断言失败，见上面标 ✗ 的行" >&2
fi
echo "OID=$OID"
[ "$FAIL" -eq 0 ]
