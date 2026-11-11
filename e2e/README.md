# 七七商城 E2E 测试

基于 Playwright 的**五个业务场景**端到端测试，场景来自 `docs/flows/`。

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
├── playwright.config.js
├── tests/
│   ├── scenarios/                     五个业务场景用例
│   │   ├── scenario1-browse.spec.js        商品浏览
│   │   ├── scenario2-checkout.spec.js      购买下单
│   │   ├── scenario3-fulfillment.spec.js   订单履约（发货 / 确认收货，2 个用例）
│   │   ├── scenario4-return.spec.js        售后退货
│   │   └── scenario5-split.spec.js         拆单履约
│   └── support/
│       ├── env.js                     BASE 地址（PORTAL/ADMIN/API）与唯一后缀 stamp()
│       ├── ui.js                      真实 UI 操作（登录注册 / 下拉选择 / 加购下单 / 审核 / 下发 / WMS 回传 / 确认收货 / 退货）
│       ├── assert.js                  只读断言查询（GET + 从 localStorage 读令牌）
│       └── data.js                    仅「页面无入口」的数据准备（当前清单为空）
└── report/                            报告与视频（gitignore）
```

`data.js` 的规则：只有当某个准备动作在页面上确实没有入口时才允许放进去（当前为空——补库存等
都有真实入口，已走 `ui.js`）；业务操作一律不放，断言读取一律走 `assert.js`。

## 运行

```bash
cd e2e
npm test                              # 全部五个场景（6 个用例）
npm run test:s1                       # 场景1 商品浏览
npm run test:s2                       # 场景2 购买下单
npm run test:s3                       # 场景3 订单履约（发货 / 确认收货）
npm run test:s4                       # 场景4 售后退货
npm run test:s5                       # 场景5 拆单履约
npm run report                        # 查看 HTML 报告
```

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
| 产物 | `test-results/` | `report/videos/raw/` |

```bash
npm run demo            # 跑容器栈并录制（env 已写进脚本，无需手工 export）
npm run demo:collect    # 改名 + 合并：report/videos/01-商品浏览.webm … 05-拆单履约.webm
                        #               report/videos/demo-全流程.webm + .mp4
```

`demo:collect` 会把场景3 的两个用例合成一条，再按 1→5 顺序拼成全流程片，最后打印每段
时长与体积（`report/` 已被 `.gitignore` 覆盖，视频不进仓库）。

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

| 文件 | 场景 | 文档 |
|---|---|---|
| `tests/scenarios/scenario1-browse.spec.js` | 管理员上架商品/SKU/图片 → 买家浏览/搜索/看详情 → 选中规格 | `docs/flows/3-场景1-商品浏览.mmd` |
| `tests/scenarios/scenario2-checkout.spec.js` | 游客加购 → 登录拦截 → 注册合并购物车 → 结算下单 → 模拟支付 | `docs/flows/4-场景2-购买下单.mmd` |
| `tests/scenarios/scenario3-fulfillment.spec.js` | 管理员审核 → 发货单下发 + WMS 出库 → 买家确认收货 | `docs/flows/5-场景3-订单履约.mmd` |
| `tests/scenarios/scenario4-return.spec.js` | 买家申请退货 → 管理员审核 → 结果回传买家 | `docs/flows/6-场景4-售后退货.mmd` |
| `tests/scenarios/scenario5-split.spec.js` | 跨仓订单自动拆 2 单 → 部分出库=部分发货 → 全部签收=已完成 | `docs/flows/22-拆单时序图`、`23-发货单状态图` |

## 测试设计说明

- 每个用例自建数据：注册独立账号（`e2e_*_<后缀>`）、通过 admin「SKU 管理」页把库存改成 50，
  可重复运行，不依赖上一次运行结果。
- 断言分两层：**UI 行为**（按钮/状态/抽屉内容/聚合状态）与**服务端事实**（订单状态码、拆单数量、
  运单号、明细快照、库存扣减）。
- 接口只读：五个场景里对业务接口的 `POST/PUT/DELETE` 直调为 **0**；`assert.js` 只发 `GET`。
- 履约链路的 UI 路径：订单列表「明细」→ 抽屉「发货单」区块 →「下发」→「模拟WMS回传」下拉
  （拣货 / 出库 / 揽收 / 签收）。

## 结果

五个场景 6 个用例 **全部通过**：

| 文件 | 场景 | 用例数 |
|---|---|---|
| `scenario1-browse.spec.js` | 商品浏览 | 1 |
| `scenario2-checkout.spec.js` | 购买下单 | 1 |
| `scenario3-fulfillment.spec.js` | 订单履约（发货 / 确认收货） | 2 |
| `scenario4-return.spec.js` | 售后退货 | 1 |
| `scenario5-split.spec.js` | 拆单履约 | 1 |

库存超卖压测见 `benchmark/oversell/README.md`。
