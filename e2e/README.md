# 七七商城 E2E 测试

基于 Playwright 的端到端测试，场景文档在 `docs/flows/02-业务场景/`。

**用例分 4 个桶，一个桶就是一个目录**（`tests/scenarios/<桶>/`），另有 `_legacy/` 放旧结构遗留的 spec：

| 桶目录 | spec | 用例 |
| --- | --- | --- |
| `业务/` | 32 | 90 |
| `并发与竞态/` | 9 | 10 |
| `台账与取证/` | 6 | 19 |
| `库存/` | 5 | 12 |
| `_legacy/`（旧结构遗留，仍被 `testDir` 匹配、仍在跑） | 6 | 10 |

合计 **58 个 spec 文件 / 141 个用例**。`docs/flows/02-业务场景/` 下另有 `性能与容量/`（5 篇）与
`事故与修复/`（6 篇）两个桶，**只有文档、没有 spec**：前者的证据是曲线数据，后者是日志 / 命令输出 /
时间线，都无法用界面演出来，压测工具在 `bench/`。

> 「桶」在这份文件里**只指用例分桶**。`bench/README.md` 里的「桶」指库存分桶（`sku_bucket` 分桶表），
> 是另一回事 —— 库存那个 `库存分桶` 场景的 spec 在 `库存/` 桶里，两者同名但不相关。

**三棵树共用同一段 rel 路径**（2026-11-14 结构重构后定下的唯一形状；改动时必须保持这个形状，
原因是 `e2e/scripts/collect-demo.mjs` 收视频、`/root/docsify/build-material.mjs` 找素材，都按这段
路径逐级对位，任一棵树单独改名都会静默对不上）：

```
文档  docs/flows/02-业务场景/<桶>/[<子分类>/]<场景名>/
spec  e2e/tests/scenarios/<桶>/[<子分类>/]<场景名>.spec.js
视频  e2e/report/videos/<桶>/[<子分类>/]<场景名>/<段序>-<段名>.mp4
```

这里的 `<桶>` 只指上表里有 spec 的那 4 个目录名：`业务` / `并发与竞态` / `台账与取证` / `库存`。
文件名/URL 里**一个编号都没有** —— 顺序只由 `/root/docsify/build-material.mjs`（文档站构建脚本，
在仓库外，不在本仓库里）的 `SCENE_BUCKETS` 书写顺序决定。

**所有业务动作都由真实 UI 点击驱动**（注册、登录、加购、下单、支付、审核、发货单下发、WMS 回传、
确认收货、申请退货、退货审核），接口只用于只读断言（`GET` 查询服务端事实），不用接口推进业务状态。

## 前置条件

1. 后端（dev 8081）、C 端（5173）、B 端（5175）已启动，MySQL 有示例数据：
   ```bash
   # 终端 1
   cd qiqi-mall-server && mvn spring-boot:run -Dspring-boot.run.profiles=dev
   # 终端 2
   cd portal-web && npm run dev
   # 终端 3
   cd admin-web && npm run dev
   ```
2. 安装依赖（首次）：
   ```bash
   cd e2e && npm install
   ```

## 目录结构

