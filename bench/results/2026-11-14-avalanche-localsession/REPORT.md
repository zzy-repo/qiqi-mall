# 雪崩实验 · 最后一轮：`useLocalSessionState=true` 能不能把 441 推到 627？

**结论先行（三句话）**

1. **安全上可以做**：全仓 grep 没有任何「绕过驱动改会话状态」的点；并且我在**真的开着这个参数**的容器上跑通了
   「下单 → 库存不足回滚 → 支付 → 重复支付幂等」四条链路，数据 100% 对得上。
2. **改动确实生效**：`SELECT @@SESSION.transaction_read_only` 探针从 **5.80 条/单** 掉到 **0 条/单**（digest 整条消失，
   不是"变小"），每单语句数 18.2~18.9 → 12.2~12.7。
3. **但吞吐没到 627**：天花板从 **447.6 ok/s → 494.4 ok/s（+10.5%）**，实测中位 494.4 vs 预测 627，**误差 −21%**。
   → **可以采用这个参数（安全、正确、+10.5%），但不能拿 627 做容量规划**；不变式 `ok/s = QPS_cap ÷ 语句/单`
   作为预测工具**不成立**，原因见 §3.4（QPS_cap 不是常量，它是同一条「20 连接 ÷ 事务时长」方程的另一种写法）。

---

## 0. 一句话时间线（独占窗口 05:47 → 07:45，每轮都在 bench-lock 下跑；全程机器独占）

| 步骤 | 时间 | 产物 |
| --- | --- | --- |
| 第一步 grep 安全证据 | 05:51 | `security-grep.txt` |
| 部署 A 臂（合并版 jar，URL 不带参数） | 05:52 | 容器内 jar md5 `2e1a77ab…`，镜像未重建 |
| 探针验证 A 臂（300rps×40s） | 05:53 | `probecheck-armA/` → **5.80 条/单** |
| 切 B 臂（同 jar，URL 加 `useLocalSessionState=true`） | 05:54 | 容器内 `SPRING_DATASOURCE_URL=…&useLocalSessionState=true` |
| 探针验证 B 臂 | 05:55 | `probecheck-armB/` → **0 条/单** |
| 写死预测 | 05:58 | `PREDICTION.txt` |
| B 臂阶梯 ×3（420/450/480/520/560/600，75s/档，预热 90s@378） | 05:57–06:27 | `localsessB-r{1,2,3}/` |
| B 臂数据正确性 + 业务链审计 | 06:31 | `data-audit.txt`、`flow-audit/` |
| A 臂阶梯 ×3（同阶梯） | 06:45–07:24 | `localsessA-r{1,2,3}/`（另有 2 轮作废，见 §5） |
| `mvn -B clean test` | 07:21–07:33 | **Tests run: 246, Failures: 0, Errors: 0 — BUILD SUCCESS** |
| 还原（URL / jar 挂载 / GC 日志 / 池变量）+ DB 清干净 | 07:35 | 见 §6 核对 |

---

## 1. 第一步：安全证据（这决定能不能做）

命令与全部命中：`bench/results/2026-11-14-avalanche-localsession/security-grep.txt`（grep -rniIE，全仓，排除
`.git/node_modules/target/dist/.venv` 与 `bench/results`；覆盖 `*.java/*.sql/*.xml/*.yml/*.yaml/*.properties/*.sh/*.ts/*.js/*.mjs/*.md`）。

