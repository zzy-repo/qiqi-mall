#!/usr/bin/env bash
#
# 跑 e2e 的唯一入口：两个 shard 并行，各自一套「后端 + 前端 + 库」，整体关进 qiqi-e2e.slice。
#
#   scripts/e2e-run.sh                                  # 全量：2 shard 并行，跑前自动重置两个 e2e 库
#   scripts/e2e-run.sh "tests/scenarios/业务/商家-履约/订单审核与发货.spec.js"
#   scripts/e2e-run.sh --affected                        # 只跑被 git 改动波及的 spec（日常用这个）
#   scripts/e2e-run.sh --affected --base=HEAD~3           # 换对比基准（默认 origin/main...HEAD + 工作区）
#   scripts/e2e-run.sh --no-reset                        # 不重置库（调试单条时快一些；也不重启后端）
#   E2E_SHARDS=1 scripts/e2e-run.sh                      # 降级：单 shard 单后端
#   E2E_WORKERS=2 scripts/e2e-run.sh                     # 每个 shard 内再开 2 个 worker
#   E2E_FRONT_MODE=dev scripts/e2e-run.sh                # 回退：前端打 4 套 vite dev server（旧默认）
#
# ⚠ reset 库会让被测服务 JVM 里缓存的主数据（如平台买家 member_id）变悬空，所以 reset 之后
#   必须重启后端；否则连续两次跑批会在 业务/渠道-多平台接入/接入主链路 上红一次（code=1，fk_order_member）。
#   本脚本不给"reset 了但没重启"留路径：跑前的 reset 由 scripts/reset-e2e-db.sh 做，它**默认自动
#   重启**对应分片后端；--no-reset 不重启（没 DROP 就没有悬空缓存）；SKIP_BACKEND_RESTART=1
#   在 reset 生效时会被直接拒绝，除非同时显式给 E2E_ALLOW_SKIP_BACKEND_RESTART=1（只给对照实验用）。
#
# 前端模式默认已是 build（前端打**构建产物** + preview-server，实测墙钟 −15%，代价是构建 +20s）。
# 直接跑 `scripts/e2e-run.sh` 拿到的就是快的那一版；只有需要「改前端源码即时生效」时才用 E2E_FRONT_MODE=dev。
#
# ⚠️ 跑 e2e **不要**传 ADMIN_PASSWORD：e2e 分片库的 admin 口令是种子默认的 123456，
#    ADMIN_PASSWORD 是容器栈 demo 录制用的强口令，混进来会让 ui.js 拿强口令登 e2e 库 →
#    大面积登录超时「假红」。脚本入口会主动 unset（见下方入口处的注释）。
#
# 四个「为什么」：
#
#  ① 为什么在 qiqi-e2e.slice 里跑：agent 直接 `npx playwright test` 时，chrome/node 都是
#     dsh-web.service 的子进程，全记在它 MemoryHigh=6G 的账上 → 一跑 e2e 就把 DSH 挤到假死。
#     `systemd-run --scope --slice=qiqi-e2e.slice` 让它们在**兄弟 slice**，超限只伤自己。
#
#  ② 为什么默认不抢 flock 了：以前两个 worker 共用一个 dev 库（8081），只能串行。
#     现在每个 shard 有**自己的库**（qiqi_mall_e2e_a / _b）+ 自己的 Redis db（1 / 2），
#     互不干扰，flock 反而成了串行的枷锁 → 默认 E2E_UNLOCKED=1。要回老行为设 E2E_UNLOCKED=0。
#
#  ③ 为什么是「两个进程」而不是 playwright 的 projects：spec 走的地址来自 tests/support/env.js
#     导出的绝对 URL，env.js 在模块加载时读 process.env；同进程内所有 project 共享一份 env，
#     per-project 的 use.baseURL 改不了 API 地址。不改 spec/support 的前提下，进程级分片
#     （每进程一套 env + `--shard=i/N`）是唯一干净做法。详见 e2e/playwright.config.js 头部。
#
#  ④ 为什么日志名默认带 TAG：日志名以前是固定的 /tmp/e2e-shard-<shard>.log，两条跑批只要落在
#     同一个 shard 就写同一个文件，后跑的静默覆盖先跑的。实测踩过：两条线同时在 shard a 上跑，
#     A 线的日志被 B 线覆盖，A 线的 agent 只能靠手工传 E2E_LOG_SUFFIX 才看到真相。
#     所以现在默认拼本次跑的 TAG（$TAG = E2E_RUN_TAG，缺省「秒级时间戳-PID」，每次跑唯一），
#     不需要使用者记得传任何东西；显式传 E2E_LOG_SUFFIX 时仍以传入值优先（向后兼容）。
#
# 注意：并行靠的是「shard × 库隔离」，不是多 worker 抢一个库；E2E_WORKERS 加大只适合
#       那些内部不互相踩数据的 spec 集合。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLICE="qiqi-e2e.slice"

# shard → portal端口 / admin端口 / 后端端口
# 前两个是常态配置；c/d 是为「4 shard × 1 worker」准备的（需要先建库并起对应的
# 后端/前端：scripts/start-e2e-backends.sh / start-e2e-frontends.sh 里同步加条目）。
SHARDS=("a:5183:5185:8091" "b:5193:5195:8092" "c:5203:5205:8093" "d:5213:5215:8094")
# 可用 shard 数 = 探测到的「后端端口活着」的个数，避免 E2E_SHARDS 喊得比实际起的多
AVAIL_SHARDS="${AVAIL_SHARDS:-2}"

WANT_SHARDS="${E2E_SHARDS:-2}"
# 从第几个分片开始取（0=a 1=b 2=c 3=d）。
# 用途：两条并发 e2e 线各自钉一个分片/库，避免撞同一个库出现假失败。
#   E2E_SHARD_FROM=1 E2E_SHARDS=1  → 只用 shard b（5193/5195/8092，库 qiqi_mall_e2e_b）
#   E2E_SHARD_FROM=0 E2E_SHARDS=1  → 只用 shard a（默认行为不变）
export SHARD_FROM="${E2E_SHARD_FROM:-0}"   # 必须 export：内层 INNER heredoc 在独立 shell 里读它
# 本次跑的标签：用来把自己的 scope 记账，--kill-stale 只清记账里的那些
TAG="${E2E_RUN_TAG:-$(date +%s)-$$}"
SCOPE_DIR=/tmp/e2e-scopes
mkdir -p "$SCOPE_DIR"
WORKERS="${E2E_WORKERS:-1}"
DO_RESET=1
PASS_ARGS=()
OUT_PREFIX=""

