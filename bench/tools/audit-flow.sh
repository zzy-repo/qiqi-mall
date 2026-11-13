#!/usr/bin/env bash
# audit-flow.sh —— 在**带 useLocalSessionState 的真实容器**上跑一条完整业务链，验证「事务语义没被驱动改坏」
#
#   用法： MEMBER_PASS=... ./audit-flow.sh        （不需要 MEMBER_PASS 其实，JWT 从 $LG/jwts.txt 取）
#
# 为什么需要它：`mvn -B clean test` 跑的是**没有该 URL 参数**的测试配置（测试类里把
# spring.datasource.url 写死成 127.0.0.1 的库），所以它证明的是「代码没被我改坏」，
# **不能**证明「驱动信任本地会话状态后事务语义仍然正确」。这条脚本补的正是这一块：
# 在**真的启用了该参数**的进程上，走真实 HTTP 把「提交 / 回滚 / 幂等 / 支付记账」都摸一遍。
#
# 检查项（每一项都要 SQL 对账，不看 HTTP 200 就算过）：
#   ① 正常下单：order + order_item + 出生台账 + 库存台账 各 1 份，金额对得上，桶扣减对得上
#   ② 库存不足下单：必须失败，且**一行都不许留**（这条验的是回滚 = 事务语义）
#   ③ 支付：payment_record 1 行 + 状态台账多 1 行（PAYMENT），且库存不动
#   ④ 重复支付：幂等拒绝，payment_record 仍然只有 1 行
set -uo pipefail
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BENCH=$(cd "$SELF/.." && pwd)
REPO=$(cd "$BENCH/.." && pwd)
LG=${LG:-/tmp/loadgen}
BASE=${BASE:-http://127.0.0.1:18080}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2-)}
OUT=${OUT:-$BENCH/results/$(date '+%F')-avalanche-localsession/flow-audit}
mkdir -p "$OUT"
REPORT=$OUT/flow-audit.txt
: > "$REPORT"
say() { echo "$*" | tee -a "$REPORT"; }
sql_db() { docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql mysql --default-character-set=utf8mb4 -uroot -N -e "use qiqi_mall_test; $1" 2>/dev/null; }

JWT=$(head -1 "$LG/jwts.txt")
# 会员 id 必须从**这条 JWT 自己**的 profile 里取：jwts.txt 的第 1 行不一定就是 lg_bench_001
MEMBER_ID=$(curl -s -H "Authorization: Bearer $JWT" "$BASE/portal/member/profile" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
MEMBER_NAME=$(curl -s -H "Authorization: Bearer $JWT" "$BASE/portal/member/profile" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["username"])')
say "== 业务链审计 $(date '+%F %T') =="
say "  容器 URL: $(docker inspect qiqi-server-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^SPRING_DATASOURCE_URL=' | cut -d= -f2- || echo '(yml 默认)')"
say "  会员: $MEMBER_NAME id=$MEMBER_ID   网关: $BASE"
say ""

tok() { curl -s -H "Authorization: Bearer $JWT" "$BASE/portal/orders/idempotent-token" | sed -n 's/.*"tokenId":"\([^"]*\)".*/\1/p'; }
post_order() { # $1=token $2=sku $3=qty
  curl -s -X POST -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
    -d "{\"receiverName\":\"Audit\",\"receiverPhone\":\"13800000000\",\"receiverAddress\":\"Audit addr\",\"items\":[{\"productSkuId\":$2,\"quantity\":$3}],\"fromCart\":false,\"idempotentToken\":\"$1\"}" \
    "$BASE/portal/orders" -w '\nHTTP=%{http_code}'
}

# ---------------------------------------------------------------- ① 正常下单
say "① 正常下单（SKU 1 × 2）"
T1=$(tok); R1=$(post_order "$T1" 1 2)
say "  HTTP: $(echo "$R1" | tail -1)   body: $(echo "$R1" | head -1 | cut -c1-160)"
OID=$(echo "$R1" | head -1 | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)["data"]
    o=d.get("order") or d
    print(o["id"])
except Exception:
    print("")')
if [ -z "$OID" ]; then OID=$(sql_db "select id from \`order\` where member_id=$MEMBER_ID order by id desc limit 1;"); fi
say "  订单 id=$OID"
say "  对账： $(sql_db "select concat('order_rows=',(select count(*) from \`order\` where id=$OID),
     ' total=',(select total_amount from \`order\` where id=$OID),
     ' items=',(select count(*) from order_item where order_id=$OID),
     ' item_sum=',(select coalesce(sum(product_price*product_quantity),0) from order_item where order_id=$OID),
     ' birth_ledger=',(select count(*) from order_status_ledger where order_id=$OID and from_status is null),
     ' stock_ledger=',(select count(*) from stock_ledger where order_id=$OID and reason='ORDER_CREATE'),
     ' delta=',(select coalesce(sum(delta),0) from stock_ledger where order_id=$OID and reason='ORDER_CREATE'))")"

# ---------------------------------------------------------------- ② 库存不足 → 必须整笔回滚
say ""
say "② 库存不足下单（把 SKU 21 的桶临时清零，下 1 件，应当失败且不落任何行）"
sql_db "update sku_bucket set stock=0 where sku_id=21;" >/dev/null
BEFORE_O=$(sql_db "select count(*) from \`order\`;")
BEFORE_L=$(sql_db "select count(*) from stock_ledger;")
T2=$(tok); R2=$(post_order "$T2" 21 1)
say "  HTTP: $(echo "$R2" | tail -1)   body: $(echo "$R2" | head -1 | cut -c1-200)"
AFTER_O=$(sql_db "select count(*) from \`order\`;")
AFTER_L=$(sql_db "select count(*) from stock_ledger;")
say "  回滚对账：order $BEFORE_O → $AFTER_O（应不变）   stock_ledger $BEFORE_L → $AFTER_L（应不变）"

# ---------------------------------------------------------------- ③ 支付
say ""
say "③ 支付订单 $OID（payType=1）"
BEFORE_P=$(sql_db "select count(*) from payment_record p join \`order\` o on o.order_sn=p.order_sn where o.id=$OID;")
BEFORE_S=$(sql_db "select count(*) from order_status_ledger where order_id=$OID;")
R3=$(curl -s -X POST -H "Authorization: Bearer $JWT" "$BASE/portal/orders/$OID/pay?payType=1" -w '\nHTTP=%{http_code}')
say "  HTTP: $(echo "$R3" | tail -1)   body: $(echo "$R3" | head -1 | cut -c1-200)"
AFTER_P=$(sql_db "select count(*) from payment_record p join \`order\` o on o.order_sn=p.order_sn where o.id=$OID;")
AFTER_S=$(sql_db "select count(*) from order_status_ledger where order_id=$OID;")
say "  支付对账：payment_record $BEFORE_P → $AFTER_P（应 +1）  状态台账 $BEFORE_S → $AFTER_S（应 +1）"
say "  订单状态：$(sql_db "select concat('status=',status,' pay_type=',pay_type,' payment_time=',ifnull(payment_time,'NULL')) from \`order\` where id=$OID;")"
say "  库存在支付时不重复扣：$(sql_db "select concat('stock_ledger_rows=',count(*),' delta_sum=',coalesce(sum(delta),0)) from stock_ledger where order_id=$OID;")"

# ---------------------------------------------------------------- ④ 重复支付幂等
say ""
say "④ 重复支付（应当幂等拒绝 / 不产生第二条流水）"
R4=$(curl -s -X POST -H "Authorization: Bearer $JWT" "$BASE/portal/orders/$OID/pay?payType=1" -w '\nHTTP=%{http_code}')
say "  HTTP: $(echo "$R4" | tail -1)   body: $(echo "$R4" | head -1 | cut -c1-200)"
say "  幂等对账：payment_record 仍为 $(sql_db "select count(*) from payment_record p join \`order\` o on o.order_sn=p.order_sn where o.id=$OID;") 行"

# ---------------------------------------------------------------- ⑤ 三方一致
say ""
say "⑤ 三方一致性扫描（支付流水 ↔ 订单状态 ↔ 库存台账，圈定压测会员）"
TMPQ=$(mktemp)
cat > "$TMPQ" <<'SQL'
-- 「已付款」的口径用 payment_time 判（状态码不止 1：支付后 OMS 会自动交付推到 7 待审核）
select 'paid_orders' k, count(*) v from `order` o join member m on m.id=o.member_id
 where m.username like 'lg!_%' escape '!' and o.payment_time is not null
union all
select 'paid_but_no_payment_row', count(*) from `order` o join member m on m.id=o.member_id
 where m.username like 'lg!_%' escape '!' and o.payment_time is not null
   and not exists (select 1 from payment_record p where p.order_sn=o.order_sn)
union all
select 'payment_row_but_never_paid', count(*) from payment_record p
  join `order` o on o.order_sn=p.order_sn join member m on m.id=o.member_id
 where m.username like 'lg!_%' escape '!' and o.payment_time is null
union all
select 'payment_amount_mismatch', count(*) from payment_record p
  join `order` o on o.order_sn=p.order_sn join member m on m.id=o.member_id
 where m.username like 'lg!_%' escape '!' and p.amount<>o.total_amount
union all
select 'paid_but_stock_ledger_missing', count(*) from `order` o join member m on m.id=o.member_id
 where m.username like 'lg!_%' escape '!' and o.payment_time is not null
   and not exists (select 1 from stock_ledger l where l.order_id=o.id and l.reason='ORDER_CREATE')
union all
select 'paid_but_no_paid_status_ledger', count(*) from `order` o join member m on m.id=o.member_id
 where m.username like 'lg!_%' escape '!' and o.payment_time is not null
   and not exists (select 1 from order_status_ledger l where l.order_id=o.id and l.to_status in (1,7));
SQL
docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" qiqi-mysql \
  mysql --default-character-set=utf8mb4 -uroot --table qiqi_mall_test < "$TMPQ" 2>&1 | tee -a "$REPORT"
rm -f "$TMPQ"

say ""
say "== 说明：② 里把 SKU 21 的桶清零是**临时数据改动**，跑完由 sql/reset-sku-bucket.sql 复位；不动 DDL。=="
say "== 产物：$REPORT =="
