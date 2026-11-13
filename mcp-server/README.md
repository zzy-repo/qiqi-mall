# qiqi-mall MCP Server（stdio 最小闭环）

把商城后台「订单审核 + 拆单」暴露成 MCP 工具，给 Claude Desktop / 任意 MCP 客户端调用。
本仓库这一层**只动 MCP 侧、后端一行没改** —— 它就是纯粹的 REST 客户端（认证 = API Key）。

> **并发控制口径（现行）**：**`version` 乐观锁**（后端 406a54b）——
> 预演只回答「会做什么」（`willDo`），写操作靠 `version` fail-closed 校验
> （缺 version → `400`，版本冲突 → `409`）。MCP 是「人的操作的模拟」：人在 UI 上会撞的锁，
> MCP 走同一个接口必然也会撞到，这正是 `update_product` / `update_sku_stock` 必须带 version 的原因。

## 闭环

```
① list_pending_orders()                 列出待审核订单（只读）
② preview_split(orderId)                拆单方案（dry-run 不落库）→ { willDo（中文预演）, plan }
③ execute_audit_pass(orderId)           订单 7→8 并真的生成发货单（写；只需 orderId）
④ list_deliveries(orderId)              回读，确认包裹数与方案一致
```

预演（`POST /admin/orders/{id}/split-preview`，等价于 `?dryRun=true`）返回
`{ willDo: "将把订单 QQ… 审核通过：状态 待审核→待发货，并按方案生成 N 个发货单", plan: [...] }` ——
预演本身不写任何数据。重复执行由后端业务守卫拦（「只有待审核订单可以审核通过」），
期间被抢改由 `version` 乐观锁拦（`409`）。

## 工具清单（按域）

