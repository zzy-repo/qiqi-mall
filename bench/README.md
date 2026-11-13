# bench/ —— 开环定速压测 harness（含首轮「闭环 vs 开环」A/B 实验数据）

> 入库目的：**今晚所有数字的证据链不能只在机器上。** 这份目录把压测工具 + 首轮实验的原始数据一起入库，
> 下周任何人都能照着重跑、并且能判断「这些数字当时是不是干净的」。

---

## 1. 这套工具解决什么问题

旧做法是**闭环**压测（`legacy/LoadGen.java`，固定并发数 VU，每次请求返回后再发下一个）。它有四个结构性毛病：

| 毛病 | 后果 |
| --- | --- |
| **coordinated omission** | 服务端卡住时，闭环压测器自己也停下来不发请求；于是「本该排队、本该超时」的那批请求**根本没有被测量**，长尾被系统性抹掉。报出来的 p99 是「服务器还能应付的那部分请求」的 p99。 |
| **每档重启** | 每个并发档要独立走一遍 reset → seed 令牌 → JVM 冷启动 → 预热 60s → 计时 60s。**7 个档 = 7 次重复付同样的固定开销**，其中真正的测量只有 7×60s。 |
| **容量上限是假的** | 闭环下吞吐被「VU 数 ÷ 延迟」自限。c64 报 390 rps，看起来是机器上限，其实只是「64 个槽位 × 164ms」的算术结果（见 §2 结论 4）。 |
| **换档不可比** | 每档之间环境已经漂了（预热、GC、缓存、别人的 load），横着比没有意义。 |

`gen.mjs` 换成**开环定速**：按**计划到达时刻**发压（到点就发，不等上一个返回），延迟起点取**计划时刻**；
然后在一个进程里用**阶梯**升压跑完全部档位 —— 一次 reset、一次预热、一次冷启动。

---

## 2. 实测结论（2026-11-13 早，4 核共享机，全部数字 `date +%s` / `/proc` 真实打点）

实验时间窗（`results/2026-11-13-closed-vs-open/lock-window.csv` 可核对独占窗口）：

```
旧法（闭环 7 档）  08:50:25 → 09:07:01   wall 996s
新法（开环阶梯）   09:07:01 → 09:15:03   wall 482s
整个独占窗口       08:48:45 → 09:15:03   1578s
```

**结论 1 —— 墙上时间 14.6 min → 6.5 min（2.24×，省 8.1 min）**

| 口径 | 旧法 | 新法 | 提效 |
| --- | --- | --- | --- |
| 整段（reset + 预热 + 测量 + 清理 + 聚合） | 875s = 14.6 min | 391s = 6.5 min | **2.24×** |
| 压测段（各档 reset/冷启动/预热都算 vs 只算一次预热+阶梯） | 996s | 381s | 2.30× |
| 纯测量 | 7 × 60s = 420s | 321s | — |

判据（新法 ≤ 旧法 1/2 才算提效）：**达标**。省下来的全是**重复的固定开销**，不是靠少测。

**结论 2 —— 同负载 TPS 差 ≤ 0.6%（证明两法测的是同一件事）**

新法阶梯的前 6 级**故意取旧法各档实测 ok/s**（`tools/mkladder.py` 生成），因此可以逐档对位：

| 旧档 | 旧 ok/s | 新 target | 新 ok/s | TPS 差 |
| --- | --- | --- | --- | --- |
| c1 | 151.8 | 152 | 152.0 | +0.1% |
| c2 | 233.1 | 233 | 233.0 | −0.0% |
| c4 | 323.8 | 324 | 323.9 | +0.0% |
| c8 | 350.0 | 350 | 349.9 | −0.0% |
| c16 | 367.7 | 368 | 368.0 | +0.1% |
| c32 | 387.9 | 388 | 387.9 | +0.0% |
| c64 | 390.4 | 388 | 387.9 | −0.6% |

**结论 3 —— 同吞吐下 p99 在 7 档里有 6 档更高（+33% ~ +888%）：长尾被真实暴露**

```
旧 p99 → 新 p99：15.8→20.9 (+32.6%)  18.1→46.5 (+157.4%)  24.9→166.6 (+570.0%)
                 53.1→524.3 (+887.9%) 114.0→174.8 (+53.3%) 244.3→462.7 (+89.4%)
c64 例外：735.6 → 462.7 (−37.1%)
```

c64 的例外**不是**计时起点错，而是操作点不同：c64 的 64 个闭环槽位本身就在排队（L=64、W≈164ms），
新法 388 rps 档的 inflight 只有 26。「吞吐相同但并发不同」不是同一操作点，所以拿它当反例不成立。
开环暴露长尾的证据是 c1~c32 六档。

**结论 4 —— 闭环的 390 rps 是假上限**

末级把目标推到 **530 rps**（闭环根本表达不出来的负载水平，因为它受 VU 数自限），
新法仍然完成并发出 **474.6 rps**（达成 89.6%，p50 4.9s / p99 6.9s，丢弃 2486 条并标 `saturated=1`）：

```
474.6 / 390.4 = +21.6%
```

→ 机器的真实容量至少比闭环报的「上限」高 21.6%。**闭环报的 390 rps 是配置出来的天花板，不是机器的天花板。**

**结论 5 —— 数据自校验（Little's Law）**

`L = ok_rps × W`（W = 平均延迟）。L 取该档 inflight 的**精确时间加权积分**（`gen.mjs` 每次 inflight 变化就按墙上秒记账，
不是采样近似），再叠加「生成器入口调度积压」。6 档最大偏差 **0.6%**，全部 ≤10%。

---

## 3. 怎么跑（三段，每段一条命令）

前置：容器栈起来（gateway `http://127.0.0.1:18080`、`qiqi-redis`、`qiqi-mysql`），
并且**必须持独占锁**（见 §6）。

先准备数据工作目录（令牌池 / JWT / 下单模板 / 冷门 SKU 清单都落这里，**不入库**）：

```bash
export LG=/tmp/loadgen MEMBER_PASS='<自选>' MYSQL_PASSWORD='<从 .env 读或自己给>'
mkdir -p "$LG"
bench/tools/prepare.sh all      # sku + cold + members(JWT) + tokens(20 万幂等令牌，纯文件)
                                # 下单模板 $LG/order.json 需自己准备（见 §4「输入文件」）
```

**① 单跑（一个固定 RPS，或一条小阶梯）**

```bash
STEPS=300 STEP_DURATION=60 WARMUP=60 LG=$LG bench/bench.sh
```

**② 阶梯（默认 7 级，一次 reset / 一次预热 / 一个进程跑完）**

```bash
STEPS=152,233,324,350,368,388,530 STEP_DURATION=45 WARMUP=60 LG=$LG bench/bench.sh
# 产物：out/{summary.csv,persecond.csv,samples.csv,steps.csv,run.json,env.csv,redis.csv,curve.png,timings.txt,provenance.txt}
```

阶梯可以闭着眼睛给，但想让结果**逐档对位旧法**就用工具生成：

```bash
bench/tools/mkladder.py oldout/timings.csv        # → 152,233,324,350,368,388,530
```

**③ A/B 对比（旧法 7 档 + 新法阶梯，同一独占窗口内相邻测量）**

```bash
LG=$LG MEMBER_PASS='<自选>' MYSQL_PASSWORD='<...>' bench/run-all.sh
# 产出：oldout/timings.csv（旧法）、out/summary.csv（新法）、compare.txt（对比表）
```

`run-all.sh` 的顺序不是随便排的：**旧法与新法必须相邻**，否则比的是机器的漂移，不是方法。

---

## 4. 数据口径（看数字前先看这一节）

* **TPS 一律用 `ok/s`**（有效吞吐 = 成功请求数 ÷ 该档时长），不用 `requests/s`。失败请求不算吞吐。
  新法里「发出去但失败」的进 `fail`，「该发但没发」的进 `dropped`，两者都不计入 `ok/s`。
* **延迟起点 = 计划到达时刻**（不是实际发出时刻）。这是开环的全部意义所在：
  `latency = 收到响应时刻 − 该请求的计划发出时刻`，于是调度滞后 + 排队时间都被算进延迟。
  每行样本还单独记了 `lag_ms`（实际 − 计划），用来分辨「是我发晚了」还是「服务器慢了」。
* **`saturated=1` 的含义**：该秒 `dropped > 0` 或 `achieved < 95% × target`。丢弃是**设计行为**，
  拓扑上等价于「客户端排队溢出」，不是 bug；但它意味着该档的 ok/s 是**下限**而不是达成的目标。
* **Little's Law 自校验**：`L_total = inflight 时间加权均值 + 调度积压均值`，应 ≈ `ok_rps × avg_latency`。
  偏差 >10% 的档位说明统计链路有问题（或者该档含丢弃请求，`little_err_pct` 留空不参与判定）。
