#!/usr/bin/env bash
# envsample2.sh —— 修正版环境采样：load1/load5 拆成两个逗号字段（v1 把 "2.71 3.20" 塞进一个字段导致解析 nan）
# 输出：epoch,load1,load5,MemAvailable_kB
OUT=${1:?用法: envsample2.sh <输出文件>}
echo "epoch,load1,load5,MemAvailable_kB" > "$OUT"
while :; do
  read -r L1 L5 <<< "$(cut -d' ' -f1-2 /proc/loadavg)"
  M=$(awk '/MemAvailable/{print $2}' /proc/meminfo)
  echo "$(date +%s),$L1,$L5,$M" >> "$OUT"
  sleep 5
done