for a in "$@"; do
  case "$a" in
    --kill-stale) KILL_STALE=1 ;;
    -h|--help)
      sed -n '2,40p' "$0" | sed 's/^# \?//'; exit 0 ;;
    --affected) AFFECTED=1 ;;
    --base=*) E2E_AFFECTED_BASE="${a#--base=}" ;;
    --no-reset) DO_RESET=0 ;;
    --output=*) OUT_PREFIX="${a#--output=}" ;;     # 我们自己按 shard 加后缀，别让两进程抢同一目录
    --list) LIST=1; PASS_ARGS+=("$a") ;;
    *) PASS_ARGS+=("$a") ;;
  esac
done

# ── SKIP_BACKEND_RESTART 护栏：本脚本不允许"reset 了但没重启" ────────────────
# reset（DROP DATABASE）会让被测服务 JVM 里缓存的主数据（如 ChannelOrderImporter.platformBuyerId()
# 的 member_id）变成悬空外键，只 reset 不重启 ⇒ 下一次拉单撞 fk_order_member ⇒ 09/1 红。
# 所以这里把"半成品路径"堵住：想在 reset 生效时跳过重启，必须再显式加一层
# E2E_ALLOW_SKIP_BACKEND_RESTART=1（那只有"故意复现这个红"的对照实验才该做）。
if [[ "$DO_RESET" == "1" && "${SKIP_BACKEND_RESTART:-0}" == "1" ]]; then
  if [[ "${E2E_ALLOW_SKIP_BACKEND_RESTART:-0}" != "1" ]]; then
    echo "✗ SKIP_BACKEND_RESTART=1 与 reset 一起用被拒绝：这样跑出来的红/绿都不算数。" >&2
    echo "  reset 会 DROP DATABASE，而后端 JVM 里缓存的主数据（平台买家 member_id 等）会变悬空外键，" >&2
    echo "  下一次拉单撞 fk_order_member → 业务/渠道-多平台接入/接入主链路 红（这就是本次要修的那个坑）。" >&2
    echo "  · 正常跑批：去掉 SKIP_BACKEND_RESTART（默认就是 reset 后自动重启）" >&2
    echo "  · 非要复现这个红：再加 E2E_ALLOW_SKIP_BACKEND_RESTART=1（结论只用于对照，不能当验收）" >&2
    exit 5
  fi
  cat >&2 <<'ALLOW_SKIP_EOF'

████████████████████████████████████████████████████████████████████████
⚠  E2E_ALLOW_SKIP_BACKEND_RESTART=1：库会重置，但**后端不会重启**。
   JVM 缓存的主数据会指向已被 DROP 的 member ⇒ 第二次跑批大概率在 09/1 上红。
   这条路径只用于"证明红就是因为它"的对照实验，不要拿它的结论当验收。
████████████████████████████████████████████████████████████████████████

ALLOW_SKIP_EOF
fi

# ── 入口先清掉 ADMIN_PASSWORD ────────────────────────────────────────────────
# e2e 分片库的 admin 口令是 sql/testdb/02-seed.sql 里的种子默认值（123456），不走强口令。
# ADMIN_PASSWORD 是给容器栈（5174/5176/18080）demo 录制用的强口令，但环境变量会串：
# 谁跑 e2e 时顺手把 ADMIN_PASSWORD=$(cat /root/.qiqi-secrets/admin.txt) 带进环境，
# e2e/tests/support/ui.js 的 adminLogin（process.env.ADMIN_PASSWORD || '123456'）就会
# 拿强口令去登 123456 的 e2e 库 → 登录失败，7 条用例全卡在登录页 20s 超时，看起来像大面积假失败
# （今天已经有一个 agent 被这个坑掉了一轮）。
# 为什么可以在这里 unset：demo 录制走的是 scripts/demo-run.sh，它是**独立入口**（自己 exec
# systemd-run → npx playwright test，直连 5174/5176/18080），完全不经过本脚本，不受影响。
unset ADMIN_PASSWORD

# ════════════════════════════════════════════════════════════════════════════
# E2E_FRONT_MODE：前端打 dev server 还是**构建产物**
#
#   build （**默认**）：`npm run build` 出的 dist + e2e/support/preview-server.mjs
#           （静态 + /portal|/admin → 该 shard 后端、/qiqi-mall → MinIO 反代，
#            端口 +100 = 5283/5285/5293/5295，**与 dev 那套可以同时存在**，
#            切模式不用先停别人正在用的 dev 栈）
#   dev   （**回退开关**，E2E_FRONT_MODE=dev）：scripts/start-e2e-frontends.sh 那 4 套 vite，
#          端口 5183/5185/5193/5195（一个 shard 一套 portal+admin）。
#          只在「改了前端源码想立刻看到效果、不想等构建」时用。
#
# 为什么要它：dev server 每次页面导航都要现编译模块、拉几百个请求（实测 778ms / 500 请求），
# 产物只要 525ms / 50 请求。spec 与断言一行不用改 —— 前端请求本来就是相对路径
# （portal 走 /portal、admin 走 /admin，见各 src/api/request.js 的 baseURL），
# 换的只是「谁来 serve 这堆静态文件、谁来反代这两个前缀」。
#
# 构建新鲜度（默认 build 模式每次跑都重建，几十秒换「产物 = 当前工作区」这条确定的锚；
# 显式 E2E_SKIP_BUILD=1 时若 dist 比源码旧则直接 die —— 理由和 preflight 的字节码锚一样：
# 跑旧产物的结论是假结论，宁可不跑）。
# 构建失败**明确报错退出**，绝不静默回落到 dev（静默回落 = 假快）。
# ════════════════════════════════════════════════════════════════════════════
fmt_epoch0() { [[ "${1:-0}" == "0" ]] && echo "-" || date -d "@$1" '+%F %T'; }
FRONT_MODE="${E2E_FRONT_MODE:-build}"
case "$FRONT_MODE" in
  dev)   PORT_OFFSET=0 ;;
  build) PORT_OFFSET="${E2E_PREVIEW_PORT_OFFSET:-100}" ;;
  *) echo "✗ E2E_FRONT_MODE='$FRONT_MODE' 不认识（只支持 dev|build）" >&2; exit 4 ;;
esac