* **稳态判定（CV）是失效的**：见 §5 缺陷 ①。目前一律保底预热 60s。
* **受扰判定**：某档 `load1` 峰值 > 本轮各档峰值中位数 × 1.5 → `disturbed=1`，数字要打折看。
  但这条在阶梯场景会误判，见 §5 缺陷 ②。

**输入文件（都在 `$LG` 里，全部不入库）**

| 文件 | 内容 | 谁生成 |
| --- | --- | --- |
| `tokens.txt` | 20 万个幂等令牌（32 位 hex，每个只能用一次） | `tools/prepare.sh tokens` |
| `jwts.txt` | 200 个压测会员的 JWT | `tools/prepare.sh members` |
| `order.json` | 下单请求体模板，支持 `{{TOKEN}} {{JWT}} {{SKU}} {{SEQ}}` 占位符 | 手写 / 从真实请求抓一份 |
| `skus_cold.txt` | 冷门 SKU 清单（80/20 里的那 20%） | `tools/prepare.sh cold` |

**输出文件**

| 文件 | 粒度 | 入库？ |
| --- | --- | --- |
| `persecond.csv` | 每秒一行（一档 45s ⇒ 45 行） | ✅ 小，入库 |
| `summary.csv` | 每档一行（含环境列、Little 校验、饱和/受扰标记） | ✅ |
| `steps.csv` | 每档的计划窗口 + 绝对 epoch 边界 | ✅ |
| `env.csv` / `redis.csv` | 5s / 10s 的环境采样与令牌池剩余量 | ✅ 小 |
| `samples.csv` | **逐请求**明细（`planned_ms,lag_ms,latency_ms,ok,status`），测量段 103,039 行（预热 12,000 条不记样本，整轮 fired=115,039） | ❌ **10 万行级 / 3.0 MB，不入库** |
| `curve.png` | 达成 TPS / target / p99 / p95 曲线 | ✅ |

> `samples.csv` 是切档算精确分位数的原料（`tools/analyze.py` 用它，不用「每秒分位数再平均」那种会低估 p99 的算法），
> 但它是逐请求级（本轮 103,039 行 / 3.0 MB）、太大，所以**只保留前 1000 行做样例**：`results/2026-11-13-closed-vs-open/samples.first1000.csv`。
> 需要全量重算时重跑一次即可（重跑比存 3 MB 便宜）。原始全量在跑它的机器上（`$BENCH/out/samples.csv`）。

---

## 5. 已知缺陷（诚实写，不要假装没有）

**① 预热仍然要 60s，自动化省不下来。**
报告里试过两条「自动判稳态」的路子，都失效：

* 用 TPS 的 10s 滑窗 CV<5%：7 档全部在 10s 就「通过」（CV≈0）。但开环下达成的 TPS 是**我们的到达率**决定的，
  这个判据测的是「发送器稳不稳」，不是「服务器稳不稳」→ 判据无效。
* 改用每档**平均延迟**的 10s 滑窗 CV<5%：324~388 rps 三档的延迟 CV 高达 10~98%，
  45s 档内根本降不到 5%（4 核共享机 + 无 cgroup 限制，抖动太大）。
  → **CV 稳态判定在共享机上失效，结论是不省预热，保留 60s。**

唯一的正面证据：阶梯第一级（152 rps）前 3 秒延迟 7.7ms vs 稳态 7.6ms —— 一次预热足够，
旧法「每档第一轮偏慢 20~30%」的浪费在新法里根本不存在（只冷启动一次）。

**② `load1` 污染判定在阶梯场景会误判。**
`load1` 是 1 分钟指数滑动平均，阶梯逐级升高时它会**跨档累积**，所以「档内峰值」基本是压测自身的负载。
实测 7 档档内峰值 5.14 → 42.08 单调上升，按「峰值 > 各档峰值中位 × 1.5」的规则会把 c32/c64（以及新法 step 5/6）误标 `disturbed`。
→ **在阶梯场景不要用 load1 判污染**，改用业务侧证据：
**`0` 条幂等拒绝（"请勿重复提交"）+ `0` 条传输错误**。幂等令牌是 Redis `GETDEL` 原子消费的硬资源，
有别人在压同一个池子就必然出现成片幂等失败；本次旧法 7 档与 11.5 万请求全程 0 条（仅 24 条 `order_sn` 唯一键碰撞），故判定环境干净。

**③ 必须独占机器（令牌池是硬互斥资源）。**
200,000 个幂等令牌灌在同一个 `qiqi-redis` 里，两个人同时压 → 互相偷令牌 → 成片「请勿重复提交」失败，两边的数据同时作废。
所以有 `bench-lock.sh` + `wait_quiet`（连续 90s 没有别人的 `java LoadGen` 才算安静）。
**没有锁就不要跑**，跑出来的数字没有意义。

**④（工具侧，已修）`tools/compare.py` 在饱和档崩溃。**
首轮 A/B 时 `summary.csv` 里饱和档的 `little_err_pct` 为空，旧版直接 `float('')` 抛 `ValueError`，
对比表输出到第 5 档就断（原始崩溃栈见 git 历史前的 `/tmp/bench2/compare.txt`）。
入库版对空值做 `n/a` 降级，其余逻辑不变；`results/.../compare.txt` 是用同一批原始 CSV 重跑得到的。
另外旧版把预热 60s 写死在算式里，入库版改成从 `timings.txt` 读。

**⑤（工具侧，已修）环境采样器 v1 有个字段分隔 bug。**
`tools/envsample2.sh` 之前的版本把 `"load1 load5"` 塞进同一个 CSV 字段，导致旧法 `load1` 列解析成 `nan`
（所以 `oldout/timings.csv` 里 `load1_min/med/max` 全是 `nan`，报告里改用 `env2.csv` 后置补算）。
入库版：`oldmethod.sh` 统一用 `envsample2.sh`，且 `envstat()` 改成**按表头取列**，不再硬编码字段下标。

---

## 6. 复现要求（硬约束）

```bash
# 必须持这把锁（同一时刻只允许一个人压这套环境）
/root/.qiqi-standing/bench-lock.sh acquire <your-name>
/root/.qiqi-standing/bench-lock.sh status
/root/.qiqi-standing/bench-lock.sh release
```

`run-all.sh` 会自动取锁；单独跑 `bench.sh` **不会** —— 手动跑就必须自己先 `acquire`。
锁被占了就等，不要「先跑一下看看」。

其他要求：独占机器（见缺陷 ③）、数据工作目录里的令牌池在**开跑前 30 分钟内**灌入
（服务端 `IdempotentTokenStore.TTL = 30min`，`prepare.sh seed` 就是灌这一步）。

---

## 7. 目录结构与文件说明

```
bench/
  README.md                     ← 本文件
  gen.mjs                       ← 主工具：开环定速生成器（定速、阶梯、丢包标记、精确 inflight 积分）
  bench.sh                      ← 一键 harness：唯一一次 reset/预热/收尾 + 切档 + 出图 + provenance
  run-all.sh                    ← 一键 A/B：锁 + load1 门禁 + 旧法 7 档 + 新法阶梯 + 对比
  oldmethod.sh                  ← 闭环 7 档复现（**只用于 A/B 对照，不要当日常工具**）
  tools/
    analyze.py                  ← 阶梯产物 → summary.csv / steady.csv / curve.png（口径都在这里）
    compare.py                  ← 新旧对比（墙上时间 + 同负载 p99 + 提效倍数）
    mkladder.py                 ← 由旧法实测 ok/s 生成新法阶梯（保证逐档对位）
    mkreport.py                 ← 把一轮产物拼成纯文本 report.txt
    envsample2.sh               ← 环境采样器（load1/load5/MemAvailable，5s 一次）
    prepare.sh                  ← DB reset + sku/冷门 SKU + 会员/JWT + 幂等令牌灌 Redis
    avalanche.sh                ← 雪崩实验一键：reset+三自检 → 令牌 → 三采样器 → 阶梯 → GC 解析 → 判定（见 §11）
    cgwatch.sh                  ← 200ms 水位采样：cgroup v2(mem/peak/events/cpu/PSI) + /proc 线程数 + load/swap
    gcwatch.py                  ← -Xlog:gc* 日志 → 每次 STW + 每秒 GC 占比 / after-GC 堆
    avalanche.py                ← 档窗口对齐（压测+persecond+水位+GC）→ ladder.csv/.txt + 雪崩点判定
    jvm-gc-on.sh / jvm-gc-off.sh ← 开/关 GC 日志（追加 -Xlog:gc*，重启容器、不重建镜像）
  jvm-gc.override.yml           ← GC 日志用的 compose override（**只在显式 -f 时生效**，不叫 override.yml 是为了不被自动叠加）
  legacy/
    LoadGen.java                ← 旧闭环压测器（历史对照，单文件 Java 21 虚拟线程实现）
  results/
    2026-11-13-closed-vs-open/  ← 首轮实验的原始数据（见下表）
```