| 域 | 工具 | 读/写 | 后端接口 |
|---|---|---|---|
| 订单 | `list_orders` | 只读 | `GET /admin/orders?page&size&status&orderSn`（后端真分页；orderSn 模糊） |
| 订单 | `get_order_detail` | 只读 | `GET /admin/orders/{id}`（`{order,items,returnApplies}`） |
| 订单 | `count_deliveries` | 只读 | `GET /admin/deliveries/counts?orderIds=`（不含已取消） |
| 订单 | `list_pending_orders` | 只读 | `GET /admin/orders?status=7` |
| 订单 | `preview_split` | 只读（dry-run 预演） | `POST /admin/orders/{id}/split-preview` → `{willDo, plan}`（无码） |
| 订单 | `execute_audit_pass` | **写** | `POST /admin/orders/{id}/audit-pass`，入参只有 `{orderId}`（无码） |
| 订单 | `list_deliveries` | 只读 | `GET /admin/deliveries?orderId=` |
| 订单 | `create_order` | **写** | `POST /admin/orders`，body `{order,items}`；总额由后端按「明细单价×数量」重算，新建 version=0 |
| 订单 | `update_order` | **写（带 version）** | `PUT /admin/orders/{id}`（只改收货人/电话/地址/支付方式；**status 被后端强制忽略**，状态只能走审核/发货接口） |
| 订单 | `audit_reject_order` | **写（L3，支持 dryRun）** | `POST /admin/orders/{id}/audit-reject`：待审核(7)→已关闭(4) + **回补总池库存** |
| 订单 | `ship_order` | **写（L3，支持 dryRun）** | `POST /admin/orders/{id}/ship`：已付款(1)→已发货(2)，**直发路径不拆单** |
| 订单 | `delete_order` | **写（L3，支持 dryRun，不可恢复）** | `DELETE /admin/orders/{id}`：级联删明细/发货单/物流 |
| 商品 | `search_products` | 只读 | `GET /admin/products`（后端只支持 publishStatus，关键字/分类/分页在工具层做） |
| 商品 | `get_product_detail` | 只读 | `GET /admin/products/{id}` + `GET /admin/skus?productId=` |
| 商品 | `list_categories` | 只读 | `GET /admin/categories`（扁平 → 工具层拼树） |
| 商品 | `list_product_types` | 只读 | `GET /admin/product-types` + 每类型 `GET /admin/attributes?typeId=` |
| 商品 | `list_attributes` | 只读 | `GET /admin/attributes?typeId=` |
| 商品 | `list_skus` | 只读 | `GET /admin/skus?productId=` |
| 商品 | `get_sku_detail` | 只读 | `GET /admin/skus/{id}` → `{sku,specs}`（单个 SKU 详情，含规格值） |
| 商品 | `list_sku_attributes` | 只读 | `GET /admin/skus/attributes?productId=`（该商品所属类型下的属性，建 SKU 时选规格名用） |
| 商品 | `create_product` | **写** | `POST /admin/products`，body `{product,gallery}`（新建 version=0；**不自动建 SKU**） |
| 商品 | `delete_product` | **写（支持 dryRun）** | `DELETE /admin/products/{id}`（级联 SKU/相册；被订单明细/购物车引用时拒绝 → 应改为下架） |
| 商品 | `create_sku` | **写** | `POST /admin/skus`，body `{sku,specs}`（新建 version=0；productId 必填） |
| 商品 | `delete_sku` | **写（支持 dryRun）** | `DELETE /admin/skus/{id}`（被订单明细/购物车引用时拒绝 → 应把库存改 0） |
| 字典 | `category(action)` | 1 只读 + 3 写（L2） | `GET /admin/categories/{id}`、`POST /admin/categories`、`PUT/DELETE /admin/categories/{id}`（**表无 version 列**；有子分类/商品引用时拒删） |
| 字典 | `product_type(action)` | 1 只读 + 3 写（L2） | `GET /admin/product-types/{id}`、`POST`、`PUT/DELETE /admin/product-types/{id}`（被属性/商品引用时拒删） |
| 字典 | `attribute(action)` | 1 只读 + 3 写（L2） | `GET /admin/attributes/{id}`、`POST`、`PUT/DELETE /admin/attributes/{id}`（被 SKU 规格值使用时拒删） |
| 商品 | `update_product` | **写（带 version）** | `PUT /admin/products/{id}`，body `{product:{id,version,...}}` |
| 商品 | `update_sku_stock` | **写（带 version）** | `PUT /admin/skus/{id}`，body `{sku:{id,version,stock/price}}` |
| 履约 | `delivery(action)` | 2 只读 + 7 个 **L3 写** | `GET /admin/deliveries?orderId=`（**orderId 必填**，无单条详情/无全局列表）、`GET /counts?orderIds=`、`PUT /{id}`、`POST /{id}/split`、`POST /{id}/merge`、`DELETE /{id}?version=`、`POST /{id}/dispatch`、`POST /{id}/cancel`、**`POST /{id}/wms-callback?event=pick\|outbound\|transit\|signed`**（模拟 WMS 回传，后台「模拟回传」按钮；幂等、不收 version；7 个写动作都支持 `?dryRun=true`） |
| 退货 | `return_apply(action)` | 2 只读 + 1 个 **L3 写** + 2 个普通写 | `GET /admin/return-applies?status=&orderId=`、`PUT /admin/return-applies/{id}`（审核，支持 `?dryRun=true`；**表无 version 列**）、`POST /admin/return-applies`（**代客登记**：orderId/memberId/reasonId/returnReason 四项必填，缺一个后端只会 500）、`DELETE /admin/return-applies/{id}`（删记录，**不退款、不回补库存**） |
| 退货 | `return_reason(action)` | 1 只读 + 3 写（L2） | `GET/POST /admin/return-reasons`、`PUT/DELETE /admin/return-reasons/{id}`（被引用时后端拒删） |
| 配置 | `warehouse(action)` | 1 只读 + 3 写（L2） | `GET/POST /admin/warehouses`、`PUT/DELETE /admin/warehouses/{id}` |
| 配置 | `order_setting(action)` | 1 只读 + 1 写（L2，**影响全站**） | `GET/PUT /admin/order-setting`（全局一行，upsert，无 version） |
| 会员 | `member(action)` | 2 只读 + 3 写 | `GET/POST /admin/members`、`GET/PUT/DELETE /admin/members/{id}`（**PUT 必须 version**；DELETE 是 L3） |
| 会员 | `member_address(action)` | 1 只读 + 2 写（L2） | `GET/POST /admin/member-addresses`、`PUT/DELETE /admin/member-addresses/{id}`（表无 version 列） |
| 会员 | `member_cart(action)` | 1 只读 + 3 写（L2） | `GET /admin/carts?memberId=`、`POST /admin/carts`、`PUT/DELETE /admin/carts/{id}`（表无 version 列；改的是库里那条记录，不是买家自助购物车） |
| 概览 | `dashboard_overview()` | 只读 | `GET /admin/dashboard/overview` |
| 上传 | `upload_image_from_url(url)` | **写**（`MCP_ENABLE_WRITE`） | `POST /admin/upload/image-from-url`（body `{url}`）。**只传 URL，不传文件 / base64 / 本地路径**：拉取由后端做，带全套 SSRF 防护 —— 只允许 https（http / file / ftp / gopher 一律拒）、禁内网 / 回环 / 链路本地 / CGNAT（`127/8`、`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、`100.64/10`、`::1`、`fc00::/7`、`fe80::/10`）、**不跟随重定向**（3xx 直接拒）、连接 5s / 读取 10s、正文 **≤5MB**、**前 16 字节嗅探**真实类型（只收 jpg/png/webp，不信 Content-Type）、**解析一次拿 IP 后用该 IP 连**（防 DNS 重绑定）。失败是中文原因 + 400/403/413/415/502/504（不是 500）。返回结构与人工上传一致：`{url: "/qiqi-mall/products/2026/11/uuid.webp", objectName}`，scope `catalog:write` |
| 台账 | `list_ledgers(type,bizId,operatorType,from,to,page,size)` | 只读 | `GET /admin/ledgers`（scope `ledger:read`）：七类台账 UNION —— ORDER_STATUS / DELIVERY_STATUS / RETURN_STATUS / AUDIT / WMS / STOCK / PAYMENT，**真分页**；`type` 留空=查全部（此时 `bizId` 传订单 id 会连它的包裹与售后一起带出来）；`operatorType` 只放行 ADMIN/MEMBER/APIKEY/SYSTEM，且 **STOCK 支没有操作人列**、按操作人筛时被排除 |
| 库存对账 | `get_inventory_reconcile(skuId)` | 只读 | `GET /admin/inventory/reconcile?skuId=`（scope `stock:read`）：`bucketSum`（桶权威）/ `skuStock`（展示缓存）/ `drift` / `ledgerNet`（**全生命周期进出和，不是余额**）/ 桶数一致性 / 最近 20 条流水。SKU 不存在时后端 `code=1` 报错（不是空对象） |
| 库存对账 | `list_inventory_drifts(threshold,page,size)` | 只读 | `GET /admin/inventory/reconcile/drift`（scope `stock:read`）：`|桶和−缓存| > threshold`，**真分页**；`threshold` 默认 100，传 `0` = 任何非零偏差都列（排查缓存回写是否在工作） |
| 自动审核 | `list_order_audit_queue(result,page,size)` | 只读 | `GET /admin/order-audit/queue`（scope `audit:read`）：每单最新一轮决策，五判定 AUTO_PASS/MANUAL/BLOCKED/FROZEN/NO_MATCH。⚠️ 后端是手写 Map → 行数组叫 **`rows` 不是 `records`**，行内字段是 **snake_case**（工具层不改名） |
| 自动审核 | `get_order_audit_trail(orderId)` | 只读 | `GET /admin/order-audit/orders/{id}/trail`（scope `audit:read`）：判定 / 状态台账 / 操作审计三段按时间拼，带「判定↔台账」对齐信息。**id 不存在时返回空数组且 code=0** → 工具层判空 |
| 自动审核 | `list_audit_policies()` | 只读 | `GET /admin/audit-policies`（scope `audit:read`）：策略（AUTO/BLOCK/EXCEPTION，含优先级/启用/versionNo/条件行）+ 全局排除 + 字段/操作符元数据 |
| 自动审核 | `list_audit_runs(page,size)` | 只读 | `GET /admin/audit-runs`（scope `audit:read`）：每轮 run 一行（范围内单数 + 五类计数），形态同 queue（`rows`） |
| 多平台 | `list_channel_shops()` | 只读 | `GET /admin/channel-shops`（scope `channel:read`）：裸数组，每店带平台类型 / 启用 / 水位 / 上次拉单 / 今日 OK·FAIL·SKIP |
| 多平台 | `list_channel_sync_logs(shopId,action,result,page,size)` | 只读 | `GET /admin/channel-sync-logs`（scope `channel:read`）：**真分页**；`action` = PULL/SHIP_PUSH，`result` = OK/FAIL/SKIP（**SKIP 不是失败**：幂等命中或 SKU 对不上整单跳过），带请求/响应原文 |

并发控制口径：**只读工具无副作用、可反复调用；写工具靠 `version` 乐观锁**。
`update_product` / `update_sku_stock` 的 `version` 是 **zod 必填**（缺了在协议层就被拒，请求都到不了后端）；
后端 409 的中文原因会原样透出，并附上「带 `[409]` + 请重新读取最新 version」的下一步提示。
成功后后端不回新版本号（`data:null`），**由工具层回读**一次把新 version 交给模型。

**哪些工具带 version、哪些本来就不带**（照着后端真实入参写，别凭空造参数）：

| 表 | 有 version 列？ | 工具里的口径 |
|---|---|---|
| product / sku / member / delivery_order / order | 有 | 写工具必须带 version；缺 → `400`，冲突 → `409`；成功后工具**回读**新 version 返回给模型 |
| delivery_order 的 dispatch / cancel | 有，但接口不收 | 后端只让 WMS/运维调用，靠「仅待下发」状态守卫 + 影响行数闸门；工具里没有 version 参数 |
| return_apply / return_reason / warehouse / order_setting / member_address / cart / category / product_type / attribute | **没有** | 接口根本不吃 version；并发靠「UPDATE 影响 0 行 → `409 记录已被删除`」兜底；工具里**不能**造一个 version 参数（那样每次调用都会被当非法入参） |

`version` 在 `delivery(action=update|split|merge|delete)` 里是**按 action 必填**（同一个工具还承载不吃 version 的
`dispatch`/`cancel`），所以走 zod 可选 + 工具层/后端双重把关；`member(action=update)` 与
`update_product` / `update_sku_stock` 是 zod 必填。

字段口径（别照"标准商品"想当然，已读后端实体确认）：`Product` 只有
name / description / categoryId / typeId / publishStatus / pic / version —— **没有 subtitle、没有 price**
（价格在 SKU 上，改价请用 `update_sku_stock`）；相册是「整组替换」，误传会删图，故 `update_product` 刻意不支持。
`Sku` 只有 productId / price / stock / pic / version，`update_sku_stock` 只改 stock/price（不碰规格值）。

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
    ├── _shared.mjs    公共件：text / fail / failWrite / clampPage / paginate（分页全项目统一 1/10/50）
    ├── order.mjs      订单域 6 只读 + 6 写（execute_audit_pass / create_order / update_order / audit_reject_order / ship_order / delete_order）
    ├── catalog.mjs    商品域 8 个只读工具（含 get_sku_detail / list_sku_attributes）
    ├── catalog-write.mjs  商品域 6 个写工具（改：update_product / update_sku_stock，带 version；增删：create_product / delete_product / create_sku / delete_sku）
    ├── catalog-dict.mjs   商品字典 3 个：category / product_type / attribute（各 action=get|create|update|delete，三张表无 version）
    ├── delivery.mjs   履约域 1 个合并工具 delivery(action)：list/get + 7 个 L3 写动作（update/split/merge/delete/dispatch/cancel/wms_callback）
    ├── returns.mjs    退货域 2 个：return_apply(list|get|audit|create|delete) / return_reason(list|create|update|delete)
    ├── config.mjs     配置域 2 个：warehouse(list|create|update|delete) / order_setting(get|update)
    ├── member.mjs     会员域 3 个：member / member_address / member_cart(action=list|create|update|delete)
    ├── misc.mjs       概览 + 上传 2 个：dashboard_overview（只读）、upload_image_from_url（写，按 URL 拉图存对象存储）
    ├── ledger.mjs     台账域 1 个只读：list_ledgers（七类台账 UNION 检索，scope ledger:read）
    ├── inventory.mjs  库存对账域 2 个只读：get_inventory_reconcile / list_inventory_drifts（scope stock:read）
    ├── audit.mjs      自动审核域 4 个只读：list_order_audit_queue / get_order_audit_trail / list_audit_policies / list_audit_runs（scope audit:read）
    └── channel.mjs    多平台接入域 2 个只读：list_channel_shops / list_channel_sync_logs（scope channel:read）

../scripts/mcp-coverage.mjs   覆盖账目生成器（零依赖）：扫后端 @*Mapping + 解析工具声称的接口，
                              生成下面「覆盖账目」那一段；`--check` 与 README 比对（不一致 exit 1）
```

