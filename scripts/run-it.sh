#!/usr/bin/env bash
#
# 跑后端集成测试（`mvn test`，连 **dev 库** `qiqi_mall`）—— 带一把跨命令互斥锁。
# `scripts/run-it.sh`
#
# ── 为什么要有它 ────────────────────────────────────────────────────────────
# `mvn test`（dev 库 qiqi_mall）与 `npm test`（test 库 qiqi_mall_test，走 18080 的后端）
# 各自都「自建数据 + 自清理」，但两边的**清理/复位动作会互相踩**：
#   · MCP 那边的用例会真调后端写 test 库，同时又直连 test 库数行数（断言「行长不变」）；
#   · 后端的 IT 用例在同一台机器上抢 CPU/连接池，且 dev 库里若有别人的写，容易把「预演零落库」这类
#     断言误判成失败（历史事故：并发跑 mvn test 时 DryRunPreview 那 13 条假红，diff 全是 ±1 行）。
# 所以给两边加**同一把文件锁**：谁先跑谁拿锁，另一个在门口等，绝不并行。
#
# ── 用法 ───────────────────────────────────────────────────────────────────
#   ./scripts/run-it.sh                    # 裸跑就行：锁 + 三个 Hikari 旋钮都已内置（见下）
#   ./scripts/run-it.sh -Dtest=DryRunPreviewIntegrationTest     # 参数原样透传给 mvn
#   ./scripts/run-it.sh -DfailIfNoTests=false -Dtest=OptimisticLockIntegrationTest
#
# 环境变量：
#   QIQI_TEST_LOCK       锁文件（默认 /tmp/qiqi-test.lock，与 run-mcp-test.sh 同一个）
#   QIQI_TEST_LOCK_WAIT  等锁上限秒数（默认 1800 = 30 分钟；超时立刻失败，不静默继续）
#
# ── 默认带这三个 Hikari 旋钮（2026-11-16 实测后**固化在脚本里**，别删） ──────────
#   -Dspring.datasource.hikari.maximum-pool-size=6    每个上下文最多 6 条连接
#   -Dspring.datasource.hikari.minimum-idle=0         空闲时把连接**还回去**（最关键的一条）
#   -Dspring.datasource.hikari.idle-timeout=10000     10s 就还，别攥着默认的 10 分钟
#
#   病根不是「池太大」，是**池不还**。本机 MySQL `max_connections=151`，而常驻已被
#   e2e 后端 / dev server 占掉 52 条，留给这套 IT 的只有 ~99 条；而这套 IT 要起
#   **18 个 Spring 上下文**（见下面「资源账」）。若每个池都赖着不放：
#       18 × 6 = 108 > 99  ⇒  必定在某次「新上下文启动」时被库踢掉，报的是
#       java.sql.SQLNonTransientConnectionException: … "Too many connections"（08004 / 1040）
#   而 surefire 把它记成**某一个测试类的 error**，看起来极像「某条用例失败」——
#   实际那个类一条都没跑（2026-11-16 一天之内三个 agent 各自踩过一次）。
#
#   实测（同一台机器、同一把锁、连接数每 5s 采样）：
#     · 只带 pool-size=6            → 峰值 143 条、必红（AuditRunIntegrationTest 起上下文时被拒）
#     · 再补 minimum-idle=0 + idle-timeout=10000 → 峰值 73 条、321 用例 0 失败 0 错误
#   ⇒ 三个值必须**同时**在。少一个就退回「档位不够用」，而错误现场不会告诉你少了哪个。
#
#   调用方想覆盖某个值：直接再传一个同名 `-D` 即可（本脚本按 key 去重，只补你没传的）。
#
#   ⚠️ **不要用调大 MySQL max_connections 来「解决」这件事**：那只是把「这台机器上跑不下
#      一整套 IT」藏起来。库的上限是运行环境的既定事实（生产更不该为测试放宽），
#      测试侧该做的只有一件事：**用完就还**，而不是伸手要更多。
#
# ── 资源账：18 个上下文是**结构性成本**，不是配置问题（改测试的人先看这段） ─────────
#   Spring 的测试上下文缓存按**配置属性集**做 key：属性集不同 = 一个新上下文 + 一个新连接池，
#   而且活到这个 JVM 结束（不手动 @DirtiesContext 就不会关）。
#   ⇒ **每新增一个「新属性组合」的 @SpringBootTest，就多一个常驻池。**
#   ⇒ 想加一个只靠 `@SpringBootTest(properties=…)` 就能测的类时，先看看能不能**复用**
#     已有属性集（照抄某个现有类的那几行），而不是随手写一组新的。
#   ⇒ 上下文数再涨，调池也救不了（一个池最少也要 1 条连接），那时唯一的出路是
#     **收敛成共享属性集**。今天不治，但瓶颈在这里：以后再撞连接上限，别从库那边找原因。
#
# ── 说明 ───────────────────────────────────────────────────────────────────
# · **不改**原来的调用方式：直接 `cd qiqi-mall-server && mvn -o test` 照样能用
#   （但那句裸命令**不带**上面三个旋钮 —— 全量跑请走本脚本）；
# · 回归/发版前请统一走 `scripts/run-it.sh`，别在两边同时手敲 mvn/npm。
# · 除了锁与那三个默认属性，mvn 的其余参数完全由调用方决定。
set -uo pipefail