`results/2026-11-13-closed-vs-open/`：

| 文件 | 说明 |
| --- | --- |
| `report.txt` | 完整报告（含环境独占窗口、墙上时间逐笔账、令牌池消耗、干扰证据、结论与例外） |
| `summary.csv` | 新法每档一行：target/ok_rps/达成%/p50/p95/p99/Little 校验/饱和/受扰/环境列 |
| `persecond.csv` | 新法每秒一行（320 行） |
| `steps.csv` | 新法 7 档的计划窗口 + 绝对 epoch 边界 |
| `env.csv` / `redis.csv` | 新法跑的环境采样 / 令牌池剩余量采样 |
| `timings.csv` | 旧法 7 档逐档打点（墙上/ reset / seed / JVM+跑 / ok_rps / p99 / verdict） |
| `timings.txt` | 新法整段墙上时间逐笔打点（T0 → T_cleanup_done） |
| `compare.txt` | 两法对比表（本文件由 `tools/compare.py` 从上面同一批 CSV 重跑得到，见缺陷 ④） |
| `provenance.txt` | 数据出处的自证：git HEAD、镜像 digest、cgroup 配额、脚本 md5、阶梯参数、令牌池规模 |
| `run.json` | 新法一次运行的机器可读摘要（fired/completed/dropped/ok/fail/错误分布/客户端 CPU） |
| `lock-window.csv` | 独占窗口时间线（acquire → load1_ok → oldmethod → newmethod → release） |
| `load1.csv` | 全程 load1 采样（15s 一次，用来判「当时机器忙不忙」） |
| `samples.first1000.csv` | 逐请求明细的**前 1000 行样例**（全量 10.4 万行不入库，见 §4） |
| `curve.png` | 达成 TPS / target / p99 / p95 曲线 |

---

## 8. 与原型的差异（入库时做了什么）

原型散在 `/tmp/bench2/`、`/tmp/loadgen/`，跑通即用、路径全写死。入库做了**只动路径与凭据、不动测量逻辑**的最小改动：

1. **去掉所有硬编码机器路径**。`bench.sh` / `run-all.sh` / `oldmethod.sh` 现在用
   `SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)` 定位自己，仓库根由 `SELF/..` 推出；
   `gen.mjs --outdir` 默认 `out/`（或 `BENCH_OUT`）而不是 `/tmp/bench2/out`；
   `mkreport.py` 的 `BENCH_DIR` 默认取脚本上一级；`compare.py` 的 env 序列目录改成从传入的 `timings.csv` 推。
   **数据工作目录**（令牌池/JWT/模板/冷门 SKU 清单）统一走 `LG` 环境变量，默认 `/tmp/loadgen`（原型的习惯位置）。
2. **凭据脱敏**：`tools/prepare.sh` 原来有 `MYSQL_PASSWORD` 的兜底默认值和一个写死的会员口令，
   现改成**只从环境变量或 `<repo>/.env` 读，读不到就报错退出**，脚本内不再含任何明文口令。
   令牌/JWT 本来就只作为 `--token-file` / `--auth-file` 命令行参数传入，不硬编码。
   `tokens.txt` / `jwts.txt`（凭据类）和任何大批 CSV 都没入库。
3. **修了 `compare.py` 的饱和档崩溃**（缺陷 ④）和 **`oldmethod.sh` 的采样器字段下标**（缺陷 ⑤）。
4. **`legacy/LoadGen.java` 只作历史对照保留**，注释里已标注「不要再当日常工具」。

**仍然存在、但已声明为历史事实的绝对路径**（不改，因为它们是证据的一部分，改了就对不上原始报告）：

* `results/2026-11-13-closed-vs-open/provenance.txt` 里的 `script_md5 /tmp/bench2/...`、`loadgen_reused /tmp/loadgen/...`
  —— 记录的是**当时**跑的那份脚本在哪、md5 是多少。想看「现在这份和当时那份是不是一样」，比对 md5 即可（路径已变，内容只差上面第 1~3 条的路径/凭据改动）。
* `results/.../timings.csv` 的 `csv` 列（如 `/tmp/bench2/oldout/tier-old-c1.csv`）—— 旧法逐档明细当时落在哪。
* `oldmethod.sh` / `bench.sh` 里的 `$LG` 默认值 `/tmp/loadgen`，以及 `round_active()` 里对
  `/tmp/loadgen/round-B32b.log`（另一个 agent 的轮次日志）的探测 —— 这是**本机现场**的历史旁路，
  换机器请用 `LG=` / `RIVAL_ROUND_LOG=` 覆盖或直接忽略（`wait_quiet` 只是「尽量别撞车」，真正的互斥靠 bench-lock）。
* `lock-window.csv` 里的锁名 `bench2-prototype` 是当时取的 owner 标签（历史值）。

---

## 9. 一句话结论

**采用。** 同一负载水平下两法 TPS 差 ≤0.6%（测的是同一件事），墙上时间 14.6 min → 6.5 min（2.24×），
且开环把闭环掩盖的长尾暴露出来（同吞吐下 p99 高 33%~888%），
还顺带发现「闭环容量 390 rps」是被 VU 数自限的**假上限**（530 目标下开环仍完成 474.6 rps，+21.6%）。
代价是 60s 预热省不掉、必须独占机器、`load1` 不能当污染判据 —— 三条都写在上面的缺陷清单里。

---

## 10. 验证「删等待」类改动的标准动作（e2e）

删掉 e2e 里的固定 `waitForTimeout` 之后，「跑一次是绿的」说明不了任何事 —— 竞态窗口是概率性的，
一次通过只证明「这次没踩上」。标准动作四步：

1. **挑被删处覆盖的 test**：先 grep 被改的 support helper 的调用方（`grep -rn <helper 名> tests/`）。
   support 里的改动影响**所有**调用它的 spec，不是只有同目录那几个 —— 漏掉一个调用方，
   这条结论就只管了半个入口。
2. **独占一个 shard**：拿 `bench-lock.sh`，跑前 `pgrep -f playwright` 确认没有别的跑批；
   然后钉死一个分片/库（`E2E_SHARD_FROM=1 E2E_SHARDS=1` → shard b / `qiqi_mall_e2e_b`）。
3. **同一个 shard + 重复跑**：`--repeat-each=20 --workers=1`（别为了快加 worker，
   并发本身会制造新的互相干扰，红了分不清是被测物还是自己）。
4. **判据**：20/20 全绿才算安全；任何一次红都算「该处不成立」，把 sleep 从 git 原值恢复，
   不许为了让绿去改断言或放宽 timeout。

```bash
/root/.qiqi-standing/bench-lock.sh acquire e2e-repeat-each
pgrep -f playwright || true                 # 有别的跑批就等它结束，别强开
cd /root/qiqi-mall && E2E_SHARD_FROM=1 E2E_SHARDS=1 E2E_WORKERS=1 \
  bash scripts/e2e-run.sh <覆盖到的 spec 文件...> --repeat-each=20 --workers=1
/root/.qiqi-standing/bench-lock.sh release
```

理由：竞态窗口靠**重复次数**覆盖，比「`page.route` 注入延迟 + 等干净窗口」省一个数量级，
而且不受其它 agent 并发重置库的影响（注入延迟那套一旦和别人共用库，就没有判别力）。
**先估时再定 N**：单轮耗时 × N 才是成本，别按「删改点数」拍脑袋。
单轮耗时取 `e2e/report/runs.jsonl` 里同文件的历史中位数之和（例：6 个 spec 合计 ≈ 276s，
再加并发/DB 重置的开销），×N 就是总墙钟 —— 一个 helper 里的删改会波及**所有**调用它的 spec，
实际要跑的 test 数常是删改点的 2~3 倍，按删改点数估会差一个数量级。

与其它跑批共存怎么办：`repeat-each` 的判据是**零失败**而非**耗时**，所以和别人并发跑
不影响结论（各用各的 shard 库就不会被重置污染）；但单遍墙钟会明显变长（抢 CPU），
**估时要把并发算进去**，别拿空载时的中位数当准。

N 的档位：**N=20 属严格口径**（发版前跑满）；日常**N=3 做烟测**够用，
红了的按第 4 步处理，绿的再排严格档。
实测口径（2026-11-13 晚，27 条 test / shard b / 与另一 agent 跑批共存）：
3 遍 + 第 4 遍 4 条共 85 条 test，`e2e-run.sh` 总墙钟 16m13s（含 preflight + 建库重置），
即**一遍 27 条约 4.5 分钟** → N=20 约 90~95 分钟。`runs.jsonl` 的历史中位数偏慢（空载前的老前端），
估时按实测一类值上浮 30% 留余量，别照抄中位数。