工具总数：**48 个**（write=1）/ **35 个**（write=0 —— 纯写工具 13 个不注册：
`execute_audit_pass` / `create_order` / `update_order` / `audit_reject_order` / `ship_order` / `delete_order` /
`update_product` / `update_sku_stock` / `create_product` / `delete_product` / `create_sku` / `delete_sku` /
`upload_image_from_url`；
用 `action` 承载写动作的工具仍在，但 action 枚举里只剩只读动作）。
第 6 批的 9 个工具（台账 / 库存对账 / 自动审核 / 渠道）**全部只读、不受写开关影响**：write=0 时也在，
且这一批**没有新增任何写工具**（红线：AI 能读证据与巡检，不能改判定、不能放行、不能动钱）。
测试：`npm test` 串行跑 12 个文件（loop / catalog / catalog-write / catalog-dict / order-read / order-write /
optimistic-lock / delivery / returns / config / member / misc），
自带造数据+清数据，**连跑两轮、第二轮不 reset 库**，两轮都必须 0 失败。

## 覆盖账目（脚本生成，勿手改）

这段账目以前是**手写**的：写「112 端点 / 覆盖 73」，而重扫实测是 134 个端点（第 6 批加工具前真实覆盖 74，加完 83）—— 手写的账一定会漂移。
现在它是 `scripts/mcp-coverage.mjs` 的**生成物**：扫后端 `@*Mapping` 拼全路径、解析 MCP 侧声称的接口、
按方法+路径段比对，输出下表。**别手改这一段**（`--check` 会红）。