```
e2e/
├── playwright.config.js                testDir = tests/scenarios（递归匹配 *.spec.js）
├── tests/
│   ├── scenarios/                      用例：4 个桶 ×（子分类）× 场景
│   │   ├── 业务/                        32 个 spec / 90 用例（9 个子分类）
│   │   │   ├── 买家侧/                  7 个（注册登录 / 浏览搜索 / 购物车 / 结算支付 / 订单收货 / 售后 / 地址）
│   │   │   ├── 商家-商品管理/            4 个（类型属性 / 分类 / 维护 / 图片上传预览）
│   │   │   ├── 商家-履约/                6 个（审核发货 / 拆单 / 发货单调整 / 退货处理 / 删除守卫 / 后台建单）
│   │   │   ├── 商家-基础配置/            3 个（仓库 / 订单设置 / 退货原因）
│   │   │   ├── 账号与会员/               3 个（登录验证码 / 管理员账号 / 会员管理）
│   │   │   ├── 数据概览/                 1 个
│   │   │   ├── AI集成/                  1 个（令牌管理；另两篇是能力/边界说明，无 UI 用例）
│   │   │   ├── 订单-自动审核/            6 个（判定链四种结局 3 / 六笔单走查 2 / 审核策略与优先级 1）
│   │   │   └── 渠道-多平台接入/           1 个（接入主链路）
│   │   ├── 并发与竞态/                   9 个 spec / 10 用例（4 个子分类：版本冲突 / 状态竞态 /
│   │   │                                重复提交与幂等 / 双向竞态）
│   │   ├── 台账与取证/                   6 个 spec / 19 用例（台账七类 6 个；取证链与证据对齐是纯概念页）
│   │   ├── 库存/                         5 个 spec / 12 用例（SKU与规格 / 条件更新 / 库存分桶 / 库存对账 /
│   │   │                                库存台账与对账）
│   │   └── _legacy/                     旧结构遗留的 spec（6 文件 / 10 用例，仍被 testDir 匹配，仍在跑）
│   └── support/                        共用工具（25 个文件，规则写在各自头部注释里）
│       ├── env.js                      BASE 地址（PORTAL/ADMIN/API）与唯一后缀 stamp()
│       ├── test.js                     共享 test/expect：挂「组件静默失效」守卫（console-guard.js）
│       ├── ui.js / portal-ui.js / admin-ui*.js / catalog-ui.js / audit-ui.js …
│       │                               真实 UI 操作（登录注册 / 加购下单 / 审核 / 下发 / WMS 回传 / 确认收货 / 退货）
│       ├── assert.js                   只读断言查询（GET + 从 localStorage 读令牌）
│       ├── db.js                       直连 mysql 的只读查询；「端口 → 库名」映射只写在这一份
│       ├── danger-ui.js                高危确认弹窗 / 冲突弹窗的定位与流程
│       ├── replay-toast.js             台账「重放渠道通知」三态断言（expectReplayToast）
│       ├── tid.js                      data-testid 选择器（含拿不到 testid 时的兜底清单）
│       └── data.js                     仅「页面无入口」的数据准备（当前清单为空）
├── scripts/                            precheck-env.mjs（镜像过期检查）/ collect-demo.mjs（视频收集）
└── report/                             报告与视频（gitignore）
```

`data.js` 的规则：只有当某个准备动作在页面上确实没有入口时才允许放进去（当前为空——补库存等
都有真实入口，已走 `ui.js`）；业务操作一律不放，断言读取一律走 `assert.js`。

## 运行

```bash
cd e2e
npm test                              # 全部用例（testDir=tests/scenarios，递归匹配 *.spec.js）
npm run test:iso                      # 同上，但 BASE 地址钉到 shard a：portal 5183 / admin 5185 / api 8091（库 qiqi_mall_e2e_a）
npx playwright test "tests/scenarios/库存/库存台账与对账.spec.js"                        # 只跑单个 spec（换路径即可）
npx playwright test "tests/scenarios/台账与取证/台账七类/支付流水.spec.js" -g "用例四"   # 只跑其中一个用例
npm run report                        # 查看 HTML 报告（report/html）
```

要**全量 / 并行**跑请走仓库根目录的 `scripts/e2e-run.sh`（分片 × 独立库 × 独立后端，整体关进
`qiqi-e2e.slice`，不至于把宿主拖垮）：`scripts/e2e-run.sh`、`scripts/e2e-run.sh --affected`、
`E2E_SHARDS=1 scripts/e2e-run.sh "tests/scenarios/并发与竞态/重复提交与幂等/平台重推与503重试.spec.js"`。

分片（shard）各含哪几套服务，写在 `scripts/e2e-run.sh` 的 `SHARDS` 表里，一个 shard = 一套
portal + admin + 后端 + 一个独立库：**shard a** = portal 5183 / admin 5185 / api 8091 / 库
`qiqi_mall_e2e_a`；**shard b** = 5193 / 5195 / 8092 / 库 `qiqi_mall_e2e_b`（表里还留着 c
（5203/5205/8093）与 d（5213/5215/8094），默认只用 a、b 两片，`E2E_SHARDS` 控制用几片）。

目标环境可用环境变量覆盖（默认跑本地开发栈）：

```bash
PORTAL_BASE=http://127.0.0.1:5174 \
ADMIN_BASE=http://127.0.0.1:5176 \
API_BASE=http://127.0.0.1:18080 \
npm test
```

### demo 录制档（同一份 spec，两档共用）

同一份 spec 有两种跑法：**验收档**（`npm test`，跑得快、只留失败视频，用来判断用例对错）与
**demo 档**（`npm run demo`，即 `DEMO=1`，全量录制 + 放慢每个动作，用来出演示视频）。两档只差环境变量，
**断言一行不改**：

| | 验收档 `npm test` | demo 档 `npm run demo` |
|---|---|---|
| 环境 | 本地开发栈 5173 / 5175 / 8081 | **容器栈（测试环境）** 5174 / 5176 / 18080 |
| 视频 | `retain-on-failure`（不占空间） | `on`，1280×720 全量录制 |
| 视口 | 1440×950 | 1280×720 |
| 降速 | 0 | `slowMo: 400`（人眼看得到点击） |
| 用例超时 | 120s | 300s（降速后墙钟变长） |
| 产物 | `test-results/` | `/tmp/pwout-demo-<pid>`（`report/videos/raw/` 只在不传 `--output` 时） |

