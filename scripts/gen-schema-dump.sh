#!/usr/bin/env bash
#
# 从「真实库」导出结构 DDL，生成两份产物（都是生成物，禁止手改）：
#
#   ./scripts/gen-schema-dump.sh                # 重新生成 sql/schema.sql
#   ./scripts/gen-schema-dump.sh --baseline     # 生成 V1__baseline.sql（一次性，已冻结）
#   ./scripts/gen-schema-dump.sh --verify       # 自校验：把生成结果灌进临时库，与源库四类指纹比对
#
# 为什么要有这个脚本
# ------------------
# 以前 DDL 存在四份手工维护的副本（sql/schema.sql、sql/testdb/01-schema.sql、11 个
# `sql/_rollback/migrate-*.sql`（收敛前在 `sql/` 根）、以及每个库里"事实上的结构"），靠人工同步。实测两份手写文件与真实库
# 结构 0 差异，但**注释已漂移 30 行**、还留着 4 行指向早已删除的表（tenant/plan/…）的
# DROP TABLE IF EXISTS —— 手抄注定漂移，只是什么时候被发现的问题。
#
# 现在唯一事实来源是 db/migration/V*.sql（Flyway 管），sql/schema.sql 由本脚本从库里 dump
# 出来（给 docker-compose 的 mysql 首次 initdb 用，compose 不在本次可改范围）。所以：
#   · 结构变更 = 新写一个 V2__xxx.sql → 起服务 → 跑本脚本刷新 sql/schema.sql → 提交两份
#   · 永远不要手改 sql/schema.sql，也不要手改 V1（历史迁移不可变）
#
# 为什么不用 `mysqldump` 的默认输出
# ---------------------------------
#   1) 默认按表名字母序输出，而表之间有 25 个外键；字母序下 channel_order_map 会排在
#      channel_shop 前面 → 空库建表直接失败（靠 SET FOREIGN_KEY_CHECKS=0 硬绕能过，
#      但那等于把"依赖顺序"这件事从文件里删掉了，读文件的人看不出谁依赖谁）。
#      这里改成按外键依赖**拓扑排序**（父表在前），文件从头到尾可以直接顺序执行。
#   2) 默认会带上 AUTO_INCREMENT=933 这类当前计数器值 → 空库建出来的自增起点跟生产不一样，
#      diff 时永远有噪音。这里削掉（计数器是运行期状态，不是结构）。
#   3) 默认把视图 dump 成「临时表占位 + /*!50001 CREATE VIEW */」两段，还带库名前缀和
#      DEFINER=`root`@`localhost`。前者没法直接执行，后者会让换账号跑迁移失败。
#      这里用 SHOW CREATE VIEW 拿到真实定义，去掉 DEFINER 与库名前缀。
#
# 注意：本脚本只读数据库，只写下面两个目标文件（--verify 时会临时建/删一个库）。
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"
# 源库：qiqi_mall_test 是四个库里最"标准"的一份（结构 + 已有参考数据齐全，且没有 dev 库
# 里那些手工调试行）。四个库结构实测完全一致，取哪个都等价。
SRC_DB="${SRC_DB:-qiqi_mall_test}"

BASELINE_OUT="$REPO_DIR/qiqi-mall-server/src/main/resources/db/migration/V1__baseline.sql"
SCHEMA_OUT="$REPO_DIR/sql/schema.sql"
VERIFY_DB="zz_schemadump_check"

MODE="schema"
case "${1:-}" in
  --baseline) MODE="baseline" ;;
  --verify)   MODE="verify" ;;
  "")         MODE="schema" ;;
  -h|--help)  sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
  *) echo "未知参数：$1（可用：--baseline / --verify / 无参数）" >&2; exit 1 ;;
esac

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi

# --raw：别把换行转义成字面量 \n（否则 SHOW CREATE TABLE 出来是单行垃圾）
# </dev/null：docker exec -i 会吃掉调用方的 stdin（while read 循环里会静默只读第一行）
mysqlq() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -uroot -N -B --raw "$@" < /dev/null
}
mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$@"
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ── 1. 表清单，按外键依赖拓扑排序（父表先出） ────────────────────────────────
# ⚠️ 必须排除 flyway_schema_history：库里 baseline 之后它就在（Flyway 自己的记账表），
#    它属于工具状态、不属于业务结构。漏排的后果很隐蔽——schema.sql/V1 会多建一张历史表，
#    而 Flyway 建历史表时发现同名表结构不对，报的错和"真结构问题"完全不像。
mysqlq -e "SELECT TABLE_NAME FROM information_schema.TABLES
            WHERE TABLE_SCHEMA='$SRC_DB' AND TABLE_TYPE='BASE TABLE'
              AND TABLE_NAME <> 'flyway_schema_history'
            ORDER BY TABLE_NAME;" > "$TMP/tables.txt"
