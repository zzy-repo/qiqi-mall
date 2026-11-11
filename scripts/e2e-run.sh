#!/usr/bin/env bash
#
# 跑 e2e 的唯一入口：两个 shard 并行，各自一套「后端 + 前端 + 库」，整体关进 qiqi-e2e.slice。
#
#   scripts/e2e-run.sh                                  # 全量：2 shard 并行，跑前自动重置两个 e2e 库
#   scripts/e2e-run.sh "tests/scenarios/03-商家-履约/1-订单审核与发货.spec.js"
#   scripts/e2e-run.sh --no-reset                        # 不重置库（调试单条时快一些）
#   E2E_SHARDS=1 scripts/e2e-run.sh                      # 降级：单 shard 单后端
#   E2E_WORKERS=2 scripts/e2e-run.sh                     # 每个 shard 内再开 2 个 worker
#
# 三个「为什么」：
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
# 注意：并行靠的是「shard × 库隔离」，不是多 worker 抢一个库；E2E_WORKERS 加大只适合
#       那些内部不互相踩数据的 spec 集合。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLICE="qiqi-e2e.slice"
LIMITS=(--property=MemoryMax=4G --property=MemoryHigh=3G --property=MemorySwapMax=1G)

# shard → portal端口 / admin端口 / 后端端口
SHARDS=("a:5183:5185:8091" "b:5193:5195:8092")

WANT_SHARDS="${E2E_SHARDS:-2}"
WORKERS="${E2E_WORKERS:-1}"
DO_RESET=1
PASS_ARGS=()
OUT_PREFIX=""

for a in "$@"; do
  case "$a" in
    --no-reset) DO_RESET=0 ;;
    --output=*) OUT_PREFIX="${a#--output=}" ;;     # 我们自己按 shard 加后缀，别让两进程抢同一目录
    --list) LIST=1; PASS_ARGS+=("$a") ;;
    *) PASS_ARGS+=("$a") ;;
  esac
done

# 单条 spec / 带路径过滤：--shard 会把它只分给一个 shard，另一个 shard 报「No tests found」
# 并以非零退出，看起来像失败。所以只要给了文件过滤，就自动退回单 shard（除非显式指定 E2E_SHARDS）。
if [[ -z "${E2E_SHARDS:-}" ]]; then
  for a in "${PASS_ARGS[@]:-}"; do
    [[ "$a" == -* ]] && continue
    WANT_SHARDS=1
    break
  done
fi

if [[ "${LIST:-0}" == "1" ]]; then
  echo "slice: $SLICE  ${LIMITS[*]}"
  echo "shards: $WANT_SHARDS (每 shard workers=$WORKERS)  库: qiqi_mall_e2e_a/_b  redis-db: 1/2"
  echo "flock: $([[ "${E2E_UNLOCKED:-1}" == "1" ]] && echo 关闭（独立库） || echo /tmp/e2e.lock)"
  exit 0
fi

# 前置：两个 shard 的服务必须在跑，否则测试会指着空气打
missing=0
for s in "${SHARDS[@]:0:$WANT_SHARDS}"; do
  IFS=: read -r name pport aport backend <<< "$s"
  for port in "$pport" "$aport" "$backend"; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/" 2>/dev/null || true)"
    if [[ "$code" == "000" ]]; then
      echo "✗ shard $name 的端口 $port 没响应；先跑 scripts/start-e2e-backends.sh + start-e2e-frontends.sh" >&2
      missing=1
    fi
  done
done
[[ "$missing" == "1" ]] && exit 2

# 前置：重置库（独立库的最大好处就是可以随便重置）
if [[ "$DO_RESET" == "1" ]]; then
  echo "▶ 重置 e2e 库"
  ( cd "$REPO_DIR" && ./scripts/reset-e2e-db.sh --all ) | grep -E '^▶|^✓|^✅|^❌' || true
fi

INNER="$(mktemp /tmp/e2e-inner-XXXX.sh)"
cat > "$INNER" <<'INNER_EOF'
#!/usr/bin/env bash
# 在 scope 内部跑：按 shard 起 playwright，各自一套 env，收齐退出码
set -uo pipefail
SFX="${E2E_LOG_SUFFIX:-}"
REPO_DIR="$1"; shift
WANT_SHARDS="$1"; shift
WORKERS="$1"; shift
OUT_PREFIX="$1"; shift
UNLOCKED="$1"; shift
cd "$REPO_DIR/e2e"

SHARDS=("a:5183:5185:8091" "b:5193:5195:8092")
declare -A PIDS

i=0
for s in "${SHARDS[@]:0:$WANT_SHARDS}"; do
  IFS=: read -r name pport aport backend <<< "$s"
  i=$((i+1))
  out="${OUT_PREFIX:-/tmp/pwout}-$name"
  log="/tmp/e2e-shard-$name${E2E_LOG_SUFFIX:-}.log"
  extra=()
  [[ "$WANT_SHARDS" -gt 1 ]] && extra+=("--shard=$i/$WANT_SHARDS")
  echo "▶ shard $name: 5183→$pport / admin→$aport / api→$backend  输出 $out"
  env PORTAL_BASE="http://127.0.0.1:$pport" \
      ADMIN_BASE="http://127.0.0.1:$aport" \
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
    echo "✗ shard $name 失败（日志 /tmp/e2e-shard-$name$SFX.log）"
    fail=1
  fi
done

echo
echo "──── 各 shard 摘要 ────"
for s in "${SHARDS[@]:0:$WANT_SHARDS}"; do
  name="${s%%:*}"
  printf '%-2s %s\n' "$name" "$(grep -E '^\s+[0-9]+ (passed|failed|flaky|skipped)' /tmp/e2e-shard-$name$SFX.log 2>/dev/null | tail -3 | tr '\n' ' ')"
  grep -E '✘|✗.*›' /tmp/e2e-shard-$name$SFX.log 2>/dev/null | head -5
done
exit "$fail"
INNER_EOF
chmod +x "$INNER"

echo "▶ 在 $SLICE 里跑（shards=$WANT_SHARDS, workers/shard=$WORKERS）"
START=$(date +%s)
set +e
if [[ "${E2E_UNLOCKED:-1}" == "1" ]]; then
  systemd-run --scope --quiet --slice="$SLICE" "${LIMITS[@]}" -- \
    "$INNER" "$REPO_DIR" "$WANT_SHARDS" "$WORKERS" "$OUT_PREFIX" 1 "${PASS_ARGS[@]}"
else
  systemd-run --scope --quiet --slice="$SLICE" "${LIMITS[@]}" -- \
    flock /tmp/e2e.lock "$INNER" "$REPO_DIR" "$WANT_SHARDS" "$WORKERS" "$OUT_PREFIX" 0 "${PASS_ARGS[@]}"
fi
rc=$?
set -e
ELAPSED=$(( $(date +%s) - START ))
rm -f "$INNER"

echo
echo "════ 总耗时 $((ELAPSED/60))m$((ELAPSED%60))s  退出码 $rc ════"
exit "$rc"
