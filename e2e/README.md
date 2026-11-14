# 七七商城 E2E 测试

基于 Playwright 的端到端测试，场景来自 `docs/flows/`。用例按业务域分 **9 个类别**
（`tests/scenarios/01-买家侧/` … `09-多平台接入/`），对应 `docs/flows/02-业务场景/` 下的 **50 个场景文档**；
当前 **47 个 spec 文件 / 121 个用例**，另有 6 个上一代 spec（10 个用例）仍在跑，见「用例与文档对应」。

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
│   ├── scenarios/                      用例：9 个业务类别目录 + 上一代 spec
│   │   ├── 01-买家侧/                   10 个 spec（注册登录 / 浏览搜索 / 购物车 / 结算支付 / 订单收货 /
│   │   │                                售后 / 地址 / 支付并发 / 确认收货并发 / 抢购库存）
│   │   ├── 02-商家-商品管理/             7 个（类型属性 / 分类 / 维护 / SKU / 图片 / 两类编辑并发）
│   │   ├── 03-商家-履约/                15 个（审核发货 / 拆单 / 发货单 / 退货 / 各类并发 /
│   │   │                                删除守卫 / 七类台账 / 支付流水加固 …）
│   │   ├── 04-商家-基础配置/             3 个（仓库 / 订单设置 / 退货原因）
│   │   ├── 05-账号与会员/               4 个（登录验证码 / 管理员 / 会员 / 会员编辑并发）
│   │   ├── 06-数据概览/                 1 个
│   │   ├── 07-AI集成/                   1 个（令牌管理）
│   │   ├── 08-订单自动审核/              5 个（自动过审 / 阻止冻结 / 不命中 / 审核轨迹 / 防回归）
│   │   ├── 09-多平台接入/               1 个（接入最小闭环）
│   │   ├── scenario1-browse.spec.js … scenario5-split.spec.js
│   │   └── image-chain.spec.js          上一代 spec（6 文件 / 10 用例，仍被 testDir 匹配，仍在跑）
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
npm run test:iso                      # 同上，但 BASE 地址钉到 shard a（5183/5185/8091）
npx playwright test "tests/scenarios/03-商家-履约/15-支付流水加固.spec.js"                 # 只跑单个 spec（换路径即可）
npx playwright test "tests/scenarios/03-商家-履约/15-支付流水加固.spec.js" -g "用例四"     # 只跑其中一个用例
npm run report                        # 查看 HTML 报告（report/html）
```

要**全量 / 并行**跑请走仓库根目录的 `scripts/e2e-run.sh`（分片 × 独立库 × 独立后端，整体关进
`qiqi-e2e.slice`，不至于把宿主拖垮）：`scripts/e2e-run.sh`、`scripts/e2e-run.sh --affected`、
`E2E_SHARDS=1 scripts/e2e-run.sh "tests/scenarios/03-商家-履约/15-支付流水加固.spec.js"`。

目标环境可用环境变量覆盖（默认跑本地开发栈）：

```bash
PORTAL_BASE=http://127.0.0.1:5174 \
ADMIN_BASE=http://127.0.0.1:5176 \
API_BASE=http://127.0.0.1:18080 \
npm test
```

### demo 录制档（同一份 spec，两档共用）

验收档要快、要干净；出片另开一档，`DEMO=1` 切换，**断言一行不改**：

| | 验收档 `npm test` | demo 档 `npm run demo` |
|---|---|---|
| 环境 | 本地开发栈 5173 / 5175 / 8081 | **容器栈（测试环境）** 5174 / 5176 / 18080 |
| 视频 | `retain-on-failure`（不占空间） | `on`，1280×720 全量录制 |
| 视口 | 1440×950 | 1280×720 |
| 降速 | 0 | `slowMo: 400`（人眼看得到点击） |
| 用例超时 | 120s | 300s（降速后墙钟变长） |
| 产物 | `test-results/` | `/tmp/pwout-demo-<pid>`（`report/videos/raw/` 只在不传 `--output` 时） |

```bash
npm run demo -- "tests/scenarios/03-商家-履约/15-支付流水加固.spec.js"                  # 打容器栈录一条
npm run demo -- "tests/scenarios/04-商家-基础配置/3-退货原因字典.spec.js" --grep "字典维护页面"

# 录完把 webm 归并成 mp4（collect-demo 现在按「一次录制 → 一段或多段片」工作，不再有全流程合并片）
node scripts/collect-demo.mjs --from /tmp/pwout-demo-<pid> \
  --out report/videos/03-商家-履约/15-支付流水加固.mp4