# ════════════════════════════════════════════════════════════════════════════
# --affected：按 git 改动挑出受影响的 spec（映射表与安全阀都在 scripts/e2e-affected.mjs）
#
# 为什么日常要它：全量 122 条在 4 核 + 2 分片上要 20 分钟，而改一个后端包通常只波及个位数 spec。
# 三种结果，都不含糊：
#   none   → 改的是 docs / 物料，直接退出（0 秒，且明确告诉你「无需跑」）
#   full   → 命中横切面（shared/**、sql/**、前端公共层…）→ 老老实实跑全量（宁可多跑）
#   subset → 只跑命中的那几个文件，并按实测耗时 LPT 装进可用分片
# 注意：子集模式**不跳过 reset**（reset 是隔离前提），只是改成只重置本次要用的那个分片库。
# ════════════════════════════════════════════════════════════════════════════
AFFECTED_SPECS=()
if [[ "${AFFECTED:-0}" == "1" ]]; then
  _aff_base=()
  [[ -n "${E2E_AFFECTED_BASE:-}" ]] && _aff_base=("--base=$E2E_AFFECTED_BASE")
  # 演练/自测用：直接给一份改动清单，不读 git（例：E2E_AFFECTED_PATHS=qiqi-mall-server/.../oms/X.java）
  [[ -n "${E2E_AFFECTED_PATHS:-}" ]] && _aff_base+=("--paths=$E2E_AFFECTED_PATHS")
  if ! _aff_out="$(node "$REPO_DIR/scripts/e2e-affected.mjs" --shell "${_aff_base[@]}")"; then
    echo "✗ --affected 映射失败（scripts/e2e-affected.mjs 出错）；不敢猜，直接停。" >&2
    exit 4
  fi
  eval "$_aff_out"
  case "${MODE:-}" in
    none)
      echo "· --affected：无需跑 e2e —— ${REASON}"
      exit 0 ;;
    full)
      echo "⚠ --affected 命中横切面 → 升级为全量：${REASON}"
      ;;
    subset)
      AFFECTED_SPECS=("${SPECS[@]}")
      echo "▶ --affected：${REASON} → 只跑这 ${#AFFECTED_SPECS[@]} 个文件"
      printf '    %s\n' "${AFFECTED_SPECS[@]}"
      ;;
    *)
      echo "✗ --affected 返回未知 MODE='${MODE:-}'；停（不猜）" >&2
      exit 4 ;;
  esac
fi

