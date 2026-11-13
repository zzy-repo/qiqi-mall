#!/usr/bin/env bash
# =============================================================================
# scripts/check-jdbc-session-safety.sh —— useLocalSessionState=true 的**安全前提**门禁
# =============================================================================
#
# 用法（CI 与发布前都跑这一条；publish.sh 的 preflight 已把它当硬门禁）
#   bash scripts/check-jdbc-session-safety.sh
#
#   退出码：
#     0 = 前提成立（没有任何"绕过驱动改会话状态"的写法）
#     1 = 命中禁止形态（逐条打印 file:line，末尾给结论）
#     2 = 跑不起来（脚本自身/依赖/工作区问题，**不当成通过**）
#
# -----------------------------------------------------------------------------
# 这个脚本在守什么
# -----------------------------------------------------------------------------
# application-{dev,test}.yml 的 JDBC URL 里开了 `useLocalSessionState=true`：
#   Connector/J 不再每条语句发 `SELECT @@SESSION.transaction_read_only` 探针，
#   改用**自己的本地视图**（autocommit / transaction isolation / read-only）判断会话状态。
#   收益：每单语句数 18.8 → 12.5（少 33%）、天花板 447.6 → 494.4~501.4 ok/s（600 档 +10.5%）。
#
# 代价只有一条，但很硬：**驱动信任本地状态**。只要有人绕过驱动去改服务器上的会话状态，
# 本地视图与服务器真实状态就会**静默分叉** —— 出错形态不是"崩"，而是**做错账**
# （例如服务器已经 read-only 了、驱动还以为可写；或 autocommit 在服务器上被改、
#   驱动仍以为在事务里 → 一批本该回滚的写变成逐条自动提交）。
#
# 所以"仓库里 0 处绕过写法"不是一次性 grep 结论，而是一条**持续约束** = 本脚本。
# 破坏它会以"静默"的方式发生（没人报错、没人发现），只有门禁能拦住回潮。
#
# -----------------------------------------------------------------------------
# 扫描范围
# -----------------------------------------------------------------------------
#   qiqi-mall-server/src/main/**   （Java / yml / properties / xml：生产代码与配置）
#   sql/**                          （**排除 sql/_rollback/`** —— 那里是已下线的历史脚本，
#                                     不随应用启动、不在任何运行路径上，拦下来只会制造噪声）
#
# -----------------------------------------------------------------------------
# 禁止形态（命中即 exit 1）
# -----------------------------------------------------------------------------
#   A. SQL 侧会话状态改写：SET SESSION / SET TRANSACTION / SET autocommit / SET @@
#      （顺带覆盖同类 SET GLOBAL：它改的是"新连接的默认值"，同样会让驱动的
#        本地视图与之后的实际连接行为分叉，没有理由漏掉）
#   B. 手工改连接状态：setTransactionIsolation( / setReadOnly(
#   C. 连接池/URL 级别的会话初始化：connection-init-sql（Hikari 的 init SQL）/ sessionVariables（MySQL URL 参数）
#      —— 这两者都是"连接一建立就替应用 SET 一把"，正是驱动本地视图最容易失真处。
#         要改服务器默认值请用 SET GLOBAL（改在服务器侧、由驱动在连接时自行读取），不要用 init SQL。
#   D. 原生 JDBC 旁路：src/main 里出现 getConnection( 或 JdbcTemplate（本仓库应是 0 处：
#      事务边界只由 Spring 的 DataSourceTransactionManager 管，绕过去就等于绕过了驱动 API）
#
# -----------------------------------------------------------------------------
# 允许清单（**故意放行**，不是漏检）
# -----------------------------------------------------------------------------
#   · src/test 下的 setAutoCommit(  —— 走驱动自己的 API（Connection.setAutoCommit），
#     useLocalSessionState 的语义正是"以 API 设定值为准"，本地视图 **同步更新**，安全。
#     而且本脚本不扫 src/test（测试不是运行时路径）。
#   · sql/ 里的 SET @x 用户变量       —— @x 是**用户变量**，不属于驱动缓存的那三样会话状态
#     （autocommit / isolation / read-only）；且这些脚本跑在**另一个** mysql 会话里，与应用的连接无关。
#     注意：`SET @@x`（系统变量）**不在**放行范围，它是禁止形态 A。
#   · 整行注释里的同名字样           —— 见下方「注释行」。
#     说明性文字（本文档、yml 注释、SQL 注释）里出现 "SET SESSION" 是**文档**，永远不可执行；
#     真实语句不可能"长在行首注释里"。跳过注释是为了让红线本身能被写进代码旁边而不误报。
#
# -----------------------------------------------------------------------------
# 备注
# -----------------------------------------------------------------------------
#   · 本脚本只读，不改任何文件、不连数据库、不需要容器。
#   · 反向验证（证明门禁真能抓）：在任一 src/main 文件里临时加一行 `stmt.execute("SET SESSION x=1")`
#     （或 .sql 里加一行非注释的 `SET SESSION x=1;`）→ 重跑应 exit 1 且打印该 file:line；
#     删掉再跑应 exit 0。注意**必须是非注释行**：整行注释被有意跳过（见「注释行」）。
#   · 相关文档：bench/README.md §15「JDBC 会话状态红线」（收益/风险/下一闸门）。
# =============================================================================