| # | 探针模式 | 命中 | 结论 |
| --- | --- | --- | --- |
| ① | `SET autocommit` | **1**（`bench/README.md:646`，只是文档里那张 SQL 画像表格） | **无风险**：不是可执行代码 |
| ② | `SET SESSION` | **0** | 无风险 |
| ③ | `SET TRANSACTION` | **0** | 无风险 |
| ④ | `SET @@` | **0** | 无风险 |
| ⑤ | `SET GLOBAL` | **0** | 无风险 |
| ⑥ | `setAutoCommit(` | **3**（全在 `src/test`：`OptimisticLockIntegrationTest:1863`、`PaymentIdemKeyTest:687,710`） | **安全**，见下 ⑥ 详解 |
| ⑦ | `setTransactionIsolation(` | **0** | 无风险 |
| ⑧ | `setReadOnly(` | **0** | 无风险（也解释了为什么画像里没有 `SET SESSION TRANSACTION READ ONLY`） |
| ⑨ | `@Transactional(isolation` / `isolation =` | **0** | 无风险：`src/main` 里 37 处 `@Transactional` 注解只有两种属性：`rollbackFor`(35)、`REQUIRES_NEW`(2)，**没有一处指定隔离级别** |
| ⑩ | `getConnection(` | **5**：3 处 `src/test`、`bench/tools/QpsProbe.java`（我们自己的探针工具）、README 一段线程栈 | **安全**，见下 ⑥/⑩ 详解 |
| ⑪ | `DriverManager` | **1**（`bench/tools/QpsProbe.java`，独立进程，与业务连接无关） | 无风险 |
| ⑫ | `TransactionTemplate` | **6**：`PaymentRecordServiceImpl`（生产，REQUIRES_NEW）+ 测试 | **安全**：仍走 Spring 的 `DataSourceTransactionManager` → 事务边界由**驱动 API** 控制，驱动自己的本地视图同步更新 |
| ⑬ | `DataSourceUtils` / `connection-init-sql` / `sessionVariables` | **0** | 无风险：Hikari 没有配 `connection-init-sql`，连接池不会在借出/归还时执行任何 `SET` |
| ⑭ | `SET NAMES` / `SET time_zone` / `SET sql_mode` | **0** | 无风险 |
| ⑮ | `SET @x`（**用户变量**） | **78**，分布在 `sql/seed-order-audit-demo.sql`(26)、`sql/reset-sku-bucket*.sql`(20)、`sql/_rollback/migrate-*.sql`(22)、`V2__channel_sync_idem.sql`(6)、`bench/tools/prepare.sh`(1) | **安全（但要看清是哪一种 `SET`）**：`SET @db := …` 是**用户自定义变量**，不属于驱动缓存的三样会话状态（autocommit / transaction isolation / read-only），`useLocalSessionState` 完全不碰它。其中 `V2__channel_sync_idem.sql` 是 Flyway 迁移（启动时跑一次，用 `SET @db/@s/@has_col/@ddl` + PREPARE 拼 DDL），其余是独立 mysql 客户端的 seed/reset 脚本（**另一个会话**，物理上不可能影响应用的连接） |

**⑥ 详解（唯一需要解释的三处 `setAutoCommit`）**——这是本轮唯一"需要额外验证"的点，结论是安全：

```java
// OptimisticLockIntegrationTest:1862-1863（造并发窗口：攥住行锁不提交）
Connection conn = dataSource.getConnection();
conn.setAutoCommit(false);
// PaymentIdemKeyTest:686-687 / 709-710（另开一条自动提交连接写"第三方已提交数据"）
try (Connection conn = dataSource.getConnection()) { conn.setAutoCommit(true); ... }
```

- 这三次都是**通过驱动自己的 API** 改的（`Connection.setAutoCommit`），而 `useLocalSessionState=true` 的语义正是
  「以 `Connection.setAutoCommit()` / `setTransactionIsolation()` 设定的内部值为准，不去问服务器」——
  **驱动自己的 API 会更新它自己的本地视图**，所以 `commit()`/`rollback()` 仍然正确。
- 真正危险的形态（本轮**一处都没有**）是：应用用**别人**的手改状态，例如 `stmt.execute("SET autocommit=0")`、
  MyBatis 里写 `<update>SET SESSION …</update>`、或连接池 `connection-init-sql`。这三种在全仓都是 0 命中。
- 落库核对（本轮实测）：这两条测试都在 `mvn -B clean test` 里跑过（246 tests 全绿），`OptimisticLockIntegrationTest`
  正是靠这条"独立连接攥锁"来造 409 并发窗口的 —— 它过了，说明边界语义没坏。

