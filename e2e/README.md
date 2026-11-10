# 七七商城 E2E 测试

基于 Playwright 的四个业务场景端到端测试，场景来自 `docs/flows/3~6`。

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

## 运行

```bash
cd e2e
npm test                              # 全部四个场景
npm run test:s1                       # 场景1 商品浏览
npm run test:s2                       # 场景2 购买下单
npm run test:s3                       # 场景3 订单履约（当前失败，见 FINDINGS.md）
npm run test:s4                       # 场景4 售后退货（当前失败，见 FINDINGS.md）
npm run report                        # 查看 HTML 报告
```

目标环境可用环境变量覆盖：

```bash
PORTAL_BASE=http://127.0.0.1:5174 \
ADMIN_BASE=http://127.0.0.1:5176 \
API_BASE=http://127.0.0.1:8082 \
npm test
```

## 用例与文档对应

| 文件 | 场景 | 文档 |
|---|---|---|
| `tests/scenario1-browse.spec.js` | 管理员上架 → 买家浏览/搜索/看详情 → 选中 SKU | `docs/flows/3-场景1-商品浏览.mmd` |
| `tests/scenario2-checkout.spec.js` | 匿名加购 → 登录拦截 → 注册合并 → 结算 → 下单 → 模拟支付 | `docs/flows/4-场景2-购买下单.mmd` |
| `tests/scenario3-fulfillment.spec.js` | 管理员发货 → 买家确认收货 | `docs/flows/5-场景3-订单履约.mmd` |
| `tests/scenario4-return.spec.js` | 买家申请退货 → 管理员审核 → 结果回传买家 | `docs/flows/6-场景4-售后退货.mmd` |

## 测试设计说明

- 每个用例自建数据（注册独立账号、补足 SKU 库存），可重复运行，不依赖上一次运行结果。
- 断言分两层：**UI 行为**（按钮/状态/跳转）与**服务端事实**（接口返回、库存扣减、明细快照）。
- 每个用例的失败信息里写明了「缺口」，便于直接对应到 `FINDINGS.md`。

## 结果

四个场景 5 个用例 **全部通过**：

| 文件 | 场景 | 用例数 |
|---|---|---|
| `scenario1-browse.spec.js` | 商品浏览 | 1 |
| `scenario2-checkout.spec.js` | 购买下单 | 1 |
| `scenario3-fulfillment.spec.js` | 订单履约（发货 / 确认收货） | 2 |
| `scenario4-return.spec.js` | 售后退货 | 1 |

首轮暴露的问题与修复记录见 `FINDINGS.md`；库存超卖压测见 `benchmark/oversell/README.md`。
