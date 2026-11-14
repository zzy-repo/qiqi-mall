#!/usr/bin/env bash
# 压测数据准备（一次性）—— 只操作容器栈的 qiqi_mall_test 库 + qiqi-redis
#
#   ./prepare.sh status        看当前状态（不改数据）
#   ./prepare.sh sku           固定 1 个 SKU：库存置 500000，打印 id/stock/version
#   ./prepare.sh members       注册 200 个会员，JWT 落 $BENCH_WORKDIR/jwts.txt
#   ./prepare.sh tokens        生成 200000 个幂等令牌到 $BENCH_WORKDIR/tokens.txt（纯文件，不碰服务）
#   ./prepare.sh seed          把 tokens.txt 灌进 Redis（TTL 1800s）——**必须在开跑前 30 分钟内执行**
#   ./prepare.sh tokens-api    走真实接口 GET /portal/orders/idempotent-token 取令牌（慢，用于口径对照）
#   ./prepare.sh reset         删掉本套压测造的数据（按测试会员精确删）+ 库存重置回 500000
#   ./prepare.sh all           sku + members + tokens
#
# 环境变量：BASE / SKU_ID / SKU_STOCK / MEMBERS / TOKENS
#           BENCH_WORKDIR（数据工作目录，默认 /tmp/loadgen；tokens.txt / jwts.txt / order.json 落这里）
#           MYSQL_PASSWORD（必填，否则从 <repo>/.env 的 MYSQL_ROOT_PASSWORD 读；脚本内不含任何口令默认值）
#           MEMBER_PASS（必填：压测会员口令，本脚本不提供默认值，也不回显）
set -euo pipefail

