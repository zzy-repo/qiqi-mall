#!/usr/bin/env bash
#
# 录演示视频专用：cgroup 隔离（chrome/node 进 qiqi-e2e.slice）+ 打容器栈（右上角「测试环境」）。
#
#   scripts/demo-run.sh "tests/scenarios/业务/商家-基础配置/订单设置.spec.js"
#   scripts/demo-run.sh "tests/scenarios/业务/商家-基础配置/退货原因字典.spec.js" --grep "字典维护页面"
#   ADMIN_PASSWORD='…' scripts/demo-run.sh "<spec>"   # 口令也可显式给；不给就读 /root/.qiqi-secrets/admin.txt
#   npm run demo -- "tests/scenarios/<桶>/[<子分类>/]<场景>.spec.js"        # 等价写法
#
# ⚠️ 跑测试请用 scripts/e2e-run.sh（shard 并行 + 独立 e2e 库），**不要**用这个脚本。
#    原因：这是"拍摄机"，素材必须来自测试环境（容器栈 5174/5176/18080，右上角红标「测试环境」）；
#    e2e-run.sh 是"跑分机"，端口写死 5183/5185/8091、5193/5195/8092 并会重置 e2e 库。
#    拿 e2e-run.sh 录片会得到"徽标对但数据来自 e2e 库"的片子（已经被踩过一次）。
#
# ── admin 口令（必读，踩过）────────────────────────────────────────────────────────
# 容器栈（18080 → 库 qiqi_mall_test）的 admin 是**强口令**（/root/.qiqi-secrets/admin.txt，600），
# 不是种子默认的 123456。所以本脚本自己解析口令，优先级：
#   ① 调用方已 export 的 ADMIN_PASSWORD
#   ② 否则读 ADMIN_PASSWORD_FILE（默认 /root/.qiqi-secrets/admin.txt，存在且非空就用）
#   ③ 都没有 → **报错退出**（绝不静默回落 123456）
# 解析到之后**先打一次 POST /admin/auth/login 做预检**，通过才起 playwright：口令错 / 账号在验证码态 /
# 栈不可达都在这里就红。为什么非要预检：登录失败时 spec 只是停在登录页等 20s 超时，录出来的
# 视频就是**一张登录页**，而退出码看起来和「跑完了」没区别 —— 已经因此录废过 6 段片子。
# 为什么不能写死 123456：口令由 scripts/set-admin-password.sh 轮换（强口令，24 位），写死等于
# 把「口令被轮换」这件事变成「录片静默变废」；而且弱口令写进仓库本身就是安全事故。
# 临时跳过预检（只在明确知道登录接口不能用时）：DEMO_SKIP_LOGIN_PRECHECK=1
#
# 参数原样透传给 npx playwright test（--grep / --project 等都支持）；
# 没显式给 --output 时自动落到 /tmp/pwout-demo-<pid>，避免多 agent 抢同一目录。
# 录完用 node e2e/scripts/collect-demo.mjs 把 webm 归并成 mp4（路径按 <桶>/[<子分类>/]<场景>/<段序>-<段名>.mp4）。
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

# ── 容器栈地址：预检和下面的 env 注入共用一份，别在两处各写一遍端口 ──────────────
PORTAL_BASE_URL="http://127.0.0.1:5174"
ADMIN_BASE_URL="http://127.0.0.1:5176"
API_BASE_URL="http://127.0.0.1:18080"

# ── admin 口令：解析（环境变量 → 密钥文件 → 报错退出，不许回落 123456）─────────
SECRET_FILE="${ADMIN_PASSWORD_FILE:-/root/.qiqi-secrets/admin.txt}"
ADMIN_PASS=""
PASS_SRC=""
if [[ -n "${ADMIN_PASSWORD:-}" ]]; then
  ADMIN_PASS="$ADMIN_PASSWORD"
  PASS_SRC="环境变量 ADMIN_PASSWORD"
