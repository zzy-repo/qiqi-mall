# qiqi-mall MCP Server（stdio 最小闭环）

把商城后台「订单审核 + 拆单」暴露成 MCP 工具，给 Claude Desktop / 任意 MCP 客户端调用。
本仓库这一层**只动 MCP 侧、后端一行没改** —— 它就是纯粹的 REST 客户端（认证 = API Key，确认码 = 后端签发）。

## 闭环

```
① list_pending_orders()                          列出待审核订单（只读）
② preview_split(orderId)                         拆单方案（dry-run 不落库）+ 后端签发的 confirmCode
③ execute_audit_pass(orderId, confirmCode)       订单 7→8 并真的生成发货单（写）
④ list_deliveries(orderId)                       回读，确认包裹数与方案一致
```

确认码（confirmCode）**由后端签发、后端校验**（`POST /admin/orders/{id}/split-preview` 发票，
`POST /admin/orders/{id}/audit-pass` 收码）：UUID、默认 300 秒、一次性、绑定该订单 + 拆单方案指纹。
MCP 只负责透传，**不再自己造票据**（旧的 `src/confirm.mjs` 已删除）。

## 工具清单（按域）

| 域 | 工具 | 读/写 | 后端接口 |
|---|---|---|---|
| 订单 | `list_orders` | 只读 | `GET /admin/orders?page&size&status&orderSn`（后端真分页；orderSn 模糊） |
| 订单 | `get_order_detail` | 只读 | `GET /admin/orders/{id}`（`{order,items,returnApplies}`） |
| 订单 | `count_deliveries` | 只读 | `GET /admin/deliveries/counts?orderIds=`（不含已取消） |
| 订单 | `list_pending_orders` | 只读 | `GET /admin/orders?status=7` |
| 订单 | `preview_split` | 只读（拿确认码） | `POST /admin/orders/{id}/split-preview` → `{plan, confirmCode, expiresInSec}` |
| 订单 | `execute_audit_pass` | **写** | `POST /admin/orders/{id}/audit-pass`，body `{confirmCode}` |
| 订单 | `list_deliveries` | 只读 | `GET /admin/deliveries?orderId=` |
| 商品 | `search_products` | 只读 | `GET /admin/products`（后端只支持 publishStatus，关键字/分类/分页在工具层做） |
| 商品 | `get_product_detail` | 只读 | `GET /admin/products/{id}` + `GET /admin/skus?productId=` |
| 商品 | `list_categories` | 只读 | `GET /admin/categories`（扁平 → 工具层拼树） |
| 商品 | `list_product_types` | 只读 | `GET /admin/product-types` + 每类型 `GET /admin/attributes?typeId=` |
| 商品 | `list_attributes` | 只读 | `GET /admin/attributes?typeId=` |
| 商品 | `list_skus` | 只读 | `GET /admin/skus?productId=` |

确认码口径：**只读工具不需要确认码；写工具必须透传后端签发的 `confirmCode`**（MCP 不自己发票据）。

订单状态码（`Order.java` 注释 + admin-web 两处一致，工具返回一律翻成中文）：

| 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| 待付款 | 已付款 | 已发货 | 已完成 | 已关闭 | 退款中 | 已退款 | 待审核 | 待发货 | 部分发货 |

## 目录结构（新增域照这个模式加文件）

```
src/
├── index.mjs          入口：McpServer + StdioServerTransport，调 registerAll(server, { writeEnabled })
├── mall.mjs           REST 客户端（X-Api-Key 认证 / 解 {code,message,data} / code≠0 抛中文原因）
└── tools/
    ├── index.mjs      统一注册入口（按域 import；文件头有「新增域」清单）
    ├── _shared.mjs    公共件：text / fail / clampPage / paginate（分页全项目统一 1/10/50）
    ├── order.mjs      订单域 6 只读 + 1 写（list_orders/get_order_detail/count_deliveries 是第 3 批）
    └── catalog.mjs    商品域 6 个只读工具
```

新增一个域 = 新建 `src/tools/<域>.mjs` + 在 `src/tools/index.mjs` 加一行 import/调用；
工具内一律「中文 title + 说清什么时候用的 description + zod `.describe()` 参数 +
文本摘要 + `JSON:` 两段 + 空结果明确文案 + `fail(e)` 兜错」。

## 认证：API Key（不再登录拿 JWT）

管理端一律用 **API Key**：请求头 `X-Api-Key: <MALL_API_KEY>`，环境变量 **`MALL_API_KEY` 必填**。
登录拿 JWT、401 自动重登那套已经删掉了 —— key 是**按库隔离**的（dev 库一把、test 库一把），
换环境只换 key，代码和配置都不动。

### 造一把 key

用仓库根的 `scripts/init-ai-key.sh`（它拿 admin 的 JWT 去建 key，**不是** key 自己建自己）：

