#!/usr/bin/env bash
# scripts/env-local.sh — 本机环境事实集中定义（可 source）
#
# 用法：
#   source scripts/env-local.sh              # 静默加载，只在探测失败时打 warn 到 stderr
#   source scripts/env-local.sh --check      # 加载后立刻打印自检表
#   env_local_check                          # 手动再跑一次自检
#
# 设计约定：
#   1. 只导出环境变量，不改变调用方的 shell 选项（不动 set -e/-u），
#      因此 `set -u` 下 source 一次也不会中断调用方。
#   2. 任何探测失败都不算错误：变量留空 + 一行 warn 到 stderr，
#      调用方自己用 [ -n "$VAR" ] 判断。
#   3. 已经非空的环境变量会被尊重（不会被覆盖），方便临时覆盖调试。
#   4. 不写入任何密钥明文，只写凭证“文件路径”。
#
# 变量清单：
#   CHROME_BIN           playwright 自带 chromium 可执行文件（headless 可直接跑）
#   MMDC                 mermaid-cli **12.x** 的 mmdc 可执行文件（v11 渲染结果不一致，必须 v12）
#   PUPPETEER_CFG        puppeteer/mermaid-cli 用的启动配置 JSON（--no-sandbox 等），不存在则自动生成
#   PUPPETEER_EXECUTABLE_PATH  让 puppeteer 复用 CHROME_BIN，避免再下一次 chromium
#   OSSUTIL_CONFIG       ossutil 配置文件（含 AK/SK，勿打印）
#   ALI_CRED_FILE        阿里云 AK/SK 的 env 格式文件（键名 ALI_AK / ALI_SK）
#   ALI_REGION           OSS/DNS/CDN 区域
#   ALI_OSS_ENDPOINT     OSS bucket 访问 endpoint
#   QIQI_ROOT            商城主项目根目录
#   DOCSIFY_ROOT         docsify 文档站根目录
#   ADMIN_PASSWORD_FILE  后台管理员口令文件路径（只给路径，绝不读内容）
#   E2E_NODE_MODULES     e2e 用例的 node_modules
#
# 说明：本文件由环境探查得出（2026-11），见 /root/.aliyun-qiqi/README.txt 的 OSS 部分。

# ---- 内部工具 ---------------------------------------------------------------
_env_local_warn() { printf '[env-local] WARN: %s\n' "$*" >&2; }

# 设置变量：已有非空值则保留
_env_local_set() {
    # $1 = 变量名，$2 = 值
    local _name="$1" _val="$2" _cur=""
    eval "_cur=\${$_name:-}"
    if [ -n "$_cur" ]; then
        return 0
    fi
    if [ -n "$_val" ]; then
        export "$_name=$_val"
    else
        export "$_name="
        _env_local_warn "$_name 探测失败，已留空"
    fi
}

# ---- CHROME_BIN：playwright 缓存目录里版本号最高的 chromium ------------------
_env_local_find_chrome() {
    local c
    c=$(ls -d /root/.cache/ms-playwright/chromium-*/chrome-linux64/chrome 2>/dev/null \
        | sort -V | tail -1)
    if [ -n "$c" ] && [ -x "$c" ]; then
        printf '%s' "$c"; return 0
    fi
    # 兜底：系统里其它 chromium/chrome
    for c in "$(command -v chromium 2>/dev/null)" \
             "$(command -v chromium-browser 2>/dev/null)" \
             "$(command -v google-chrome 2>/dev/null)"; do
        if [ -n "$c" ] && [ -x "$c" ]; then printf '%s' "$c"; return 0; fi
    done
    return 1
}