BASE=${BASE:-http://127.0.0.1:18080}
SKU_ID=${SKU_ID:-1}
SKU_STOCK=${SKU_STOCK:-500000}
COLD_FROM=${COLD_FROM:-2}
COLD_TO=${COLD_TO:-21}
MEMBERS=${MEMBERS:-200}
TOKENS=${TOKENS:-200000}
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=${REPO:-$(cd "$SELF/../.." && pwd)}
DIR=${BENCH_WORKDIR:-/tmp/loadgen}
MYSQL_CONT=qiqi-mysql
DB=qiqi_mall_test
MEMBER_PREFIX=lg_bench_
MEMBER_PASS=${MEMBER_PASS:-}
TOKEN_TTL_MS=1800000   # 与服务端 IdempotentTokenStore.TTL 一致（30 分钟）

mkdir -p "$DIR"

# 数据库口令：只从环境变量或项目 .env 读，脚本内不含任何默认口令，也不回显
if [ -z "${MYSQL_PASSWORD:-}" ]; then
  MYSQL_PASSWORD=$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO/.env" 2>/dev/null | cut -d= -f2- || true)
fi
if [ -z "${MYSQL_PASSWORD:-}" ]; then
  echo "缺少 MYSQL_PASSWORD：请 export MYSQL_PASSWORD=... 或确认 $REPO/.env 里有 MYSQL_ROOT_PASSWORD" >&2
  exit 2
fi
if [ -z "${MEMBER_PASS:-}" ]; then
  echo "缺少 MEMBER_PASS：压测会员口令必须由调用方通过环境变量传入（脚本不内置默认口令）" >&2
  exit 2
fi

sql() { docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PASSWORD" -N -e "$1" 2>/dev/null; }
sql_db() { docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PASSWORD" -N -e "use $DB; $1" 2>/dev/null; }

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖: $1" >&2; exit 2; }; }
need curl; need python3

# ---------------------------------------------------------------- status
cmd_status() {
  echo "== 服务 =="
  curl -s -o /dev/null -w "gateway /api/health -> %{http_code}\n" "$BASE/api/health" || true
  echo "== SKU =="
  sql_db "select id,product_id,price,stock,version from sku where id=$SKU_ID;"
  echo "== 测试会员 =="
  sql_db "select count(*) from member where username like '${MEMBER_PREFIX}%';"
  echo "== 数据量 =="
  sql_db "select 'order', count(*) from \`order\` union all select 'order_item', count(*) from order_item union all select 'delivery_order', count(*) from delivery_order union all select 'payment_record', count(*) from payment_record union all select 'stock_ledger', count(*) from stock_ledger;"
  echo "== 本地文件 =="
  for f in "$DIR/tokens.txt" "$DIR/jwts.txt" "$DIR/members.tsv"; do
    [ -f "$f" ] && echo "$f  $(wc -l < "$f") 行" || echo "$f  缺失"
  done
  echo "== Redis 幂等令牌（样本）=="
  docker exec -i qiqi-redis redis-cli --scan --pattern 'idem:order:*' 2>/dev/null | head -3
  echo "TTL(样本): $(docker exec -i qiqi-redis redis-cli --scan --pattern 'idem:order:*' 2>/dev/null | head -1 | xargs -r -I{} docker exec -i qiqi-redis redis-cli TTL {} 2>/dev/null)"
}

# ---------------------------------------------------------------- sku
cmd_sku() {
  echo "== 固定 SKU：id=$SKU_ID stock=$SKU_STOCK =="
  sql_db "update sku set stock=$SKU_STOCK where id=$SKU_ID;"
  sql_db "select concat('id=',id,' product_id=',product_id,' price=',price,' stock=',stock,' version=',version) from sku where id=$SKU_ID;"
  echo "注：下单扣减走 UPDATE sku SET stock=stock-qty WHERE id=? AND stock>=qty（不动 version）；
库存全部集中在 sku.stock，inventory（分仓）只在管理员拆单/发货链路用到，本压测口径不涉及。"
}

# ---------------------------------------------------------------- cold（80/20 里的 20% 冷门 SKU 池）
cmd_cold() {
  echo "== 冷门 SKU 池：id $COLD_FROM..$COLD_TO，库存各置 $SKU_STOCK，清单写 $DIR/skus_cold.txt =="
  sql_db "update sku set stock=$SKU_STOCK where id between $COLD_FROM and $COLD_TO;"
  sql_db "select id from sku where id between $COLD_FROM and $COLD_TO order by id;" | tr -d '\r' > "$DIR/skus_cold.txt"
  echo "冷门 SKU 数: $(wc -l < "$DIR/skus_cold.txt")；库存抽查: $(sql_db "select concat(id,'=',stock) from sku where id in ($COLD_FROM,$COLD_TO);" | tr '\n' ' ')"
  echo "注：审计要求 80% 打热点 SKU($SKU_ID)、20% 打随机冷门 SKU，否则行锁竞争被摊薄、分桶前后比不出差异。"
}

# ---------------------------------------------------------------- members
cmd_members() {
  echo "== 注册 $MEMBERS 个会员（前缀 $MEMBER_PREFIX）=="
  : > "$DIR/members.tsv"; : > "$DIR/jwts.txt"
  reg_one() {
    i=$1; u=$(printf "%s%03d" "$MEMBER_PREFIX" "$i")
    # 先试注册；已存在则改为登录（幂等，可反复执行）
    body=$(curl -s -m 20 -X POST "$BASE/portal/auth/register" -H 'Content-Type: application/json' \
      -d "{\"username\":\"$u\",\"password\":\"$MEMBER_PASS\",\"nickname\":\"$u\"}" || echo '{}')
    tok=$(printf '%s' "$body" | python3 -c 'import sys,json
try: d=json.load(sys.stdin)
except Exception: d={}
dd=d.get("data") or {}
print(dd.get("accessToken",""), dd.get("memberId",""))' 2>/dev/null)
    if [ -z "${tok%% *}" ]; then
      body=$(curl -s -m 20 -X POST "$BASE/portal/auth/login" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$u\",\"password\":\"$MEMBER_PASS\"}" || echo '{}')
      tok=$(printf '%s' "$body" | python3 -c 'import sys,json
try: d=json.load(sys.stdin)
except Exception: d={}
dd=d.get("data") or {}
print(dd.get("accessToken",""), dd.get("memberId",""))' 2>/dev/null)
    fi
    set -- $tok
    if [ -n "${1:-}" ]; then
      printf '%s\n' "$1" >> "$DIR/jwts.txt"
      printf '%s\t%s\t%s\n' "${2:-?}" "$u" ok >> "$DIR/members.tsv"
    else
      printf 'ERR\t%s\t%s\n' "$u" "$(printf '%s' "$body" | head -c 120)" >> "$DIR/members.tsv"
    fi
  }
  export -f reg_one; export BASE MEMBER_PREFIX MEMBER_PASS DIR
  seq 1 "$MEMBERS" | xargs -P 2 -I{} bash -c 'reg_one {}'
  echo "JWT 条数: $(wc -l < "$DIR/jwts.txt") / $MEMBERS"
  echo "失败条数: $(grep -c '^ERR' "$DIR/members.tsv" || true)"
  echo "样例行（脱敏）: $(head -1 "$DIR/members.tsv" | awk -F'\t' '{print "memberId="$1" username="$2" "$3}')"
}

# ---------------------------------------------------------------- tokens (纯文件)
cmd_tokens() {
  echo "== 生成 $TOKENS 个幂等令牌到 $DIR/tokens.txt（格式与服务端一致：UUID 去横线 32 位 hex）=="
  python3 - "$TOKENS" > "$DIR/tokens.txt" <<'PY'
import sys, uuid
n = int(sys.argv[1])
w = sys.stdout.write
for _ in range(n):
    w(uuid.uuid4().hex + "\n")
PY
  echo "条数: $(wc -l < "$DIR/tokens.txt")；样例: $(head -1 "$DIR/tokens.txt")"
  echo "⚠ 令牌有 30 分钟 TTL（服务端 IdempotentTokenStore.TTL=30min），文件本身不过期，"
  echo "  但**灌进 Redis 后 30 分钟内必须用完**，所以真正的池子是 ./prepare.sh seed。"
}

# ---------------------------------------------------------------- tokens-api（真实接口取）
cmd_tokens_api() {
  [ -s "$DIR/jwts.txt" ] || { echo "先跑 ./prepare.sh members" >&2; exit 2; }
  echo "== 走真实接口取 $TOKENS 个令牌（GET /portal/orders/idempotent-token）=="
  : > "$DIR/tokens.txt"
  fetch_one() {
    jwt=$(shuf -n1 "$DIR/jwts.txt")
    curl -s -m 20 "$BASE/portal/orders/idempotent-token" -H "Authorization: Bearer $jwt" \
      | python3 -c 'import sys,json
try: d=json.load(sys.stdin)
except Exception: print(""); raise SystemExit
print((d.get("data") or {}).get("tokenId",""))' 2>/dev/null
  }
  export -f fetch_one; export BASE DIR
  seq 1 "$TOKENS" | xargs -P 8 -I{} bash -c 'fetch_one' | grep -v '^$' > "$DIR/tokens.txt"
  echo "拿到: $(wc -l < "$DIR/tokens.txt") / $TOKENS"
}

# ---------------------------------------------------------------- 把令牌灌进 Redis
cmd_seed() {
  [ -s "$DIR/tokens.txt" ] || { echo "先跑 ./prepare.sh tokens" >&2; exit 2; }
  echo "== 灌 Redis（key=idem:order:<token>，PX $TOKEN_TTL_MS）=="
  local before after
  before=$(docker exec -i qiqi-redis redis-cli DBSIZE | tr -d '\r')
  python3 - "$DIR/tokens.txt" "$TOKEN_TTL_MS" <<'PY' | docker exec -i qiqi-redis redis-cli --pipe >/dev/null
import sys
path, ttl = sys.argv[1], sys.argv[2]
w = sys.stdout.write
with open(path) as f:
    for line in f:
        t = line.strip()
        if not t:
            continue
        k = "idem:order:" + t
        w("*5\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$1\r\n1\r\n$2\r\nPX\r\n$%d\r\n%s\r\n"
          % (len(k), k, len(ttl), ttl))
PY
  after=$(docker exec -i qiqi-redis redis-cli DBSIZE | tr -d '\r')
  echo "DBSIZE: $before -> $after （差 $((after-before))）"
  echo "TTL 抽查: $(docker exec -i qiqi-redis redis-cli TTL "idem:order:$(head -1 "$DIR/tokens.txt")")"
}

# ---------------------------------------------------------------- precheck（开跑前查「别人的残留」）
# 我的 reset 只删 username like 'lg\_%' 名下的订单，别人的残留删不到，
# 但残留会让 order 表越来越大 —— 前后档可比性靠这个检查兜住。
cmd_precheck() {
  echo "== 开跑前残留检查 =="
  sql_db "
select concat('order 总数=', count(*),
              ' / 其中压测会员名下=', sum(member_id in (select id from member where username like 'lg\\_%'))) from \`order\`;
select concat('  ↑ 其中非压测单=', count(*), '（=14 说明是 data.sql 种子基线；>14 才是别人的残留）')
  from \`order\` where member_id not in (select id from member where username like 'lg\\_%');
select concat('残留样例: id=', id, ' sn=', order_sn, ' member=', member_id, ' status=', status, ' amount=', total_amount)
  from \`order\` where member_id not in (select id from member where username like 'lg\\_%') order by id desc limit 5;
select concat('order_item=', (select count(*) from order_item),
              ' delivery_order=', (select count(*) from delivery_order),
              ' payment_record=', (select count(*) from payment_record),
              ' return_apply=', (select count(*) from return_apply),
              ' cart=', (select count(*) from cart));
select concat('stock_ledger 总数=', count(*), ' / 孤儿(order已不存在)=', coalesce(sum(order_id not in (select id from \`order\`)),0)) from stock_ledger;
select concat('member 总数=', count(*), ' / 压测会员=', sum(username like 'lg\\_%')) from member;
select concat('sku 1 stock=', (select stock from sku where id=1), ' / sku 2..21 min=', (select min(stock) from sku where id between 2 and 21), ' max=', (select max(stock) from sku where id between 2 and 21));
"
  echo "redis idem:order:* 非空数（SCAN 抽样 20000 个计数，够看有没有别人灌的池子）: $(docker exec -i qiqi-redis redis-cli --scan --pattern 'idem:order:*' 2>/dev/null | head -20000 | wc -l)"
  echo "判定：①order 表 = 14 种子单（非压测单=14 即达标，多出来的是别人的残留）②孤儿台账=0 ③压测会员=200（201 含冒烟用的 lg_probe_01）④sku1 与 2..21 库存=500000"
  echo "      任一条不符 → 先让对应 agent 清干净再开跑，否则表体积/库存是隐藏变量。"
}

# ---------------------------------------------------------------- reset
# 口径说明：order_sn 由服务端生成（QQ+yyyyMMddHHmmss+6位随机），压测侧无法指定前缀，
# 因此「按 order_sn 前缀删」不可行；改为**按测试会员精确圈定**（username like 'lg\_%'，
# 覆盖 lg_bench_* 与冒烟用的 lg_probe_*，都属于本套压测自己造的会员）
# —— 这是压测自己造的数据，边界比前缀更准（不会误伤别人的单）。
cmd_reset() {
  echo "== reset：按测试会员圈定并逐级删除 =="
  sql_db "
set @sku_id = $SKU_ID;
create temporary table lg_m (id bigint primary key) select id from member where username like 'lg\_%';
create temporary table lg_o (id bigint primary key) select id from \`order\` where member_id in (select id from lg_m);
create temporary table lg_d (id bigint primary key) select id from delivery_order where order_id in (select id from lg_o);
select concat('会员 ', (select count(*) from lg_m), ' / 订单 ', (select count(*) from lg_o), ' / 发货单 ', (select count(*) from lg_d)) as before_reset;
delete from delivery_logistics where delivery_id in (select id from lg_d);
delete from delivery_item      where delivery_id in (select id from lg_d);
delete from delivery_order     where id          in (select id from lg_d);
delete from return_apply       where order_id    in (select id from lg_o);
-- 支付流水按 order_sn 关单：用 join 而不是临时 varchar 表（临时表继承默认 collation，与业务表 utf8mb4_0900_ai_ci 比较会报 ERROR 1267）
delete p from payment_record p join \`order\` o on o.order_sn = p.order_sn where o.member_id in (select id from lg_m);
delete from order_item         where order_id    in (select id from lg_o);
delete from stock_ledger       where order_id    in (select id from lg_o);
-- 三张「只追加」状态台账：reset 语义必须是「删掉属于被 reset 订单的那些行」，
-- 不能 TRUNCATE 全表（台账是证据表，可能含非压测订单/历史行）。
-- 2026-11-14 事故：这三张漏删，单日堆到 5,717,917 行孤儿，/admin/ledgers 挂 25s。
delete from order_status_ledger    where order_id in (select id from lg_o);
delete from delivery_status_ledger where order_id in (select id from lg_o);
delete from return_status_ledger   where order_id in (select id from lg_o);
delete from \`order\`            where id          in (select id from lg_o);
delete from cart               where member_id   in (select id from lg_m);
-- 孤儿台账兜底（历史误删订单留下的、订单已不存在的库存台账；只扫压测用到的 SKU 区间）
delete l from stock_ledger l left join \`order\` o on o.id = l.order_id where o.id is null and l.sku_id between 1 and 21;
update sku set stock = $SKU_STOCK where id = @sku_id;
update sku set stock = $SKU_STOCK where id between $COLD_FROM and $COLD_TO;
select concat('sku ', id, ': stock=', stock, ' version=', version) from sku where id = @sku_id;
-- 注意：MySQL 不允许同一条查询里多次引用同一张 TEMPORARY 表（ERROR 1137），
-- 所以这里把三张台账 UNION 起来、只引用 lg_o 一次。
select concat('台账自检 lg_o 残留(应为 0)=', count(*)) as ledger_reset_check
  from (select order_id from order_status_ledger
        union all select order_id from delivery_status_ledger
        union all select order_id from return_status_ledger) t
 where t.order_id in (select id from lg_o);
select concat('台账历史孤儿(参考值,非本次负责,可非 0)=', (select count(*) from order_status_ledger l left join \`order\` o on o.id=l.order_id where o.id is null))
       as ledger_orphan_ref;
"
  echo "（Redis 令牌不清理：30 分钟自然过期；如需立刻清，见 README 里的 redis-cli 一行）"
}

case "${1:-status}" in
  status)     cmd_status ;;
  sku)        cmd_sku ;;
  cold)       cmd_cold ;;
  members)    cmd_members ;;
  tokens)     cmd_tokens ;;
  tokens-api) cmd_tokens_api ;;
  seed)       cmd_seed ;;
  precheck)   cmd_precheck ;;
  reset)      cmd_reset ;;
  all)        cmd_sku; cmd_cold; cmd_members; cmd_tokens ;;
  *) echo "用法: $0 {status|precheck|sku|cold|members|tokens|tokens-api|seed|reset|all}" >&2; exit 2 ;;
esac