<!-- BEGIN mcp-coverage（本段由 scripts/mcp-coverage.mjs 生成，勿手改） -->
**覆盖账目（脚本生成）**：后端 `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping`
共 **134** 个端点（`qiqi-mall-server/src/main/java` 全量扫描，类级前缀已拼上）。分类如下：

| 分类 | 端点 | MCP 已覆盖 | 缺口 | 说明 |
| --- | ---: | ---: | ---: | --- |
| 管理端业务 | 93 | 83 | 10 | 后台 admin-web 上「人能点」的接口（AI 应当覆盖的部分） |
| 内部系统 | 7 | — | — | 登录/验证码、买家注册，以及 mock 平台的入站回调（/channel-api）；不是后台业务操作 |
| 红线 | 8 | — | — | `/admin/admins**` + `/admin/api-keys**`：管理员与密钥管理，API Key 一律 403 |
| multipart 形态 | 1 | — | — | 只吃文件流的接口（`MultipartFile`），MCP 入参是模型产出的 JSON 文本，形态上做不到 |
| portal 端 | 24 | — | — | 买家端接口：读写都依赖会员身份，API Key 没有会员主语（`CurrentUser.id()` 为 null） |
| health | 1 | — | — | 健康检查，不是业务接口 |
| **合计** | **134** | **83** | **10** | 已覆盖只对管理端业务有「应当覆盖」的含义 |