**⑩ 详解**：生产代码 `src/main` 里**没有任何** `getConnection()`／`JdbcTemplate`／`DataSource` 注入 —— 所有数据库访问都走
Spring 事务代理 + MyBatis-Plus。也就是说**不存在绕过 Spring 事务的原生 JDBC 路径**（这正是这个参数最怕的东西）。

**第一步结论：安全上支持继续。** 没有发现不安全点，因此没有停在第一步。

---

## 2. 第二步：改动生效的**硬证据**（不是吞吐）

注入方式：`bench/jdbc-url.override.yml` + `bench/tools/jdbc-url.sh`
→ 只给 server-1/-2 加一个环境变量 `SPRING_DATASOURCE_URL`（Spring Boot 宽松绑定，
优先级高于 `application-test.yml`），**jar 不动、镜像不动、池不动、JAVA_OPTS 不动**。

容器里**实际**的变量（`docker inspect` 读的，不是我以为的）：

```
A 臂：SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/qiqi_mall_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
B 臂：SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/qiqi_mall_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useLocalSessionState=true
两臂 jar md5 都是 2e1a77ab7cdaa91be4881762f749c553（上一轮合并版），镜像 digest 全程未变：sha256:935feb00…
```

**语句级证据**（跑 300 rps × 40s，performance_schema DIGEST 做差；产物 `probecheck-armA/`、`probecheck-armB/`）：

| 指标（300 rps 档） | A 臂（默认 false） | B 臂（true） | 变化 |
| --- | --- | --- | --- |
| 每单语句数 | **17.38** | **11.43** | −5.95 条/单（−34%） |
| `SELECT @@SESSION.transaction_read_only`（Connector/J 每条 DML 前的探针） | **5.80 条/单**（窗口内 50,610 次，占全部语句 **33.4%**） | **0 条/单**（该 DIGEST 在 B 臂**整条不存在**） | **探针彻底消失** |
| `SET autocommit = ?` | 1.93 条/单 | 1.90 条/单 | **没变**（`useLocalSessionState` 只省"问服务器"，不省这句） |
| `COMMIT` | 0.96 条/单 | 0.95 条/单 | 没变 |
| driver 类语句合计 | 8.69 条/单 | **2.85 条/单** | −5.84（≈探针数） |

同一件事在正式阶梯（450~600 档）上的复核（`sqlprofile.txt`）：A 臂 driver **9.0~9.6 条/单**，
B 臂 driver **3.0~3.2 条/单**，差额 6.0~6.4 = 探针数；read 3.0/3.1 与 write 6.0~6.4 **两臂完全一致** ——
说明**只掉了探针，业务 SQL 一条没变**。

> 为什么必须看这个：吞吐会被 JIT/热机/GC/锁竞争污染，而"某个 DIGEST 从 5.80 条/单 变成 0"是语句级的直接观察。
> 这条证据拿到之后，后面的吞吐才值得看。

---

## 3. 第三步：三档中位数对照 + 预测对撞

### 3.1 阶梯（每档 75s，预热 **90s@378rps**；热点 SKU 0.8、Tomcat 200、Hikari 10、镜像未变）

**A 臂**（合并版 jar，URL 不带参数；3 轮）

| λ | ok/s 逐轮 | 中位 | p50 | p95 | p99 | 语句/单 | 探针/单 | SQL/s | 行锁/s | 锁等ms | MySQL 核 |
| --: | --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 420 | 419.9 / 419.9 / 419.9 | **419.9** | 180 | 540 | 833 | 18.91 | 6.34 | 7,618 | 201 | 23 | 1.43 |
| 450 | 437.2 / 449.9 / 449.9 | **449.9** | 1760 | 4066 | 4810 | 18.20 | 6.08 | 7,749 | 199 | 26 | 1.45 |
| 480 | 432.8 / 434.0 / 447.0 | **434.0** | 4606 | 6690 | 7361 | 18.79 | 6.27 | 7,558 | 195 | 28 | 1.41 |
| 520 | 429.1 / 431.6 / 435.9 | **431.6** | 5227 | 6992 | 7786 | 18.36 | 6.12 | 7,476 | 189 | 28 | 1.40 |
| 560 | 416.8 / 445.2 / 454.3 | **445.2** | 5380 | 6915 | 7587 | 18.58 | 6.20 | 7,796 | 197 | 26 | 1.44 |
| 600 | 420.7 / 447.6 / 451.1 | **447.6** | 5444 | 7476 | 8228 | 18.76 | 6.26 | 7,794 | 195 | 26 | 1.45 |