elif [[ -s "$SECRET_FILE" ]]; then
  ADMIN_PASS="$(tr -d '\r\n' < "$SECRET_FILE")"
  PASS_SRC="密钥文件 $SECRET_FILE"
fi
if [[ -z "$ADMIN_PASS" ]]; then
  cat >&2 <<EOF

✗ 拿不到 admin 口令，已停止（不会回落 123456 去录出一张登录页）。

  录制的容器栈 admin 是**强口令**（$API_BASE_URL → 库 qiqi_mall_test），不是种子默认的 123456。
  请二选一：
    · export ADMIN_PASSWORD="\$(cat $SECRET_FILE)"      # 或任意正确的口令
    · 确认 $SECRET_FILE 存在且非空（chmod 600）

  为什么脚本不自己补一个默认口令：口令由 scripts/set-admin-password.sh 轮换，
  写死 123456 的后果是「口令一换，录片静默变成登录页视频」，而且弱口令本身不该进仓库。

EOF
  exit 6
fi

# ── admin 口令预检：起 playwright **之前**先真登一次 ─────────────────────────────
# 为什么：登录失败时 spec 只是停在登录页等 20s 超时 → 视频是一张登录页，退出码却看不出区别
# （已经因此录废过 6 段片子）。所以在这里就把「口令错 / 验证码态 / 栈没起」变成明确报错。
# 口令走 stdin 进 curl（--data-binary @-），不上命令行：ps / 日志里都看不到。
if [[ "${DEMO_SKIP_LOGIN_PRECHECK:-0}" != "1" ]]; then
  # ① 先看账号是不是已经在验证码态：容器栈**没有**验证码 DEBUG 日志，进了这个态只能等 5 分钟窗口过期。
  #    不先看的话，这一步会拿到 401「验证码错误」并被误报成「口令不对」。
  _cap="$(curl -s --max-time 5 "$API_BASE_URL/admin/auth/captcha-required?username=admin" 2>/dev/null || true)"
  if [[ "$_cap" == *'"data":true'* ]]; then
    echo "✗ admin 账号正处在**验证码态**（5 分钟内失败 ≥5 次），无法预检口令。" >&2
    echo "  容器栈没有验证码 DEBUG 日志，等这个窗口过期（约 5 分钟）再跑；" >&2
    echo "  或用正确口令成功登录一次即清零：ADMIN_PASSWORD=… 后重跑本脚本。" >&2
    exit 7
  fi
  # ② 真登一次。JSON 交给 node 拼（口令里可能出现引号/反斜杠，别用 printf 手拼）。
  _json="$(DEMO_PW_FOR_JSON="$ADMIN_PASS" node -e \
    'process.stdout.write(JSON.stringify({username:"admin",password:process.env.DEMO_PW_FOR_JSON}))')"
  _body_file="$(mktemp /tmp/demo-login-precheck-XXXXXX.json)"
  _code="$(printf '%s' "$_json" | curl -sS --max-time 10 -o "$_body_file" -w '%{http_code}' \
      -H 'Content-Type: application/json' --data-binary @- \
      "$API_BASE_URL/admin/auth/login" 2>/dev/null || true)"
  _resp="$(cat "$_body_file" 2>/dev/null || true)"
  rm -f "$_body_file"
  case "$_code" in
    200)
      if [[ "$_resp" != *accessToken* ]]; then
        echo "✗ admin 口令预检异常：登录返回 200 但 body 里没有 accessToken。body 前 200 字节：${_resp:0:200}" >&2
        exit 7
      fi
      # 成功登录会把 captcha:fail:admin 清零 —— 顺带把之前误撞的失败计数也清掉，不是副作用。
      echo "✓ admin 口令预检通过（来源：$PASS_SRC；POST $API_BASE_URL/admin/auth/login → 200 + accessToken）"
      ;;
    401)
      if [[ "$_resp" == *验证码* ]]; then
        echo "✗ admin 口令预检失败：接口返回「验证码错误」（账号已在验证码态）。" >&2
        echo "  容器栈没有验证码 DEBUG 日志，等 5 分钟窗口过期再跑。" >&2
      else
        echo "✗ admin 口令不对（POST $API_BASE_URL/admin/auth/login → 401「用户名或密码错误」）。" >&2
        echo "  本次用的口令来源：$PASS_SRC。" >&2
        echo "  · 用环境变量：export ADMIN_PASSWORD=\"\$(cat $SECRET_FILE)\"（或该环境的正确口令）" >&2
        echo "  · 或检查 $SECRET_FILE 与库里 admin 的口令是否一致（scripts/set-admin-password.sh 可重设）" >&2
        echo "  注意：这次失败已让失败计数 +1（5 分钟窗口，触发 5 次就要验证码）；别反复盲试。" >&2
      fi
      exit 7
      ;;
    ""|000)
      echo "✗ 连不上容器栈 API $API_BASE_URL —— 先确认 5174/5176/18080 在跑（docker compose ps），" >&2
      echo "  否则录出来的片子要么打空端口、要么停在登录页。" >&2
      exit 8
      ;;
    *)
      echo "✗ admin 口令预检返回意外状态 $_code。body 前 200 字节：${_resp:0:200}" >&2
      exit 7
      ;;
  esac