---

## 11. 雪崩实验（A 型：内存 / 线程耗尽）—— 观测能力 + 基线雪崩点

目标：找「雪崩点」—— 一个可复现的到达率阈值，越过它响应时间非线性恶化。
**本轮结论先行**：雪崩点在 **400 ok/s 档**（350→400 之间），触发判据是 **① p99 翻倍**；
但**根因不是内存**（堆只用了 89MB / 上限 11.5GB，GC 占比 ≤1.2%，0 次 Full GC），
而是**「Tomcat 200 工作线程抢 HikariCP 10 个数据库连接」的应用侧排队**（线程池打满，有线程栈实证）。

### 11.1 一条命令复现

```bash
# 观测开关（会重启 server-1/-2，~15s，**不重建镜像**）：给 JAVA_OPTS 追加 -Xlog:gc*
bench/tools/jvm-gc-on.sh

# 跑基线阶梯（50→500，每档 75s，预热 60s；自动 reset/三自检/灌令牌/采样/判定）
MEMBER_PASS='<自选>' bench/tools/avalanche.sh

# 还原发布值（-XX:MaxRAMPercentage=75）
bench/tools/jvm-gc-off.sh
```

自定义阶梯 / 重复某几档：

```bash
MEMBER_PASS='<自选>' STEPS=300,350,400 STEP_DURATION=75 LABEL=repeat bench/tools/avalanche.sh
```

产物落在 `results/<日期>-avalanche-<LABEL>/`：`ladder.csv`（每档一行全字段）、`ladder.txt`（人读表 + 判定）、
`cg.csv`（200ms 水位）、`gc_<容器>.log` + `gc_events.csv` / `gc_persecond.csv`、`steps.csv` /
`samples.csv` / `persecond.csv`（gen.mjs 原生）、`env.csv` / `docker-stats.csv`、`run.log`（逐阶段打点）。

### 11.2 观测能力：怎么采、采到哪、要不要重启

| 指标 | 手段 | 要不要重启 | 为什么这么做 |
| --- | --- | --- | --- |
| 容器 mem.current / mem.peak / mem.events(high,max,oom_kill) / cpu.stat / PSI | `tools/cgwatch.sh`：**宿主机直接读 cgroup v2**（`/sys/fs/cgroup/system.slice/docker-<id>.scope/`），200ms 一次 | 否 | 走 `docker exec` 每 200ms 起进程会把采样成本变成干扰；宿主机直读只有几十次小文件读 |
| JVM GC 次数 / STW 暂停 / **after-GC 堆** | JAVA_OPTS 追加 `-Xlog:gc*:file=/tmp/gc.log:time,uptimemillis,level,tags:filecount=0`，跑完 `docker exec cat` 出来离线解析（`tools/gcwatch.py`） | **是**（仅重启容器，不重建镜像） | **镜像里是 JRE**：`/opt/java/openjdk/bin` 只有 `java/jfr/keytool`，**没有 jcmd / jstat / jps**，jcmd 那条路走不通。`-Xlog` 是 JVM 官方的低开销手段（每事件 <0.1%） |
| Tomcat 线程池占用 | 宿主机读 `/proc/<java-pid>/task/*/comm` 按前缀 `http-nio` 计数 | 否 | 内核对 comm 截断 15 字符（`http-nio-0.0.0.`），但按前缀计数够用，且**不需要任何工具**。实测口径：空闲 12 = 10(min-spare) + 1 acceptor + 1 poller；满载 202 = 200(max-threads) + 1 + 1 |
| load1 / swap / MemAvailable | `/proc/loadavg`、`/proc/meminfo` | 否 | 同 `envsample2.sh` 口径 |
| 容器 CPU（核） | cgroup `cpu.stat usage_usec` 增量 ÷ 档窗口 | 否 | 不用 `docker stats`（它自己每 5s 起一次进程）；这一列单位是「占满 1 核的百分比」 |
| 下游 MySQL 是否被打爆 | 压测同时采 `Threads_running/Threads_connected/Innodb_row_lock_waits/deadlocks/Queries` | 否 | 用来区分「应用侧排队」还是「DB 被打爆」 |
| **线程栈**（线程到底卡在哪） | `kill -3 <java-pid>`（SIGQUIT）：JVM 自己把 thread dump 打到容器 stdout，`docker logs --since <epoch>` 取回 | 否 | JRE 没有 jstack，SIGQUIT 是唯一不改代码的取栈办法。**注意 `docker logs --since` 只吃 epoch 或 RFC3339，传 `'%F %T'` 会报 `invalid value for since`** |

一条真实采样（`cg.csv` 与 `gc_persecond.csv` 原样，取自 400 rps 档）：

```
epoch_ms,ts,container,mem_current_B,mem_peak_B,mem_high_events,mem_max_events,oom_kill,cpu_usage_usec,psi_mem_some_avg10,psi_cpu_some_avg10,thr_total,thr_http,host_load1,host_load5,host_memavail_kB,host_swapused_kB,host_runnable
# 400 rps 档窗口内的一对真实采样（2026-11-14 02:43:42）：线程 202/200 打满、mem.current 640/560MB、CPU 排队(psi_cpu 51)
1794595422235,2026-11-14 02:43:42.235,qiqi-server-1,671252480,677560320,0,0,0,358018376,0.00,51.22,234,202,27.75,15.15,6996840,3243344,39/2373
1794595422235,2026-11-14 02:43:42.235,qiqi-server-2,577593344,587943936,0,0,0,356841026,0.00,51.95,234,202,27.75,15.15,6996840,3243344,39/2373
```

（字段顺序见 11.2 表；`psi_mem_some_avg10=0.00` 而 `psi_cpu_some_avg10=51.2` —— 内存无压力、CPU 有排队，
这一行本身就是「不是内存型」的第一手证据。）

```
epoch_s,jvm,gc_count,pause_ms_sum,heap_after_max_MB,full_gc
1794595411,qiqi-server-1,1,23.113,81.0,0
1794595411,qiqi-server-2,1,13.225,79.0,0
```

### 11.3 口径与判据（**开跑前写定，不是事后挑的**）

* 雪崩类型 A；开环定速阶梯，**延迟起点 = 计划到达时刻**（gen.mjs 已实现，修 coordinated omission）；
  TPS 一律报 **ok/s**；每档 75s（在要求的 60~90s 内）；每档必带版本锚（HEAD + 镜像 digest）。
* 判据（三者任一**先**出现即判该档为雪崩点）：
  ① p99 相对上一档翻倍 ② 错误率 > 1% ③ GC 时间占比 > 20%。
* 每档前：`prepare.sh reset` + **`sql/reset-sku-bucket.sql`（必须写进桶）** + 三自检 + 预热 60s。
  ⚠️ **不能只 `UPDATE sku.stock`**：`bucket-enabled=true` 下 `sku_bucket` 才是唯一权威，`sku.stock` 只是
  展示缓存 —— 只改它会「一件库存都没变，还立刻被 250ms 回写覆盖」，压测的库存前提就是假的。

三自检（`avalanche.sh` 里任一不过就退，不许带病开跑）：桶行数 = SKU 数 × N（21×8=168）、
每 SKU 桶数 = N（坏 SKU 数 = 0）、缓存与桶偏差 = 0。

### 11.4 基线阶梯数据（2026-11-14 02:33–02:47，4 核共享机，独占窗口）

版本锚：`git HEAD=41c1682f94ca6453764ea3d9650ab92cb2d613b2` ｜
镜像 `sha256:935feb000f5d356515c6d3d29197e34f928a6e0844ef7c873a8a24996d9b39d9`（未重建）｜
参考配置 `stock.mode=bucket(N=8) ledger=ignore idem-shards=1 cache-refresh-ms=250`、
Tomcat `max-threads=200`（默认）、Hikari `maximumPoolSize=10`（默认）、容器无 cpu/mem 限额。