**B 臂**（同一 jar，URL 加 `useLocalSessionState=true`；3 轮）

| λ | ok/s 逐轮 | 中位 | p50 | p95 | p99 | 语句/单 | 探针/单 | SQL/s | 行锁/s | 锁等ms | MySQL 核 |
| --: | --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 420 | 413.4 / 419.9 / 420.0 | **419.9** | 70 | 1716 | 2667 | 12.42 | 0.00 | 5,104 | 172 | 19 | 1.26 |
| 450 | 449.8 / 449.9 / 449.9 | **449.9** | 92 | 267 | 425 | 12.70 | 0.00 | 5,497 | 209 | 18 | 1.34 |
| 480 | 479.9 / 479.9 / 479.9 | **479.9** | 326 | 1016 | 1519 | 12.49 | 0.00 | 5,738 | 224 | 24 | 1.37 |
| 520 | 475.6 / 501.4 / 508.4 | **501.4** | 3345 | 6160 | 6900 | 12.25 | 0.00 | 5,718 | 214 | 25 | 1.36 |
| 560 | 471.4 / 484.6 / 491.9 | **484.6** | 4776 | 7119 | 8045 | 12.33 | 0.00 | 5,646 | 212 | 26 | 1.33 |
| 600 | 493.5 / 494.4 / 500.7 | **494.4** | 5215 | 7023 | 7722 | 12.47 | 0.00 | 5,673 | 213 | 26 | 1.35 |

**并排（Δ = B 相对 A，中位）**

| λ | A ok/s | B ok/s | Δ | A p99 | B p99 |
| --: | --: | --: | --: | --: | --: |
| 420 | 419.9 | 419.9 | +0.0%（两臂都压满目标） | 833 | 2667 |
| 450 | 449.9 | 449.9 | +0.0%（两臂都压满目标） | 4810 | 425 |
| 480 | 434.0 | 479.9 | **+10.6%** | 7361 | 1519 |
| 520 | 431.6 | 501.4 | **+16.2%** | 7786 | 6900 |
| 560 | 445.2 | 484.6 | **+8.9%** | 7587 | 8045 |
| 600 | 447.6 | 494.4 | **+10.5%** | 8228 | 7722 |

- **A 臂的天花板** ≈ **447.6 ok/s**（480 档起就开始丢请求：3,025→12,397 条/档）
- **B 臂的天花板** ≈ **494~501 ok/s**（480 档还能 0 丢弃压满，520 档才开始丢）
- **净收益 +10.5%（600 档）/ +16.2%（520 档），峰值 501.4**

### 3.2 预测 vs 实测（预测在 05:58、看任何阶梯数据之前写定）

`PREDICTION.txt` 原文两个口径都记了，这里都算误差：

| 预测口径 | 每单语句数 | 预测 ok/s | 实测中位（600 档） | 误差 |
| --- | --: | --: | --: | --: |
| ① 任务书口径：20.10 − 7.04 = 13.1（**合并前**画像） | 13.1 | **627** | **494.4** | **−21.1%** |
| ② 本轮 probecheck 实测（合并后 17.38 − 5.95） | 11.43 | **719** | **494.4** | **−31.2%** |
| A 臂对照（同公式，用来证明公式在 A 臂上是准的） | 18.20~18.91 | 434~451 | 419.9~447.6 | −3.6% ~ +2.2% |

**公式在 A 臂上准到 ±3%，在 B 臂上差 −21%~−31%。** 这不是"跑崩了"，是公式的前提被打破了 —— 见 §3.4。

### 3.3 门禁的诚实交代（我改了判据，理由在此）

