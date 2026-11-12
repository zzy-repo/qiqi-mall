#!/usr/bin/env bash
#
# 重启 e2e 分片后端：编译 → 重启 → 等健康 → 反查字节码真的是新的。四条一步不能少、顺序不能换。
#
# ── 为什么必须「先编译、再 restart」，以及为什么最后还要反查 ──────────────────
# `systemctl restart qiqi-e2e-server-a` 只是把 JVM 重新拉起来，它加载的是
# qiqi-mall-server/target/classes 里**当时**那份字节码。**restart 不会编译**：
# 不先 mvn compile，重启只是「把旧代码再跑一遍」，而且它看起来完全成功 ——
# 端口通、健康 200、日志无异常，「一切都好」。
#
# 真实事故（2026-11-12）：两个分片进程 19:05 起来，target/classes 直到 21:58 才被编译，
# 中间那一整天所有 e2e 结论都是在**旧字节码**上得出的 —— 修复被验证成「没修好」、
# 没修的被验证成「好了」，全部是假阴性/假阳性，白跑几小时才发现。
#
# 所以本脚本把「编译」钉在第一步，并在最后一步**反查事实而不是相信过程**：
#   ① 进程启动时间 ≥ target/classes 里最新 class 的 mtime（进程必须是编译之后才起的）
#   ② jcmd GC.class_histogram 里关键类确实被加载（不是起了个空壳）
# 任一条不成立就非 0 退出 —— 「restart 了但其实还在跑旧代码」这件事不允许再静默发生。
#
# ── 用法 ───────────────────────────────────────────────────────────────────
#   ./scripts/restart-e2e-backends.sh              # 编译 + 重启 a(8091) + b(8092)
#   ./scripts/restart-e2e-backends.sh --only b     # 只重启 b 分片（并行作业时避开别人在用的那个）
#   ./scripts/restart-e2e-backends.sh --only a
#   ./scripts/restart-e2e-backends.sh --check-only # 只体检不重启：看现在的进程是不是在跑旧字节码
#
# ── 安全边界 ───────────────────────────────────────────────────────────────
# 只用 systemd unit 名（systemctl restart qiqi-e2e-server-X），**绝不** pkill -f / killall 宽匹配：
# 这台机器上同时有 dev 后端(8081)、容器栈(18080)、别人的 e2e 分片与录片进程在跑，
# 按命令行子串匹配会连带误杀别人的作业（本项目已踩过）。--only 就是为此存在的：
# 只想动自己那个分片时，另一个分片一根手指都不碰。
#
# ── 幂等 ───────────────────────────────────────────────────────────────────
# 重复跑没问题：每次都重新编译 + 重新拉起 + 重新校验；已经在跑的目标分片也是 restart（不是跳过），
# 所以「我改了代码，想确保分片跑的是新代码」这个用途永远成立。
# 唯一需要人介入的情况：systemd 的瞬态单元已经被 --collect 回收（unit not loaded），
# 此时 restart 无从下手，脚本会提示先跑 scripts/start-e2e-backends.sh 起一次。
#
# ── 前置 ───────────────────────────────────────────────────────────────────
# target/e2e-cp.txt（运行时 classpath）必须已存在 —— 由 scripts/start-e2e-backends.sh 用到的那份。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$REPO_DIR/qiqi-mall-server"
CLASSES_DIR="$SERVER_DIR/target/classes"
CP_FILE="$SERVER_DIR/target/e2e-cp.txt"
HEALTH_WAIT="${HEALTH_WAIT:-90}"          # 健康等待上限（秒），需求口径 90s

# 关键类：起得来但没加载这些类 = 要么旧字节码、要么上下文没初始化完，都不算「生效」
KEY_CLASSES=(StockLedgerService DeliveryFlowService ReturnApplyController)

ALL_SHARDS=("a:8091" "b:8092")
ONLY=""
CHECK_ONLY=0

