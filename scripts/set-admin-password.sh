#!/usr/bin/env bash
#
# 用「真实口令」重设某个库里的 admin 账号密码（默认测试库 qiqi_mall_test）。
#
#   ./scripts/set-admin-password.sh
#   ADMIN_PASSWORD='...' ./scripts/set-admin-password.sh
#   DB_NAME=qiqi_mall_test TABLE_NAME=admin ADMIN_USER=admin ./scripts/set-admin-password.sh
#
# 为什么需要它（安全回退的补丁）：
#   sql/testdb/02-seed.sql 里 admin 的口令哈希是**演示弱口令**（固定哈希，谁都能反推）。
#   只要有人跑一次 scripts/init-test-db.sh / scripts/reset-test-db.sh，生产后台的
#   admin 口令就从强口令被打回弱口令 —— 这是真实的安全回退。
#   正确做法不是把手写哈希塞进被 git 跟踪的种子文件（那样强口令就进了版本库，
#   而且种子是脚本生成的、下次重跑就丢），而是：**种子照旧是弱口令占位，
#   复位链路的最后一步用密钥文件（/root/.qiqi-secrets/admin.txt）覆盖掉**。
#
# 口令来源（没有任何默认弱口令）：
#   ADMIN_PASSWORD 环境变量 → 否则读 /root/.qiqi-secrets/admin.txt（600）→ 都没有就立刻失败。
#
# 安全边界（铁律）：
#   - 默认只处理 qiqi_mall_test；qiqi_mall（dev）与 qiqi_mall_e2e_*（e2e 独立库，
#     必须保持弱口令）**硬拒绝**，即使传参也不许改。
#   - 目标表/账号同样可用 TABLE_NAME / ADMIN_USER 覆盖，但都做白名单校验后再拼 SQL。
#   - 输出永不含口令明文（也不打印哈希），只打印「已更新 <库>.<表> 的 <账号>」。
#
# 幂等：先读出当前哈希做一次 matches。已经等于目标口令 → 直接退出，不写库、不换盐；
#       不一致才用**一条** UPDATE 改，改完再回读校验。
#
# 哈希算法：优先用项目自己的 Spring Security BCryptPasswordEncoder（jshell + 本地 m2 里的
#   spring-security-crypto jar，与 server 运行时同一个实现）；取不到 jar 就退回 python3 bcrypt
#   （$2b$，Spring 的 BCryptPasswordEncoder 能校验）。
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- 参数（都可覆盖）----
DB_NAME="${DB_NAME:-qiqi_mall_test}"
TABLE_NAME="${TABLE_NAME:-admin}"
ADMIN_USER="${ADMIN_USER:-admin}"

# MySQL 连接（与 init-test-db.sh / reset-test-db.sh 同一约定）。
# 注意：这里的 123456 是 **MySQL root 密码**（docker 里本地库的默认值），
# 与 admin 后台账号口令完全无关 —— admin 口令只能来自密钥文件或 ADMIN_PASSWORD。
CONTAINER="${MYSQL_CONTAINER:-qiqi-mysql}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-qiqi123456}"
SECRET_FILE="${ADMIN_SECRET_FILE:-/root/.qiqi-secrets/admin.txt}"

# ---- 1) 读口令：先环境变量，再密钥文件；空即失败（绝不回落到任何默认值）----
ADMIN_PASS="${ADMIN_PASSWORD:-$(cat "$SECRET_FILE" 2>/dev/null || true)}"
# 密钥文件通常带结尾换行；去掉尾部空白，避免把 \n 当成口令的一部分
ADMIN_PASS="${ADMIN_PASS%$'\n'}"
ADMIN_PASS="${ADMIN_PASS%$'\r'}"
if [ -z "${ADMIN_PASS:-}" ]; then
  echo "❌ 缺少 admin 密码" >&2
  echo "   请设置 ADMIN_PASSWORD，或写入 $SECRET_FILE（chmod 600）" >&2
  exit 1
fi

# ---- 2) 目标库护栏：dev 库与 e2e 库一律不许碰 ----
case "$DB_NAME" in
  qiqi_mall)
    echo "✗ 拒绝对 dev 库 qiqi_mall 改口令（本脚本只服务于测试库 qiqi_mall_test）" >&2
    exit 1
    ;;
  qiqi_mall_e2e_*)
    echo "✗ 拒绝对 e2e 库 $DB_NAME 改口令：e2e 独立库必须保持固定的演示弱口令" >&2
    exit 1
    ;;