fi

# ── 注入给 playwright 的环境变量：照 scripts/e2e-run.sh 的清单对过一遍 ────────────
# e2e-run.sh 注入 PORTAL_BASE/ADMIN_BASE/API_BASE/E2E_WORKERS/E2E_SHARD_NAME/E2E_OUT_DIR/
# E2E_HTML_DIR（并**主动 unset ADMIN_PASSWORD**，因为 e2e 库的 admin 是 123456）。
# 本脚本的差额只有两类，都补齐了：
#   · ADMIN_PASSWORD —— 原来没注入，就是「录出来全是登录页」的根因（上面已修）。
#   · E2E_WORKERS=1  —— e2e-run 按 shard×worker 缩放，录片必须**串行**：多 worker 会让同一次录制的
#     片段并发写同一个 outputDir（视频互相穿插），4G 内存也不够 2 个 chrome。叫法沿用它的变量名。
#   · E2E_HTML_DIR=report/html-demo-<pid> —— e2e-run 给每个 shard 独立 HTML 目录就是为了避免
#     「两个进程同时写同一个 outputFolder → 互相清空/写坏」。demo 以前用默认 report/html，
#     而同时开两路录片（很常见：一批 6 段）会互删报告，所以按 pid 分开。
# 不注入的两项（都不是缺口）：
#   · E2E_OUT_DIR —— DEMO=1 时 playwright.config.js 走 ./report/videos/raw，真正生效的是
#     `--output`（默认 /tmp/pwout-demo-<pid>，调用方给了就用调用方的），跟 E2E_OUT_DIR 无关。
#   · E2E_SHARD_NAME —— 只进 HTML 报告的 project 名字（chromium-a），不影响任何地址/目录。
# CAPTCHA_LOG 仍保留默认（容器栈没有验证码 DEBUG 日志，它只是躺着不用，见文件头「限制」）。
exec systemd-run --scope --quiet --slice=qiqi-e2e.slice \
  --property=MemoryMax=4G --property=MemoryHigh=3G --property=MemorySwapMax=1G -- \
  env DEMO=1 \
      PORTAL_BASE="$PORTAL_BASE_URL" \
      ADMIN_BASE="$ADMIN_BASE_URL" \
      API_BASE="$API_BASE_URL" \
      ADMIN_PASSWORD="$ADMIN_PASS" \
      CAPTCHA_LOG=/tmp/dev-server.log \
      E2E_WORKERS=1 \
      E2E_HTML_DIR="report/html-demo-$$" \
  flock /tmp/e2e.lock npx playwright test "${ARGS[@]}" "${OUT_ARGS[@]}"
