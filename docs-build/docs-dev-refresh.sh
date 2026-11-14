#!/usr/bin/env bash
# dev 文档站一键刷新：改完 docs/** 或 build-material.mjs 后跑这一条，3027 立即可见。
#   3027 = Rspress **dev** 构建（/root/docs-rspress/doc_build_dev，含口语稿 + 12 篇私人笔记）
#          容器 `docs-rspress`（nginx:alpine，conf=/root/docs-rspress/_deploy/nginx.conf）
#   3028 = 旧 docsify 站（容器 `docsify`，只换了端口，供对照/回退）
#   3010 = Rspress prod（不动，生产随代码版本发布时再发）
set -uo pipefail

RSPRESS=/root/docs-rspress
OUT_DIR=$RSPRESS/doc_build_dev
BASE=http://100.113.135.55:3027
CHECK=/root/qiqi-mall/scripts/check-docs-site.mjs

cd "$RSPRESS" || exit 2

echo "▶ 构建 Rspress dev：npm run build:dev → doc_build_dev"
OUT=$(npm run build:dev 2>&1) || { echo "$OUT" | tail -40; echo "✗ dev 构建失败"; exit 1; }
echo "$OUT" | grep -E '图页|场景页|口语稿|缺视频|写入|built in|pages? built' | tail -6

# 抽查：首页 + 一个真实页面（Rspress 静态产物是 .html 结尾，不是 _sidebar.md 那套）
PAGE_PATH='material/scenarios/库存/库存台账与对账.html'
[ -f "$OUT_DIR/$PAGE_PATH" ] || PAGE_PATH=$(cd "$OUT_DIR" && find material/scenarios -name '*.html' | sort | head -1)
HOMEP=$(curl -s -o /dev/null -w '%{http_code}' --max-time 6 "$BASE/")
PAGE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/$PAGE_PATH")
printf "dev 站: index=%s  page=%s (%s)  页面数=%s  图页=%s\n" \
  "$HOMEP" "$PAGE" "$PAGE_PATH" \
  "$(find "$OUT_DIR" -name '*.html' 2>/dev/null | wc -l)" \
  "$(find "$OUT_DIR/material/diagrams" -name '*.html' 2>/dev/null | wc -l)"

# 12 项体检（静态扫产物；dev 站豁免：--internal-ok 首页内网信息 + --private-ok 第 ⑩⑪ 项）
echo "▶ 12 项体检：$(basename "$CHECK") --root $OUT_DIR --skip-http --page-ext=html"
CHECKOUT=$(node "$CHECK" --root "$OUT_DIR" --skip-http --page-ext=html \
  --internal-ok --internal-ok-files=index.html,index.md --private-ok 2>&1)
CHECK=$?
echo "$CHECKOUT" | grep -E '^(✅|❌) 第|^(✅|❌) [0-9①-⑫]|^结论|^页面 |^孤儿图 |^── ' || echo "$CHECKOUT" | tail -20

if [ "$HOMEP" = 200 ] && [ "$PAGE" = 200 ] && [ "$CHECK" = 0 ]; then
  echo "✅ 刷新完成：3027 = Rspress dev（$(find "$OUT_DIR" -name '*.html' | wc -l) 页），体检 12 项 PASS"
  echo "→ 直达：$BASE/    旧站对照：http://100.113.135.55:3028/"
else
  echo "✗ 刷新后有问题：index=$HOMEP page=$PAGE 体检退出码=$CHECK"
  exit 1
fi