**一句话汇总**：管理端业务 **93** / MCP 已覆盖 **83** / 缺口 **10**（后端共 134 个端点 = 管理端业务 93 + 内部系统 7 + 红线 8 + multipart 1 + portal 端 24 + health 1）；缺口 10 个里**结构上不给 AI 10 个、真漏 0 个**。

**portal 端 24 个端点（只读 13 / 写入 11）**：写接口需要买家身份，API Key 一律 403（`CurrentUser.id()` 为 null，写进去的数据无主）；只读接口与后台读的是**同一份数据**，用管理端工具读等价（所以单列在这里，不计入「管理端业务缺口」）。

**管理端业务缺口 10 个 = 结构上不给 AI 10 + 真漏 0**（「结构上不给 AI」的判定见下：它们的写 scope 不在 ALL_SCOPES 里，任何 API Key 都 403）：

| 方法 | 路径 | 性质 | 源文件 |
| --- | --- | --- | --- |
| POST | `/admin/audit-policies` | 不给 AI：audit:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/audit/controller/AuditPolicyController.java |
| PUT | `/admin/audit-policies/{id}` | 不给 AI：audit:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/audit/controller/AuditPolicyController.java |
| DELETE | `/admin/audit-policies/{id}` | 不给 AI：audit:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/audit/controller/AuditPolicyController.java |
| POST | `/admin/audit-policies/{id}/move` | 不给 AI：audit:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/audit/controller/AuditPolicyController.java |
| POST | `/admin/order-audit/run` | 不给 AI：audit:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/audit/controller/OrderAuditController.java |
| POST | `/admin/order-audit/orders/{id}/freeze` | 不给 AI：audit:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/audit/controller/OrderAuditController.java |
| POST | `/admin/order-audit/orders/{id}/unfreeze` | 不给 AI：audit:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/audit/controller/OrderAuditController.java |
| POST | `/admin/channel-shops/{id}/pull` | 不给 AI：channel:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/channel/controller/ChannelAdminController.java |
| POST | `/admin/channel/push-shipment` | 不给 AI：channel:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/channel/controller/ChannelAdminController.java |
| POST | `/admin/payments/{id}/replay-channel-callback` | 不给 AI：payment:write 不在 ALL_SCOPES 里（任何 key 都拿不到） | com/qiqi/mall/oms/controller/AdminPaymentController.java |