| 档 | 目标 rps | **ok/s** | p50 | p95 | p99 | max | 错% | 丢 | after-GC 堆峰 | GC% | mem.current 峰 | CPU(核%) | 线程 | load1 | swap |
| --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 0 | 50 | 50.0 | 8.5 | 13.8 | 19.1 | 51.1 | 0.00 | 0 | 50MB | 0.1 | 446MB | 20 | 19 | 5.19 | 3.1GB |
| 1 | 100 | 100.0 | 8.3 | 16.4 | 27.8 | 87.0 | 0.00 | 0 | 48MB | 0.1 | 441MB | 25 | 19 | 4.95 | 3.1GB |
| 2 | 150 | 150.0 | 10.2 | 27.8 | 62.5 | 325.1 | 0.01 | 0 | 50MB | 0.1 | 452MB | 36 | 23 | 5.97 | 3.1GB |
| 3 | 200 | 200.0 | 12.8 | 35.8 | 62.6 | 157.1 | 0.00 | 0 | 58MB | 0.3 | 450MB | 41 | 23 | 7.31 | 3.1GB |
| 4 | 250 | 250.0 | 18.6 | 72.3 | 123.3 | 328.9 | 0.01 | 0 | 63MB | 0.4 | 445MB | 51 | 26 | 12.13 | 3.1GB |
| 5 | 300 | 300.0 | 40.8 | 479.0 | 1540.4 | 4577.9 | 0.01 | 0 | 127MB | 0.9 | 557MB | 69 | **202** | 16.78 | 3.1GB |
| 6 | 350 | 349.9 | 66.9 | 216.9 | 322.6 | 737.5 | 0.03 | 0 | 104MB | 0.7 | 551MB | 69 | **202** | 22.08 | 3.1GB |
| 7 | **400** | 399.9 | **1697.2** | **3638.9** | **4477.1** | 6869.7 | 0.02 | 0 | 86MB | 1.2 | 754MB | 72 | **202** | 29.93 | 3.1GB |
| 8 | 450 | 404.6 | 4937.1 | 6369.8 | 7084.4 | 9524.3 | 0.02 | **3292** | 88MB | 0.7 | 803MB | 73 | **202** | 28.96 | 3.1GB |
| 9 | 500 | 410.7 | 5255.0 | 7040.5 | 7751.1 | 10018.9 | 0.01 | **6796** | 89MB | 0.7 | 909MB | 74 | **202** | 29.38 | 3.1GB |

重复性（同参数再跑一遍 300/350/400 三档，ok/s 噪声带 ±2.8%）：**300.0/300.0、349.9/349.9、399.9/399.9（差 ≤0.01%）**。
但 p99 只在 400 档复现（下表），说明「拐点」是 400 档、而**单档尾部会有瞬态**：

| 目标 rps | 第一轮 p99 | 第二轮 p99 | 判读 |
| --: | --: | --: | --- |
| 300 | 1540.4ms | 197.7ms | **不一致**：单档尾部瞬态，不是拐点（第一轮该档 p99 的秒级中位数只有 133ms） |
| 350 | 322.6ms | 283.2ms | 一致，健康 |
| 400 | 4477.1ms | 3130.3ms | 一致崩溃（第三次独立验证 3960ms） |

### 11.5 雪崩点与机制

**判据扫描结果（照口径机械执行）**：判据 ① 在第 2 档（150 rps）就触发（p99 27.8→62.5ms，×2.25）；
判据 ② 与 ③ **全程一次都没触发**（错误率峰值 0.03%，GC 占比峰值 1.2%）。

**但 150 那一档是伪触发**，理由三条，都在数据里：
① 绝对量健康（p99 62.5ms、0 错误、0 丢弃）；② 下一档没有继续恶化（200 档 p99 62.6ms，持平）；
③ 根本没有任何资源被压到（堆 50MB、线程 23、CPU 0.36 核）。
⇒ 「×2」这条判据在低基线（28ms）上会自己误报，**它就是本轮暴露出来的口径缺陷**（见 11.7）。

**真正的雪崩点 = 400 rps 档**：p50 从 67ms 跳到 1697ms（×25），p99 322.6→4477.1ms（×13.9），
且此后**不再恢复**（450/500 档 p99 7.0–7.8s），下一档开始出现调度丢弃（450 档丢 3292、500 档丢 6796）。
吞吐天花板 ≈ **405–415 ok/s**（450/500 档实测只有 404.6/410.7 ok/s）。

**当时的状态**：after-GC 堆 86MB（**上限 11.5GB 的 0.75%**）、GC 占比 1.2%、GC 次数 67 次 **0 次 Full GC**、
容器 memory.current 峰 754MB、`memory.events` high/max/oom_kill **全 0**（注意：容器没有内存上限，
所以 max/high 事件**在物理上不可能触发**，这一列只能当「没 OOM」的证据，不能当「没内存压力」的证据）、
CPU 0.72 核、Tomcat 线程 **202/200 已打满**、load1 29.9。

**根因（线程栈实证，不是推测）**：400 rps 档对两个容器各发一次 `kill -3`，栈里
**190/202（server-1）与 186/202（server-2）个 http-nio 线程都停在**：

```
"http-nio-0.0.0.0-8082-exec-67" ... TIMED_WAITING (parking)
  at java.util.concurrent.SynchronousQueue.poll
  at com.zaxxer.hikari.util.ConcurrentBag.borrow(ConcurrentBag.java:151)
  at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:162)
  at org.springframework.jdbc.datasource.DataSourceTransactionManager.doBegin(...)
```

只有 **8 个**线程真的在跑 SQL（`socketRead0` 等 MySQL 回包）。即：**200 个 Tomcat 工作线程在抢 10 个
数据库连接**（Hikari 默认 `maximumPoolSize=10`，每单约 11 条 SQL）。旁证：同一时刻 MySQL 侧
`Threads_running` 只有 10~14、`Innodb_deadlocks` 无、行锁等待约 180/s（中等），**DB 没被打爆**；
服务器 CPU 0.7 核/容器（4 核机器上远未饱和）。所以雪崩既不是内存也不是 CPU，也不是 MySQL，
而是**应用侧的连接池排队**——线程池打满这一层（属 A 型口径里的「线程池打满」），闸门在 DB 连接池。

### 11.6 已知缺陷（诚实写）

1. **判据 ① 太松**：「相对上一档翻倍」在低基线上必然误报（150 档 ×2.25 = 62ms 被判雪崩）。
   下一轮应改成「×2 **且** p99 > 200ms **且** 下一档不回落」——本轮为了不事后挪靶子，**没有**改判据，
   而是把机械结果与人工判读分开写清。
2. **档内状态累积**：一轮阶梯里第 k 档面对的是前 k−1 档留下的数据（本轮跑到 400 档时 `order` 表已
   20 万行）。300 档 p99 在两轮里差 8 倍（1540 vs 198ms）就是这种累积/瞬态的体现。
   要把 350~400 之间定位到 ±25 rps，得**每档独立 reset + 各自预热**（更慢，但可比）。
3. **`-Xlog:gc*` 本身有开销**（每事件 <0.1%，且只写容器内 /tmp），但严格说本轮的绝对延迟包含了它；
   拿这组数和没有 GC 日志的历史轮比，要记住这一点。
4. **容器无 cpu/mem 限额**，所以「内存打满 → OOM」这条路在物理上不可达；本轮关于「不是内存」的结论
   建立在「堆 89MB vs 上限 11.5GB + 0 次 Full GC」上，而不是建立在「没触发 memcg max 事件」上。
5. **线程栈只有瞬时快照**（SIGQUIT 一次），不是时间序列；但 190/202 vs 8 的对比足够定性。
6. **重建 server 后必须重启 gateway**（实测踩过，已写进 jvm-gc-on/off.sh）：nginx 在启动时解析
   upstream 容器 IP 并缓存，重建 server-1/-2 后 IP 会变（实测两个实例的 8082/8083 还对调了），
   不重启 gateway 就是「容器 healthy 但网关 502 / no live upstreams」。
   排查特征：`docker logs qiqi-gateway` 里出现 `upstream: "http://172.21.0.4:8083"`（端口和 IP 对不上号）。

---

## 12. 连接池是不是闸门？—— Hikari maxPoolSize 10 / 15 / 20 / 30 对照实验

第一步的线程栈显示 **190/202 个 http-nio 线程停在 `HikariPool.getConnection`**，由此推出「连接池是闸门、
调大应右移拐点」。本轮**只改这一个变量**做验证，结论是：**假设不成立 —— 连接池是症状，不是闸门。**

### 12.1 怎么只改这一个变量（不改代码、不重建镜像）

```bash
HIKARI_POOL=30 bench/tools/jvm-gc-on.sh      # 环境变量 → SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
MEMBER_PASS='<自选>' STEPS=320,350,380,400,420,450 STEP_DURATION=75 DUMP_AT_RPS=450 \
  LABEL=pool30-r1 bench/tools/avalanche.sh
bench/tools/jvm-gc-off.sh                    # 还原发布态（并会顺手重启 gateway 修 upstream IP）
```

Spring Boot 宽松绑定生效（yml 里没有 hikari 段，所以环境变量就是唯一来源）。
**生效的硬证据是数连接**（`information_schema.processlist`）：pool=10 → 每实例 10 条，pool=30 → 每实例 30 条。

多组并列对比用 `tools/poolcmp.py`（自动从每组 `provenance.txt` 读池大小）：

```bash
python3 bench/tools/poolcmp.py bench/results/2026-11-14-avalanche-pool{10-r1,10-r2,15-r1,20-r1,30-r1,30-r2}
# 产出：并列表、每组拐区起点/饱和点、DB 侧对照、等连接线程数
```

