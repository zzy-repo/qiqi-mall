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
