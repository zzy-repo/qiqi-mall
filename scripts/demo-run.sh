#!/usr/bin/env bash
#
# 录演示视频专用：cgroup 隔离（chrome/node 进 qiqi-e2e.slice）+ 打容器栈（右上角「测试环境」）。
#
#   scripts/demo-run.sh "tests/scenarios/04-商家-基础配置/2-订单设置.spec.js"
#   scripts/demo-run.sh "tests/scenarios/04-商家-基础配置/3-退货原因字典.spec.js" --grep "字典维护页面"
#   npm run demo -- "tests/scenarios/<类别>/<场景>.spec.js"        # 等价写法
#
# ⚠️ 跑测试请用 scripts/e2e-run.sh（shard 并行 + 独立 e2e 库），**不要**用这个脚本。
#    原因：这是"拍摄机"，素材必须来自测试环境（容器栈 5174/5176/18080，右上角红标「测试环境」）；
#    e2e-run.sh 是"跑分机"，端口写死 5183/5185/8091、5193/5195/8092 并会重置 e2e 库。
#    拿 e2e-run.sh 录片会得到"徽标对但数据来自 e2e 库"的片子（已经被踩过一次）。
#
# 参数原样透传给 npx playwright test（--grep / --project 等都支持）；
# 没显式给 --output 时自动落到 /tmp/pwout-demo-<pid>，避免多 agent 抢同一目录。
# 录完用 node scripts/collect-demo.mjs 把 webm 归并成 mp4。
#
# 限制：容器栈（18080）**没有**验证码 DEBUG 日志。需要读验证码的 spec 只能打 dev 栈，那种情况手动执行：
#   env DEMO=1 PORTAL_BASE=http://127.0.0.1:5173 ADMIN_BASE=http://127.0.0.1:5175 \
#       API_BASE=http://127.0.0.1:8081 CAPTCHA_LOG=/tmp/dev-server.log \
#       flock /tmp/e2e.lock npx playwright test "<spec>"
# （CAPTCHA_LOG 这里保留默认值 /tmp/dev-server.log，容器栈下它只是躺着不用。）
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 参数原样透传；调用方已给 --output 就不再追加，避免重复旗标
ARGS=()
HAS_OUTPUT=0
for a in "$@"; do
  ARGS+=("$a")
  [[ "$a" == --output=* ]] && HAS_OUTPUT=1
done
OUT_ARGS=()
if [[ "$HAS_OUTPUT" == "0" ]]; then
  OUT_ARGS=("--output=/tmp/pwout-demo-$$")
fi

cd "$REPO_DIR/e2e"
exec systemd-run --scope --quiet --slice=qiqi-e2e.slice \
  --property=MemoryMax=4G --property=MemoryHigh=3G --property=MemorySwapMax=1G -- \
  env DEMO=1 \
      PORTAL_BASE=http://127.0.0.1:5174 \
      ADMIN_BASE=http://127.0.0.1:5176 \
      API_BASE=http://127.0.0.1:18080 \
      CAPTCHA_LOG=/tmp/dev-server.log \
  flock /tmp/e2e.lock npx playwright test "${ARGS[@]}" "${OUT_ARGS[@]}"