```bash
npm run demo -- "tests/scenarios/业务/商家-履约/退货处理.spec.js"                       # 打容器栈录一条
npm run demo -- "tests/scenarios/业务/商家-基础配置/退货原因字典.spec.js" --grep "字典项进入买家退货下拉"

# 录完把 webm 归并成 mp4（collect-demo 按「一次录制 → 一段或多段片」工作，没有全流程合并片）
node scripts/collect-demo.mjs --from /tmp/pwout-demo-<pid> \
  --out report/videos/业务/商家-履约/退货处理/1-审核通过.mp4
```

`npm run demo` 的参数原样透传给 `npx playwright test`（`--grep` / `--project` 都行），没给 `--output`
时自动落到 `/tmp/pwout-demo-<pid>`（`<pid>` 是进程号，所以并行跑多份录制时不会抢同一目录）。`collect-demo.mjs` 用 `--only` 取子集、
`--order` 定拼接顺序（关键词没命中直接 exit 1，不允许顺序静默错乱），只出 `.mp4`；`report/` 已被
`.gitignore` 覆盖，视频不进仓库。
⚠️ 容器栈（18080）**没有**验证码 DEBUG 日志：要读验证码的 spec 只能打 dev 栈（见 `scripts/demo-run.sh` 头部）。

### 环境 pre-check（防「改完没发布」）

容器镜像是构建时快照，源码改了不会自动生效。`scripts/precheck-env.mjs` 会比较
`admin-web/src`、`portal-web/src`、`qiqi-mall-server/src` 的最新 mtime 与对应镜像的 Created 时间：

```bash
npm test        # pretest 钩子：只警告不阻断（dev 栈测试不需要重建镜像）
npm run demo    # predemo 钩子：--strict，镜像过期直接 exit 1（demo 打容器栈，旧代码必错）
node scripts/precheck-env.mjs --strict   # 也可手动跑
```

过期时输出「哪个服务过期 + 源码时间 + 镜像时间 + 修复命令」。docker 不可用 / 镜像不存在 /
命令超时会打印「无法检测，跳过」并 exit 0——检查本身不能把测试搞挂。

已知局限：mtime 会因 `git checkout`、解压、`touch` 而失真（`touch` 只改时间不改内容时，
docker 按内容缓存也不会重建镜像，会一直判过期，此时用 `docker compose build --no-cache <svc>`）。
更可靠的做法是构建时给镜像打 git commit label（`--label git.commit=$(git rev-parse HEAD)`），
运行时比对 HEAD，属后续升级。

## 用例与文档对应

**口径**：`docs/flows/02-业务场景/` 下按 6 个桶组织（其中 4 个有 spec，`业务` 再分 9 个子分类），
共 **65 个场景页**；`tests/scenarios/` 用**同一段 rel 路径**一一对应 —— 目录名、场景目录名、spec
文件名逐字相同（`业务/买家侧/购物车` ↔ `tests/scenarios/业务/买家侧/购物车.spec.js`）。
一个场景若拆成多条用例（正常态 / 边界 / 并发 / 防回归），都放在同一个 spec 文件里。

| 桶 | 场景页数 | spec 目录 | 文档目录 |
|---|---|---|---|
| 业务 | 31 | `tests/scenarios/业务/`（32 spec / 90 用例，9 个子分类） | `docs/flows/02-业务场景/业务/` |
| 并发与竞态 | 10 | `tests/scenarios/并发与竞态/`（9 spec / 10 用例，4 个子分类） | `docs/flows/02-业务场景/并发与竞态/` |
| 台账与取证 | 8 | `tests/scenarios/台账与取证/`（6 spec / 19 用例，2 个子分类） | `docs/flows/02-业务场景/台账与取证/` |
| 库存 | 5 | `tests/scenarios/库存/`（5 spec / 12 用例，无子分类） | `docs/flows/02-业务场景/库存/` |
| 性能与容量 | 5 | 无 spec（压测装置在 `bench/`；早期超卖/死锁复现脚本在 `benchmark/1-防超卖与死锁/`） | `docs/flows/02-业务场景/性能与容量/` |
| 事故与修复 | 6 | 无 spec（证据是日志 / 命令输出 / 时间线，界面演示不了） | `docs/flows/02-业务场景/事故与修复/` |

**数与数不等的地方（有意为之，别当成漏写）**：

