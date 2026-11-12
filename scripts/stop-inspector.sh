#!/usr/bin/env bash
# 停止由 scripts/start-inspector.sh 启动的 MCP Inspector。
# 只按 systemd 单元名停止，不使用 pkill -f 宽匹配。
set -euo pipefail

UNIT="mcp-inspector"

if systemctl is-active --quiet "$UNIT" 2>/dev/null; then
  systemctl stop "$UNIT"
  echo "[stop-inspector] 已停止 $UNIT"
else
  systemctl stop "$UNIT" >/dev/null 2>&1 || true
  echo "[stop-inspector] $UNIT 本来就没在跑"
fi

systemctl reset-failed "$UNIT" >/dev/null 2>&1 || true
echo "[stop-inspector] 当前状态: $(systemctl is-active "$UNIT" 2>/dev/null || echo inactive)"
