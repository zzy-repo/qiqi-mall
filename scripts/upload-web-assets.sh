#!/usr/bin/env bash
# =============================================================================
# scripts/upload-web-assets.sh —— 把 portal-web / admin-web 的构建产物（JS/CSS）
#                                 同步到 OSS（源站）→ 由 cdn.rose7.cn 对外提供
# =============================================================================
#
# 用法
#   scripts/upload-web-assets.sh                       # ★默认 plan：只比对，不写 OSS、不删对象
#   scripts/upload-web-assets.sh --apply               # 真上传（幂等：内容没变的对象一律跳过）
#   scripts/upload-web-assets.sh --apply --prune       # 真上传 + 删远端多余对象（老 hash 文件）
#   scripts/upload-web-assets.sh portal-web --apply    # 只处理一个（portal-web | admin-web）
#   scripts/upload-web-assets.sh --verify              # 只做 CDN 侧 HEAD 自检（不碰 OSS）
#   scripts/upload-web-assets.sh --dist /tmp/b portal-web --apply   # 指定构建目录（本地验证用）
#
#   环境变量
#     CDN_HOST=https://cdn.rose7.cn     CDN 域名（verify 用）
#     CRED_FILE=/root/.aliyun-qiqi/credentials.env
#     OSS_BUCKET=qiqi-mall-static  OSS_PREFIX=qiqi-mall  OSS_ENDPOINT=oss-cn-beijing.aliyuncs.com
#
# -----------------------------------------------------------------------------
# 为什么默认是 plan 而不是 --apply
# -----------------------------------------------------------------------------
# 这个 bucket（qiqi-mall-static）是线上商品图和静态资源的唯一源站，凭据还同时带
# CDN/DNS 权限（见 /root/.aliyun-qiqi/README.txt 第 5、6 节：默认禁止上传/覆盖/删除）。
# 所以写操作必须显式 --apply，跟 scripts/publish.sh 的 --dry-run 约定一致。
#
# -----------------------------------------------------------------------------
# 幂等性（跑十遍和跑一遍结果一样）
# -----------------------------------------------------------------------------
#   - 上传键 = <prefix>/<app>/<dist 内相对路径>，例如
#         qiqi-mall/portal-web/assets/index-zXXvaN8y.js
#     文件名带 content hash ⇒ 同一份产物重复上传时键相同、内容相同、头相同。
#   - 每个对象先 head_object：ETag(=MD5) 与 Content-Type/Cache-Control 都一致就跳过，
#     一致即「无需变更」，不做任何写操作（无时间戳抖动、无 CDN 无谓回源）。
#   - 只要有一项不一致（比如改了缓存策略），就整对象重传覆盖 —— 覆盖是原子的。
#   - --prune 只删「本地不存在」的对象，且严格限定在 <prefix>/<app>/ 之下，
#     绝不触碰 qiqi-mall/products/、qiqi-mall/seed/ 等其它前缀。
#
# -----------------------------------------------------------------------------
# 三个必须遵守的约定
# -----------------------------------------------------------------------------
#   1) 绝不上传 index.html（以及任何 *.html）：索引必须由容器提供，
#      否则「发版」就变成「刷 CDN 缓存」——那是另一套风险（发布秒级不可回滚）。
#      script 里对 *.html 是硬跳过 + 打印提示，没有开关能打开它。
#   2) assets/* 文件名带 hash ⇒ Cache-Control: public, max-age=31536000, immutable。
#      一年 + immutable，永不刷 CDN；改版靠 index.html 换 hash 文件名实现。
#   3) 必须显式写 Content-Type：oss2 不会猜扩展名，缺了就是 application/octet-stream，
#      浏览器对 module script 的 MIME 校验很严（text/html / octet-stream 直接拒绝执行）。
#
# -----------------------------------------------------------------------------
# ★ 红线：永远不要对 qiqi-mall-static 跑 upload-web-assets.sh --prune
# -----------------------------------------------------------------------------
#   原因：nginx 对 /assets/ 的兜底是 302 到 CDN，而边缘 Caddy 会给这条 302 也加
#         cache-control: public, max-age=31536000 → 浏览器可能长期缓存该 302；
#         一旦 prune 删掉旧 hash 对象，缓存里的 302 就指向 404 → 白屏复发。
#   如需清理旧 hash，必须先确认没有浏览器还缓存着对应旧 index，且要同时刷新该 302 的缓存。
#
# -----------------------------------------------------------------------------
# 前提（不做会白屏，见 RUNBOOK，不是本脚本能修的）
# -----------------------------------------------------------------------------
#   qiqi-mall-static 是 private bucket，CDN 走 l2_oss_key 私有回源鉴权；
#   实测该 bucket 的 GetBucketCORS = NoSuchCORSConfiguration（没有 CORS 规则）⇒
#   跨域 <script type="module" crossorigin> 会被浏览器拦掉。
#   上 CDN 之前必须先在 bucket 上加 CORS 规则，见脚本末尾 --verify 的提示与本文件【需求】节。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRED_FILE="${CRED_FILE:-/root/.aliyun-qiqi/credentials.env}"
OSS_BUCKET="${OSS_BUCKET:-qiqi-mall-static}"
OSS_PREFIX="${OSS_PREFIX:-qiqi-mall}"
OSS_ENDPOINT="${OSS_ENDPOINT:-oss-cn-beijing.aliyuncs.com}"
OSS_REGION="${OSS_REGION:-cn-beijing}"
CDN_HOST="${CDN_HOST:-https://cdn.rose7.cn}"