上一轮的门禁是「该档 SQL/s < 7,500 ⇒ 判冷启动，数据作废」。**这条绝对阈值不能套到 B 臂**：
它当年是按「19~20 条/单」标定的墙，而 B 臂每单只有 12.4 条，它的墙本来就在 **5.1k~6.3k** —— 套绝对阈值会把 B 臂
**每一档**都误判成冷启动（`localsess.py` 第一版就是这样，输出里 B 臂一排 ⚠冷）。

本轮改成两条（都在 `localsess.py` 里写成代码，不是事后挑）：

1. `该档 SQL/s < 本臂各档 SQL/s 中位数 × 0.85` ⇒ **冷启动（SQL/s 没爬到本臂的墙）**；
2. `该档没达成目标（<99%）且有丢弃` ⇒ **已到天花板（饱和）** —— 这正是我们要找的点，不是要作废的点。

按新判据：A 臂 420/450 两档干净（0 丢弃、100% 达成）；B 臂 420/450/480 三档干净（600 档的 B-r1 420 档
是唯一一次冷启动：4,982 SQL/s、丢 495、只跑到 413.4，它的 520/560/600 不受影响）。

### 3.4 不变式为什么不成立（本轮最重要的发现）

把每臂的「ok/s × 语句/单」算出来 —— 这就是上一轮说的"常量"：

| λ | A：ok/s × 语句/单 | B：ok/s × 语句/单 |
| --: | --: | --: |
| 480 | 434.0 × 18.79 = **8,154** | 479.9 × 12.49 = **5,995** |
| 520 | 431.6 × 18.36 = **7,922** | 501.4 × 12.25 = **6,141** |
| 600 | 447.6 × 18.76 = **8,398** | 494.4 × 12.47 = **6,165** |

**"常量"从 7.9k~8.4k 掉到 5.2k~6.2k（−27%）**，而语句数掉了 33%。也就是说：这个量**几乎与每单语句数成正比**，
它不是外部常量，它是**同一条方程换了个写法**：

```
上一轮：ok/s × 语句/单 = 8,215  ←  当成"机器的语句预算"
真相  ：吞吐 = 20 条数据库连接 ÷ 每单事务时长
        ⇒ ok/s × 语句/单 = 20/T × 语句/单        （T = 每单事务墙钟时长）
        ⇒ 只要 T 基本不变，"常量"就必然跟着语句/单一起变
```

用本轮实测的数字把这条方程钉死（600 档，`sqlprofile.csv` + `provenance.txt` 的 10 连接/实例）：

| | A 臂 | B 臂 |
| --- | --: | --: |
| 每单语句时间（SUM_TIMER_WAIT/单） | 22.1~25.2 ms | 20.7~21.3 ms |
| 派生事务墙钟时长 = 20 连接 ÷ ok/s | **44.7~47.5 ms** | **40.5 ms**（−10~15%） |
| 语句时间占事务时长 | ~53% | ~51% |
| 600 档 ok/s | 447.6 | 494.4 |

旁证（三条独立证据，都指向同一个闸门）：

1. **线程栈**：600 rps 档对两个实例各发一次 SIGQUIT，`threaddump-summary.csv`：
   server-1 **191/202**、server-2 **187/202** 个 `http-nio` 线程停在 `HikariPool.getConnection`（92.6%~94.6%），
   真正在跑 SQL 的只有 **8~9 个** —— 与 A 臂上一轮的形状（190/202、9~10 个）**一样**。
2. **连接数**：`provenance.txt` 里 MySQL 侧 `172.21.0.4 -> 10 条`、`172.21.0.5 -> 10 条`（共 20），
   没有被池配置或参数改变。
3. **DB 侧没被打爆**：MySQL 只用 **1.26~1.45 核**（4 核的 32~36%）、`nr_throttled=0`（无节流）、
   行锁等待 189~224/s、平均每次等 18~28ms、COMMIT 的 fsync 908~957/s —— **两臂的 DB 侧画像几乎重合**，
   只有语句率下降了（7.7k → 5.7k），正好等于"事务没变快多少，但每单少了 6 条语句"。