### 12.2 结果（λ=计划入站速率，ok/s=有效吞吐，噪声带 ±2.8%）

| λ | pool10 | pool15 | pool20 | pool30 |
| --: | --: | --: | --: | --: |
| 400 | 399.9 | 399.9 | 399.9 | 393.8 |
| 420 | 403.6 | **407.7** | 406.1 | 392.9 |
| 450 | 400.4 | 398.1 | **404.7** | 390.5 |
| **拐区起点** | 400 rps | 400 rps | 400 rps | 400 rps |
| **饱和点** | 420 rps | 420 rps | 420 rps | 400~420 rps |

**拐区起点和饱和点在四个池子上一模一样 —— 没有任何右移。** 10/15/20 的吞吐差 ≤1%（同一噪声带内，
分不出差别），30 反而低 3~5%。

机制上池子**确实松开了**（最大档 SIGQUIT，450 rps）：

| 池 | 真在跑 SQL 的线程 | 等连接的线程 |
| --: | --: | --: |
| 10 | 9~10 | 190/202（94%） |
| 15 | 12~15 | 172~180/202（85~89%） |
| 20 | 18~20 | 148~172/202（73~85%） |
| 30 | 24~28 | 117~166/202（58~82%） |

「真跑 SQL」精确跟着池大小走 —— 但吞吐不动。多出来的并发全部变成了 **InnoDB 行锁上的排队**：

| 池 | MySQL Threads_running 峰 | 行锁等待/秒 | **平均每次等待** | 累计等待 |
| --: | --: | --: | --: | --: |
| 10 | 22 | 185~191 | **30ms** | 2.1k s |
| 15 | 31 | 223 | 60ms | 5.1k s |
| 20 | 42 | 242 | 94ms | 8.7k s |
| 30 | 61 | 241~255 | **166ms** | 16.3k s |

### 12.3 真正的不变式（本轮最硬的发现）

**每一个**配置、每一个饱和档上，`SQL/s` 都被钉在 **7,300~7,830**，而每单约 **19 条 SQL**，
于是 ok/s ≈ SQL/s ÷ 19 ≈ 400。连「去掉热点 SKU」（`SKU_RATIO=0`，行锁等待从 ~200/s 掉到 19~25/s，降 10 倍）
那一轮，SQL/s 仍是 7,664、ok/s 398。
⇒ 闸门更像 **「每单 ~19 条 SQL × MySQL 这台机约 7.7k QPS」** 这个乘积，而不是连接池、也不是热点行锁本身。
⚠️ 这是**强相关**，还不是因果：本轮没采 MySQL 自身 CPU、没动 SQL 条数。要把它变成因果，
下一步应采 MySQL cgroup CPU + 做「合并/减少每单 SQL」的对照。

### 12.4 当年「hikari 10→30 只 +9%、无机制解释」错在哪？——**它没错**

在死锁已修（`ledger-insert-ignore=true` + 分桶）的镜像上重做，得到的不是 +9%，而是
「10/15/20 无差别、30 低 3~5%」。所以「当年死锁掩盖了连接池收益」这个猜测**不成立**：
死锁修掉之后连接池依然没有收益。当年缺的不是"等死锁修好再试"，而是**机制解释**——现在有了。

### 12.5 推荐取值：保持 10（发布默认），不要动

调大无收益（10/15/20 不可区分），30 是净损失（DB 并发 ×2.8、单次锁等待 ×5、累计等待 ×7.6、吞吐 -3~5%）。
将来要提吞吐，方向是**减少每单 SQL 条数**或**降低热点行串行度**，不是加连接。

### 12.6 本轮踩到的坑（都已修进工具）

1. **60s@100rps 预热不足以热到 320 rps 的工作点**：每组第一轮在 320 档假崩（278~301 ok/s、丢 1400~3100），
   第二轮（热 JVM）就正常 ⇒ **跨组对比必须按同轮次比**；下一轮应把预热改成「在接近首档的负载下热 90s」。
2. **不要在 avalanche.sh 运行期间编辑它**（bash 惰性读脚本）：本轮 pool20-r1 因此在收尾阶段报
   `unexpected EOF`，丢了 GC 快照与自动清场（压测原始数据完好，GC 两列缺失）。
3. 采样器表头曾 echo 到 stdout（`mysql.csv` 缺表头）→ 解析器已做缺表头兼容。
4. `group by 1` 撞聚合函数（ERROR 1056）导致 provenance 的连接数一行为空 → 已改显式子查询。

---

## 13. 闸门是「每单 SQL 条数 × 这台机的语句吞吐」吗？—— 采 MySQL CPU + SQL 画像 + 一处合并对照

上一轮（§12）只拿到**强相关**：每个配置每个饱和档 `SQL/s` 都钉在 7,300~7,830、每单约 19 条，
于是 ok/s ≈ 400。当时明确标注「没采 MySQL CPU、没动 SQL 条数 ⇒ 不是因果」。本轮把它往因果推：
① 采 MySQL 自己的 cgroup CPU/节流；② 用 performance_schema 出「那 19 条到底是哪几条」；
③ 只改**一处** SQL（建单的 INSERT+UPDATE 合成一条），再压同一批档位对撞。

### 13.1 新工具（都是只读观测，不改业务/不重建镜像）

| 工具 | 干什么 | 为什么必须新写 |
| --- | --- | --- |
| `tools/mycpu.sh` | 宿主机直读 **MySQL 容器**的 `cpu.stat`（usage_usec / nr_throttled / throttled_usec）、`cpu.max`、memory、以及**宿主机** `cpu.pressure` + `/proc/stat` 全机忙核数，1s 一次 | `cgwatch.sh` 只采 server-1/-2，DB 侧一直是空白；`docker exec` 每 200ms 起进程本身就是干扰 |
| `tools/sqldigest.sh` | 每 2s 快照 `performance_schema.events_statements_summary_by_digest`（按 SCHEMA 分行、洗掉逗号/换行） | 慢查询日志有阈值、要改配置；DIGEST 聚合天然回答「同一形状执行了几次、总共多久」 |
| `tools/sqlprofile.py` | 按档窗口把两次快照**做差** → 每档每语句的次数/占比/均耗时、每单语句数、driver/read/write 三分类 | 累计计数器必须做差才有意义；口径（样本用压测内部轴 `planned_ms`、档窗口用墙上 `epoch`）踩过一次坑，见 §13.7 |
| `tools/QpsProbe.java` | 单文件 Java 探针：N 条连接闭环保压 `SELECT 1`，输出 qps/p50/p99 | 要一个**与业务代码无关**的外部常量（QPS_cap），否则「改吞吐 + 改每单条数」是自证 |
| `tools/qpscap.sh` | 两段式测 QPS_cap：①机器空闲裸能力 ②**450 rps 下单负载跑着时**的探针 | ①证明机器没变；②才是「业务饱和时 MySQL 还剩多少语句能力」 |
| `tools/sqlmerge.py` | 多轮 A/B 并列表 + 每臂拐区起点/饱和点 + 因果检验（预测 vs 实测） | 单轮数字分不出 ±3% 的差别 |
| `tools/jar-deploy.sh` + `bench/jar-mount.override.yml` | 把 mvn 出来的 jar **只读挂**到 `/app/app.jar` 再重建容器 | 任务要求改业务代码，又禁止重建镜像：挂 jar 是唯一同时满足两者的做法，且 A/B 两臂除 jar 内容外完全同构（同一镜像 digest、同一 JAVA_OPTS、同一 Hikari 10） |

`avalanche.sh` 顺手挂上了这三个采样器（`MYSQL_CGROUP` / `SQLPROFILE` 默认开），
并给 `mysql.csv` **追加**了 6 列（data_fsyncs / log_fsyncs / log_waits / bp_wait_free / rows_updated / rows_inserted，
只能往后追加 —— `avalanche.py` 按位置取前 6 列）。

### 13.2 ① MySQL 自己的 CPU 与 throttle（饱和档，380/400/420/450 每档 75s，6 轮）

| 档 | MySQL 核数（改前 A） | MySQL 核数（改后 B） | `cpu.max` | nr_throttled | 全机忙核 | cpu.pressure some / full |
| --: | --: | --: | --- | --: | --: | --- |
| 380 | 1.39 | 1.06~1.42 | `max 100000` | **0** | 4.00 / 4 | 91% / 0% |
| 400 | 1.44 | 1.31~1.42 | `max 100000` | **0** | 4.00 / 4 | 91% / 0% |
| 420 | 1.44 | 1.42 | `max 100000` | **0** | 4.00 / 4 | 91% / 0% |
| 450 | 1.43 | 1.42 | `max 100000` | **0** | 4.00 / 4 | 91% / 0% |