```

`npm run demo` 的参数原样透传给 `npx playwright test`（`--grep` / `--project` 都行），没给 `--output`
时自动落到 `/tmp/pwout-demo-<pid>`（多 agent 不抢同一目录）。`collect-demo.mjs` 用 `--only` 取子集、
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

**口径**：`docs/flows/02-业务场景/` 下按业务域分 9 个目录、共 50 个场景；`tests/scenarios/` 用**同名目录**
一一对应，目录内的 spec 文件名也与文档场景目录同名（`3-商品维护` ↔ `3-商品维护.spec.js`）。
一个场景若拆成多条用例（正常态 / 边界 / 并发 / 防回归），都放在同一个 spec 文件里。

| 类别 | 场景数 | spec 目录 | 文档目录 |
|---|---|---|---|
| 01-买家侧 | 10 | `tests/scenarios/01-买家侧/`（10 spec / 35 用例） | `docs/flows/02-业务场景/01-买家侧/` |
| 02-商家-商品管理 | 7 | `tests/scenarios/02-商家-商品管理/`（7 spec / 21 用例） | `docs/flows/02-业务场景/02-商家-商品管理/` |
| 03-商家-履约 | 15 | `tests/scenarios/03-商家-履约/`（15 spec / 41 用例） | `docs/flows/02-业务场景/03-商家-履约/` |
| 04-商家-基础配置 | 3 | `tests/scenarios/04-商家-基础配置/`（3 spec / 4 用例） | `docs/flows/02-业务场景/04-商家-基础配置/` |
| 05-账号与会员 | 4 | `tests/scenarios/05-账号与会员/`（4 spec / 9 用例） | `docs/flows/02-业务场景/05-账号与会员/` |
| 06-数据概览 | 1 | `tests/scenarios/06-数据概览/`（1 spec / 2 用例） | `docs/flows/02-业务场景/06-数据概览/` |
| 07-AI集成 | 4 | `tests/scenarios/07-AI集成/`（1 spec / 1 用例：令牌管理） | `docs/flows/02-业务场景/07-AI集成/` |
| 08-订单自动审核 | 3 | `tests/scenarios/08-订单自动审核/`（5 spec / 7 用例） | `docs/flows/02-业务场景/08-订单自动审核/` |
| 09-多平台接入 | 3 | `tests/scenarios/09-多平台接入/`（1 spec / 1 用例：接入最小闭环） | `docs/flows/02-业务场景/09-多平台接入/` |

**数与数不等的地方（有意为之，别当成漏写）**：

- 07：文档 4 篇是能力/边界说明（MCP 最小闭环、并发冲突、工具能力矩阵、令牌管理），
  E2E 只覆盖「令牌管理」这一条能端到端走通的链路。
- 08：文档 3 篇按「判定链 → 六笔单走查 → 状态与证据对齐」讲，spec 按**结局**拆成 5 个
  （自动过审 / 阻止 / 冻结 / 不命中 / 审核轨迹 + 防回归）。
- 09：文档 3 篇（接入主链路 / 幂等与失败重试 / 内部系统边界），E2E 只钉住最小闭环。

**跨类别的机制时序图**在 `docs/flows/03-机制时序/`，用例里出现「为什么会这样」的问题时看这几张：

| 机制 | 文档 |
|---|---|
| 下单与支付（支付流水、幂等的来龙去脉） | `docs/flows/03-机制时序/4-下单与支付.mmd` |
| 买家确认收货（并发确认收货物料的依据） | `docs/flows/03-机制时序/5-买家确认收货.mmd` |
| 退货与退款（退款流水 / 溯源字段） | `docs/flows/03-机制时序/6-退货与退款.mmd` |
| 审核与跨仓拆单（拆单、审核并发） | `docs/flows/03-机制时序/7-审核与跨仓拆单.mmd` |
| 下发与仓库回传（发货单、WMS 台账） | `docs/flows/03-机制时序/8-下发与仓库回传.mmd` |

**上一代 spec（`scenario1-browse` … `scenario5-split`、`image-chain`）**：仍在 `tests/scenarios/` 根目录、
仍被 `testDir` 递归匹配并在跑（6 文件 / 10 用例），内容已被上面 01~09 的目录覆盖并扩写；
新用例一律写进类别目录，不再往根目录加文件。（这些文件头部注释里写的 `docs/flows/3-场景1-商品浏览.mmd`
一类路径是重组前的旧文档，**对应关系以本表为准**。）

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

用例总数：**47 个 spec / 121 个用例**（9 个业务类别）+ **6 个上一代 spec / 10 个用例**。
本文件不再维护「某次运行全绿的用例表」——那张表里的数字每加/减一个用例就过期一次，
也是这份 README 第三次被审计判「过期」的原因。本次运行的事实看两处：

```bash
npm run report                       # HTML 报告（report/html），逐条 passed / failed
npm test -- --reporter=list          # 控制台逐条结果（含耗时）
```

库存超卖 / 死锁压测与复现脚本见 `benchmark/1-防超卖与死锁/README-超卖与死锁.md`。
