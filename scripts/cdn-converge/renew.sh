#!/bin/bash
# Renew the rose7.cn CDN certificate and re-push it to Aliyun CDN.
# Called by cron. certbot only acts inside its renewal window (30 days).
set -u
HERE=/root/qiqi-mall/scripts/cdn-converge
cd "$HERE" || exit 1
exec "$HERE/.venv/bin/certbot" renew --quiet \
  --config-dir "$HERE/letsencrypt" \
  --work-dir "$HERE/le-work" \
  --logs-dir "$HERE/le-logs" \
  --deploy-hook "python3 $HERE/deploy_cdn.py"