# 三个 Hikari 默认值（理由见头部注释）。只补调用方**没传**的 key —— 传了就以调用方为准。
DEFAULT_D_PROPS=(
  "-Dspring.datasource.hikari.maximum-pool-size=6"
  "-Dspring.datasource.hikari.minimum-idle=0"
  "-Dspring.datasource.hikari.idle-timeout=10000"
)

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="${QIQI_TEST_LOCK:-/tmp/qiqi-test.lock}"
LOCK_WAIT="${QIQI_TEST_LOCK_WAIT:-1800}"
SERVER_DIR="$REPO_DIR/qiqi-mall-server"

if ! command -v flock >/dev/null 2>&1; then
  echo "✗ 找不到 flock（util-linux）—— 没有锁就没法保证与 npm test 串行，拒绝裸跑" >&2
  exit 2
fi

# ---------------------------------------------------------------- 拿锁（fd 9）
exec 9>"$LOCK_FILE" || { echo "✗ 打不开锁文件 $LOCK_FILE" >&2; exit 2; }
if flock -n 9; then
  echo "[lock] $(date '+%F %T') ✓ 立即取得 $LOCK_FILE（当前没有别的测试在跑）"
else
  echo "[lock] $(date '+%F %T') … $LOCK_FILE 被占用（另一个 mvn test / npm test 正在跑），最多等 ${LOCK_WAIT}s"
  if ! flock -w "$LOCK_WAIT" 9; then
    echo "[lock] $(date '+%F %T') ✗ 等锁 ${LOCK_WAIT}s 超时：另一个测试进程还没结束，本次**什么都没跑**" >&2
    exit 75
  fi
  echo "[lock] $(date '+%F %T') ✓ 等到锁，开始执行"
fi

# ------------------------------------------------- 补默认属性（调用方同 key 优先）
# 只补调用方没传的 key：`./scripts/run-it.sh -Dspring.datasource.hikari.minimum-idle=3` 会
# 让 minimum-idle 用 3，另两个仍用默认值 —— 不会出现「同一个 key 传两遍、谁生效看 Maven 心情」。
EXTRA_D_PROPS=()
for _prop in "${DEFAULT_D_PROPS[@]}"; do
  _key="${_prop%%=*}"            # 例：-Dspring.datasource.hikari.idle-timeout
  _overridden=0
  for _arg in "$@"; do
    if [[ "$_arg" == "$_key"=* ]]; then _overridden=1; break; fi
  done
  (( _overridden )) || EXTRA_D_PROPS+=("$_prop")
done
unset _prop _key _overridden _arg

# ---------------------------------------------------------------- 跑（锁跟着 fd 9 一直持有到脚本退出）
echo "[it] $(date '+%F %T') → cd qiqi-mall-server && mvn -o test ${EXTRA_D_PROPS[*]:-} $*"
cd "$SERVER_DIR" || exit 2
mvn -o test ${EXTRA_D_PROPS[@]+"${EXTRA_D_PROPS[@]}"} "$@"
rc=$?
echo "[it] $(date '+%F %T') 退出码 $rc（锁随脚本退出释放）"
exit "$rc"
