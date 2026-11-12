#!/usr/bin/env bash
#
# 跑 MCP server 的测试（`npm test`，连 **test 库** `qiqi_mall_test`，后端 18080）—— 与 run-it.sh 共用一把锁。
# `scripts/run-mcp-test.sh`
#
# ── 为什么要有它 ────────────────────────────────────────────────────────────
# `npm test` 与 `mvn test` 分别打 test 库 / dev 库，但**同一台机器、同一套 docker MySQL**：
#   · MCP 用例里有大量「调用前后数它自己那几张表行数不变」的只读断言；
#   · 后端 IT 用例（`mvn test`）会真起 Spring Boot、抢连接池与 CPU，dev 库也会被写；
# 两边同时跑，红的是「对方在动」，不是「被测的东西坏了」。
# 所以两个入口**共用** `$QIQI_TEST_LOCK`（默认 /tmp/qiqi-test.lock）：谁先跑谁拿锁，另一个排队等。
#
# ── 用法 ───────────────────────────────────────────────────────────────────
#   ./scripts/run-mcp-test.sh                 # 等价于 cd mcp-server && npm test
#   ./scripts/run-mcp-test.sh -- test:delivery   # 参数原样透传给 npm（这里只跑单个文件）
#   ./scripts/run-mcp-test.sh run test:lock
#
# 前置（脚本会帮忙兜一半）：
#   · 后端必须已在 18080 跑起来、且连的是 `qiqi_mall_test`；
#   · `MALL_API_KEY` 必填（key 按库隔离）。若环境里没给，脚本会自动读 /tmp/test-env-ai-key.txt
#     （那是 scripts/init-ai-key.sh 生成测试环境 key 的默认落盘位置）——**只提示已载入，不回显内容**；
#   · 想显式指定：`MALL_API_KEY=... ./scripts/run-mcp-test.sh`
#
# 环境变量：
#   QIQI_TEST_LOCK       锁文件（默认 /tmp/qiqi-test.lock，与 run-it.sh 同一个）
#   QIQI_TEST_LOCK_WAIT  等锁上限秒数（默认 1800 = 30 分钟；超时立刻失败，不静默继续）
#
# ── 说明 ───────────────────────────────────────────────────────────────────
# · **不改**原来的调用方式：直接 `cd mcp-server && npm test` 照样能用，这个脚本只是新增的入口；
# · 本脚本只做「载入 key + 排队拿锁 + 透传参数」，不 reset 任何库（测试本身自建自清）。
set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="${QIQI_TEST_LOCK:-/tmp/qiqi-test.lock}"
LOCK_WAIT="${QIQI_TEST_LOCK_WAIT:-1800}"
MCP_DIR="$REPO_DIR/mcp-server"
KEY_FILE="${QIQI_TEST_KEY_FILE:-/tmp/test-env-ai-key.txt}"

if ! command -v flock >/dev/null 2>&1; then
  echo "✗ 找不到 flock（util-linux）—— 没有锁就没法保证与 mvn test 串行，拒绝裸跑" >&2
  exit 2
fi

# ---------------------------------------------------------------- API Key（不回显）
if [[ -z "${MALL_API_KEY:-}" && -r "$KEY_FILE" ]]; then
  MALL_API_KEY="$(cat "$KEY_FILE")"
  export MALL_API_KEY
  echo "[mcp] 已从 $KEY_FILE 载入 MALL_API_KEY（不回显）"
fi
if [[ -z "${MALL_API_KEY:-}" ]]; then
  echo "✗ MALL_API_KEY 为空：测试第一句就会失败。请 export MALL_API_KEY=...（或确认 $KEY_FILE 存在）" >&2
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
echo "[mcp] $(date '+%F %T') → cd mcp-server && npm test $*"
cd "$MCP_DIR" || exit 2
npm test "$@"
rc=$?
echo "[mcp] $(date '+%F %T') 退出码 $rc（锁随脚本退出释放）"
exit "$rc"