EXPECT_ONLY=0
for arg in "$@"; do
  if [[ "$EXPECT_ONLY" == "1" ]]; then ONLY="$arg"; EXPECT_ONLY=0; continue; fi
  case "$arg" in
    --only)      EXPECT_ONLY=1 ;;
    --only=*)    ONLY="${arg#--only=}" ;;
    --check-only) CHECK_ONLY=1 ;;
    -h|--help)   sed -n '2,42p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *)
      echo "✗ 不认识的参数：$arg" >&2
      echo "  用法：$0 [--only a|b] [--check-only]" >&2
      exit 1
      ;;
  esac
done
if [[ "$EXPECT_ONLY" == "1" ]]; then
  echo "✗ --only 需要值：--only a / --only b / --only=a" >&2
  exit 1
fi

# 只允许 a/b：c/d 的库已经补表，但后端与前端还没接进系统，重启它们没有意义
unit_of() { echo "qiqi-e2e-server-$1"; }

TARGETS=()
if [[ -n "$ONLY" ]]; then
  case "$ONLY" in
    a|b)
      TARGETS=()
      for s in "${ALL_SHARDS[@]}"; do
        if [[ "${s%%:*}" == "$ONLY" ]]; then TARGETS+=("$s"); fi
      done
      ;;
    *) echo "✗ --only 只接受 a 或 b（收到「$ONLY」）" >&2; exit 1 ;;
  esac
else
  TARGETS=("${ALL_SHARDS[@]}")
fi

fail() { echo "✗ $*" >&2; }

# ---------------------------------------------------------------- 健康 / 事实

# /api/health 真身：{"service":"qiqi-mall-server","env":"dev","status":"ok"}
# 注意：本服务的未知路径也会回 200（catch-all），所以**不能只看状态码**，
# 必须同时看到 "status":"ok" —— 否则「端口被别的东西占着」也会被当成健康。
health_body() {
  curl -s --max-time 3 "http://127.0.0.1:$1/api/health" 2>/dev/null || true
}
health_ok() {
  local body
  body="$(health_body "$1")"
  [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$1/api/health" 2>/dev/null || true)" == "200" ]] \
    && [[ "$body" == *'"status":"ok"'* ]]
}

pid_of() { systemctl show -p MainPID --value "$(unit_of "$1")" 2>/dev/null || echo 0; }
start_epoch_of() {   # /proc/<pid> 的 mtime 就是进程启动时刻（秒级）
  local pid="$1"
  [[ "$pid" =~ ^[0-9]+$ ]] && [[ "$pid" != "0" ]] && [[ -d "/proc/$pid" ]] || { echo 0; return; }
  stat -c %Y "/proc/$pid" 2>/dev/null || echo 0
}
fmt_epoch() { [[ "${1:-0}" == "0" ]] && echo "-" || date -d "@$1" '+%F %T'; }

# ---------------------------------------------------------------- 前置检查

[[ -f "$CP_FILE" ]] || { fail "缺 $CP_FILE（运行时 classpath）：先跑 ./scripts/start-e2e-backends.sh 生成"; exit 1; }
[[ -d "$CLASSES_DIR" ]] || { fail "缺 $CLASSES_DIR：先跑一次 mvn compile"; exit 1; }
command -v jcmd >/dev/null || echo "⚠ 没有 jcmd（JDK 工具）：字节码校验将退化为「进程启动时间 ≥ class mtime」这一条"

# ---------------------------------------------------------------- 改前快照

echo "════ 改前状态 ════"
for s in "${TARGETS[@]}"; do
  name="${s%%:*}"; port="${s#*:}"; pid="$(pid_of "$name")"
  printf '  shard %-1s 单元 %-22s PID %-8s 启动 %-19s 健康 %s\n' \
    "$name" "$(unit_of "$name")" "${pid:-0}" "$(fmt_epoch "$(start_epoch_of "$pid")")" \
    "$(health_ok "$port" && echo ok || echo NO)"