set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/.." && pwd)"

for tool in find grep; do
  command -v "$tool" >/dev/null 2>&1 || { echo "✗ 缺少工具：$tool（门禁跑不起来，不当成通过）" >&2; exit 2; }
done
[ -d "$REPO_ROOT/qiqi-mall-server/src/main" ] || { echo "✗ 找不到 $REPO_ROOT/qiqi-mall-server/src/main（工作区不对？）" >&2; exit 2; }
[ -d "$REPO_ROOT/sql" ] || { echo "✗ 找不到 $REPO_ROOT/sql（工作区不对？）" >&2; exit 2; }

cd "$REPO_ROOT" || exit 2

# ------------------------------------------------------------------ 待扫描文件
# 只收文本类后缀（跳过 jar/图片/二进制），并排除 sql/_rollback/。
mapfile -t FILES < <(
  find qiqi-mall-server/src/main sql \
       -type f \( -name '*.java' -o -name '*.kt' -o -name '*.sql' \
                  -o -name '*.yml' -o -name '*.yaml' -o -name '*.properties' -o -name '*.xml' \) \
       -not -path 'sql/_rollback/*' \
       -print 2>/dev/null | sort
)
if [ "${#FILES[@]}" -eq 0 ]; then
  echo "✗ 扫描范围里一个文件都没找到（find 结果为空，工作区不对？）" >&2
  exit 2
fi

# ------------------------------------------------------------------ 命中收集
# grep 出 file:line:content，再丢掉「整行注释」：行首（含缩进）是 # / -- / // / /* / *
# 说明：内容里带冒号的路径不存在于本仓库（没有文件名含 ':'），所以按 "file:line:" 前缀匹配是安全的。
grep_code() {
  local pattern="$1" hits
  hits="$(
    grep -nIiE -- "$pattern" "${FILES[@]}" 2>/dev/null \
      | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(#|--|//|/\*|\*)' \
      || true
  )"
  printf '%s' "$hits"
}

HITS_TOTAL=0
FAILED_KINDS=0

report_kind() { # $1=标题 $2=正则 $3=补充说明
  local title="$1" pattern="$2" hint="${3:-}" hits n
  hits="$(grep_code "$pattern")"
  if [ -z "$hits" ]; then
    printf '  ✓ %s（0 处）\n' "$title"
    return 0
  fi
  FAILED_KINDS=$((FAILED_KINDS + 1))
  n="$(printf '%s\n' "$hits" | wc -l | tr -d ' ')"
  HITS_TOTAL=$((HITS_TOTAL + n))
  printf '  ✗ %s —— 命中 %s 处：\n' "$title" "$n"
  printf '%s\n' "$hits" | sed -E 's/^([^:]+:[0-9]+):(.*)$/      \1: \2/' | cut -c1-220
  [ -n "$hint" ] && printf '      ↳ %s\n' "$hint"
  return 0
}

echo "JDBC 会话状态安全门禁（useLocalSessionState=true 的前提）"
echo "扫描范围：${#FILES[@]} 个文件 = qiqi-mall-server/src/main + sql/（排除 sql/_rollback/）"
echo