APPLY=0; PRUNE=0; VERIFY=0; DIST_OVERRIDE=""
APPS=()

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apply)  APPLY=1 ;;
    --plan)   APPLY=0 ;;
    --prune)  PRUNE=1 ;;
    --verify) VERIFY=1 ;;
    --dist)   DIST_OVERRIDE="${2:?--dist 需要目录}"; shift ;;
    -h|--help) usage; exit 0 ;;
    portal-web|admin-web) APPS+=("$1") ;;
    *) echo "未知参数: $1（--help 看用法）" >&2; exit 2 ;;
  esac
  shift
done
[ ${#APPS[@]} -gt 0 ] || APPS=(portal-web admin-web)
if [ -n "$DIST_OVERRIDE" ] && [ ${#APPS[@]} -ne 1 ]; then
  echo "--dist 只能和单个 app 一起用（当前: ${APPS[*]}）" >&2; exit 2
fi

# 每个 app 的 dist 目录 + OSS 键前缀
dist_for() {
  local app=$1
  if [ -n "$DIST_OVERRIDE" ]; then printf '%s' "$DIST_OVERRIDE"; else printf '%s/%s/dist' "$REPO_ROOT" "$app"; fi
}
prefix_for() { printf '%s/%s' "$OSS_PREFIX" "$1"; }

# ------------------------------------------------------------------ 前置检查
fail=0
for app in "${APPS[@]}"; do
  d=$(dist_for "$app")
  if [ ! -d "$d" ]; then
    echo "❌ $app 构建产物不存在: $d" >&2
    echo "   先构建：cd $REPO_ROOT/$app && CDN_BASE=$CDN_HOST/$(prefix_for "$app")/ npm run build" >&2
    fail=1
  elif [ ! -d "$d/assets" ]; then
    echo "❌ $d 下没有 assets/" >&2; fail=1
  fi
done
[ "$fail" -eq 0 ] || exit 1
if [ "$VERIFY" -eq 0 ] && [ ! -r "$CRED_FILE" ]; then
  echo "❌ 读不到凭据文件: $CRED_FILE" >&2; exit 1
fi

MODE_TXT="plan（不写 OSS）"
[ "$APPLY" -eq 1 ] && MODE_TXT="APPLY（真上传）"

# ------------------------------------------------------------------ OSS 同步
if [ "$VERIFY" -eq 0 ]; then
  sample_keys=$(mktemp)
  trap 'rm -f "$sample_keys"' EXIT

  # app:dist:prefix 三元组传给 python（argv，不进 shell 变量拼接，避免空格问题）
  pairs=()
  for app in "${APPS[@]}"; do pairs+=("$app:$(dist_for "$app"):$(prefix_for "$app")"); done

  python3 - "$APPLY" "$PRUNE" "$CRED_FILE" "$OSS_BUCKET" "$OSS_ENDPOINT" "$OSS_REGION" "$sample_keys" "${pairs[@]}" <<'PY'
# -*- coding: utf-8 -*-
"""dist 的 JS/CSS 同步到 OSS（不带 index.html）。只读凭据，任何回显都过 redact()。"""
import hashlib
import os
import sys

apply_mode = sys.argv[1] == "1"
prune_mode = sys.argv[2] == "1"
cred_path, bucket_name, endpoint, region = sys.argv[3:7]
sample_path = sys.argv[7]
targets = []
for spec in sys.argv[8:]:
    app, dist, prefix = spec.split(":", 2)
    targets.append((app, dist, prefix))

# 扩展名 → Content-Type。缺了 Content-Type 浏览器不会把 module script 当 JavaScript 执行。
CTYPE = {
    "js": "application/javascript; charset=utf-8",
    "mjs": "application/javascript; charset=utf-8",
    "css": "text/css; charset=utf-8",
    "map": "application/json; charset=utf-8",
    "json": "application/json; charset=utf-8",
    "svg": "image/svg+xml",
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "webp": "image/webp",
    "gif": "image/gif",
    "ico": "image/x-icon",
    "woff": "font/woff",
    "woff2": "font/woff2",
    "ttf": "font/ttf",
    "txt": "text/plain; charset=utf-8",
}
# 文件名带 content hash ⇒ 一年 + immutable（改版靠 index.html 换文件名，永不刷 CDN）
CACHE_HASHED = "public, max-age=31536000, immutable"
# 万一以后有非 hash 名字的静态文件（比如 favicon.svg 固定名），给个短点的缓存，别给一年
CACHE_STABLE = "public, max-age=3600"

SKIP_EXT = {"html", "htm"}   # ★硬红线：index.html 必须由容器提供


def load_creds(path):
    ak = sk = None
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip().strip('"').strip("'")
            if k == "ALI_AK":
                ak = v
            elif k == "ALI_SK":
                sk = v
    if not ak or not sk:
        raise SystemExit("credentials.env 缺 ALI_AK / ALI_SK")
    return ak, sk


AK, SK = load_creds(cred_path)


def redact(text):
    out = str(text)
    for s in (AK, SK):
        if s:
            out = out.replace(s, "***REDACTED***")
    return out


def headers_for(rel, hashed):
    ext = rel.rsplit(".", 1)[-1].lower() if "." in rel else ""
    return CTYPE.get(ext, "application/octet-stream"), (CACHE_HASHED if hashed else CACHE_STABLE)


def collect(dist):
    """返回 [(rel, abs_path, etag_local, ctype, cache)]；跳过 *.html。"""
    rows, skipped = [], []
    for dirpath, _dirs, files in os.walk(dist):
        for fn in sorted(files):
            abs_path = os.path.join(dirpath, fn)
            rel = os.path.relpath(abs_path, dist).replace(os.sep, "/")
            ext = fn.rsplit(".", 1)[-1].lower() if "." in fn else ""
            if ext in SKIP_EXT:
                skipped.append(rel)
                continue
            h = hashlib.md5()
            with open(abs_path, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
            # assets/ 下的文件名都带 hash；其余（favicon.svg 之类）用短缓存
            hashed = rel.startswith("assets/")
            ctype, cache = headers_for(rel, hashed)
            rows.append((rel, abs_path, h.hexdigest(), ctype, cache))
    return rows, skipped


import oss2  # noqa: E402

# endpoint 允许写主机名（线上用法）或完整 URL（本地 MinIO 自检用 http://127.0.0.1:9000）
ep = endpoint if endpoint.startswith(("http://", "https://")) else "https://" + endpoint
auth = oss2.Auth(AK, SK)
bucket = oss2.Bucket(auth, ep, bucket_name, region=region)

grand_up = grand_skip = grand_bytes = 0
samples = []
for app, dist, prefix in targets:
    rows, skipped = collect(dist)
    if not rows:
        print("  ❌ %s: %s 下没有任何可上传文件" % (app, dist))
        continue
    print("\n=== %s ===" % app)
    print("    本地 %d 个文件 / %.2f MB   →  oss://%s/%s/"
          % (len(rows), sum(os.path.getsize(r[1]) for r in rows) / 1048576.0, bucket_name, prefix))
    if skipped:
        print("    跳过（index 必须由容器提供，绝不上 CDN）: %s" % ", ".join(skipped))

    up = skip = up_bytes = 0
    actions = []
    for rel, abs_path, md5_local, ctype, cache in rows:
        key = prefix + "/" + rel
        size = os.path.getsize(abs_path)
        need = True
        reason = "新增"
        try:
            meta = bucket.head_object(key)
            etag = (meta.etag or "").strip('"').lower()
            same_body = (etag == md5_local) if "-" not in etag else (meta.content_length == size)
            same_ct = (meta.headers.get("Content-Type", "") == ctype)
            same_cache = (meta.headers.get("Cache-Control", "") == cache)
            if same_body and same_ct and same_cache:
                need, reason = False, "未变"
            else:
                miss = []
                if not same_body:
                    miss.append("内容")
                if not same_ct:
                    miss.append("Content-Type")
                if not same_cache:
                    miss.append("Cache-Control")
                reason = "更新(%s)" % "+".join(miss)
        except oss2.exceptions.NoSuchKey:
            pass
        actions.append((key, need, reason, size, ctype, cache))
        if need:
            up += 1
            up_bytes += size
            if apply_mode:
                bucket.put_object_from_file(
                    key, abs_path, headers={"Content-Type": ctype, "Cache-Control": cache}
                )
        else:
            skip += 1
        if len(samples) < 8:
            samples.append(key)

    for key, need, reason, size, ctype, cache in actions:
        flag = "↑" if need else "="
        print("    %s %-56s %8d  %-8s %s" % (flag, key, size, reason, cache))
    print("    → %s：上传 %d 个（%.2f MB），跳过 %d 个"
          % ("已执行" if apply_mode else "[plan] 将上传", up, up_bytes / 1048576.0, skip))

    # ---------------------------------------------------------- 清理多余对象
    if prune_mode:
        local = {(prefix + "/" + r[0]) for r in rows}
        stale = []
        listed = 0
        for obj in oss2.ObjectIterator(bucket, prefix=prefix + "/"):
            listed += 1
            if obj.key not in local:
                stale.append(obj.key)
        print("    远端 %d 个对象，本地不存在 %d 个" % (listed, len(stale)))
        for key in stale:
            print("    %s %s" % ("-" if apply_mode else "[plan] 将删除", key))
        if apply_mode and stale:
            for i in range(0, len(stale), 500):
                bucket.batch_delete_objects(stale[i:i + 500])
        if not apply_mode and stale:
            print("    （plan 模式未删除任何对象）")

    grand_up += up
    grand_skip += skip
    grand_bytes += up_bytes

with open(sample_path, "w", encoding="utf-8") as fh:
    fh.write("\n".join(samples))

print("\n合计：上传/覆盖 %d 个（%.2f MB），未变跳过 %d 个%s"
      % (grand_up, grand_bytes / 1048576.0, grand_skip,
         "" if apply_mode else "   ← plan 模式，OSS 未发生任何写入"))
PY
fi

# ------------------------------------------------------------------ CDN 侧自检
if [ "$VERIFY" -eq 1 ] || [ "$APPLY" -eq 1 ]; then
  echo
  echo "=== CDN 侧自检（HEAD $CDN_HOST，带 Origin 头看 CORS） ==="
  # 取每个 app 的入口 js + 一个 css 做样本；不存在的对象给 404，正常
  for app in "${APPS[@]}"; do
    d=$(dist_for "$app"); p=$(prefix_for "$app")
    for f in $(ls "$d/assets" 2>/dev/null | grep -E '^index-.*\.(js|css)$' | head -2); do
      url="$CDN_HOST/$p/assets/$f"
      hdrs=$(curl -sSI -m 10 -H 'Origin: https://mall.rose7.cn' "$url" 2>&1 || true)
      code=$(printf '%s' "$hdrs" | sed -n '1s/.* \([0-9][0-9][0-9]\).*/\1/p')
      acao=$(printf '%s' "$hdrs" | sed -n 's/^[Aa]ccess-[Cc]ontrol-[Aa]llow-[Oo]rigin: *//p' | tr -d '\r')
      ctype=$(printf '%s' "$hdrs" | sed -n 's/^[Cc]ontent-[Tt]ype: *//p' | tr -d '\r')
      cache=$(printf '%s' "$hdrs" | sed -n 's/^[Cc]ache-[Cc]ontrol: *//p' | tr -d '\r')
      auth=$(printf '%s' "$hdrs" | sed -n 's/^[Xx]-[Oo]ss-[Cc]dn-[Aa]uth: *//p' | tr -d '\r')
      printf '  %-72s HTTP=%s\n' "$p/assets/$f" "${code:-?}"
      printf '    Content-Type=%s  Cache-Control=%s  x-oss-cdn-auth=%s  ACAO=%s\n' \
        "${ctype:-<无>}" "${cache:-<无>}" "${auth:-<无>}" "${acao:-<无>}"
    done
  done
  cat <<'TIP'

  判读：
    HTTP=200 + x-oss-cdn-auth=success + Content-Type=application/javascript  → 源站与回源鉴权都没问题
    ACAO=<无>  ⇒ ★会白屏：module script 是 CORS 请求，缺 Access-Control-Allow-Origin 直接被浏览器拒绝执行。
                需要对桶 qiqi-mall-static 增加 CORS 规则（本脚本不改 OSS 配置，需人工/主线决策）：
                  来源 Origin : https://mall.rose7.cn, https://admin.rose7.cn（按实际入口域名加）
                  Methods     : GET, HEAD
                  Headers     : *
                  Expose      : ETag, Content-Length, Content-Type
                  MaxAge      : 3600
    Cache-Control 不是 max-age=31536000, immutable  ⇒ 检查上传时的 Cache-Control（本脚本写对了；若被 CDN 缓存规则覆盖，看 RUNBOOK 六节）
TIP
fi

if [ "$APPLY" -eq 0 ]; then
  echo
  echo "[plan] 未写 OSS。确认无误后加 --apply 执行；首次只传一个 app 更稳："
  echo "  $0 --dist /tmp/cdnbuild/portal-web portal-web --apply"
fi