done
if [[ -f "$CLASSES_DIR/com/qiqi/mall/stock/service/StockLedgerService.class" ]]; then
  echo "  最新 class（StockLedgerService）mtime：$(date -r "$CLASSES_DIR/com/qiqi/mall/stock/service/StockLedgerService.class" '+%F %T')"
fi

# ---------------------------------------------------------------- 步骤 0：编译前置的「过期字节码」体检
# --check-only：只回答「现在跑的是不是旧代码」，不动任何进程。
if [[ "$CHECK_ONLY" == "1" ]]; then
  newest="$(find "$CLASSES_DIR" -name '*.class' -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1)"
  echo
  echo "════ 字节码体检（--check-only，不重启）════"
  stale=0
  for s in "${TARGETS[@]}"; do
    name="${s%%:*}"; pid="$(pid_of "$name")"; st="$(start_epoch_of "$pid")"
    if [[ "${newest:-0}" -gt "$st" ]]; then
      printf '  shard %-1s PID %-8s 启动 %-19s ← 旧于最新 class %-19s ✗ 在跑旧字节码\n' \
        "$name" "$pid" "$(fmt_epoch "$st")" "$(fmt_epoch "$newest")"
      stale=1
    else
      printf '  shard %-1s PID %-8s 启动 %-19s ≥ 最新 class %-19s ✓\n' \
        "$name" "$pid" "$(fmt_epoch "$st")" "$(fmt_epoch "$newest")"
    fi
  done
  [[ "$stale" == "1" ]] && { echo "  → 需要 ./scripts/restart-e2e-backends.sh（会先编译再重启）" >&2; exit 4; }
  exit 0
fi

# ---------------------------------------------------------------- 步骤 1：编译（必须最先）
echo
echo "════ 步骤 1/4：编译（mvn -o -q -DskipTests compile）════"
compile_start="$(date +%s)"
if ! ( cd "$SERVER_DIR" && mvn -o -q -DskipTests compile ); then
  fail "编译失败 —— 分片后端还是旧字节码，**不要**继续 restart（那样只会把旧代码再跑一遍）"
  echo "  排查：① -o 是离线模式，依赖没缓存会直接失败；去掉 -o 试一次看要下什么" >&2
  echo "        ② 看上面 mvn 的编译错误（源码改坏了）" >&2
  exit 3
fi
echo "  ✓ 编译完成（$(( $(date +%s) - compile_start ))s）"

# 编译后的「最新 class 时刻」：后面所有分片的进程启动时间都必须 ≥ 它
NEWEST_CLASS_MTIME="$(find "$CLASSES_DIR" -name '*.class' -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1)"
NEWEST_CLASS_MTIME="${NEWEST_CLASS_MTIME:-0}"
echo "  最新 class mtime：$(fmt_epoch "$NEWEST_CLASS_MTIME")"

# ---------------------------------------------------------------- 步骤 2：重启
echo
echo "════ 步骤 2/4：重启（只用 systemd unit 名，不用 pkill -f）════"
for s in "${TARGETS[@]}"; do
  name="${s%%:*}"; unit="$(unit_of "$name")"
  load="$(systemctl show -p LoadState --value "$unit" 2>/dev/null || echo not-found)"
  if [[ "$load" != "loaded" ]]; then
    fail "单元 $unit 不存在（LoadState=$load）—— 瞬态单元被 --collect 回收后无法 restart"
    echo "  处理：先跑一次 ./scripts/start-e2e-backends.sh 把分片起起来，之后本脚本即可反复 restart" >&2
    exit 4
  fi
  echo "  ▶ systemctl restart $unit"
  if ! systemctl restart "$unit"; then
    fail "systemctl restart $unit 失败"
    echo "  排查：systemctl status $unit --no-pager -l；日志 /tmp/e2e-server-$name.log" >&2
    exit 5
  fi
done