# ── A. SQL 侧会话状态改写 ─────────────────────────────────────────────
report_kind "A. SQL 改会话状态（SET SESSION / TRANSACTION / autocommit / @@ / GLOBAL）" \
  'SET[[:space:]]+(SESSION|GLOBAL|autocommit|TRANSACTION|@@)' \
  "驱动已不再回读服务器会话状态，这类 SET 会让本地视图与服务器分叉。要用会话状态就交给驱动 API（Spring 事务管理器）。"

# ── B. 手工改连接状态 ────────────────────────────────────────────────
report_kind "B. 手工改连接状态（setTransactionIsolation( / setReadOnly(）" \
  'setTransactionIsolation[[:space:]]*\(|setReadOnly[[:space:]]*\(' \
  "隔离级别/只读由 Spring 事务管理器经驱动 API 设定；业务代码直接摆弄连接状态是最容易让本地视图失真的地方。"

# ── C. 连接池/URL 级别的会话初始化 ────────────────────────────────────
report_kind "C. 连接池初始化会话（connection-init-sql / sessionVariables）" \
  'connection-init-sql|connectionInitSql|sessionVariables' \
  "要改服务器默认值请在服务器侧用 SET GLOBAL（新连接自带），不要用 init SQL 替应用 SET。"

# ── D. 原生 JDBC 旁路（本仓库应为 0）─────────────────────────────────
report_kind "D. 原生 JDBC 旁路（getConnection( / JdbcTemplate，应为 0）" \
  'getConnection[[:space:]]*\(|JdbcTemplate' \
  "事务边界只由 Spring 的 DataSourceTransactionManager 管；出现过原生连接就说明有绕过驱动 API 的路径。"

# ── E. 正向断言：URL 里确实开着这个参数（防"写进去又被覆盖/被删"）───────
# 反向的坑比正向更隐蔽：参数被人从 yml 删掉、或新增 profile 复制了旧 URL，性能会悄悄退回 447.6。
PROFILE_YMLS=()
while IFS= read -r f; do PROFILE_YMLS+=("$f"); done < <(
  find qiqi-mall-server/src/main/resources -maxdepth 1 -type f \
       \( -name 'application*.yml' -o -name 'application*.yaml' \) -print 2>/dev/null | sort
)

MISSING_PARAM=0
if [ "${#PROFILE_YMLS[@]}" -eq 0 ]; then
  echo "  ! E. 正向断言：没找到 application*.yml（跳过）"
else
  for f in "${PROFILE_YMLS[@]}"; do
    # 只对**定义了 datasource url** 的 profile 断言（application.yml 这类壳文件没有 URL，跳过）
    if grep -qE '^[[:space:]]*url:[[:space:]]*jdbc:mysql' "$f"; then
      if grep -E '^[[:space:]]*url:[[:space:]]*jdbc:mysql' "$f" | grep -q 'useLocalSessionState=true'; then
        printf '  ✓ E. %s 的 datasource URL 带 useLocalSessionState=true\n' "$f"
      else
        printf '  ✗ E. %s 的 datasource URL **没有** useLocalSessionState=true（性能会静默退回 447.6 ok/s）\n' "$f"
        MISSING_PARAM=$((MISSING_PARAM + 1))
      fi
    else
      printf '  · E. %s 未定义 datasource URL（跳过）\n' "$f"
    fi
  done
  if [ "$MISSING_PARAM" -ne 0 ]; then FAILED_KINDS=$((FAILED_KINDS + MISSING_PARAM)); fi
fi

echo
if [ "$FAILED_KINDS" -ne 0 ]; then
  echo "✗ 门禁未通过：$FAILED_KINDS 类问题，共 $HITS_TOTAL 处禁止命中。"
  echo "  这些写法会让驱动本地会话视图与服务器真实状态分叉 —— useLocalSessionState=true 的前提不成立。"
  echo "  修法见本脚本头（禁止形态 / 允许清单），或 bench/README.md §15「JDBC 会话状态红线」。"
  exit 1
fi

echo "✓ 无绕过驱动改会话状态的写法（useLocalSessionState 的前提成立）"
exit 0