```bash
# 默认：http://127.0.0.1:8081（dev 库），key 名「本地开发-全权」，9 个 scope 全开、永不过期
bash scripts/init-ai-key.sh

# 测试环境（18080 / qiqi_mall_test）：
BASE_URL=http://127.0.0.1:18080 \
ADMIN_USER=admin ADMIN_PASS=123456 \
KEY_NAME=测试环境-全权 OUT_FILE=/tmp/test-env-ai-key.txt \
bash scripts/init-ai-key.sh

# 想换一把新的（明文只在创建时出现一次）：
BASE_URL=http://127.0.0.1:18080 KEY_NAME=测试环境-全权 OUT_FILE=/tmp/test-env-ai-key.txt \
bash scripts/init-ai-key.sh --force
```

要点：

- **幂等**：按 `KEY_NAME` 查，同名已存在就不新建（明文丢了才需要 `--force` 重建）。
- 明文只打印到终端 + 按 600 权限写到 `OUT_FILE`（默认 `/tmp/qiqi-mall-ai-key.txt`），
  库里只存 sha256，**绝不提交进仓库**。
- 建出来的 key 一把可管多库吗？不行 —— key 属于某个库的 `ai_api_key` 表，dev 的 key 到 test 库只会 401。
- ⚠️ **`scripts/reset-test-db.sh` 会 DROP `ai_api_key`**，复位后测试环境的 key 就没了，需要重跑上面的
  `BASE_URL=http://127.0.0.1:18080 ... bash scripts/init-ai-key.sh`。
- 现在测试环境的那把在 `/tmp/test-env-ai-key.txt`（名字「测试环境-全权」，9 个 scope 全开）。

### 配上 MALL_API_KEY

```bash
export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)      # 测试环境
# 或写进你自己的 shell profile / MCP 客户端配置的 env 里（见文末「客户端配置示例」）
```

没设 `MALL_API_KEY` 时，工具调用会直接返回一句中文原因（`缺少 MALL_API_KEY 环境变量…`），
不会发出没有凭证的请求；key 不属于当前库（或已被禁用）时返回 `API Key 无效或已被禁用（HTTP 401…）`。

## 运行

```bash
cd mcp-server
npm install
export MALL_BASE=http://127.0.0.1:18080          # 默认就是这个
export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)   # 必填（key 按库隔离，见上面「认证」）
export MCP_ENABLE_WRITE=1     # 0=只读（默认 0），1=开放写工具
npm start
```

`npm test` 跑三套测试：用 SDK client 通过 stdio 真启动本 server，逐条断言。
**数据自建自清**，跑完库态与跑前一致（不消费种子数据、不需要复位脚本）：

- `test/loop.mjs`（13 条 = 验收 12 条 + 「跨单用码后重新预览」1 条）：订单闭环 ——
  预览不落库、confirmCode 是后端签发的 UUID 且 300 秒、缺码被 zod 拒、假码被拒、
  **跨单用码被拒（码与订单不匹配）**、执行成功且 deliveries === plan、真落库、状态 7→8、
  **同码复用被拒**、回读闭环、写开关生效。
- `test/catalog.mjs`（27 条）：商品域 6 个只读工具，含分页夹取、空结果文案、**只读校验（调用前后 product/sku 行数不变）**。
- `test/order-read.mjs`（32 条）：订单域 3 个只读工具，含分页夹取、状态码中文化（7→待审核）、单号模糊匹配、
  详情聚合、批量包裹数比对库、**只读校验（order/order_item/delivery_order 行数不变）**。

跑法（**必须**先把 key 喂进去，否则第一句就报「缺少 MALL_API_KEY」）：

```bash
cd mcp-server
export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)
npm test     # 连跑两遍都该全绿；第二遍不 reset，验证测试自给自足
```

> 测试里的分工：**管理端调用一律用 API Key**；只有 `/portal/orders/{id}/pay` 仍用**会员 JWT**
> （该接口校验「只能付自己的单」，API Key 没有会员主语会 403）——所以 `test/_setup.mjs`
> 每次造单都临时注册一个一次性买家。

## 用 MCP Inspector 调试

```bash
# ⚠️ v2.15 的 stdio 子进程不继承父进程环境变量，必须显式 -e 传
npx -y @modelcontextprotocol/inspector \
  -e MALL_BASE=http://127.0.0.1:18080 \
  -e MALL_API_KEY=ak_xxxxxxx \
  -e MCP_ENABLE_WRITE=1 \
  node mcp-server/src/index.mjs
```

踩坑记录（v2.15.0 实测）：

- **stdio 子进程拿不到父进程 env** → 不传 `-e` 会漏掉写工具（`MCP_ENABLE_WRITE` 不生效，只剩 12 个只读工具）；
  `MALL_API_KEY` 也必须 `-e` 传，否则每个工具调用都返回「缺少 MALL_API_KEY 环境变量…」