# ---------------------------------------------------------------- 步骤 3：等健康（最多 90s）
echo
echo "════ 步骤 3/4：等健康（/api/health 200 且 body 含 \"status\":\"ok\"，最多 ${HEALTH_WAIT}s）════"
deadline=$(( $(date +%s) + HEALTH_WAIT ))
all_ok=0
while (( $(date +%s) < deadline )); do
  all_ok=1
  for s in "${TARGETS[@]}"; do
    name="${s%%:*}"; port="${s#*:}"
    health_ok "$port" || all_ok=0
  done
  if [[ "$all_ok" == "1" ]]; then break; fi
  sleep 2
done

# ---------------------------------------------------------------- 步骤 4：反查字节码（关键：不信过程，只信事实）
echo
echo "════ 步骤 4/4：反查字节码是不是真的新的 ════"
status=0
printf '  %-5s %-8s %-19s %-7s %s\n' '分片' 'PID' '启动时间' '健康' '关键类(jcmd 计数)' 
for s in "${TARGETS[@]}"; do
  name="${s%%:*}"; port="${s#*:}"; unit="$(unit_of "$name")"
  pid="$(pid_of "$name")"
  st="$(start_epoch_of "$pid")"
  h="$(health_ok "$port" && echo ok || echo NO)"

  # ① 进程启动时间必须 ≥ 最新 class mtime（否则 = 在跑编译前的旧字节码）
  if [[ "$st" == "0" ]]; then
    fail "shard $name 拿不到进程启动时间（PID=${pid:-0} 不在）"
    echo "  排查：systemctl status $unit --no-pager -l；日志 /tmp/e2e-server-$name.log" >&2
    status=1; continue
  fi
  if [[ "$st" -lt "$NEWEST_CLASS_MTIME" ]]; then
    fail "shard $name 的进程（启动 $(fmt_epoch "$st")）早于最新 class（$(fmt_epoch "$NEWEST_CLASS_MTIME")）→ **在跑旧字节码**"
    echo "  排查：restart 是否真的执行了（systemctl status $unit）；单元若被别的东西带起，先停再跑本脚本" >&2
    status=1; continue
  fi

  # ② 关键类确实被加载（起了空壳 / 加载了别的 classpath 都会露馅）
  cls=""
  if command -v jcmd >/dev/null; then
    hist="$(timeout 60 jcmd "$pid" GC.class_histogram 2>/dev/null || true)"
    if [[ -z "$hist" ]]; then
      cls="jcmd 失败(降级:仅看启动时间)"
      echo "  ⚠ shard $name：jcmd 拿不到 class histogram（attach 失败？），仅以启动时间判定" >&2
    else
      for c in "${KEY_CLASSES[@]}"; do
        n="$(grep -c "$c" <<<"$hist" || true)"
        cls+="$c=$n "
        if [[ "${n:-0}" == "0" ]]; then
          fail "shard $name：关键类 $c 没被加载 —— 后端可能没起完整，或 classpath 指错了目录"
          echo "  排查：确认 $CP_FILE 里的第一段是 $CLASSES_DIR；日志 /tmp/e2e-server-$name.log" >&2
          status=1
        fi
      done
    fi
  else
    cls="(无 jcmd)"
  fi

  printf '  %-5s %-8s %-19s %-7s %s\n' "$name" "$pid" "$(fmt_epoch "$st")" "$h" "$cls"
  [[ "$h" == "ok" ]] || { status=1; echo "  ✗ shard $name 健康不是 ok（端口 $port）" >&2; }
done

echo
if [[ "$status" != "0" ]]; then
  fail "有分片没通过校验 —— 结论：**现在不能拿它跑 e2e**（会变成旧字节码/半启动上的假结论）"
  echo "  排查顺序：① systemctl status qiqi-e2e-server-<x> --no-pager -l" >&2
  echo "            ② /tmp/e2e-server-<x>.log 尾部（启动异常/端口占用）" >&2
  echo "            ③ ss -ltnp | grep -E ':809[12] '（端口是不是被别的进程占着）" >&2
  exit 6
fi
echo "✓ 全部通过：编译 → 重启 → 健康 → 字节码校验，分片跑的就是最新代码"
