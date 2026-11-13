#!/usr/bin/env python3
"""gcwatch.py —— 解析 JVM GC 日志（-Xlog:gc*），产出「每个 STW 事件」与「每秒汇总」两张表

    python3 gcwatch.py <outdir> <label>=<gc.log路径> [<label>=<路径> ...]
    # 例：python3 gcwatch.py out/server-1=/tmp/gc1.log server-2=/tmp/gc2.log

为什么要这个脚本：容器里的镜像是 JRE（没有 jcmd/jstat），拿不到 GC 计数与堆占用。
唯一不改业务代码、不加 agent 的办法就是让 JVM 自己把 GC 日志写出来（-Xlog:gc*），
然后离线解析 —— 这也是 JVM 官方推荐的低开销观测方式（p99 影响 < 0.1%/事件量级）。

解析的两种行（都是 -Xlog:gc* 的固定格式，JDK 21 / G1）：

  [2026-11-14T02:29:51.175+0800][19902ms][info][gc          ] GC(19) Pause Young (Normal) (G1 Evacuation Pause) 92M->37M(136M) 11.776ms
  [2026-11-14T02:29:52.611+0800][21339ms][info][gc,start    ] GC(20) Pause Young (Normal) (G1 Evacuation Pause)

只有**完成行**同时带 "A->B(C) Tms"：A = GC 前已用、B = **GC 后已用**（这就是要的「堆使用 after GC」）、
C = 当前堆容量、T = STW 暂停毫秒。start 行没有这些，跳过。

口径：
  · 「GC 时间占比」= ΣSTW 暂停毫秒 / 窗口毫秒。只算 STW（Pause *），并发标记线程的耗时不算
    ——它不阻塞应用线程，混进去会把占比虚高。
  · 堆使用用 B（after GC），不用 A：A 里有大量还没回收的垃圾，不是真实占用。
"""
import csv
import os
import re
import sys
from datetime import datetime

# [时间][uptime][level][tags]  GC(n) 正文
LINE = re.compile(
    r"^\[(?P<time>[^\]]+)\]\[(?P<up>\d+)ms\]\[(?P<level>[^\]]*)\]\[(?P<tags>[^\]]*)\]\s*"
    r"(?P<gc_id>GC\(\d+\))?\s*(?P<body>.*)$"
)
# 完成行正文：Pause Young (Normal) (G1 Evacuation Pause) 92M->37M(136M) 11.776ms
DONE = re.compile(
    r"^(?P<kind>Pause [A-Za-z]+.*?)\s+(?P<before>[\d.]+)(?P<bu>[KMG])->(?P<after>[\d.]+)(?P<au>[KMG])"
    r"\((?P<cap>[\d.]+)(?P<cu>[KMG])\)\s+(?P<pause>[\d.]+)ms\s*$"
)
UNIT = {"K": 1.0 / 1024.0, "M": 1.0, "G": 1024.0}


def to_mb(v, u):
    return float(v) * UNIT.get(u, 1.0)


def parse_epoch(s):
    """'2026-11-14T02:29:51.175+0800' → epoch 秒（带时区偏移，跨时区也不会错）"""
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%S.%f%z").timestamp()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    outdir = sys.argv[1]
    srcs = []
    for spec in sys.argv[2:]:
        if "=" not in spec:
            print(f"✗ 参数要写成 label=path：{spec}", file=sys.stderr)
            sys.exit(2)
        label, path = spec.split("=", 1)
        srcs.append((label, path))
    os.makedirs(outdir, exist_ok=True)

    ev_path = os.path.join(outdir, "gc_events.csv")
    ps_path = os.path.join(outdir, "gc_persecond.csv")
    per_sec = {}
    summary = []

    with open(ev_path, "w", newline="") as fev:
        wev = csv.writer(fev)
        wev.writerow(["epoch_ms", "epoch_s", "jvm", "gc_id", "kind",
                      "heap_before_MB", "heap_after_MB", "heap_cap_MB", "pause_ms", "is_full"])
        for label, path in srcs:
            if not os.path.exists(path):
                print(f"⚠ 缺文件 {label}={path}（该 JVM 的 GC 数据缺失）", file=sys.stderr)
                continue
            n_lines = n_pause = 0
            total_pause = 0.0
            first_ts = last_ts = None
            with open(path, errors="replace") as f:
                for line in f:
                    n_lines += 1
                    m = LINE.match(line.rstrip("\n"))
                    if not m:
                        continue
                    d = DONE.match(m.group("body").strip())
                    if not d:
                        continue  # start 行 / 阶段行 / 堆分布行
                    try:
                        ts = parse_epoch(m.group("time"))
                    except Exception:
                        continue
                    pause = float(d.group("pause"))
                    kind = d.group("kind")
                    is_full = 1 if kind.startswith("Pause Full") else 0
                    n_pause += 1
                    total_pause += pause
                    first_ts = ts if first_ts is None else min(first_ts, ts)
                    last_ts = ts if last_ts is None else max(last_ts, ts)
                    after = to_mb(d.group("after"), d.group("au"))
                    wev.writerow([
                        int(ts * 1000), int(ts), label, m.group("gc_id") or "",
                        kind, f"{to_mb(d.group('before'), d.group('bu')):.1f}",
                        f"{after:.1f}", f"{to_mb(d.group('cap'), d.group('cu')):.1f}",
                        f"{pause:.3f}", is_full,
                    ])
                    sec = int(ts)
                    b = per_sec.setdefault((sec, label), {"gc": 0, "pause": 0.0,
                                                          "heap_max": 0.0, "full": 0})
                    b["gc"] += 1
                    b["pause"] += pause
                    b["heap_max"] = max(b["heap_max"], after)
                    b["full"] += is_full
            span = (last_ts - first_ts) if (first_ts and last_ts) else 0
            summary.append(dict(label=label, lines=n_lines, pauses=n_pause,
                                total_pause_ms=round(total_pause, 1),
                                span_s=round(span, 1),
                                stw_ratio_pct=round(100.0 * total_pause / (span * 1000), 2) if span else 0.0))
            print(f"  {label}: {n_lines} 行 / {n_pause} 次 STW / Σ暂停 {total_pause:.0f}ms "
                  f"/ 跨度 {span:.0f}s / 全局 STW 占比 {summary[-1]['stw_ratio_pct']}%")

    with open(ps_path, "w", newline="") as fps:
        w = csv.writer(fps)
        w.writerow(["epoch_s", "jvm", "gc_count", "pause_ms_sum", "heap_after_max_MB", "full_gc"])
        for (sec, label) in sorted(per_sec):
            b = per_sec[(sec, label)]
            w.writerow([sec, label, b["gc"], f"{b['pause']:.3f}",
                        f"{b['heap_max']:.1f}", b["full"]])

    print(f"→ {ev_path}\n→ {ps_path}")
    for s in summary:
        print(f"  全局：{s['label']} 全程 STW 占比 {s['stw_ratio_pct']}% （GC 日志本身的开销不计在内）")


if __name__ == "__main__":
    main()