- UI URL 参数是 `?MCP_INSPECTOR_API_TOKEN=…`（不是旧版的 `MCP_PROXY_AUTH_TOKEN`）
- UI 用的是 **Mantine**，不是 MUI：Run 按钮文案是 `Execute Tool`，连服务器点 `span.mantine-Switch-track`
- 成功后参数输入框会被 React 受控组件清空（想连点两次同参，靠 state 保留而不是输入框）
- **默认拒绝 `HOST=0.0.0.0`**：要 `DANGEROUSLY_BIND_ALL_INTERFACES=true` 才放行（它自己会警告 backend 能 spawn 本地进程）
- **明文 HTTP + 非 localhost = 非安全上下文**：`crypto.randomUUID` 直接 `undefined`，连服务器必失败；必须 HTTPS 或 localhost
- **还有一层 Origin 校验**：跨机访问要点「连接」需把浏览器 Origin 登记进 `ALLOWED_ORIGINS`，否则 `403 Invalid origin`（该变量一设就整体覆盖默认值，localhost/127.0.0.1 要一并列上）
- **token 可固定**：`MCP_INSPECTOR_API_TOKEN=<固定串>` 能顶掉随机 `randomBytes(32)`（`MCP_INSPECTOR_SECRET_KEY` 只加密凭据存储，**不影响** token，别搞混）

## 从文档首页打开

不想每次手敲上面那条命令的话，用脚本起一个**常驻、地址稳定**的实例：

```bash
bash scripts/start-inspector.sh    # 在仓库根目录执行
```

- **地址在文档站首页**「测试环境」表格的「MCP 调试器」那一行，直接点开即可
  （`http://100.113.135.55:3027/`）。脚本每次启动都会把这个格子刷成最新的带 token 链接。
- 脚本会把实例跑成 systemd 单元 `mcp-inspector`（`qiqi-svc.slice`），不占当前 shell / DSH 的资源和生命周期，退出终端也不影响。
- token 通过 `MCP_INSPECTOR_API_TOKEN` 固定，正常重启地址不变；**但地址里的 token 每次重启仍可能变**（比如你改过 `INSPECTOR_TOKEN` 或用命令行手工起过），所以**重启后要再跑一次 `bash scripts/start-inspector.sh`**，它会自动刷新首页链接。
- 停用：`bash scripts/stop-inspector.sh`（只按 systemd 单元名停，不会误杀别的进程）。

对外地址为什么是 `https://<主机名>.ts.net:8444` 而不是 `http://<IP>:6274`：

- `6274` 只绑 `127.0.0.1`，对外由 `tailscale serve` 提供 HTTPS 入口（脚本会自动确保这条映射）。
- 从**非 localhost 的明文 HTTP** 打开时浏览器不给「安全上下文」，`crypto.randomUUID` / `crypto.subtle` 为 `undefined`，Inspector 一连接就报 `crypto.randomUUID is not a function`。所以走 HTTPS 不是为了好看，是**必须**。
- 想强行直接暴露端口（仅限可信网络）：`HOST=0.0.0.0 BIND_HOST=0.0.0.0 bash scripts/start-inspector.sh`，脚本会加 `DANGEROUSLY_BIND_ALL_INTERFACES=true` 绕过它的拦截，但页面如上所述会连不上，不建议。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MALL_BASE` | `http://127.0.0.1:18080` | 后端网关地址 |
| `MALL_API_KEY` | —（**必填**） | 管理端 API Key，随请求头 `X-Api-Key` 发出；**按库隔离**（dev 一把、test 一把），造 key 见上面「认证」 |
| `MCP_ENABLE_WRITE` | `0` | `0`=只注册 12 个只读工具（订单 6 + 商品 6）；`1`=再加 1 个写工具 `execute_audit_pass`（共 13 个） |

见 `.env.example`。

## 客户端配置示例

```json
{
  "mcpServers": {
    "qiqi-mall": {
      "command": "node",
      "args": ["/root/qiqi-mall/mcp-server/src/index.mjs"],
      "env": {
        "MALL_BASE": "http://127.0.0.1:18080",
        "MALL_API_KEY": "ak_你的测试库key",
        "MCP_ENABLE_WRITE": "1"
      }
    }
  }
}
```

## 安全性

- **认证**：只用 API Key（`X-Api-Key`），key 按库隔离、可单独禁用/删除；口令（`MALL_USER`/`MALL_PASS`）不再出现在 MCP 侧。
  key 明文不入库（库里只存 sha256），也不要提交进仓库。
- **写开关**：`MCP_ENABLE_WRITE=0` 时写工具根本不注册，模型连看都看不到。
- **确认码在后端**：`confirmCode` 由 `split-preview` 签发、`audit-pass` 校验，存在后端 KV（默认 300 秒、一次性、
  绑定 op + 订单 id + 拆单方案指纹）。**闸门在执行写操作的那一层**，所以直接 curl 写接口也绕不过去 ——
  这是把票据从 MCP 进程内 Map（旧 `src/confirm.mjs`）搬到后端的根本原因。
- **防偷换**：重算的拆单方案指纹与发码时不一致就拒绝（「拆单方案已变化」），避免「确认的是 A、执行成 B」。
- **跨单防护**：码里带订单 id，`audit-pass` 会校验「码里的 id == 路径 id」，A 单的码拿去审核 B 单会被拒。
- **stdio 纪律**：所有日志走 `console.error`，stdout 只留给 JSON-RPC。
