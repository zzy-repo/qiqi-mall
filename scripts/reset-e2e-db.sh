#!/usr/bin/env bash
#
# 重置 e2e 专用库（每个 Playwright shard 一套），可反复重跑。
#
#   ./scripts/reset-e2e-db.sh qiqi_mall_e2e_a     # 重置单个库（**并自动重启** shard a 的后端）
#   ./scripts/reset-e2e-db.sh --all               # 重置 qiqi_mall_e2e_a / _b（按 E2E_SHARD_N），并重启对应后端
#   SKIP_BACKEND_RESTART=1 ./scripts/reset-e2e-db.sh ...   # 只重置库、不重启（⚠ 只给对照实验用，会红）
#
# ⚠ reset 库会让被测服务 JVM 里缓存的主数据（如平台买家 member_id）变悬空，所以 reset 之后
#   必须重启后端；否则连续两次跑批会在 09-多平台接入/1-接入最小闭环 上红一次（code=1，fk_order_member）。
#   本脚本默认就把这两件事绑在一起做（见文件末尾「步骤 5」），不是为了"顺手"，是因为只做一半必红。
#
# 结构从哪来：Flyway（qiqi-mall-server/src/main/resources/db/migration/V*.sql）。
#   · 以前是 `sed 库名 < sql/testdb/01-schema.sql | mysql` —— 那份 DDL 是手抄的第二份副本，
#     新加表忘了同步进去，库就少表（今晚 qiqi_mall_test 缺 3 张 channel 表就是这么来的）。
#     01-schema.sql 已下线到 sql/_rollback/。
#   · 现在：DROP DATABASE → CREATE DATABASE → flyway migrate（空库 ⇒ 全量执行 V1）
#     → 断言 39 表 + 1 视图 + flyway_schema_history 有 V1 → 再灌 seed。
#
# 为什么必须 DROP **DATABASE** 而不是逐表 DROP TABLE（最容易埋雷的地方）
#   ------------------------------------------------------------------
#   flyway_schema_history 也是库里的表。只删业务表、留下历史表，Flyway 会认为
#   "V1 已经应用过了"，于是**什么都不做** —— 库是空的，seed 灌不进去，报错还很难懂。
#   整库删掉，历史表跟着消失，Flyway 才会把 V1 当"空库初始化"从头执行。
#
# 为什么 seed 还需要（结构归 Flyway，数据归 seed）
#   02-seed.sql 是 fetch-seed-data.mjs 生成的固定 id 种子；它自带 TRUNCATE + 逐表重灌，
#   可反复执行。注意它**不含 channel_shop**（那 3 行店铺是结构的一部分，由 V1 提供）。
#
# 幂等：DROP/CREATE DATABASE + Flyway 空库初始化 + seed 的 TRUNCATE 重灌 → 重跑 = 整库重置。
#       跑完打印关键表行数，两次跑出来的数字必须一致。
#
# 踩坑：容器里的 mysql 客户端默认字符集是 latin1，必须显式 --default-character-set=utf8mb4，
#      否则中文会被二次编码成「苹果 → è‹¹æžœ」。
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"
# Flyway 走 JDBC（宿主机 → 容器发布的端口），与上面的 mysql 客户端不是一条路
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
SEED="$REPO_DIR/sql/testdb/02-seed.sql"
SERVER_POM="$REPO_DIR/qiqi-mall-server/pom.xml"
ALL_SHARDS=("qiqi_mall_e2e_a" "qiqi_mall_e2e_b" "qiqi_mall_e2e_c" "qiqi_mall_e2e_d")
SHARD_N="${E2E_SHARD_N:-2}"
SHARDS=("${ALL_SHARDS[@]:0:$SHARD_N}")

if [[ "${1:-}" == "--all" ]]; then
  TARGETS=("${SHARDS[@]}")
elif [[ -n "${1:-}" ]]; then
  TARGETS=("$1")
else
  echo "用法：$0 <库名> | --all" >&2
  echo "可选库名：${SHARDS[*]}" >&2
  exit 1
fi