**回答①：MySQL 在饱和档只用 1.42~1.46 核（4 核的 36%），`cpu.max=max`（没有配额）、`nr_throttled=0`、
`nr_periods=0` —— 节流在物理上不可能发生。所以「DB 算力打满」这个因果不成立。**
但要看清另一半：**这台机 4 核已经是 4.00/4 打满**（app 2×0.71 + MySQL 1.43 + 压测器 0.22 + 平台其它容器），
`cpu.pressure some` 91% 说明到处在等 CPU。而且把**每条语句的耗时**拆开看：
一档里所有语句的 `SUM_TIMER_WAIT` 合计约 800~840s，而 MySQL CPU 只有约 110s ⇒
**86.6% 的语句时间是「等」（行锁/IO/调度），只有 13.4% 在算**。

### 13.3 ② SQL 画像：那「19 条」到底是哪几条（改前 A@420 档，30,199 单）

| # | 语句（DIGEST 归一化后） | 条/单 | 占比 | 均耗时 | 分类 |
| --: | --- | --: | --: | --: | --- |
| 1 | `SELECT @@SESSION.transaction_read_only` | **7.04** | **35.0%** | 0.14ms | driver |
| 2 | `SET autocommit = ?` | **2.01** | **10.0%** | 0.12ms | driver |
| 3 | `COMMIT` | 1.00 | 5.0% | **4.11ms** | driver |
| 4 | `INSERT INTO order` | 1.00 | 5.0% | 0.52ms | write |
| 5 | `INSERT INTO order_status_ledger`（出生台账） | 1.00 | 5.0% | 0.53ms | write |
| 6 | `INSERT IGNORE INTO stock_ledger` | 1.00 | 5.0% | 0.53ms | write |
| 7 | `UPDATE sku_bucket SET stock=stock-?` | 1.00 | 5.0% | **16.84ms** | write |
| 8 | `UPDATE stock_ledger SET bucket_no=...` | 1.00 | 5.0% | 0.57ms | write |
| — | （其余）`INSERT order_item` / `UPDATE order`(总额回填) / `SELECT sku` / `SELECT product` / `SELECT order_item`(明细回查) 各 1.00 条 | 5.00 | 25.0% | 0.3~0.6ms | — |
| | **合计** | **20.10** | 100% | | |

- **driver 10.05 条（50%）**：其中 7 条是 Connector/J 在**每条 DML 之前**发的一句
  `SELECT @@session.transaction_read_only`（`useLocalSessionState=false` 的默认行为）。
  ⇒ 删一条 DML 实际省的是 **2 条语句**（DML + 它的探针），这是选合并点时最关键的一条画像结论。
- 按**时间**排则是另一个故事：`UPDATE sku_bucket` 一档累计 510s（占全部语句时间 **63.8%**），
  `COMMIT` 127s（15.9%），其余 20% —— 时间几乎全花在等那把热点行锁上（与 §12 的行锁数据一致）。
- 也解释了「19 vs 我数出来的 10 条业务语句」：19~20 条里有一半是驱动/池自己的往返，不是业务 SQL。

### 13.4 ② 唯一改的那处合并 + mvn

**一句话**：建单不再「先插 `total_amount=0`、扣完库存再 `UPDATE order` 回填总额」，
改成把读（SKU/商品/价格）前置、总额算好后**一条 INSERT 写对** —— 少 1 条 DML、连带少 1 条 read_only 探针。

```java
// 改前：两条 DML
order.setTotalAmount(BigDecimal.ZERO);
orderService.save(order);          // INSERT order(total=0)
for (ItemInput in : inputs) { ...deductStock...; total = total.add(...); }
orderItemService.saveBatch(items);
order.setTotalAmount(total);
int rows = orderService.getBaseMapper().updateById(order);   // ← 这条消失
OptimisticLockGuard.requireAffected(rows, MSG_RECORD_DELETED);

// 改后：一条 DML（读前置 → 总额算完 → 一次 INSERT → 再扣库存/写明细）
for (ItemInput in : inputs) { sku=getById; product=getById; resolved.add(...); total=total.add(...); }
if (resolved.isEmpty()) throw new IllegalStateException("商品信息无效，无法下单");
order.setTotalAmount(total);
orderService.save(order);          // INSERT order(total=正确值)
...deductStock / build items...
orderItemService.saveBatch(items);
```

等价性（写在类注释里，逐条对齐）：落库终态逐列相同；失败语义相同（整笔 REQUIRES_NEW 回滚）；
读的快照相同（同一事务内的一致性读，价格/商品名取值不变）；明细为空时抛异常的位置前移但提交状态同样为空。
**代价如实记**：那条 `updateById` 后面跟的 `requireAffected(MSG_RECORD_DELETED)` 一起消失了 ——
它在本事务里不可达（同事务内没有 DELETE order，未提交的 INSERT 对其他事务不可见），
但确实是一条被删掉的防御性检查。

`mvn -B clean test`：**Tests run: 246, Failures: 0, Errors: 0 —— BUILD SUCCESS**（含下单契约/库存/台账/幂等全量集成测试）。

### 13.5 ③ 对照阶梯（每档 75s，预热 90s@350 rps，热点 SKU 0.8，Tomcat 200 / Hikari 10 / 镜像未变）

`ok/s` 每臂 3 轮原始值 + 中位数（噪声带 ±2.8%；420/450 三档两臂都可比，
380/400 的 r1/r2 是**冷 JVM 假崩**，见 §13.7，只有热机轮可比 —— 这两档两臂都正好压满目标，本来也没有差别可比）：

| λ | A 改前 r1/r2/r3 | **A 中位** | B 改后 r1/r2/r3 | **B 中位** | Δ |
| --: | --- | --: | --- | --: | --: |
| 380 | 379.9 / 360.1* / 379.9 | 379.9 | 285.5* / 286.8* / 380.0 | (380.0 热机) | 0%（都压满） |
| 400 | 400.0 / 392.6* / 399.9 | 399.9 | 369.1* / 352.2* / 399.9 | (399.9 热机) | 0%（都压满） |
| 420 | 402.7 / 397.0 / 400.5 | **400.5** | **419.9 / 419.9 / 419.9** | **419.9** | **+4.8%** |
| 450 | 399.6 / 393.6 / 398.4 | **398.4** | **434.1 / 446.2 / 441.0** | **441.0** | **+10.7%** |

（*冷启动轮，见 §13.7；B@420 三轮完全一致 419.9 = 目标值、丢弃 0，说明饱和点已经不在 420。）

| 指标 | A 改前（450 档） | B 改后（450 档） |
| --- | --: | --: |
| ok/s（中位） | 398.4 | **441.0** |
| p50 / p99 | 4.6s / 7.2~8.0s | 3.6s / 6.4~7.0s |
| 每单语句数 | 20.62 | **18.77**（−9.0%） |
| **SQL/s（语句率）** | **7,703~7,767** | **7,575~7,716**（−0.4%，没变） |
| MySQL 核数 | 1.43 | 1.42（没变） |
| 行锁等待/s | 190.3 | 193.5（没变） |
| fsync/s（data / log） | 900 / 855 | 914 / 868（没变） |
| after-GC 堆峰 / GC% | 95MB / 0.8% | 93MB / 0.9%（没变） |

**拐区起点 / 饱和点**（判据沿用 §11.3 的弹性版：弹性 = Δok/s ÷ Δλ < 0.5 判饱和；拐区起点 = p50 首次 ≥1s）：

| | 拐区起点 | 饱和点 |
| --- | --- | --- |
| A 改前 | **400 rps**（p50 319ms → 2337ms，×7.3） | **420 rps**（弹性 0.06） |
| B 改后 | **450 rps**（p50 60→241→649→3570ms） | **>450 rps**（450 档弹性 0.71，仍跟着涨） |

### 13.6 外部常量 QPS_cap：它变没变？预测 vs 实测

先解决**自证陷阱**：合并既然改的是「每单语句数」，就不能只用「吞吐涨了」当结论 —— 必须把
`QPS_cap` 当外部常量先量出来。

**① 裸能力（与业务代码完全无关：`QpsProbe` 10 条连接闭环保压 `SELECT 1`，20s，机器空闲）**

| | 第一次 | 第二次 | 结论 |
| --- | --: | --: | --- |
| A 改前 | 21,832 qps | 22,026 qps | 一致 |
| B 改后 | 21,419 qps | 21,694 qps | 一致（两臂差 ≤2%） |

⇒ **机器/MySQL 的裸语句能力在合并前后没变，整组对照成立。**

**② 饱和负载下的探针（450 rps 下单负载跑着，同一探针 10 连接 × 25s）**

| | 第一次 | 第二次 |
| --- | --: | --: |
| A 改前 | 9,762 qps | 14,185 qps |
| B 改后 | 14,476 qps | 8,814 qps |