**为什么少 6 条语句只换到 +10% 而不是 +42%**：那 6 条探针每条 0.1~0.15ms，一共值 ~0.9ms，
而每单事务墙钟是 **40~47ms**，其中：
`UPDATE sku_bucket` 单条就 12~17ms（热点行锁）、`COMMIT` 4.3ms（落盘）、其余语句 <3ms，
剩下 **约 20ms 是应用侧**（Spring/MyBatis/JDBC 往返 + 4 核满负荷下的 CPU 争用：宿主机 **4.00/4 核**、
`cpu.pressure some 92%`）。
省 0.9ms 在 45ms 上就是 **~2%** —— 这和 450 档的实测对得上：
该档两臂中位都是 449.9（都被"目标值"顶住，看不出差别），但看**没压满的那一轮**，
A-r1 是 437.2（丢 955 条）→ B 臂三轮 449.8/449.9/449.9（0 丢），即 **+2.9%**。
天花板能多出 10~16%，是因为"少 6 次往返"把事务时长里的**抖动**也压小了，拐点右移。

---

## 4. 第四步：安全性与数据正确性复核（不只信 TPS）

### 4.1 `mvn -B clean test`

```
[INFO] Tests run: 246, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（含下单契约/库存/台账/幂等/并发乐观锁全量集成测试，`/tmp/mvn-test.log`）

> ⚠️ 诚实标注：**这套测试跑的是"没有该 URL 参数"的测试配置**（测试类里把 `spring.datasource.url` 写死成
> `127.0.0.1` 的库），所以它证明的是「代码没坏」，**不**证明「驱动信任本地会话状态后语义仍对」。
> 后者由下面 4.2/4.3 两条在**真的开着参数**的容器上补上。

### 4.2 压测后数据正确性（`data-audit.txt`，口径：只统计 `lg_%` 会员名下的单，31,496 单）

| 检查 | 结果 |
| --- | --- |
| 订单 ↔ 明细行数 | 订单 31,496 / 明细 31,496；明细数≠1 的订单 **0**；孤儿明细 **0** |
| 金额 | `total_amount` = Σ(价格×数量) 不一致 **0**；`total_amount ≤ 0` 的 **0**（min 249.99 / max 2299.99） |
| 出生台账 | 每单恰好 1 条 `from_status IS NULL` 的 `order_status_ledger`；状态错 **0**；原因码错 **0** |
| 扣减台账 | 每单 `ORDER_CREATE` 台账行数 = 明细数（**0 例外**）；`Σdelta = -Σqty`（**0 例外**）；出现非负扣减 **0** |
| **桶守恒** | 21 个 SKU × 500,000 = 10,500,000；实测 Σ桶 10,468,504 + Σ扣减 31,496 = **10,500,000（精确相等）** |
| 展示缓存 | `sku.stock` 与 Σ桶偏差的 SKU **0** |
| 唯一性 | 重复 `order_sn` **0**、重复 `stock_ledger.idem_key` **0**、重复 `order_status_ledger.idem_key` **0** |
| 桶负库存 | **0** |
| 付款 | 该负荷不下单支付 ⇒ `payment_record` 0 行、非待付款订单 0 行（支付链路由 4.3 单独验） |

### 4.3 业务链审计（`flow-audit/flow-audit.txt`，在**开着 `useLocalSessionState=true`** 的容器上走真实 HTTP）

| 场景 | 结果 |
| --- | --- |
| ① 正常下单（SKU1×2） | 200；订单 1 行、明细 1 行、出生台账 1 行、库存台账 1 行、`total=3999.98 = Σ明细`、`delta=-2` ✅ |
| ② 库存不足下单（把 SKU21 的桶清零后下 1 件） | 返回 `库存不足…`；**订单数、台账数一条都没变**（回滚语义正确 —— 这是这个参数最该担心的点）✅ |
| ③ 支付 | `payment_record` 0→1、状态台账 1→2、`status=7 待审核`、`payment_time` 有值、**库存台账不重复扣**（仍 1 行 delta=-2）✅ |
| ④ 重复支付 | 返回 `当前订单状态不可支付`，`payment_record` **仍 1 行**（幂等）✅ |
| ⑤ 三方一致性扫描 | 已付款订单 2 / 有支付流水无付款状态 **0** / 有流水无 `payment_time` **0** / 金额不一致 **0** / 已付款却缺库存台账 **0** / 缺已付款状态台账 **0** ✅ |

---

## 5. 本轮踩的坑（两条，都已修进工具，并**如实作废两轮数据**）

1. **`avalanche.sh` 的锁释放 bug（真 bug，已修）**：`cleanup()` 在 EXIT trap 里**无条件**执行 `bench-lock.sh release`。
   于是「acquire 失败 → exit 3」这一条路径也会**把别人正在跑的锁释放掉**。本轮实测后果：
   A-r2 acquire 失败 → 释放了 A-r1 的锁 → A-r3 立刻"成功"拿到锁 → **两个压测并发跑**。
   → 已改成只在 `LOCK_HELD=1`（自己真的拿到）时释放。
2. **两轮数据作废**：`bench/results/INVALID-localsessA-r1-concurrent/`、`INVALID-localsessA-r3-concurrent/`
   （并发跑，两轮的客户端互相抢令牌/互相 reset），以及 `INVALID-localsessA-r2-lockbusy/`（没拿到锁，没跑起来）。
   **作废的目录都留在原地、名字写明原因**，没有删除 —— 想看"当时为什么不能用"随时可查。
   另外一次：用 `nohup ./chain.sh &` 串行跑两轮时，子进程被工具的作业回收牵连（A-r3 起跑 4 分钟后消失），
   已改成 `setsid nohup … & disown` 每轮独立起。
3. **门禁绝对阈值不可跨臂复用**：见 §3.3（7,500 是按 19~20 条/单标定的）。

---

## 6. 还原与清洁核对（跑完的现场）

```
qiqi-server-1/-2  SPRING_DATASOURCE_URL = (未注入 → 走 yml)
qiqi-server-1/-2  JAVA_OPTS = -XX:MaxRAMPercentage=75          （GC 日志已摘掉，池变量未设=默认 10）
qiqi-server-1/-2  jar md5 = e8b9cc01caf0db9336e8a244c21f8b1d   （镜像自带，jar 挂载已拆）
镜像（未重建）    sha256:935feb000f5d356515c6d3d29197e34f928a6e0844ef7c873a8a24996d9b39d9
网关 /api/health  200
DB               lg_% 订单 0、库存台账 0、Σ桶 = 10,500,000（21×500000）、缓存偏差 0
```

- 未改业务代码、未改 DDL、未动 V1/V2、未重建镜像、未 `pkill -f`、未 `git add/commit`。
- bench 侧**新增**：`bench/jdbc-url.override.yml`、`tools/jdbc-url.sh`、`tools/probecheck.sh`、
  `tools/localsess.py`、`tools/audit-data.sql`、`tools/audit-data.sh`、`tools/audit-flow.sh`；
  **修改**：`tools/avalanche.sh`（只改锁释放那 3 行，附注释）。

---

## 7. 结论：可采用，但**不是**因为 627

**可以采用的判据是三条同时成立**——本轮三条都成立：

| 判据 | 结果 |
| --- | --- |
| ① 关系成立（少语句 → 多吞吐） | ✅ 但幅度是 **+10.5%（600 档）/ 峰值 +16.2%**，**不是**预测的 +42% |
| ② 数据正确 | ✅ 31,496 单元数据零不一致 + 桶守恒精确 + 回滚/支付/幂等四方审计全绿 |
| ③ 无回归 | ✅ `mvn -B clean test` 246/0/0 |

**不成立的**：`ok/s = QPS_cap ÷ 每单语句数` 作为**预测工具**。实测 494.4 vs 预测 627（−21%）——差得不是噪声，
（A 臂同公式误差只有 ±3%）——而是因为 `QPS_cap` 本身随语句数一起变（§3.4）。

**一句话**：这个参数**该开**（白拿 10%，一行配置、零代码、数据无差），
但**别把它当成"把 441 变 627"的手段**；真想再往上走，闸门现在是「20 连接 × 40.5ms 事务」，不是语句条数。

---

## 8. 生产取舍：驱动"信任本地状态"意味着什么风险

`useLocalSessionState=true` 的语义是：**驱动不再每条语句去问服务器的会话状态**，而是相信自己的本地视图
（autocommit / transaction isolation / read-only 三样）。风险不是"会不会崩"，而是**"会不会静默地做错账"**：

| 出问题的条件 | 后果 | 本仓现状 |
| --- | --- | --- |
| 有人**绕过驱动**改会话状态：`stmt.execute("SET autocommit=0")`、MyBatis 里写 `SET SESSION …`、`SET TRANSACTION READ ONLY` | 驱动本地视图与服务器不一致 → `commit()/rollback()` 落错边界，可能出现"该回滚的提交了" | grep **0 命中**（本轮证据），但要**持续**保持 |
| 连接池配 `connection-init-sql` / `sessionVariables`（借出/归还时对连接执行 `SET`） | 同上，且**每条**连接都可能漂 | grep **0 命中**；Hikari 未配 |
| 数据库前置**连接多路复用**中间件（ProxySQL multiplexing、MySQL Router 连接复用等）：多个客户端会话被复用到同一条后端连接 | 本地状态整体不可信（别人的 SET 会落到你的会话上） | 本仓是直连 `mysql:3306`，无中间件；**上这类中间件就不能开这个参数** |
| DBA 用 `SET SESSION …` / `SET GLOBAL …` 调参并期待影响应用连接 | 应用侧不生效（只有新连接走 GLOBAL 默认值） | 运维约定问题，不是代码问题 |
| 驱动/连接池版本升级后 reset 逻辑改变 | 需要回归 | 已有 246 条集成测试 + 本轮四方审计脚本可复用 |

**落地建议（三条硬约束，建议写进 review checklist / CI grep 门禁）**

1. **禁止**在业务代码/SQL 里出现 `SET SESSION`、`SET TRANSACTION`、`SET autocommit`、`SET @@`、`setReadOnly(`、
   `setTransactionIsolation(`（本轮 grep 已确认现状为 0，加一条 CI 检查防止回潮）。
2. **禁止**给 Hikari 配 `connection-init-sql`；如需在连接上做初始化，用 `SET GLOBAL` 改服务器默认值（新连接生效），
   不要用会话级 SET。
3. **禁止**在该参数开启的前提下引入连接多路复用型代理；若要引入，必须先做一轮同样的对照（语句级证据 + 数据审计）。

另外一条**配置风险**：本轮是通过环境变量 `SPRING_DATASOURCE_URL` 注入的，而环境变量**覆盖 yml**。
上生产时建议把它写进配置文件（`application-prod.yml`）而不是环境变量，避免"某台机器上有人另设了一个 URL 悄悄把这个参数关掉"
（或反过来，在完全没做过验证的服务上被设成 `true`）。

---

## 9. 下一步最该做的（按性价比，本轮数据支持）

1. **重做连接池对照（pool 10/20/30）在"12.4 条/单"的新工作点上**。上一轮 §12 的结论「池子不是闸门」
   是在「19~20 条/单」时测的；现在线程栈仍显示 **191/202 在等连接**，而语句已经少了 33%，
   多给的连接有可能终于变成真吞吐（也可能仍旧变成行锁排队 —— 必须实测，别信推测）。
2. **`SET autocommit = ?` 2.0 条/单**（Spring 每单开 2 个事务，其中一个 `REQUIRES_NEW`）—— 省 2 条/单 + 2 次往返。
3. **`COMMIT` 4.3ms/次、fsync 900~960/s**：这是落盘的钱，与语句条数无关；要不要动
   `innodb_flush_log_at_trx_commit` / `sync_binlog` 是**可用性取舍**，不该由性能实验顺手定。
4. **热点桶**：`UPDATE sku_bucket` 单条 12~17ms、行锁等待 213/s、平均等 25ms —— 桶数（现在 8）或热点分散，
   是剩下最大的单点。