mysqlq -e "SELECT TABLE_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA='$SRC_DB' AND REFERENCED_TABLE_NAME IS NOT NULL;" > "$TMP/fks.txt"

python3 - "$TMP/tables.txt" "$TMP/fks.txt" > "$TMP/order.txt" <<'PY'
import sys, collections
tables = [l.strip() for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
deps = collections.defaultdict(set)
for line in open(sys.argv[2], encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) == 2:
        deps[p[0]].add(p[1])
order, seen = [], set()
def visit(t, stack=()):
    if t in seen:
        return
    if t in stack:                      # 自引用/环：忽略该边，别死在递归里
        return
    for d in sorted(deps.get(t, ())):
        if d != t and d in tables:
            visit(d, stack + (t,))
    seen.add(t)
    order.append(t)
for t in tables:
    visit(t)
sys.stdout.write('\n'.join(order) + '\n')
PY

# ── 2. 拼 DDL 主体 ──────────────────────────────────────────────────────────
BODY="$TMP/body.sql"
{
  echo "-- ============================================================================"
  echo "-- 1. 表结构（$(wc -l < "$TMP/order.txt" | tr -d ' ') 张，按外键依赖拓扑排序：父表在前）"
  echo "-- ============================================================================"
  echo
} > "$BODY"

while read -r t; do
  # 输出形如 "表名<TAB>CREATE TABLE …"（多行）：只削第一行的「表名<TAB>」前缀，
  # 后面的行是 DDL 正文，不能按行取 $2（否则只剩第一行）。
  ddl="$(mysqlq -e "SHOW CREATE TABLE \`$SRC_DB\`.\`$t\`;" | sed -E '1s/^[^\t]*\t//')"
  # AUTO_INCREMENT=n 是运行期计数器，不是结构 → 削掉，否则空库起点与现网不同、diff 永远有噪音
  ddl="$(printf '%s' "$ddl" | sed -E 's/ AUTO_INCREMENT=[0-9]+//g')"
  { printf -- '-- %s\n%s;\n\n' "$t" "$ddl"; } >> "$BODY"
done < "$TMP/order.txt"

{
  echo "-- ============================================================================"
  echo "-- 2. 视图（1 个）"
  echo "--    去掉 DEFINER（否则换账号跑迁移会失败）与库名前缀（否则只能建在同一个库上）"
  echo "-- ============================================================================"
  echo
} >> "$BODY"
mysqlq -e "SHOW CREATE VIEW \`$SRC_DB\`.\`v_biz_status_timeline\`;" \
  | awk -F'\t' '{print $2}' \
  | sed -E "s/ ALGORITHM=UNDEFINED//; s/ DEFINER=\`[^\`]+\`@\`[^\`]+\`//; s/\`${SRC_DB}\`\.//g" \
  >> "$BODY"
printf ';\n\n' >> "$BODY"

{
  echo "-- ============================================================================"
  echo "-- 3. 参考数据（channel_shop 店铺种子）"
  echo "--    这 3 行是结构的一部分（后端/前端都靠 shop_code 定位店铺），不是演示数据："
  echo "--    历史来源是 channel_shop 的种子段（原 migrate-channel.sql：历史脚本，已下线；"
  echo "--    结构请用 db/migration/V1__baseline.sql，本段基线已包含它），e2e 重置后必须有，否则店铺表为空。"
  echo "--    02-seed.sql 既不 TRUNCATE 也不 INSERT 它 —— 所以必须跟着结构一起进基线。"
  echo "-- ============================================================================"
  echo
} >> "$BODY"
docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
  mysqldump --default-character-set=utf8mb4 -uroot \
  --no-create-info --compact --skip-extended-insert --complete-insert --order-by-primary \
  --no-tablespaces --skip-triggers --skip-comments "$SRC_DB" channel_shop >> "$BODY"
printf '\n' >> "$BODY"

emit_baseline() {
  cat <<HDR
-- ============================================================================
-- V1 · 七七商城基线（39 张表 + 1 个视图 + channel_shop 参考数据）
-- ============================================================================
-- ⚠️ 本文件是**生成物**，禁止手改。生成方式（2026-11-13）：
--      ./scripts/gen-schema-dump.sh --baseline
--    源库：$SRC_DB（与 qiqi_mall / qiqi_mall_e2e_a / qiqi_mall_e2e_b 结构实测一致：
--    四类指纹 [COLUMNS/STATISTICS/TABLE_CONSTRAINTS/CHECK] 逐项一致，视图定义归一化后逐字节相同）
--
-- 为什么把历史上 11 个手写脚本一次性并入 V1，而不是保留成 V2..V12
-- ----------------------------------------------------------------
--   1) 四个库结构早已一致，历史增量对任何环境都不再有意义（没有"老版本库"需要升级）。
--   2) 那 11 个脚本**不能**当迁移用：migrate-ai-api-key / image-relative-url /
--      ledger-idem-shard / sku-bucket 里硬编码了 qiqi_mall. 与 qiqi_mall_test. 的**双库写入**，
--      在 Flyway 里跑会把基线建好的库再往别的库写一遍；还混着 DML 回填、7 个
--      zz_xxx_add_col 之类的一次性幂等存储过程、以及一份只读报表（ledger-verify）。
--   3) 代价：从此无法用 Flyway 重建"历史版本"的库。我们不需要（e2e 只要最新结构），
--      历史脚本仍留在 sql/_rollback/ 可读。
--
-- 本文件在什么情况下会被执行
-- --------------------------
--   · 已有库（4 个）：**不会执行**。它们已被 flyway baseline 到版本 1（历史里是一条
--     type=BASELINE 的 V1 记录），结构早就是 V1 描述的样子。
--   · 空库（e2e 重置 / 新环境）：**全量执行**，从 0 建出 39 表 + 1 视图 + 参考数据。
--   两条路径由 spring.flyway.baseline-on-migrate + baseline-version=1 区分，见 application-*.yml。
--
-- 后续变更怎么走
-- --------------
--   加表/加列/改索引 = 新增 V2__xxx.sql（V1 永远不再改）。改完跑
--   ./scripts/gen-schema-dump.sh 刷新 sql/schema.sql（docker initdb 用），两份一起提交。
-- ============================================================================

HDR
  cat "$BODY"
}

emit_schema_doc() {
  cat <<HDR
-- ============================================================================
-- 七七商城 数据库结构（dev 库 qiqi_mall）
-- ============================================================================
-- ⚠️ 本文件是**生成物**，禁止手改。真实来源是：
--      qiqi-mall-server/src/main/resources/db/migration/V1__baseline.sql（+ 之后的 V2、V3…）
--    刷新方式：./scripts/gen-schema-dump.sh
--
-- 为什么还留着它：docker-compose.yml 把它挂进 mysql 容器的
-- docker-entrypoint-initdb.d/01-schema.sql —— mysql 数据卷首次初始化时用它建表。
-- 也就是说它必须与 V1 内容一致，否则"卷重建后的库"与"Flyway 建出来的库"会打架。
-- 一致性由 ./scripts/gen-schema-dump.sh --verify 保证（四类指纹逐项比对）。
--
-- 与 V1 唯一的差别：本文件前面多了逐表 DROP（保持"可反复重跑"的老行为；
-- V1 跑在空库上，不需要也不该有 DROP）。
-- ⚠️ 因此重跑本文件 = 清空目标库的结构（数据会随表一起没），只在确定要重建时用。
--
-- ⚠️ 本文件**不写库名**（无 USE、无建库语句），库名必须由调用方给：
--       mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/schema.sql
--    docker-compose 的 mysql 首次初始化会自动按 MYSQL_DATABASE 选库并建库
--    （entrypoint 按 MYSQL_DATABASE 环境变量选库并喂 initdb 脚本），这里不需要也不该写。
--    2026-11-13 两次事故的根因就是文件自己选库，见 sql/_rollback/README.txt。
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;

DROP VIEW IF EXISTS \`v_biz_status_timeline\`;

HDR
  # 建表前的清理：与旧版行为一致（逐表 DROP + IF EXISTS）。子表先删（拓扑逆序）。
  tac "$TMP/order.txt" | while read -r t; do
    [[ -n "$t" ]] && printf 'DROP TABLE IF EXISTS `%s`;\n' "$t"
  done
  echo
  cat "$BODY"
  echo "SET FOREIGN_KEY_CHECKS = 1;"
}

# ── 3. 落盘 / 校验 ─────────────────────────────────────────────────────────
fingerprints() {   # $1 = 库名 → 打印 4 类指纹 + 表数/视图数
  local db="$1"
  mysqlq -e "SELECT
      (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$db' AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME<>'flyway_schema_history'),
      (SELECT COUNT(*) FROM information_schema.VIEWS  WHERE TABLE_SCHEMA='$db'),
      (SELECT MD5(GROUP_CONCAT(x ORDER BY x SEPARATOR '|')) FROM (SELECT CONCAT_WS('~',TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,IFNULL(COLUMN_DEFAULT,''),EXTRA,IFNULL(COLUMN_COMMENT,'')) AS x FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME<>'flyway_schema_history') t),
      (SELECT MD5(GROUP_CONCAT(x ORDER BY x SEPARATOR '|')) FROM (SELECT CONCAT_WS('~',TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,IFNULL(SUB_PART,''),INDEX_TYPE) AS x FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME<>'flyway_schema_history') t),
      (SELECT MD5(GROUP_CONCAT(x ORDER BY x SEPARATOR '|')) FROM (SELECT CONCAT_WS('~',TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE) AS x FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME<>'flyway_schema_history') t),
      (SELECT MD5(GROUP_CONCAT(x ORDER BY x SEPARATOR '|')) FROM (SELECT CONCAT_WS('~',CONSTRAINT_NAME,CHECK_CLAUSE) AS x FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='$db') t),
      (SELECT COUNT(*) FROM \`$db\`.channel_shop);"
}

case "$MODE" in
  baseline)
    mkdir -p "$(dirname "$BASELINE_OUT")"
    emit_baseline > "$BASELINE_OUT"
    echo "✓ 已生成 $BASELINE_OUT（$(wc -l < "$BASELINE_OUT" | tr -d ' ') 行）"
    ;;
  schema)
    emit_schema_doc > "$SCHEMA_OUT"
    echo "✓ 已生成 $SCHEMA_OUT（$(wc -l < "$SCHEMA_OUT" | tr -d ' ') 行）"
    ;;
esac

if [[ "$MODE" == "verify" ]]; then
  echo "▶ 自校验：把生成的结构灌进临时库 $VERIFY_DB，与源库 $SRC_DB 逐项比对"
  mysql_exec -e "DROP DATABASE IF EXISTS $VERIFY_DB;
                 CREATE DATABASE $VERIFY_DB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
  mysql_exec "$VERIFY_DB" < "$BODY"

  src="$(fingerprints "$SRC_DB")"; dst="$(fingerprints "$VERIFY_DB")"
  IFS=$'\t' read -r s_tab s_view s_col s_idx s_con s_ck s_shop <<< "$src"
  IFS=$'\t' read -r d_tab d_view d_col d_idx d_con d_ck d_shop <<< "$dst"
  fail=0
  cmp_line() {  # $1=名称 $2=源 $3=生成
    if [[ "$2" == "$3" ]]; then printf '  ✓ %-18s %s\n' "$1" "$2"; else
      printf '  ✗ %-18s 源=%s 生成=%s\n' "$1" "$2" "$3"; fail=1; fi
  }
  cmp_line "BASE TABLE 数"   "$s_tab"  "$d_tab"
  cmp_line "VIEW 数"         "$s_view" "$d_view"
  cmp_line "COLUMNS md5"     "$s_col"  "$d_col"
  cmp_line "STATISTICS md5"  "$s_idx"  "$d_idx"
  cmp_line "CONSTRAINTS md5" "$s_con"  "$d_con"
  cmp_line "CHECK md5"       "$s_ck"   "$d_ck"
  cmp_line "channel_shop 行" "$s_shop" "$d_shop"
  mysql_exec -e "DROP DATABASE IF EXISTS $VERIFY_DB;"
  if [[ "$fail" -ne 0 ]]; then
    echo "❌ 生成结果与源库不一致（见上），不要提交" >&2
    exit 1
  fi
  echo "✓ 生成结果与 $SRC_DB 完全一致（39 表 + 1 视图 + channel_shop 3 行）"
fi