# ---- MMDC：必须是 mermaid-cli 12.x ------------------------------------------
_env_local_mmdc_version() {
    # 打印形如 12.2.0 的版本号，取不到则空
    [ -x "$1" ] || return 0
    "$1" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

_env_local_find_mmdc() {
    local cand ver
    for cand in \
        "${MMDC:-}" \
        /root/qiqi-mall/node_modules/.bin/mmdc \
        "/root/未分组/mall-学习项目/node_modules/.bin/mmdc" \
        /root/.npm/_npx/*/node_modules/.bin/mmdc
    do
        [ -n "$cand" ] || continue
        ver=$(_env_local_mmdc_version "$cand")
        case "$ver" in
            12.*) printf '%s' "$cand"; return 0 ;;
        esac
    done
    return 1
}

# ---- PUPPETEER_CFG：缺失则生成 ---------------------------------------------
_env_local_ensure_puppeteer_cfg() {
    local cfg="/tmp/puppeteer-cfg.json"
    if [ ! -s "$cfg" ]; then
        printf '%s\n' '{"args":["--no-sandbox","--disable-setuid-sandbox","--disable-dev-shm-usage"]}' \
            > "$cfg" 2>/dev/null || { _env_local_warn "无法生成 $cfg"; return 1; }
    fi
    printf '%s' "$cfg"
}

# ---- 主流程 -----------------------------------------------------------------
_env_local_chrome=$(_env_local_find_chrome || true)
_env_local_set CHROME_BIN "$_env_local_chrome"

_env_local_mmdc=$(_env_local_find_mmdc || true)
_env_local_set MMDC "$_env_local_mmdc"

_env_local_pcfg=$(_env_local_ensure_puppeteer_cfg || true)
_env_local_set PUPPETEER_CFG "$_env_local_pcfg"

# puppeteer 复用同一个 chromium，避免重复下载
if [ -n "${CHROME_BIN:-}" ]; then
    _env_local_set PUPPETEER_EXECUTABLE_PATH "$CHROME_BIN"
fi

# ---- 阿里云：只给路径与区域，绝不读密钥 ------------------------------------
_env_local_set ALI_CRED_FILE /root/.aliyun-qiqi/credentials.env
_env_local_set OSSUTIL_CONFIG /root/.aliyun-qiqi/ossutilconfig
_env_local_set ALI_REGION cn-beijing
_env_local_set ALI_OSS_ENDPOINT https://oss-cn-beijing.aliyuncs.com

for _f in "${ALI_CRED_FILE:-}" "${OSSUTIL_CONFIG:-}"; do
    if [ -n "$_f" ] && [ ! -r "$_f" ]; then
        _env_local_warn "凭证文件不可读：$_f"
    fi
done
# 记住这个坑：键名是 ALI_AK / ALI_SK，不是 ALIBABA_CLOUD_ACCESS_KEY_ID
if [ -r "${ALI_CRED_FILE:-/nonexistent}" ] && ! grep -q '^ALI_AK=' "$ALI_CRED_FILE" 2>/dev/null; then
    _env_local_warn "ALI_CRED_FILE 里没有 ALI_AK 键（见 /root/.aliyun-qiqi/README.txt）"
fi

# ---- 项目路径 ---------------------------------------------------------------
_env_local_set QIQI_ROOT /root/qiqi-mall
_env_local_set DOCSIFY_ROOT /root/docsify
_env_local_set ADMIN_PASSWORD_FILE /root/.qiqi-secrets/admin.txt
_env_local_set E2E_NODE_MODULES /root/qiqi-mall/e2e/node_modules

for _d in "${QIQI_ROOT:-}" "${DOCSIFY_ROOT:-}" "${E2E_NODE_MODULES:-}"; do
    if [ -n "$_d" ] && [ ! -d "$_d" ]; then
        _env_local_warn "目录不存在：$_d"
    fi
done
if [ -n "${ADMIN_PASSWORD_FILE:-}" ] && [ ! -r "$ADMIN_PASSWORD_FILE" ]; then
    _env_local_warn "口令文件不可读：$ADMIN_PASSWORD_FILE"
fi

unset _f _d _env_local_chrome _env_local_mmdc _env_local_pcfg

# ---- 自检 -------------------------------------------------------------------
env_local_check() {
    local name val missing=0
    for name in CHROME_BIN MMDC PUPPETEER_CFG ALI_CRED_FILE OSSUTIL_CONFIG \
                ALI_REGION ALI_OSS_ENDPOINT QIQI_ROOT DOCSIFY_ROOT \
                ADMIN_PASSWORD_FILE E2E_NODE_MODULES; do
        eval "val=\${$name:-}"
        if [ -n "$val" ]; then
            printf 'ok    %-22s %s\n' "$name" "$val"
        else
            printf 'MISS  %-22s (未探测到)\n' "$name"
            missing=$((missing + 1))
        fi
    done
    if [ "$missing" -eq 0 ]; then
        printf 'all %s vars ok\n' 11
    else
        printf '%s var(s) missing\n' "$missing"
    fi
    [ "$missing" -eq 0 ]
}

# `source scripts/env-local.sh --check` 或 ENV_LOCAL_CHECK=1 时自动自检
case "${1:-}" in
    --check|-c) env_local_check ;;
esac
if [ "${ENV_LOCAL_CHECK:-0}" = "1" ]; then env_local_check; fi

true