口径：MCP 侧声称的接口来自 `mcp-server/src/tools/*.mjs` 头注释 + `mcp-server/src/mall.mjs` 的 request() 路径常量，去 query、通配 `{id}` 后按「方法 + 段数 + 段值」严格比对（通配只匹配通配，避免 `/admin/skus/{id}` 顺手把 `/admin/skus/attributes` 也算掉）。重算：`node scripts/mcp-coverage.mjs`。
<!-- END mcp-coverage -->

```bash
node scripts/mcp-coverage.mjs           # 打印账目（stdout）
node scripts/mcp-coverage.mjs --check    # 与上面这段比对，不一致 exit 1（CI / 提交前跑）
node scripts/mcp-coverage.mjs --write    # 重新生成并写回本文件（人肉确认后再提交）
```

### 不给 AI 清单（刻意留的缺口，不是漏做）

这 10 个管理端端点**永远不会有 MCP 工具**，而且不是靠「没人写」兜住的 —— 它们的写 scope
（`audit:write` / `channel:write` / `payment:write`）**不在 `AiApiKeyService.ALL_SCOPES` 里**，
所以任何 API Key 打过去都是 403（过滤器还专门为这几种 scope 回一句「该接口对 API Key 只读」）。
上面缺口表里的「结构上不给 AI」就是这条规则自动算出来的，不是人工维护的清单。