esac
if [[ ! "$DB_NAME" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
  echo "✗ 库名不合法：$DB_NAME" >&2
  exit 1
fi
if [[ "$DB_NAME" != "qiqi_mall_test" && "${ALLOW_NON_TEST_DB:-0}" != "1" ]]; then
  echo "✗ 只处理 qiqi_mall_test（当前 $DB_NAME）；确实要改别的测试库请显式设 ALLOW_NON_TEST_DB=1" >&2
  exit 1
fi
if [[ ! "$TABLE_NAME" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
  echo "✗ 表名不合法：$TABLE_NAME" >&2
  exit 1
fi
if [[ -z "$ADMIN_USER" || "$ADMIN_USER" == *"'"* ]]; then
  echo "✗ 账号名不合法：$ADMIN_USER" >&2
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ 容器 $CONTAINER 没在运行，先 docker compose up -d mysql" >&2
  exit 1
fi

mysql_exec() { # 密码走 MYSQL_PWD；字符集必须显式 utf8mb4（容器里默认 latin1）
  docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$@"
}

# ---- 3) BCrypt 实现：优先 Spring 的 BCryptPasswordEncoder，退回 python3 bcrypt ----
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"
java_cp=""
if command -v jshell >/dev/null 2>&1; then
  crypto_jar="$(ls -1 "$M2_REPO"/org/springframework/security/spring-security-crypto/*/spring-security-crypto-*.jar 2>/dev/null | tail -1 || true)"
  jcl_jar="$(ls -1 "$M2_REPO"/org/springframework/spring-jcl/*/spring-jcl-*.jar 2>/dev/null | tail -1 || true)"
  if [ -n "$crypto_jar" ]; then
    java_cp="$crypto_jar${jcl_jar:+:$jcl_jar}"
  fi
fi

HASH_IMPL=""

# bcrypt_verify <hash> ：口令在 $ADMIN_PASS，返回 0=匹配 1=不匹配 2=实现不可用
bcrypt_verify() {
  local hash="$1" out=""
  if [ -n "$java_cp" ]; then
    out="$(QIQI_PW="$ADMIN_PASS" QIQI_HASH="$hash" jshell --class-path "$java_cp" -s - <<'JSHELL' 2>/dev/null || true
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
System.out.println("QIQI_MATCH=" + new BCryptPasswordEncoder().matches(System.getenv("QIQI_PW"), System.getenv("QIQI_HASH")));
/exit
JSHELL
)"
    case "$out" in
      *"QIQI_MATCH=true"*)  HASH_IMPL="spring"; return 0 ;;
      *"QIQI_MATCH=false"*) HASH_IMPL="spring"; return 1 ;;
    esac
  fi
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import bcrypt' >/dev/null 2>&1; then
    if QIQI_PW="$ADMIN_PASS" QIQI_HASH="$hash" python3 - <<'PY' >/dev/null 2>&1
import os, bcrypt, sys
ok = bcrypt.checkpw(os.environ["QIQI_PW"].encode(), os.environ["QIQI_HASH"].encode())
sys.exit(0 if ok else 1)
PY
    then HASH_IMPL="python"; return 0; else HASH_IMPL="python"; return 1; fi
  fi
  return 2
}

# bcrypt_hash ：输出一个 BCrypt 哈希（$2a$/$2b$，Spring 都能校验）
bcrypt_hash() {
  local out=""
  if [ -n "$java_cp" ]; then
    out="$(QIQI_PW="$ADMIN_PASS" jshell --class-path "$java_cp" -s - <<'JSHELL' 2>/dev/null || true
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
System.out.println("QIQI_HASH=" + new BCryptPasswordEncoder().encode(System.getenv("QIQI_PW")));
/exit
JSHELL
)"
    out="$(printf '%s\n' "$out" | sed -n 's/^QIQI_HASH=//p' | tail -1)"
    if [ -n "$out" ]; then HASH_IMPL="spring"; printf '%s' "$out"; return 0; fi
  fi
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import bcrypt' >/dev/null 2>&1; then
    out="$(QIQI_PW="$ADMIN_PASS" python3 - <<'PY' 2>/dev/null || true
import os, bcrypt
print(bcrypt.hashpw(os.environ["QIQI_PW"].encode(), bcrypt.gensalt(rounds=10, prefix=b"2b")).decode())
PY
)"
    out="$(printf '%s\n' "$out" | tail -1)"
    if [ -n "$out" ]; then HASH_IMPL="python"; printf '%s' "$out"; return 0; fi
  fi
  echo "✗ 生成 BCrypt 哈希失败：jshell+spring-security-crypto 与 python3 bcrypt 都不可用" >&2
  return 1
}

# ---- 4) 读当前哈希（并确认账号存在）----
cur_hash="$(mysql_exec -N -B -e "SELECT password FROM \`$DB_NAME\`.\`$TABLE_NAME\` WHERE username='$ADMIN_USER' LIMIT 1;" 2>/dev/null | tr -d '\r\n' || true)"
if [ -z "$cur_hash" ]; then
  echo "✗ $DB_NAME.$TABLE_NAME 里没有账号 $ADMIN_USER（种子没灌成功，或该库还没灌过种子）" >&2
  exit 1
fi

# ---- 5) 幂等：已经是目标口令就不动库 ----
set +e
bcrypt_verify "$cur_hash"
verified=$?
set -e
case "$verified" in
  0)
    echo "✓ $DB_NAME.$TABLE_NAME 的 $ADMIN_USER 已是密钥文件里的口令（实现：$HASH_IMPL），未改动"
    exit 0
    ;;
  1) : ;; # 需要更新
  *)
    echo "✗ 无法校验现有哈希：缺少 BCrypt 实现（jshell+spring-security-crypto 或 python3 bcrypt）" >&2
    exit 1
    ;;
esac

# ---- 6) 一条 UPDATE 改库 ----
new_hash="$(bcrypt_hash)"
if [ -z "$new_hash" ]; then
  echo "✗ 新哈希为空，中止" >&2
  exit 1
fi

mysql_exec <<SQL
UPDATE \`$DB_NAME\`.\`$TABLE_NAME\` SET password='$new_hash' WHERE username='$ADMIN_USER';
SQL

# ---- 7) 回读校验（读库里的真值，不用内存变量自证）----
after_hash="$(mysql_exec -N -B -e "SELECT password FROM \`$DB_NAME\`.\`$TABLE_NAME\` WHERE username='$ADMIN_USER' LIMIT 1;" 2>/dev/null | tr -d '\r\n' || true)"
set +e
bcrypt_verify "$after_hash"
after_ok=$?
set -e
if [ "$after_ok" -ne 0 ]; then
  echo "✗ 回读校验失败：更新后 $DB_NAME.$TABLE_NAME 的 $ADMIN_USER 无法用密钥文件口令校验通过" >&2
  exit 1
fi

echo "已更新 $DB_NAME.$TABLE_NAME 的 $ADMIN_USER（BCrypt/$HASH_IMPL，已回读校验）"