# 单条 spec / 带路径过滤：--shard 会把它只分给一个 shard，另一个 shard 报「No tests found」
# 并以非零退出，看起来像失败。所以只要给了文件过滤，就自动退回单 shard（除非显式指定 E2E_SHARDS）。
#
# ⚠️ 坑（今晚踩过，别再改回去）：这里以前写的是
#      for a in "${PASS_ARGS[@]:-}"; do …; done
#   空数组时 `${arr[@]:-}` 会**迭代一次空字符串**（不是零次），于是 `[[ "" == -* ]]` 为假 →
#   走进「给了文件过滤」的分支 → 把 WANT_SHARDS 改成 1。现象就是：不传任何额外参数直接
#   `./scripts/e2e-run.sh --output=…` 会**静默降级成单分片**（日志里 shards=1，没有权重分片、
#   也没跑机器互斥锁），跑得慢一倍而且没人报错。
#   修法：先判长度再展开，彻底不用 `${arr[@]:-}`——长度判断是显式的，不存在「空迭代」的歧义。
if [[ -z "${E2E_SHARDS:-}" && ${#PASS_ARGS[@]} -gt 0 ]]; then
  for a in "${PASS_ARGS[@]}"; do
    [[ "$a" == -* ]] && continue     # 选项（如 --list）不算文件过滤
    WANT_SHARDS=1
    break
  done
fi

# ── --affected 子集：定分片数 + 把文件交给 playwright ───────────────────────
# 子集通常只有 2~10 个文件：
#   · ≤6 个文件 → 单分片（省下一整套 chrome+node 的内存，4 核上两个进程互相抢反而更慢）
#   · 更多     → 沿用 2 分片，由 e2e-affected.mjs 按**实测秒数**做 LPT 装箱（不是按文件数均分）
# E2E_SHARDS 显式指定时以它为准，不猜。
if [[ ${#AFFECTED_SPECS[@]} -gt 0 ]]; then
  if [[ -z "${E2E_SHARDS:-}" ]]; then
    if [[ ${#AFFECTED_SPECS[@]} -le 6 ]]; then WANT_SHARDS=1; else WANT_SHARDS=2; fi
  fi
  if [[ "$WANT_SHARDS" -eq 1 ]]; then
    PASS_ARGS+=("${AFFECTED_SPECS[@]}")   # 单分片：直接把文件列给 playwright
  fi
fi

# ── --kill-stale：清掉「自己启动、但外层 unit 已经被停」的残留 scope ────────────
# 为什么需要：`systemd-run --scope` 建的 scope 是 qiqi-e2e.slice 的**兄弟**，不是触发它的
# unit 的子节点。所以 `systemctl stop <外层unit>` 停不掉里面跑着的 playwright，它们会变成
# 孤儿继续占 CPU/内存/库。
# 为什么只清记账里的：同一个 slice 里还有**别人的录片/测试**在跑，
# 按 `--slice=qiqi-e2e.slice` 全量过滤会误伤（本项目已经踩过一次，导致别人的录片被杀）。
if [[ "${KILL_STALE:-0}" == "1" ]]; then
  found=0
  for f in "$SCOPE_DIR"/*; do
    [[ -f "$f" ]] || continue
    tag="$(basename "$f")"; unit="$(cat "$f" 2>/dev/null || true)"
    [[ -z "$unit" ]] && { rm -f "$f"; continue; }
    found=1
    if systemctl is-active --quiet "$unit" 2>/dev/null; then
      echo "▶ 停残留 scope $unit（tag $tag）"
      systemctl stop "$unit" 2>/dev/null || true
    else
      echo "· $unit 已经不在了"
    fi
    rm -f "$f"
  done
  [[ "$found" == "0" ]] && echo "· 没有记账的残留 scope（别人的跑不动，见脚本注释）"
  exit 0
fi

if [[ "${LIST:-0}" == "1" ]]; then
  echo "slice: $SLICE  ${LIMITS[*]}"
  echo "shards: $WANT_SHARDS (每 shard workers=$WORKERS)  库: qiqi_mall_e2e_a/_b  redis-db: 1/2"
  echo "flock: $([[ "${E2E_UNLOCKED:-1}" == "1" ]] && echo 关闭（独立库） || echo /tmp/e2e.lock)"
  echo "front: $FRONT_MODE$([[ "$FRONT_MODE" == "build" ]] && echo "（port+$PORT_OFFSET, dist+preview-server）" || echo "（vite dev）")"
  exit 0
fi

# ════════════════════════════════════════════════════════════════════════════
# build 模式下的前端：构建 dist → 起 4 个 preview（精确到 systemd unit），退出时精确停
# ════════════════════════════════════════════════════════════════════════════
# 这一行无条件打出来（--list 之外也打）：不看文档、直接跑默认的人要能一眼看见「这次跑的是哪一版前端」。
# build 是默认值，若哪天默认被人改回 dev，这里会立刻显示 dev，而不是让结论默默变了。
echo "▶ front: $FRONT_MODE$([[ "$FRONT_MODE" == "build" ]] && echo "（port+$PORT_OFFSET, dist+preview-server）" || echo "（vite dev）")"
if [[ "$FRONT_MODE" == "build" ]]; then
  dist_epoch() { [[ -f "$1" ]] && stat -c %Y "$1" || echo 0; }
  D_PORTAL="$REPO_DIR/portal-web/dist/index.html"
  D_ADMIN="$REPO_DIR/admin-web/dist/index.html"
  # 源码新鲜度：src/** + vite.config.js + package.json 里最新的 mtime
  SRC_NEWEST="$(find "$REPO_DIR/portal-web/src" "$REPO_DIR/admin-web/src" \
      "$REPO_DIR/portal-web/vite.config.js" "$REPO_DIR/admin-web/vite.config.js" \
      "$REPO_DIR/portal-web/package.json" "$REPO_DIR/admin-web/package.json" \
      -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1 || true)"
  SRC_NEWEST="${SRC_NEWEST:-0}"
  D_MIN=$(( $(dist_epoch "$D_PORTAL") < $(dist_epoch "$D_ADMIN") ? $(dist_epoch "$D_PORTAL") : $(dist_epoch "$D_ADMIN") ))

  if [[ "${E2E_SKIP_BUILD:-0}" == "1" ]]; then
    if [[ "$D_MIN" == "0" ]]; then
      echo "✗ E2E_SKIP_BUILD=1 但没有 dist（$D_PORTAL / $D_ADMIN）→ 无从预览" >&2; exit 7
    fi
    if [[ "$D_MIN" -lt "$SRC_NEWEST" ]]; then
      echo "✗ E2E_SKIP_BUILD=1 且 dist 比源码旧（dist $(fmt_epoch0 "$D_MIN") < 源码 $(fmt_epoch0 "$SRC_NEWEST")）→" >&2
      echo "  会跑**旧产物**得出假结论。去掉 E2E_SKIP_BUILD，或先 bash scripts/e2e-preview.sh build" >&2
      exit 7
    fi
    echo "▶ [build] 复用现有 dist（dist $(fmt_epoch0 "$D_MIN") ≥ 源码 $(fmt_epoch0 "$SRC_NEWEST")，E2E_SKIP_BUILD=1）"
  else
    # 每次都重建：vite build 只要几十秒，换来「产物 = 当前工作区」这条确定的锚
    echo "▶ [build] 构建两个前端（不带 CDN_BASE，产物引用本地 /assets）"
    _bt0=$(date +%s)
    if ! bash "$REPO_DIR/scripts/e2e-preview.sh" build; then
      echo "✗ [build] 前端构建失败 —— 不跑（日志 ${E2E_LOG_DIR:-/tmp}/e2e-build-{portal,admin}.log）" >&2
      echo "  **不会**回落到 dev：那样等于把「dev 的结论」冒充成「build 的结论」（静默回落 = 假快）。" >&2
      echo "  排查完重跑即可；要临时用 dev 请显式 E2E_FRONT_MODE=dev。" >&2
      exit 8
    fi
    _bt1=$(date +%s)
    echo "▶ [build] 构建耗时 $((${_bt1}-${_bt0}))s"
  fi

  echo "▶ [build] 起 preview（shard a → 5283/5285，b → 5293/5295；反代 /portal|/admin|/qiqi-mall）"
  # 退出（正常/失败/中断）都精确停这 4 个 unit —— 不用 pkill -f，别碰别人。
  # trap 必须在 start **之前**挂：start 中途失败（端口被占/单元起不来）时已经起来的那些
  # preview 也要被收掉，否则留下孤儿占着 5283/5285/5293/5295，下一次跑就会「跳过别人的进程」。
  cleanup_preview() { bash "$REPO_DIR/scripts/e2e-preview.sh" stop >/dev/null 2>&1 || true; }
  trap cleanup_preview EXIT
  if ! bash "$REPO_DIR/scripts/e2e-preview.sh" start; then
    echo "✗ [build] preview 没起来（端口被占或 dist 不对，错误见上）—— 不跑，免得打到空端口上出一片假红。" >&2
    exit 8
  fi
fi

# 前置：两个 shard 的服务必须在跑，否则测试会指着空气打
missing=0
for s in "${SHARDS[@]:$SHARD_FROM:$WANT_SHARDS}"; do
  IFS=: read -r name pport aport backend <<< "$s"
  for port in $((pport+PORT_OFFSET)) $((aport+PORT_OFFSET)) "$backend"; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/" 2>/dev/null || true)"
    if [[ "$code" == "000" ]]; then
      if [[ "$FRONT_MODE" == "build" ]]; then
        echo "✗ shard $name 的端口 $port 没响应；先跑 scripts/start-e2e-backends.sh + scripts/e2e-preview.sh start" >&2
      else
        echo "✗ shard $name 的端口 $port 没响应；先跑 scripts/start-e2e-backends.sh + start-e2e-frontends.sh" >&2
      fi
      missing=1
    fi
  done
done
[[ "$missing" == "1" ]] && exit 2

# ════════════════════════════════════════════════════════════════════════════
# preflight 被测物版本锚（硬校验，任一不满足就 die）
#
# 为什么要有这一段（真实事故 2026-11-12）：
#   两个分片的 JVM 从 19:05 起就没重启过，而 target/classes 在 21:58 / 22:26 / 22:43
#   被反复重新编译 —— 19:05~22:33 这 3.5 小时里，**所有 e2e 结论都跑在旧字节码上**，
#   却一直被当成「已验收」。修复被验成「没修好」、没修的被验成「好了」，全是假阴性/假阳性。
#   根因是两件事各自都能「看起来成功」：systemctl restart 不编译（把旧代码再跑一遍），
#   mvn compile 不重启（写进 target/classes 的字节码根本没人加载）。
#   注意：别人的 `mvn -o test` 也会写 target/classes —— 所以「我编译过」不代表「被测物换了」。
#
# 三根锚，一根都不能少：
#   A 代码锚：e2e 后端进程启动时间 ≥ target/classes 里最新 .class 的 mtime
#             判定逻辑与 scripts/restart-e2e-backends.sh --check-only 完全一致（同一套事实、同一套口径），
#             这里只是按「本次实际要用的 shard」逐条打出来，不另立一套判定。
#   B 健康锚：/api/health 返回 200 **且** body 含 "status":"ok"
#             本服务对未知路径也回 200（catch-all），所以「端口通」≠「健康」，必须看 body。
#   C 独占锚：还有别的 e2e 跑批在跑 → 只醒目告警、不拦（并发常常是另一个 agent 在跑，
#             拦住会互相卡死；但必须让人一眼看见「结论可能互相干扰」）。
#
# 跳过开关：E2E_SKIP_VERSION_CHECK=1（只给明确知道自己在干什么的场景，跳过时打大警告）。
# 输出刻意保持 3~5 行，不刷屏。
# ════════════════════════════════════════════════════════════════════════════
CLASSES_DIR="$REPO_DIR/qiqi-mall-server/target/classes"
fmt_epoch() { [[ "${1:-0}" == "0" ]] && echo "-" || date -d "@$1" '+%F %T'; }

# 进程启动时刻：/proc/<pid> 目录的 mtime 就是进程启动时刻（秒级），与 restart-e2e-backends.sh 同源
pid_start_epoch() {
  local pid="$1"
  [[ "$pid" =~ ^[0-9]+$ ]] && [[ "$pid" != "0" ]] && [[ -d "/proc/$pid" ]] || { echo 0; return 0; }
  stat -c %Y "/proc/$pid" 2>/dev/null || echo 0
}
# 分片 → 后端 PID：优先 systemd 单元；单元被 --collect 回收时退回按监听端口反查
pid_of_shard() {
  local name="$1" port="$2" pid=""
  pid="$(systemctl show -p MainPID --value "qiqi-e2e-server-$name" 2>/dev/null || true)"
  if [[ ! "$pid" =~ ^[0-9]+$ ]] || [[ "$pid" == "0" ]]; then
    pid="$(ss -ltnpH "sport = :$port" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
  fi
  echo "${pid:-0}"
}
# 健康：状态码 200 且 body 含 "status":"ok"（只探端口通 = 假健康，见脚本头 ③ 的说明）
health_ok() {
  local port="$1"
  [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/api/health" 2>/dev/null || true)" == "200" ]] \
    && [[ "$(curl -s --max-time 3 "http://127.0.0.1:$port/api/health" 2>/dev/null || true)" == *'"status":"ok"'* ]]
}
anchor_die() {
  echo "✗ preflight 未通过：$*" >&2
  echo "  只想显式跳过（结论自负）：E2E_SKIP_VERSION_CHECK=1 bash scripts/e2e-run.sh ..." >&2
  exit 7
}

if [[ "${E2E_SKIP_VERSION_CHECK:-0}" == "1" ]]; then
  cat >&2 <<'SKIP_EOF'

████████████████████████████████████████████████████████████████████████
⚠  E2E_SKIP_VERSION_CHECK=1：已**跳过**被测物版本锚（代码锚 A / 健康锚 B / 独占锚 C）
   现在跑出来的结论可能是「旧字节码 / 半启动后端 / 并发干扰」上的假绿，不要拿它当验收。
   只有明确知道自己在干什么（例：故意验证旧字节码的行为）才带这个开关。
████████████████████████████████████████████████████████████████████████

SKIP_EOF
else
  echo "▶ preflight 被测物版本锚（跳过用 E2E_SKIP_VERSION_CHECK=1）"

  # ── A 代码锚 ──────────────────────────────────────────────────────────────
  NEWEST_CLASS_MTIME="$(find "$CLASSES_DIR" -name '*.class' -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1 || true)"
  NEWEST_CLASS_MTIME="${NEWEST_CLASS_MTIME:-0}"
  [[ "$NEWEST_CLASS_MTIME" == "0" ]] && anchor_die "找不到 $CLASSES_DIR 下的 .class —— 后端还没编译过；先跑：bash scripts/restart-e2e-backends.sh"
  code_desc=""
  for s in "${SHARDS[@]:$SHARD_FROM:$WANT_SHARDS}"; do
    IFS=: read -r name pport aport backend <<< "$s"
    pid="$(pid_of_shard "$name" "$backend")"
    st="$(pid_start_epoch "$pid")"
    if [[ "$st" == "0" ]]; then
      anchor_die "shard $name 拿不到后端进程启动时刻（PID=${pid}，端口 $backend）→ 无法证明它加载的是新字节码。
  排查：systemctl status qiqi-e2e-server-$name --no-pager -l；bash scripts/restart-e2e-backends.sh（会先 mvn compile 再重启）"
    fi
    if [[ "$st" -lt "$NEWEST_CLASS_MTIME" ]]; then
      anchor_die "e2e 后端 PID $pid 启动于 $(fmt_epoch "$st")，而 target/classes 最新编译于 $(fmt_epoch "$NEWEST_CLASS_MTIME")（更新）→ 加载的是旧字节码。
  请先跑：bash scripts/restart-e2e-backends.sh   （它会先 mvn compile 再重启）"
    fi
    code_desc+="${code_desc:+，}$name@PID$pid 启动 $(fmt_epoch "$st")"
  done
  # 空区间守卫：shard 区间如果解析成 0 个（SHARD_FROM 越界），上面两个循环会一次都不跑、
  # 然后「全绿」通过 —— 那正是「什么都没校验却看起来通过了」，必须当成失败。
  [[ -n "$code_desc" ]] || anchor_die "本次 shard 区间解析为空（SHARD_FROM=${SHARD_FROM} WANT_SHARDS=${WANT_SHARDS}）→ 一个被测物都没校验到"
  echo "  ✓ A 代码锚：$code_desc ≥ 最新 class $(fmt_epoch "$NEWEST_CLASS_MTIME")"

  # ── B 健康锚 ──────────────────────────────────────────────────────────────
  health_desc=""
  for s in "${SHARDS[@]:$SHARD_FROM:$WANT_SHARDS}"; do
    IFS=: read -r name pport aport backend <<< "$s"
    if ! health_ok "$backend"; then
      anchor_die "shard $name 的后端 $backend /api/health 不是「200 + body 含 \"status\":\"ok\"」
  → 端口通但服务没起全（本服务对未知路径也回 200，只看状态码会被骗）。
  排查：bash scripts/restart-e2e-backends.sh；或 curl -s http://127.0.0.1:$backend/api/health"
    fi
    health_desc+="${health_desc:+/}$backend"
  done
  echo "  ✓ B 健康锚：$health_desc /api/health 200 且 status=ok"

  # ── C 独占锚（只告警不拦）────────────────────────────────────────────────
  others=()
  for u in $(systemctl list-units --all --plain --no-legend 'run-*.scope' 2>/dev/null | awk '{print $1}'); do
    d="$(systemctl show "$u" -p Description --value 2>/dev/null || true)"
    [[ "$d" == *"e2e-inner-$TAG.sh"* ]] && continue
    others+=("$u")
  done
  for f in "$SCOPE_DIR"/*; do
    [[ -f "$f" ]] || continue
    ot="$(basename "$f")"
    [[ "$ot" == "$TAG" ]] && continue
    others+=("tag:$ot")
  done
  if [[ "${#others[@]}" -gt 0 ]]; then
    echo "  ⚠ 检测到并发跑批：${others[*]}，结果可能互相干扰（本次用 shard ${SHARD_FROM}~$((SHARD_FROM + WANT_SHARDS - 1))，继续跑）"
  else
    echo "  ✓ C 独占锚：没有别的 e2e 跑批在跑"
  fi
fi

# ── 审核定时任务留痕（跑前一行）───────────────────────────────────────────────
# 为什么要打这一行：AuditScheduleJob 默认开启（application-dev.yml 里
# `qiqi.audit.schedule-enabled: ${AUDIT_SCHEDULE_ENABLED:true}`，启动 5 分钟后每分钟醒一次），
# 它会去捞 status=7 的种子单自动过审/冻结 —— 种子单被它吃掉之后，spec 报出来的是
# 「待审核单数量不对 / 找不到某笔单」，看着像 spec 不稳，其实是这条后台定时任务在动数据。
# 没有这行留痕，排查时会反复在 spec 里找一个不存在的 bug。
# 所以每次跑前把「生效值 + 最近一次 run」摆出来：看到 scheduleEnabled=true 就知道结论要打折。
echo "▶ 审核定时任务留痕（status=7 的种子单会被它间歇性吃掉；报错指错地方时先看这里）"
for s in "${SHARDS[@]:$SHARD_FROM:$WANT_SHARDS}"; do
  IFS=: read -r name _pport _aport _backend <<< "$s"
  _db="qiqi_mall_e2e_$name"
  # 生效值：单元环境变量优先（Environment=AUDIT_SCHEDULE_ENABLED=...），没设就是 application-dev.yml 的默认 true
  _eff="$(systemctl show -p Environment --value "qiqi-e2e-server-$name" 2>/dev/null | tr ' ' '\n' \
            | grep -E '^AUDIT_SCHEDULE_ENABLED=' | tail -1 || true)"
  if [[ -n "$_eff" ]]; then
    _val="scheduleEnabled=${_eff#AUDIT_SCHEDULE_ENABLED=}（来源：qiqi-e2e-server-$name 的 Environment）"
  else
    _val="scheduleEnabled=true（来源：application-dev.yml 默认 \${AUDIT_SCHEDULE_ENABLED:true}，单元未覆盖）"
  fi
  _latest="$(docker exec -i -e MYSQL_PWD="${MYSQL_PASSWORD:-qiqi123456}" "${MYSQL_CONTAINER:-qiqi-mysql}" \
    mysql --default-character-set=utf8mb4 -N -B -u"${MYSQL_USER:-root}" -e \
    "SELECT id, idem_key, total, auto_pass, manual, created_at
       FROM ${_db}.audit_run ORDER BY id DESC LIMIT 1;" 2>/dev/null || true)"
  # 用 -N -B（制表符分隔）直出列值：CONCAT 遇到任一 NULL 列会把整行拼成 NULL，看不出是「没跑过」还是「字段空」
  _latest="$(tr '\t' ' ' <<<"$_latest" | sed 's/  */ /g')"
  echo "  shard $name  $_val  |  latestRun(id idem_key total auto_pass manual created_at): ${_latest:-（audit_run 空或库不可达）}"
done

# 前置：重置库（独立库的最大好处就是可以随便重置）
#
# ⚠ 这一段的调用顺序被钉死了，别改成"只 reset 不重启"：
#   reset-e2e-db.sh 在**它自己内部**（步骤 5）就把「重启对应分片后端」做完 —— DROP DATABASE
#   会让被测服务 JVM 里缓存的主数据（ChannelOrderImporter.platformBuyerId() 的 member_id 等）
#   变成悬空外键，只 reset 不重启 ⇒ 连续两次跑批时第二次会在 业务/渠道-多平台接入/接入主链路
#   上红一次（code=1，fk_order_member）。所以这里：
#     · 不传 SKIP_BACKEND_RESTART（默认就是重启，见上方护栏：想跳过必须再加一层显式开关）；
#     · reset 的退出码**不再被 `|| true` 吞掉** —— reset/重启失败就停，绝不在半成品状态上跑批
#       （旧写法 `| grep ... || true` 会把失败静默变成"重置过了"，然后红在后面，指错地方）；
#     · 单分片只重置/重启自己那个分片，双分片走 --all（restart 脚本一次编译、两个分片一起起）。
_reset_log_re='^(▶|✓|✗|✅|❌|⚠|  ▶|  ✓|  ✗|  ·)'
run_reset() {
  local desc="$1"; shift
  local log rc=0
  log="$(mktemp /tmp/e2e-reset-XXXXXX.log)"
  echo "$desc"
  ( cd "$REPO_DIR" && "$@" ) >"$log" 2>&1 || rc=$?
  grep -E "$_reset_log_re" "$log" || true
  if [[ "$rc" -ne 0 ]]; then
    echo "✗ reset（含自动重启后端）失败 exit=$rc；完整日志：$log" >&2
    echo "  reset 与重启后端是同一个动作，只做一半必红（09/1 的 fk_order_member 就是这么来的）—— 不继续跑批。" >&2
    tail -25 "$log" >&2
    exit 8
  fi
  rm -f "$log"
}
if [[ "$DO_RESET" == "1" ]]; then
  # 单分片时只重置自己要用的那个库：`--all` 会把**另一个分片的库**也 TRUNCATE，
  # 而单分片跑批不拿机器级互斥锁（见下方 flock 段），可能正好把别人正在跑的 shard b 库清掉。
  # 重置本身一点没少（本次要用的库仍然是完整重置），只是不再误伤不用的那个。
  if [[ "$WANT_SHARDS" -eq 1 ]]; then
    _only_shard="$(printf '%s' "${SHARDS[@]:$SHARD_FROM:1}" | cut -d: -f1)"
    run_reset "▶ 重置 e2e 库（仅 shard $_only_shard）+ 重启该分片后端" \
      ./scripts/reset-e2e-db.sh "qiqi_mall_e2e_$_only_shard"
  else
    run_reset "▶ 重置 e2e 库（--all）+ 重启对应分片后端" \
      ./scripts/reset-e2e-db.sh --all
  fi
fi

# ── 分片策略 ────────────────────────────────────────────────────────────────
# weight（默认）：按用例数/历史耗时做 LPT 贪心装箱，直接把文件列给 playwright。
#   `--shard=i/N` 是按**文件数**均分的，23 个文件 12/11 看着公平，实测 a 9.2 分钟
#   跑完、b 要 19.7 分钟，墙钟被最慢的那半拖住，2 并发只有 1.38×。
# file：退回 playwright 自带的 --shard=i/N（要复现老行为时用）。
SHARD_MODE="${E2E_SHARD_MODE:-weight}"
MAPFILE=""
if [[ "$WANT_SHARDS" -gt 1 && "$SHARD_MODE" == "weight" && "${#PASS_ARGS[@]}" -eq 0 ]]; then
  MAPFILE="$(mktemp /tmp/e2e-shardmap-XXXX.tsv)"
  if [[ ${#AFFECTED_SPECS[@]} -gt 0 ]]; then
    # 子集模式：只在命中的文件里装箱（全量分片图会带上一堆这次不跑的文件）
    if ! node "$REPO_DIR/scripts/e2e-affected.mjs" --map --shards="$WANT_SHARDS" > "$MAPFILE"; then
      echo "⚠ --affected 子集分片失败，退回单分片跑整个子集" >&2
      MAPFILE=""; SHARD_MODE="file"
      WANT_SHARDS=1
      PASS_ARGS+=("${AFFECTED_SPECS[@]}")
    fi
  elif ! node "$REPO_DIR/scripts/e2e-shard.mjs" --shards="$WANT_SHARDS" > "$MAPFILE"; then
    echo "⚠ 权重分片失败，退回 --shard 按文件均分" >&2
    MAPFILE=""; SHARD_MODE="file"
  else
    echo "▶ 权重分片（LPT 贪心，按用例数/历史耗时）"
    E2E_SHARD_VERBOSE=1 node "$REPO_DIR/scripts/e2e-shard.mjs" --shards="$WANT_SHARDS" 2>&1 >/dev/null | sed 's/^/  /'
  fi
  # 空桶守卫：分片图里某个 shard 一行都没有时，内层脚本会「不带文件参数」启动 playwright
  # → playwright 会把**全部**用例都跑一遍（比全量还慢，还看不出来）。宁可退回单分片。
  if [[ -s "$MAPFILE" ]]; then
    _have=$(cut -f1 "$MAPFILE" | sort -u | wc -l)
    if [[ "$_have" -lt "$WANT_SHARDS" ]]; then
      echo "⚠ 分片图只覆盖 $_have 个 shard（要 $WANT_SHARDS 个）→ 退回单分片" >&2
      rm -f "$MAPFILE"; MAPFILE=""; SHARD_MODE="file"; WANT_SHARDS=1
      PASS_ARGS+=("${AFFECTED_SPECS[@]}")
    fi
  fi
fi

INNER="/tmp/e2e-inner-$TAG.sh"
cat > "$INNER" <<'INNER_EOF'
#!/usr/bin/env bash
# 在 scope 内部跑：按 shard 起 playwright，各自一套 env，收齐退出码
set -uo pipefail
REPO_DIR="$1"; shift
WANT_SHARDS="$1"; shift
WORKERS="$1"; shift
OUT_PREFIX="$1"; shift
TAG="$1"; shift          # 本次跑的 TAG（外层传进来）：日志名默认带它，并发跑批才不会互相覆盖
UNLOCKED="$1"; shift
PORT_OFFSET="${1:-0}"; shift     # build 模式下前端端口整体 +N（preview 那套），dev 模式是 0

# 日志后缀：默认用本次跑的 TAG（每次跑唯一）；显式 E2E_LOG_SUFFIX 优先（向后兼容）。
# 分隔符的 `-` 只在这里补一次，并把传入值的前导 `-` 去掉，
# 所以 E2E_LOG_SUFFIX=abc 和 E2E_LOG_SUFFIX=-abc 都落在 /tmp/e2e-shard-<shard>-abc.log，不会出现 `--`。
SFX="${E2E_LOG_SUFFIX:-$TAG}"
SFX="${SFX#-}"
cd "$REPO_DIR/e2e"

SHARDS=("a:5183:5185:8091" "b:5193:5195:8092" "c:5203:5205:8093" "d:5213:5215:8094")
MAPFILE="${E2E_MAPFILE:-}"
declare -A PIDS

i=0
for s in "${SHARDS[@]:$SHARD_FROM:$WANT_SHARDS}"; do
  IFS=: read -r name pport aport backend <<< "$s"
  i=$((i+1))
  out="${OUT_PREFIX:-/tmp/pwout}-$name"
  log="/tmp/e2e-shard-$name-$SFX.log"
  extra=()
  if [[ -n "${MAPFILE:-}" ]]; then
    # 权重分片：直接列该 shard 该跑的文件
    mapfile -t shard_files < <(awk -F'\t' -v sh="$name" '$1==sh {for(i=2;i<=NF;i++) print $i}' "$MAPFILE")
    extra+=("${shard_files[@]}")
  elif [[ "$WANT_SHARDS" -gt 1 ]]; then
    extra+=("--shard=$i/$WANT_SHARDS")
  fi
  PP=$((pport+PORT_OFFSET)); AP=$((aport+PORT_OFFSET))
  echo "▶ shard $name: portal→$PP / admin→$AP / api→$backend  输出 $out  日志 $log"
  env PORTAL_BASE="http://127.0.0.1:$PP" \
      ADMIN_BASE="http://127.0.0.1:$AP" \
      API_BASE="http://127.0.0.1:$backend" \
      E2E_WORKERS="$WORKERS" \
      E2E_SHARD_NAME="$name" \
      E2E_OUT_DIR="$out" \
      E2E_HTML_DIR="report/html/$name" \
      npx playwright test "${extra[@]}" "$@" > "$log" 2>&1 &
  PIDS[$name]=$!
done

fail=0
for name in "${!PIDS[@]}"; do
  if wait "${PIDS[$name]}"; then
    echo "✓ shard $name 通过"
  else
    echo "✗ shard $name 失败（日志 /tmp/e2e-shard-$name-$SFX.log）"
    fail=1
  fi
done

echo
echo "──── 各 shard 摘要 ────"
for s in "${SHARDS[@]:$SHARD_FROM:$WANT_SHARDS}"; do
  name="${s%%:*}"
  printf '%-2s %s\n' "$name" "$(grep -E '^\s+[0-9]+ (passed|failed|flaky|skipped)' /tmp/e2e-shard-$name-$SFX.log 2>/dev/null | tail -3 | tr '\n' ' ')"
  grep -E '✘|✗.*›' /tmp/e2e-shard-$name-$SFX.log 2>/dev/null | head -5
done
exit "$fail"
INNER_EOF
chmod +x "$INNER"

# 内存限额按「并发浏览器数」缩放：一组 chrome+node 实测约 700~800MB，
# 3G 的 MemoryHigh 只够 2 并发；4 并发时被限流到每条用例 53 秒（串行才 17 秒），
# 实测 high 事件飙到 160 万次、墙钟反而变长。所以按 shard 数给额度：
#   1~2 shard → high 3G / max 4G
#   3~4 shard → high 6G / max 8G
# 可用 E2E_MEM_HIGH / E2E_MEM_MAX 覆盖。注意：这是**本次 run 的 scope** 额度，
# 同时 qiqi-e2e.slice 自己的 MemoryHigh/Max 也不能比它小，否则照样限流。
# 注意：真正决定内存的是**并发浏览器数** = shard 数 × 每 shard worker 数，
# 不是 shard 数本身。原来只按 shard 数缩放，于是「2 shard × 2 workers」（=4 个浏览器，
# 与「4 shard × 1 worker」并发数完全一样）只拿到 3G，注定被 high 限流。
CONCURRENCY=$(( WANT_SHARDS * WORKERS ))
if [[ "$CONCURRENCY" -le 2 ]]; then
  DEF_HIGH=3G; DEF_MAX=4G
else
  DEF_HIGH=6G; DEF_MAX=8G
fi
LIMITS=(--property=MemoryMax="${E2E_MEM_MAX:-$DEF_MAX}" --property=MemoryHigh="${E2E_MEM_HIGH:-$DEF_HIGH}" --property=MemorySwapMax=1G)

# ── 机器级互斥：全量跑必须独占 ──────────────────────────────────────────────
# 为什么：两个 agent 同时跑全量会互相重置库、抢 4 个后端和 CPU，两边都拿不到可信结果
# （实测被对方的重置打断过 3 次，Terminated 143）。
# 单条 spec（带文件过滤，WANT_SHARDS=1）不加锁，方便调试时和别人的全量并行。
# 锁在 scope 外层拿：等锁期间不占 e2e slice 的内存额度。
QUIET_LOCK=/tmp/e2e-quiet.lock
LOCK_WAIT="${E2E_LOCK_WAIT:-1800}"
if [[ "$WANT_SHARDS" -gt 1 ]]; then
  exec 9>"$QUIET_LOCK"
  if ! flock -w "$LOCK_WAIT" 9; then
    echo "✗ 等机器级互斥锁超时（$QUIET_LOCK）：现在有别的全量在跑。" >&2
    echo "  看占用者： fuser -v $QUIET_LOCK   /   ps -ef | grep playwright" >&2
    exit 3
  fi
  echo "▶ 已拿到机器级互斥锁 $QUIET_LOCK（同一时刻只有一个全量）"
fi

echo "▶ 在 $SLICE 里跑（shards=$WANT_SHARDS, workers/shard=$WORKERS）"
START=$(date +%s)
set +e
if [[ "${E2E_UNLOCKED:-1}" == "1" ]]; then
  E2E_MAPFILE="$MAPFILE" systemd-run --scope --quiet --slice="$SLICE" "${LIMITS[@]}" -- \
    "$INNER" "$REPO_DIR" "$WANT_SHARDS" "$WORKERS" "$OUT_PREFIX" "$TAG" 1 "$PORT_OFFSET" "${PASS_ARGS[@]}"
else
  E2E_MAPFILE="$MAPFILE" systemd-run --scope --quiet --slice="$SLICE" "${LIMITS[@]}" -- \
    flock /tmp/e2e.lock "$INNER" "$REPO_DIR" "$WANT_SHARDS" "$WORKERS" "$OUT_PREFIX" "$TAG" 0 "$PORT_OFFSET" "${PASS_ARGS[@]}"
fi
rc=$?
set -e
ELAPSED=$(( $(date +%s) - START ))

# 把本次 run 的 scope 名记下来（供 --kill-stale 精确清理）。
# 注意别用 `... | head -1`：找到目标时 head 提前关管道会给上游 SIGPIPE(141)，
# 在 set -e 下会让整个脚本以 1 退出——表现就是「83 条全绿却 exit 1」，踩过一次。
for _ in 1 2 3; do
  u=""
  for x in $(systemctl list-units --all --plain --no-legend 'run-*.scope' 2>/dev/null | awk '{print $1}'); do
    d="$(systemctl show "$x" -p Description --value 2>/dev/null || true)"
    if [[ "$d" == *"e2e-inner-$TAG.sh"* ]]; then u="$x"; break; fi
  done
  if [[ -n "$u" ]]; then echo "$u" > "$SCOPE_DIR/$TAG"; break; fi
  sleep 0.5
done || true
rm -f "$INNER" "${MAPFILE:-}" 2>/dev/null || true

echo
echo "════ 总耗时 $((ELAPSED/60))m$((ELAPSED%60))s  退出码 $rc ════"
exit "$rc"