- 业务 31 篇文档 vs 32 个 spec：`AI集成` 下 3 篇里只有「令牌管理」能端到端走通（MCP 最小闭环 /
  工具能力矩阵是能力与边界说明）；`订单-自动审核` 3 篇（判定链 / 六笔单走查 / 审核策略与优先级），
  spec 按**结局**拆成 6 个（自动过审 / 阻止 / 冻结 / 不命中 / 审核轨迹 + 防回归 / 策略优先级）；
  `商家-履约` 6 篇 6 个 spec、一篇一个文件，其中「后台建单」那篇讲的是管理端建单接口与买家侧结算
  下单的分界。
- 并发与竞态 10 篇 vs 9 个 spec：`版本冲突/AI端并发` 没有 spec（界面 = MCP 调试器，用
  `scripts/start-inspector.sh` 起）。它的演示视频是 2026-11-14 现场演着录的，落在
  `e2e/report/videos/并发与竞态/版本冲突/AI端并发/1-全流程.mp4`；**可自动化的 UI 用例仍欠着**。
- 台账与取证 8 篇 vs 6 个 spec：`取证链与证据对齐` 两篇是纯概念页（字段对照表 + 只读 SQL），没有专属界面。
- 库存 5 篇 = 5 个 spec，一篇一个文件。
- 性能与容量 5 篇、事故与修复 6 篇都是 0 个 spec：这两桶的结论靠曲线数据 / 命令输出证明，
  录一段界面什么也证明不了，所以既没有 spec 也没有视频（判定见 `scripts/check-docs-site.mjs`
  的 `NO_UI_SCENES` 清单）。

**跨桶的机制时序图**在 `docs/flows/03-机制时序/`，用例里出现「为什么会这样」的问题时看这几张：

| 机制 | 文档 |
|---|---|
| 下单与支付（支付流水、幂等的来龙去脉） | `docs/flows/03-机制时序/4-下单与支付.mmd` |
| 买家确认收货（并发确认收货物料的依据） | `docs/flows/03-机制时序/5-买家确认收货.mmd` |
| 退货与退款（退款流水 / 溯源字段） | `docs/flows/03-机制时序/6-退货与退款.mmd` |
| 审核与跨仓拆单（拆单、审核并发） | `docs/flows/03-机制时序/7-审核与跨仓拆单.mmd` |
| 下发与仓库回传（发货单、WMS 台账） | `docs/flows/03-机制时序/8-下发与仓库回传.mmd` |

**旧结构遗留的 spec（`tests/scenarios/_legacy/`：`scenario1-browse` … `scenario5-split`、`image-chain`）**：
仍被 `testDir` 递归匹配并在跑（6 文件 / 10 用例），内容已被上面 4 个桶覆盖并扩写；新用例一律写进桶目录，
不再往 `_legacy/` 加文件。（这些文件头部注释里写的是 2026-11-14 重构之前那批文档的路径，`docs/flows/`
下**已经没有这些文件**，现行对应关系以本表为准。）

## 测试设计说明

- 每个用例自建数据：注册独立账号（`e2e_*_<后缀>`）、通过 admin「SKU 管理」页把库存改成 50，
  可重复运行，不依赖上一次运行结果。
- 断言分两层：**UI 行为**（按钮/状态/抽屉内容/聚合状态、toast 级别、弹窗去向）与**服务端事实**
  （订单状态码、拆单数量、运单号、明细快照、库存扣减、支付流水行数与金额）。
- 业务动作走真实 UI（`ui.js` / `admin-ui*.js`）；`assert.js` 只发 `GET`。**例外只有两类**，且都在
  用例里写明了理由：① 清掉自己造的收尾数据（删单 / 删退货申请）；② 页面上有入口但需要精确控制
  次数的沙箱动作（如「重放渠道通知」既走 UI 也会用接口补打一次）。不用接口推进业务状态。
- 履约链路的 UI 路径：订单列表「明细」→ 抽屉「发货单」区块 →「下发」→「模拟WMS回传」下拉
  （拣货 / 出库 / 揽收 / 签收）。

## 结果

用例总数：**52 个 spec / 131 个用例**（4 个桶）+ **6 个旧结构遗留的 spec / 10 个用例**。
本文件不再维护「某次运行全绿的用例表」——那张表里的数字每加/减一个用例就过期一次，这也是到
2026-11-16 为止它已经被判过三次「过期」的原因。此时此刻是否全绿，只看两处：

```bash
npm run report                       # HTML 报告（report/html），逐条 passed / failed
npm test -- --reporter=list          # 控制台逐条结果（含耗时）
```

文档站侧的页面数 / 图数 / 视频数，以 `node scripts/check-docs-site.mjs`（页面与图文视频缺件）与
`node scripts/check-sidebar-continuity.mjs`（侧边栏连续性）的输出为准，同样不在本文件维护（每次重建都会变）。
库存超卖 / 死锁压测与复现脚本见 `benchmark/1-防超卖与死锁/`（`race-超卖复现.sh` / `deadlock-死锁复现.sh`）。