# 只允许重置 e2e 库：dev 库、测试库、生产库一律拒绝，避免误伤
for db in "${TARGETS[@]}"; do
  if [[ "$db" != qiqi_mall_e2e_* ]]; then
    echo "✗ 拒绝重置「$db」：本脚本只处理 qiqi_mall_e2e_* 开头的库" >&2
    exit 1
  fi
done

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi
if [[ ! -f "$SEED" ]]; then
  echo "✗ 找不到 $SEED（先跑 node scripts/fetch-seed-data.mjs 生成测试种子）" >&2
  exit 1
fi
if [[ ! -f "$SERVER_POM" ]]; then
  echo "✗ 找不到 $SERVER_POM" >&2
  exit 1
fi

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$@"
}

for DB in "${TARGETS[@]}"; do
  echo "▶ 重置 $DB（DROP DATABASE → Flyway 全量建库 → seed）"

  # 1) 整库删掉：连 flyway_schema_history 一起没，Flyway 才会把 V1 当空库初始化
  mysql_exec -e "DROP DATABASE IF EXISTS \`$DB\`;
                 CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

  # 2) 空库 ⇒ Flyway 不会 baseline，直接全量执行 V1（39 表 + 1 视图 + channel_shop 3 行）
  mvn -B -q -f "$SERVER_POM" flyway:migrate \
    -Dflyway.url="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${DB}?characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
    -Dflyway.user="$DB_USER" \
    -Dflyway.password="$DB_PASS" \
    || { echo "✗ $DB：flyway migrate 失败" >&2; exit 1; }

  # 3) 断言结构真的建出来了（缺表要在这里就炸，而不是等 e2e 跑到一半）
  verdict="$(mysql_exec -N -e "
    SELECT CONCAT(
      (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB' AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME<>'flyway_schema_history'), ',',
      (SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA='$DB'), ',',
      (SELECT COUNT(*) FROM \`$DB\`.flyway_schema_history WHERE version='1' AND success=1), ',',
      (SELECT COUNT(*) FROM \`$DB\`.channel_shop));")"
  IFS=',' read -r n_tab n_view n_hist n_shop <<< "$verdict"
  if [[ "$n_tab" != "39" || "$n_view" != "1" || "$n_hist" != "1" || "$n_shop" != "3" ]]; then
    echo "✗ $DB 结构校验失败：业务表=$n_tab（应 39）视图=$n_view（应 1）V1记录=$n_hist（应 1）channel_shop=$n_shop（应 3）" >&2
    exit 1
  fi
  echo "  ✓ 结构就绪：39 表 + 1 视图（V1 全量执行），channel_shop 3 行"

  # 4) 灌种子：库名由本脚本显式传给 mysql（`mysql <库名> < 文件`）。
  #    seed 文件里不再写 `USE <库名>`（2026-11-13 两次事故的根因：文件写死库名 = 谁 source 它
  #    就重置谁，调用方的库名被静默覆盖）。规矩见 sql/_rollback/README.txt。
  mysql_exec "$DB" < "$SEED"

  echo "▶ $DB 行数摘要"
  mysql_exec -t -e "USE ${DB};
    SELECT 'warehouse' t, COUNT(*) c FROM warehouse
    UNION ALL SELECT 'category', COUNT(*) FROM category
    UNION ALL SELECT 'product_type', COUNT(*) FROM product_type
    UNION ALL SELECT 'attribute', COUNT(*) FROM attribute
    UNION ALL SELECT 'product', COUNT(*) FROM product
    UNION ALL SELECT 'sku', COUNT(*) FROM sku
    UNION ALL SELECT 'inventory', COUNT(*) FROM inventory
    UNION ALL SELECT 'order', COUNT(*) FROM \`order\`
    UNION ALL SELECT 'delivery_order', COUNT(*) FROM delivery_order
    UNION ALL SELECT 'member', COUNT(*) FROM member
    UNION ALL SELECT 'admin', COUNT(*) FROM admin
    UNION ALL SELECT 'order_setting', COUNT(*) FROM order_setting;"
  # 「好看的数据」校验：重置后不允许任何 e2e 残留（测试账号/订单/商品都只活在运行期）
  residue="$(mysql_exec -N -e "USE ${DB};
    SELECT CONCAT(
      (SELECT COUNT(*) FROM member WHERE username LIKE 'e2e\\_%'), ',',
      (SELECT COUNT(*) FROM product WHERE name LIKE '%E2E%' OR name LIKE '%e2e%'), ',',
      (SELECT COUNT(*) FROM admin WHERE username LIKE 'e2e\\_%'), ',',
      (SELECT COUNT(*) FROM warehouse WHERE name LIKE 'E2E%'))")"
  IFS=',' read -r r_member r_product r_admin r_wh <<< "$residue"
  if [[ "$r_member$r_product$r_admin$r_wh" != "0000" ]]; then
    echo "❌ $DB 仍有 e2e 残留：member=$r_member product=$r_product admin=$r_admin warehouse=$r_wh" >&2
    exit 1
  fi
  echo "✓ $DB 干净：无 e2e 账号/商品/管理员/仓库残留（只剩种子数据）"
  echo
done

# ════════════════════════════════════════════════════════════════════════════
# 步骤 5：重启对应分片的后端（**默认必做**，和 DROP DATABASE 是同一个动作的两半）
#
# 为什么这一步不是"优化"而是"正确性"（2026-11-14 事故，连续两跑必红一次）：
#   被测服务 ChannelOrderImporter.platformBuyerId() 把「平台买家」的 member_id 缓存在
#   JVM 的 volatile 字段里。第一次跑批拿到 31 并缓存；两次跑批之间本脚本 DROP DATABASE，
#   那个 member 跟着没了 ⇒ 缓存里的 31 成了**悬空外键** ⇒ INSERT order 撞 fk_order_member
#   ⇒ 「MALL 拉单接口应返回成功」code=1。证据：不重启后端重跑 13.1s 同样失败；
#   `restart-e2e-backends.sh --only b` 清掉缓存后重跑 1 passed。
#   所以：**reset 之后必须重启后端**，否则第二次跑批会在 09-多平台接入/1-接入最小闭环 上红。
#
# 边界（都不越过铁律）：
#   · 只用 systemd unit 名重启（restart-e2e-backends.sh 内部只 systemctl restart），不 pkill -f；
#   · 分片后端**没在跑**（瞬态 unit 被 --collect 回收 / 还没 start）→ 跳过该分片并说明原因：
#     没有运行中的 JVM 就没有缓存可清，此时不重启是对的，不能因此让 reset 失败
#     （start-e2e-backends.sh 头部就要求"先 reset 建库、再起后端"，那条路必须继续可用）；
#   · restart-e2e-backends.sh 的 --only 只认 a|b；c/d 分片会显式告警并提示手工重启，不静默放过。
# ════════════════════════════════════════════════════════════════════════════
SKIP_BACKEND_RESTART="${SKIP_BACKEND_RESTART:-0}"
RESTART_TARGETS=()          # 收集「有跑着的后端」且 restart 脚本支持的分片
SKIPPED_NO_JVM=()           # 后端没在跑、无需重启的分片
UNSUPPORTED=()              # restart 脚本 --only 不支持的分片
for DB in "${TARGETS[@]}"; do
  shard="${DB#qiqi_mall_e2e_}"
  unit="qiqi-e2e-server-$shard"
  load="$(systemctl show -p LoadState --value "$unit" 2>/dev/null || echo not-found)"
  pid="$(systemctl show -p MainPID --value "$unit" 2>/dev/null || echo 0)"
  if [[ "$load" != "loaded" ]] || [[ ! "$pid" =~ ^[0-9]+$ ]] || [[ "$pid" == "0" ]]; then
    SKIPPED_NO_JVM+=("$shard")
    continue
  fi
  case "$shard" in
    a|b) RESTART_TARGETS+=("$shard") ;;
    *)   UNSUPPORTED+=("$shard") ;;
  esac
done

if [[ "$SKIP_BACKEND_RESTART" == "1" ]]; then
  cat >&2 <<'SKIP_RESTART_EOF'

████████████████████████████████████████████████████████████████████████
⚠  SKIP_BACKEND_RESTART=1：库已重置，但**后端没重启** —— 这是一条已知会红的半成品路径。
   JVM 里缓存的主数据（如 ChannelOrderImporter.platformBuyerId 的 member_id）现在指向刚被
   DROP 掉的 member，下一次拉单会撞 fk_order_member ⇒ 09-多平台接入/1-接入最小闭环 红（code=1）。
   只有"故意要复现这个红"的对照实验才该这么用；正常跑批请去掉这个开关（默认就是重启）。
████████████████████████████████████████████████████████████████████████

SKIP_RESTART_EOF
  for s in "${SKIPPED_NO_JVM[@]}"; do
    echo "  · shard $s：后端没在跑（无 JVM 缓存可清），无需重启"
  done
  echo "✅ 完成：${TARGETS[*]}（⚠ 未重启后端：SKIP_BACKEND_RESTART=1）"
  echo "   结构来源：db/migration/V1__baseline.sql（Flyway）；数据来源：sql/testdb/02-seed.sql"
  exit 0
fi

if [[ "${#RESTART_TARGETS[@]}" -eq 0 ]]; then
  echo "▶ 无需重启后端：${SKIPPED_NO_JVM[*]:-（无目标）} 的后端都没在跑（没有 JVM 缓存可清）"
  for s in "${UNSUPPORTED[@]}"; do
    echo "  ⚠ shard $s：restart-e2e-backends.sh --only 只支持 a|b，无法自动重启 —— 请手工重启该分片后端" >&2
  done
  FINAL_NOTE="（库已重置；目标分片没有运行中的后端，未重启）"
else
  echo "▶ 重启后端（清 JVM 内缓存：reset 把库删了，缓存里的主数据 id 会变悬空外键）"
  restart_start="$(date +%s)"
  if [[ "${#RESTART_TARGETS[@]}" -eq 2 ]]; then
    # a+b 一起重启：restart-e2e-backends.sh 不带 --only 就是两个都动，只编译一次
    if ! ( cd "$REPO_DIR" && ./scripts/restart-e2e-backends.sh ); then
      echo "✗ 后端重启失败（库已重置但后端还是旧 JVM ⇒ 缓存里的 member_id 是悬空外键，现在跑必红）" >&2
      echo "  处理：bash scripts/restart-e2e-backends.sh（会先 mvn compile 再重启）；单元被回收时先 scripts/start-e2e-backends.sh" >&2
      exit 1
    fi
  else
    for s in "${RESTART_TARGETS[@]}"; do
      if ! ( cd "$REPO_DIR" && ./scripts/restart-e2e-backends.sh --only "$s" ); then
        echo "✗ shard $s 的后端重启失败（库已重置但后端还是旧 JVM ⇒ 缓存里的 member_id 是悬空外键，现在跑必红）" >&2
        echo "  处理：bash scripts/restart-e2e-backends.sh --only $s" >&2
        exit 1
      fi
    done
  fi
  restart_secs=$(( $(date +%s) - restart_start ))
  for s in "${SKIPPED_NO_JVM[@]}"; do
    echo "  · shard $s：后端没在跑（无 JVM 缓存可清），无需重启"
  done
  for s in "${UNSUPPORTED[@]}"; do
    echo "  ⚠ shard $s：restart-e2e-backends.sh --only 只支持 a|b，无法自动重启 —— 请手工重启该分片后端" >&2
  done
  echo "✓ 已重启后端（清 JVM 内缓存）：shard ${RESTART_TARGETS[*]}  耗时 ${restart_secs}s"
  FINAL_NOTE="（库已重置 + 后端已重启：${RESTART_TARGETS[*]}）"
fi

echo "✅ 完成：${TARGETS[*]}${FINAL_NOTE:-}"
echo "   结构来源：db/migration/V1__baseline.sql（Flyway）；数据来源：sql/testdb/02-seed.sql"
