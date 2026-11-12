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
#   ./scripts/run-it.sh                    # 等价于 cd qiqi-mall-server && mvn -o test
#   ./scripts/run-it.sh -Dtest=DryRunPreviewIntegrationTest     # 参数原样透传给 mvn
#   ./scripts/run-it.sh -DfailIfNoTests=false -Dtest=OptimisticLockIntegrationTest
#
# 环境变量：
#   QIQI_TEST_LOCK       锁文件（默认 /tmp/qiqi-test.lock，与 run-mcp-test.sh 同一个）
#   QIQI_TEST_LOCK_WAIT  等锁上限秒数（默认 1800 = 30 分钟；超时立刻失败，不静默继续）
#
# ── 说明 ───────────────────────────────────────────────────────────────────
# · **不改**原来的调用方式：直接 `cd qiqi-mall-server && mvn -o test` 照样能用，这个脚本只是新增的入口；
# · 回归/发版前请统一走 `scripts/run-it.sh`，别在两边同时手敲 mvn/npm。
# · 只加锁，不改测试行为：`-o`（离线）与 mvn 的其余参数完全由调用方决定。
set -uo pipefail

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

# ---------------------------------------------------------------- 跑（锁跟着 fd 9 一直持有到脚本退出）
echo "[it] $(date '+%F %T') → cd qiqi-mall-server && mvn -o test $*"
cd "$SERVER_DIR" || exit 2
mvn -o test "$@"
rc=$?
echo "[it] $(date '+%F %T') 退出码 $rc（锁随脚本退出释放）"
exit "$rc"