| 端点 | 为什么不给 |
|---|---|
| `POST /admin/order-audit/run` | 一次批量改大量订单的命运（批量过审 = 批量放行） |
| `POST /admin/order-audit/orders/{id}/freeze`、`/unfreeze` | 动的是「人的刹车」：冻结/解冻决定这单能不能被自动处理 |
| `POST` / `PUT` / `DELETE /admin/audit-policies/{id}`、`POST /admin/audit-policies/{id}/move` | 动的是判定标准本身（改策略=改结论），还含策略排序 |
| `POST /admin/payments/{id}/replay-channel-callback` | 触发资金记账路径（钱） |
| `POST /admin/channel-shops/{id}/pull`、`POST /admin/channel/push-shipment` | 拉单会**建订单**、回传发货是**不可撤回的外呼**（改动在平台侧） |

**一句话口径：AI 能读证据与巡检，不能改判定、不能放行、不能动钱。**

### 新增一个域

新建一个域 = 新建 `src/tools/<域>.mjs` + 在 `src/tools/index.mjs` 加一行 import/调用；
工具内一律「中文 title + 说清什么时候用的 description + zod `.describe()` 参数 +
文本摘要 + `JSON:` 两段 + 空结果明确文案 + `fail(e)` 兜错」；
写工具统一用 `failWrite(e, {...})`（保留后端 bizCode，409 时给「重新读」的下一步）。

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

`npm test` 跑四套测试：用 SDK client 通过 stdio 真启动本 server，逐条断言。
**数据自建自清**，跑完库态与跑前一致（不消费种子数据、不需要复位脚本）：

- `test/loop.mjs`（11 条）：订单闭环 —— 预览返回 **willDo + plan**、
  `execute_audit_pass` 的 schema 只剩必填 `orderId`、预演可反复调用且不落库、
  执行成功且 `deliveries === plan`、真落库、状态 7→8、
  **重复执行被业务守卫拦**（「只有待审核订单可以审核通过」）、回读闭环、写开关生效。
- `test/optimistic-lock.mjs`（13 条）：**乐观锁（version）验收** ——
  读接口带 version（商品 + 每个 SKU）；`update_product` / `update_sku_stock`
  带正确 version 成功且 version +1（回读 + 直连 DB 双确认）、带旧 version 被拒（文本含
  「数据已被他人修改」+ `[409]`，且数据没被覆盖）、不带 version 在 zod 层被拒；
  **并发场景**：MCP 读到 version=V → 另一个人用裸 HTTP 改同一行 → MCP 用 V 提交 → 409
  → 重读 V+1 → 提交成功（商品与 SKU 两种载体都验）；write=0 时两个写工具消失。
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

- **stdio 子进程拿不到父进程 env** → 不传 `-e` 会漏掉写工具（`MCP_ENABLE_WRITE` 不生效，只剩只读工具，写 action 也会从枚举里消失）；
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
| `MCP_ENABLE_WRITE` | `0` | `0`=只注册 35 个只读工具（纯只读 + 「只读动作」工具，后者的 action 枚举里写动作被摘掉）；`1`=再加 13 个纯写工具（含 `upload_image_from_url`）并放开所有 action 的写动作，共 48 个。第 6 批的 9 个证据/巡检工具**只读、不受开关影响** |

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
- **并发控制 = version 乐观锁**：商品/SKU 的更新接口要求 `product.version` / `sku.version`，
  缺版本号回 `400`（fail-closed，**绝不**静默降级成后写覆盖），版本冲突回 `409`
  「数据已被他人修改（可能其他人在编辑），请刷新后重试」，且冲突时一个字段都不会被覆盖。
  MCP 侧把 `version` 做成 zod 必填参数、成功后回读新 version、409 时把中文原因和「重新读」的下一步一起交给模型 ——
  所以模型不可能「拿旧 version 硬重试」还以为能成功。
- **状态守卫仍在后端**：只有 `status=7` 的订单能审核通过，
  重复执行/越权状态一律被后端拦住（MCP 只是透出中文原因）。
- **stdio 纪律**：所有日志走 `console.error`，stdout 只留给 JSON-RPC。