⇒ **这个量噪声极大（同一臂两次差 45%），分辨不出两臂差别，不能当外部常量用** —— 如实写在这里，
免得后面的人拿它做结论。（它也确实说明：MySQL 在业务饱和时还有 9k~14k 的纯读语句余量，
所以「8k 就是 MySQL 的算力上限」这个说法是错的，见 §13.7 结论。）

**③ 于是常量改用「阶梯里直接量到的语句率」**（`global_status.Queries` 增量 ÷ 档时长，不是从 ok/s 反推）：
A 8,215 语句/s（450 档，= 398.4 × 20.62）、B 8,277（= 441.0 × 18.77）—— **两臂差 0.8%，常量没动。**

**预测 vs 实测（先用 A 的常量算，再去压测对撞）**

```
预测：ok/s_B = QPS_cap ÷ 语句/单_B = 8215 ÷ 18.77 = 437.5
实测：441.0              → 偏差 +0.8%
反向：ok/s_A = 8215 ÷ 20.62 = 398.4  实测 398.4  （完全吻合）
```

结论不是「改完更快了」，而是「**改完快到了不变式预测的位置**」：+10.7% 的吞吐 ≈ 1/0.91 的语句数倒数。

`tools/sqlmerge.py` 把三张三轮的原始值一起喂进去，450 档直接给出这个对撞（判据自带 ±25% 的一致性带）：

```
λ=450: 语句/单 20.71 → 18.49（预测 ok/s ×1.120）  实测 ×1.109  比值 0.99 ⇒ 与不变式一致
λ=420: 语句/单 20.64 → 18.60（预测 ok/s ×1.110）  实测 ×1.050  比值 0.95 ⇒ 与不变式一致
λ=380/400: 比值 0.61 / 0.86 ⇒ **不一致**，原因是这两档混进了冷启动轮（见 §13.7），剔除后无差别可比
（两臂这四档的 SQL/s 中位都落在 7.5~7.7k，MySQL 核数都落在 1.4 上下 —— 常量没动，动的是每单条数）
```

### 13.7 结论：不变式**部分成立** —— 关系成立，机制被改写

- **关系成立**：`ok/s ≈ 语句率上限 ÷ 每单语句数`，两臂的乘积都落在 8.2k（8215 / 8277，差 0.8%），
  预测 437 vs 实测 441（+0.8%）。压 SQL 条数**确实**能换吞吐，且换到的量等于条数倒数。
- **机制被推翻一半**：闸门**不是**「MySQL 算力打满」——
  MySQL 只用 1.42~1.46 核（4 核的 36%）、没有配额、`nr_throttled=0`、
  而且业务饱和时还能额外跑 9k~14k 条 `SELECT 1`/s；86.6% 的语句时间在等而不是在算。
- **更像的机制**：这台 **4 核机已经被 4.00/4 吃满**（app 1.42 + MySQL 1.43 + 压测器 0.22 + 平台容器 + 采样器），
  `cpu.pressure some` 91%。于是「每秒能承担多少条语句」是**整机预算**（≈8.2k 条/s），
  每条语句（应用侧 JDBC/MyBatis/Spring + DB 侧解析执行）摊一份固定 CPU；
  **少一条语句就少一份预算占用** ⇒ 吞吐按 1/条数 上升。这也解释了上一轮池子调大为什么没用
  （并发上去了，但总预算不变，多出来的并发全变成行锁排队：2.1k s → 16.3k s）。
- **下一步最该做的（按性价比）**：
  1. **关掉那 7 条 `SELECT @@session.transaction_read_only`**：Connector/J 的
     `useLocalSessionState=true`（JDBC URL 参数）。它一条不占业务语义，却是 34% 的语句
     —— 按不变式预测 ok/s ≈ 8215 ÷ 13.1 ≈ **627**。⚠️ 这会改变驱动对「会话状态」的信任方式
     （外部工具改 autocommit/read-only 会失联），必须单独做一轮对照 + 全量回归，本轮**没做**。
  2. 把 `COMMIT` 的成本往下压（4.1ms/次、占语句时间 16%）：这是**提交落盘**的钱，
     与 SQL 条数无关；改 `sync_binlog`/`innodb_flush_log_at_trx_commit` 属于可用性取舍，不是性能实验能顺手定的。
  3. 真要提吞吐上限，先把这台机从 4 核里腾出来（平台其它 15 个容器），或者给 MySQL 独占核；
     「加连接」已被证伪、「热点行锁」已被证伪、单条 SQL 合并的收益已经吃到（+10.7%）。

### 13.8 本轮踩到/修掉的坑（都已在工具里留门禁）

1. **`events_statements_history_long` 忘了关 → 直接把系统压垮**。我为取语句顺序临时开了这个 consumer，
   忘了关就跑第一轮：380 档 ok/s 273.5、丢 7,986、SQL/s 只有 5,505（离不变式的 7.7k 差很远）。
   ⇒ 它给**每条语句**写一行环形缓冲，等于给 MySQL 套了一层审计。
   现在 `avalanche.sh` 开跑前**硬门禁**：这个 consumer 是 YES 就直接退出，不许带病跑。
2. **冷 JVM 会把前两档打假**（§12.6 那条坑的升级版）：容器重建后 1~2 分钟内开跑，
   380 档 ok/s 285~287、SQL/s 只有 4.9~5.4k，**根本不在不变式的墙上**。
   ⇒ 判「这一轮能不能用」的新口径不是看它崩没崩，而是看**该档 SQL/s 是否已到 7.5~8.3k**；
   不到就说明是被冷启动/预热拖的，不是被闸门限的。A 组的 r1 与 r3 是热机轮（相差 ≤0.6%），
   B 组 r3 是热机轮；B 组 r1/r2 的 380/400 两档已按此口径作废（420/450 不受影响，冷启动在热身后 ~1.5%）。
3. **同一个 λ 放两档会让 `avalanche.py` 崩**（弹性 = Δok/s ÷ 0 → None → 格式化异常）：
   我试过「380 烧机档 + 380 测量档」的协议，直接踩到。已改成对 `None` 打 `n/a（λ 与上一档相同）`。
4. **`Com_commit` 不在 `performance_schema.global_status` 里**（MySQL 8.4）：一个不存在的变量让整个
   `concat()` 变 NULL，`mysql.csv` 整列写不出来。已换成 `Innodb_buffer_pool_wait_free`，
   并保留「前 6 列位置不变」的兼容约束。
5. **`sqlprofile.py` 混用了两个时间轴**：样本的 `planned_ms` 是压测内部轴（从 0 起），档窗口的
   `epoch_start/end` 是墙上毫秒 —— 混用得到「每单 0.00 条」这种假数字。已改为样本用 `start_ms/end_ms`。
6. **jar 挂载要 644**：`target/*.jar` 是 `0600 root:root`，容器以 `USER app` 运行，直接挂上去 JVM 读不了。
   `jar-deploy.sh` 复制到 `$LG/jar/<label>/app.jar` 并 `chmod 644`，再用 md5 核对容器内外的确是同一份。

### 13.9 本轮产物

`results/2026-11-14-avalanche-sqlbase-r{1,2,3}/`（改前，jar md5 `2176d5fc…`）、
`results/2026-11-14-avalanche-sqlfix-r{1,2,3}/`（改后，jar md5 `2e1a77ab…`）：
每轮含 `ladder.csv/.txt`、`persecond.csv`、`cg.csv`、`gc_*.csv`、`mysql.csv`（12 列）、
**`mycpu.csv`**（MySQL CPU/节流）、**`sqldigest.csv` + `sqlprofile.csv/.txt`**（SQL 画像）、`provenance.txt`（含容器内外 jar md5）。
`/tmp/loadgen/qpscap/{armA,armA2,armB,armB2}/qpsprobe.txt` 是外部常量探针的原始输出（不入库，重跑见 §13.1）。

⚠️ **`sqldigest.csv` 已 gzip**（每轮 2.7MB → 约 0.25MB；6 轮合计省 ~15MB）。要重算画像先解压：
`gunzip -k bench/results/<轮>/sqldigest.csv.gz` 再跑 `sqlprofile.py`。
`samples.csv`（逐请求明细）仍按 §4 的老规矩**不入库**，只在本机留全量。

一条命令复现（先 `tools/jar-deploy.sh <jar> <label>`，再）：

```bash
MEMBER_PASS='<自选>' STEPS=380,400,420,450 STEP_DURATION=75 WARMUP=90 WARMUP_RPS=350 \
  LABEL=sqlmerge-r1 bench/tools/avalanche.sh
python3 bench/tools/sqlprofile.py bench/results/<日期>-avalanche-sqlmerge-r1
python3 bench/tools/sqlmerge.py --a results/<改前…r1> results/<改前…r2> --b results/<改后…r1> results/<改后…r2>
```
