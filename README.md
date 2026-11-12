# qiqi-mall 七七商城

前后端分离商城项目：Vue 3 + Spring Boot，B/C 端分离两套前端 + JWT 鉴权。

## 目录结构

```
qiqi-mall/
├── portal-web/             # C 端买家商城（登录注册/购物车/结算/我的订单/收货地址）
│   ├── nginx/              # 容器内 nginx 配置模板（代理 /portal/）
│   └── src/{api,layout,router,utils,views}
├── admin-web/              # B 端管理后台（登录 + 商品/订单/会员管理）
│   ├── nginx/              # 容器内 nginx 配置模板（代理 /admin/）
│   └── src/{api,layout,router,utils,views}
├── qiqi-mall-server/       # 后端 (Spring Boot)
│   └── src/main/java/com/qiqi/mall/
│       ├── controller/     # 接口层
│       ├── service/        # 业务层 (impl 为实现)
│       ├── mapper/         # 数据访问层
│       ├── entity/         # 数据库实体
│       ├── dto/            # 传输对象
│       ├── config/         # 配置类（Jackson 时间格式等）
│       ├── security/       # 鉴权：JWT / 双过滤器链 / 验证码 / 登录上下文
│       ├── common/         # 通用返回/异常
│       └── utils/          # 工具类
├── docker-compose.yml      # 部署编排（测试环境）
├── sql/                    # 数据库脚本
└── docs/                   # 文档
```

## 文档图集约定

`docs/flows/02-业务场景/` 里场景 1、2 是单主体流程（一个人在自己操作），用流程图；场景 3、4、5 涉及多个角色和异步回传，用时序图。图型按内容选，不按风格统一。

## 数据库

结构见 `sql/schema.sql`（依据 ER 图），共 14 张表，分三个模块：

- **商品模块**：`category` 商品分类（自关联树）、`product_type` 商品类型、`attribute` 属性、`product` 商品SPU、`sku` SKU
- **订单模块**：`order` 订单、`order_item` 订单明细、`return_apply` 退货申请、`return_reason` 退货原因、`order_setting` 订单设置
- **用户模块**：`member` 会员、`member_address` 收货地址、`cart` 购物车、`admin` 管理员

MySQL 8 跑在容器里（compose 的 `mysql` 服务），首次启动自动执行建表脚本：

```bash
docker compose up -d mysql        # 本机 127.0.0.1:3306，root 密码见 .env.example
mysql -h127.0.0.1 -uroot -pqiqi123456 qiqi_mall
```

| 脚本 | 内容 |
|------|------|
| `sql/schema.sql` | 建库建表 |
| `sql/data.sql` | 示例数据（3 会员 / 3 地址 / 3 购物车 / 1 管理员 / 10 分类 / 3 类型 / 6 属性 / 5 商品 / 9 SKU / 4 订单 / 2 退货）|
| `sql/migrate-optimistic-lock.sql` | 5 张热表加乐观锁 `version` 列（幂等，已有库升级时跑；见「并发控制」一节） |

示例账号（密码统一 `123456`，BCrypt 存储）：后台 `admin`，买家 `qiqi` / `xiaoming`（`laowang` 为禁用状态）。

两个脚本都挂到 `/docker-entrypoint-initdb.d/`，数据卷为空时按序自动执行。
**已有数据卷**（表已经建好了）升级到「乐观锁」版本时，手动跑一次迁移即可（可重复执行）：

```bash
docker exec -i -e MYSQL_PWD=$MYSQL_ROOT_PASSWORD qiqi-mysql \
  mysql --default-character-set=utf8mb4 -uroot < sql/migrate-optimistic-lock.sql
```

`migrate-*.sql` 都是幂等的：重跑第二次全部命中「已存在」→ 跳过，不报错、不动数据。

## 本地开发（日常迭代）

不走容器，直接跑源码，支持热更新。

```bash
# 终端 1：后端 8081
cd qiqi-mall-server
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 终端 2：C 端买家商城 5173
cd portal-web
npm install          # 首次
npm run dev

# 终端 3：B 端管理后台 5175
cd admin-web
npm install          # 首次
npm run dev
```

- C 端商城：http://localhost:5173
- B 端后台：http://localhost:5175

**热更新表现（已实测）**：

| 改动 | 生效方式 | 耗时 |
|------|---------|------|
| 前端 Vue/CSS | Vite HMR，浏览器局部更新不刷新页面 | ~110 ms |
| 后端 Java | 改动后 `mvn compile`，devtools 自动重启 | ~4 s |

后端热重启说明：`spring-boot-devtools` 监听 `target/classes`，需要先重新编译。
用 IDEA 开启自动编译即可全自动；命令行手动执行：

```bash
cd qiqi-mall-server && mvn -o compile
```

⚠️ **新增 Maven 依赖后，dev 8081 必须整进程重启**（`mvn compile` 触发的 devtools 热重启救不了：JVM classpath 是启动时固定的，新依赖的类加载不到，会 `NoClassDefFoundError` 把服务带下去）。此时杀掉 8081 进程重新 `mvn spring-boot:run` 即可；e2e 后端（8091/8092）还要重跑 `scripts/e2e-classpath.sh` 刷新 `target/e2e-cp.txt`。

## 部署（容器）

镜像多阶段构建，只对外暴露前端端口，详见 `docs/docker.md`。

```bash
docker compose up -d --build      # 构建并启动
docker compose ps                 # 状态
docker compose logs -f portal-web # 日志
docker compose down               # 停止
```

- C 端商城：http://localhost:5174
- B 端后台：http://localhost:5176

| 环境 | C 端 | B 端 | 后端 | 说明 |
|------|------|------|------|------|
| 本地开发 | 5173 | 5175 | 8081 | 直接跑源码，热更新 |
| 容器部署 | 5174 | 5176 | 8082 | 后端仅容器内网，不映射宿主机 |

两套可同时运行，端口互不冲突。Tailscale 组网内可用 `http://100.113.135.55:<端口>` 访问。

## 鉴权与接口分流

同一个后端进程，按 URL 前缀分两条独立的 Spring Security 过滤器链：

| 前缀 | 面向 | Token | 放行规则 |
|------|------|-------|----------|
| `/admin/**` | 管理后台 | admin JWT | `/admin/auth/login`、`/admin/auth/captcha*` 公开；其余需 `ROLE_ADMIN` |
| `/portal/**` | 买家商城 | member JWT | `/portal/auth/**` 公开；商品/分类/规格 GET 公开；购物车、订单、退货、地址需 `ROLE_MEMBER` |

- Token：JWT（HS384），claims 含 `uid` / `role`，默认有效期 720 分钟，密钥 `app.jwt.secret`（可用环境变量 `JWT_SECRET` 覆盖）
- 机器调用（AI / 脚本）可用 `X-Api-Key: ak_xxx` 替代 JWT：按路径映射所需 scope（如 `order:write`），
  缺失即 `403 API Key 缺少权限：…`；`/admin/admins**`、`/admin/api-keys**` 两条红线对 API Key 一律拒绝（只能人工 JWT）。
- 身份通过 ≠ 这次写操作可以执行：14 个高危写操作还要 `confirmCode`，见下面的「确认码（confirmCode）」一节。
- 密码：`BCryptPasswordEncoder`
- 未登录/过期统一返回 `401 + {code:1, message:'未登录或登录已过期'}`；前端捕获后清 token 并跳登录页（带 `redirect`）

### 后台登录验证码

同一用户名连续失败 5 次后要求输入图形验证码（见 `docs/flows/8-验证码机制时序图.mmd`）：

1. 登录页输完用户名 → `GET /admin/auth/captcha-required?username=`（5 分钟窗口内失败 ≥5 次返回 true）
2. 需要时 `GET /admin/auth/captcha` → `{captchaId, image(base64)}`，点图可刷新，有效期 5 分钟
3. 提交登录带 `captchaId + captchaCode`，校验后**立即销毁**（防重放）；密码错误则失败计数 +1，成功则清零
4. 内存存储 + 每 10 分钟清扫过期条目

买家侧注册成功即自动登录；登录/注册成功后会把浏览器本地（匿名）购物车合并到账号云端购物车。

### 前端路由

- 两个前端都有全局守卫：无 token 访问需登录页面 → 跳登录页并记录 `redirect`，登录后回跳
- 未知路由自动回主界面：C 端回首页 `/`，B 端回数据概览 `/dashboard`

## C 端页面

| 路由 | 页面 | 登录要求 |
|------|------|---------|
| `/` | 首页（分类筛选 + 搜索 + 商品卡片） | 游客可看 |
| `/product/:id` | 商品详情（规格选择 + 加购/立即购买） | 游客可看 |
| `/cart` | 购物车（未登录用本地购物车） | 游客可用 |
| `/login` | 登录 / 注册（注册即自动登录） | — |
| `/checkout` | 结算（收货地址 + 提交订单） | 需登录 |
| `/orders` | 我的订单（详情 / 模拟支付 / **确认收货** / 申请退货 / **售后状态列**） | 需登录 |
| `/addresses` | 收货地址管理 | 需登录 |

支付为模拟：`POST /portal/orders/{id}/pay` 把订单从「待付款」置为「已付款」并记录支付时间。

## B 端页面

侧边栏：用户（会员列表 / 管理员）、订单（订单列表 / 退货申请 / 订单设置）、商品（商品列表 / 商品分类 / 商品类型）。
收货地址与购物车聚合在会员行操作里，订单明细在订单详情抽屉，属性在商品类型、SKU 在商品列表 —— 均不占独立菜单。

订单列表行操作：明细 / **审核**（通过 → 自动拆单生成发货单，不通过 → 关闭订单并回补库存）/ 编辑；
审核通过走「拆单方案预览 → 后端签发确认码 → 带码执行」，发货单（子单）上再做下发 / 编辑 / 拆分 / 合并 / 删除。
退货申请行「审核」可选通过或拒绝。

## 确认码（confirmCode）

高危写操作不是「点一下就生效」，而是**先预演、再执行**：`?dryRun=true` 只算不落库，把「将要发生什么」用中文（`willDo`）说清楚，同时签发一张一次性票据 `confirmCode`；真正执行必须把这张票据带回来。
闸门在**后端**（`ConfirmCodeService` + `ConfirmOps`），不是前端弹窗也不是 MCP 进程内的开关 —— 所以绕过前端直接 curl / 脚本 / AI 调接口，一样挡得住。

### ① 哪些操作要码（14 个 L3 高危写操作）

| 域 | 操作 | 接口 | 指纹绑定（任一项变了码即作废） |
|---|---|---|---|
| 订单 | 审核通过（自动拆单） | `POST /admin/orders/{id}/audit-pass` | 订单 id + 拆单方案指纹 |
| 订单 | 审核不通过（回补库存） | `POST /admin/orders/{id}/audit-reject` | 订单 id + 回补明细 + 回补总件数 |
| 订单 | 订单发货（直发路径，不拆单） | `POST /admin/orders/{id}/ship` | 订单 id + 当前状态 + 明细条数 + 该订单现有发货单数 |
| 订单 | 删除订单（级联删发货单） | `DELETE /admin/orders/{id}` | 订单 id + 明细条数 + 发货单条数 |
| 履约 | 下发 WMS | `POST /admin/deliveries/{id}/dispatch` | 包裹 id + 订单 id + 仓库 id + 明细摘要 |
| 履约 | 编辑包裹（改仓/改量） | `PUT /admin/deliveries/{id}` | 包裹 id + 目标仓库 id + 改后明细 |
| 履约 | 拆分包裹 | `POST /admin/deliveries/{id}/split` | 包裹 id + 新包仓库 id + 拆出明细 + 源包剩余 |
| 履约 | 合并包裹 | `POST /admin/deliveries/{id}/merge` | 包裹 id + 目标包裹 id + 源包明细条数 |
| 履约 | 删除包裹 | `DELETE /admin/deliveries/{id}` | 包裹 id + 并入目标 id + 明细条数 |
| 履约 | 取消包裹（回补库存） | `POST /admin/deliveries/{id}/cancel` | 包裹 id + 订单 id + 回补明细 + 总件数 |
| 履约 | WMS 回传 | `POST /admin/deliveries/{id}/wms-callback?event=pick\|outbound\|transit\|signed` | 包裹 id + 事件名 + 当前状态 |
| 退货 | 退货审核（通过退款 / 拒绝） | `PUT /admin/return-applies/{id}` | 申请 id + 结论(1/2) + 退款金额 + 回补总件数 |
| 商品 | 删除商品 | `DELETE /admin/products/{id}` | 商品 id + 订单明细引用数 + 购物车引用数 + 下辖 SKU 数 |
| 商品 | 删除规格 | `DELETE /admin/skus/{id}` | SKU id + 订单明细引用数 + 购物车引用数 |

### ② 怎么用：预演 → 拿码 → 带码执行

```bash
# ① 预演（dryRun=true，不落库）
curl -X POST "$B/admin/orders/7/audit-pass?dryRun=true" -H "Authorization: Bearer $AT"
# → {code:0, data:{
#      willDo:"将把订单 QQ2026...（id=7）审核通过：状态 待审核→待发货，并按方案生成 2 个发货单（深圳仓 1 条明细、北京仓 1 条明细）。",
#      confirmCode:"3f2b1c00-....-....-....-............",   # 36 位 UUID
#      expiresInSec:300 }}

# ② 执行：同一地址（不带 dryRun），body 里带上这张码
curl -X POST "$B/admin/orders/7/audit-pass" -H "Authorization: Bearer $AT" \
     -H 'Content-Type: application/json' -d '{"confirmCode":"3f2b1c00-...."}'
```

- 码是**一次性**的：存在 `KvStore` 里，校验时**原子取出即删**（Redis 档 = `GETDEL`，单实例档 = 内存 Map 的原子 remove），成功、失败、被拒都不能再用；有效期默认 **5 分钟**（`app.confirm-code.ttl-minutes`，随预演响应的 `expiresInSec` 下发，前后端不各写一份）
- 本身有业务请求体的接口（`PUT /admin/deliveries/{id}`、`PUT /admin/return-applies/{id}`、`split` / `merge`）就在自己的 body 里加 `confirmCode` 字段（也接受 `?confirmCode=` 兜底）
- `audit-pass` 另有一个真预览接口 `POST /admin/orders/{id}/split-preview`：返回同一张码，外加 `plan`（拆成几包、每包哪个仓、哪些明细），后台「查看拆单方案」用的就是它；不预览直接点通过，前端也会先替你调一次拿码
- 预演会**先跑一遍业务守卫**：删不掉的商品、已下发的包裹，预演阶段就回中文原因，不会签出一张注定失败的码（个别接口例外，见 ④）

### ③ 为什么不给可逆的小改动加码（分级）

- **L1 只读**（列表 / 详情 / 导出）：不要码。
- **L2 可逆写**（改商品名、改价格、改地址、上新商品、改订单收货信息…）：改错了能改回来，加码只让正常运营多点一次「确定」，收益为负 → **一律不加码**。
- **L3 高影响 / 不可逆**（上表 14 个）：删数据、改履约状态、动钱（退款）、动库存（回补），错了只能人工对账找补 → 必须加码。

判据不是「写没写库」，而是**错了能不能自己退回来**。

### ④ 校验顺序：先验码 → 再拦业务守卫

执行时按这个顺序检查，任何一步不过立即返回中文原因：

1. 缺码 → `缺少确认码：请先以 dryRun=true 预演本次操作`（`audit-pass` 措辞是 `缺少确认码：请先预览拆单方案`）
2. 码不存在 / 已用过 / 已过期 → `确认码无效、已使用或已过期（5 分钟），请重新以 dryRun=true 预演`（`audit-pass` 末句为 `请重新预览拆单方案`）
3. 用途不符（A 操作的码拿去 B 操作）→ `确认码用途不匹配（该码用于 …，不能用于 …），请重新预演`
4. 目标不符（A 单的码审核 B 单）→ `确认码与操作对象不匹配，请重新预演`
5. 指纹变化（期间数据被改：方案 / 明细 / 金额 / 状态）→ `操作内容已变化（期间数据被改动），拒绝执行，请重新预演`
6. 全过之后，才轮到原本的业务守卫（`只有待审核订单可以审核通过`、`只有待下发发货单可以下发`、`该商品已被 N 条订单明细引用，不能删除（可改为下架）`…）

> 有一处例外：`PUT /admin/return-applies/{id}` 把「申请不存在 / 该申请已处理 / 结论只能是 1 或 2」放在验码之前 —— 这几条属于「这次请求本身就不成立」，先回原因比先说码更好懂。

为什么是这个顺序：码代表「用户刚看到并同意的那一版意图」，码本身失效就没必要再谈业务状态；反过来若先跑守卫，「码过期了」会被误报成「订单状态不对」，让人查错方向。附带效果是：重复提交同一次操作，报的是「码已使用」（这才是真实原因），而不是看似无关的状态错误。

### ⑤ 指纹：码绑定「操作类型 + 目标 + 全部关键参数」

码在 KV 里存 `op | targetId | intentHash`，其中 `intentHash = sha256(规范化串)`，规范化串 = 操作名 + 目标 id + 该操作的关键参数（上表「指纹绑定」一列）：

- **方案 / 参数一变码即作废**：预演时 2 个包裹、执行时库存变了会变成 3 个 → 指纹对不上，直接拒（防「确认的是 A、执行成 C」）
- 规范化刻意做稳：Map 按键排序、数值归一（`2` / `2L` / `2.0` 同一个指纹）、明细行按 id 排序 —— 同一份意图从哪个客户端来都算出同一个指纹
- 刻意**不**纳入仓库名、商品名这类「改了不影响执行结果」的字段：改个名字不该让已确认的方案失效

### ⑥ 与 API Key / JWT 的分工

JWT 回答「**谁**登录着」（人），API Key（`X-Api-Key` + scopes）回答「**哪个机器**在调」（AI / 脚本），`confirmCode` 回答「**这一次**写操作你确认过了」。三者正交：前两个管身份与权限，最后一个管单次动作的意图，缺一不可。

## 并发控制：乐观锁 version（5 张热表）

**为什么**：`product` / `sku` / `order` / `delivery_order` / `member` 原来既没有 `version` 也没有 `update_time`，
`PUT /admin/products/{id}` 就是 `updateById(product)` —— 纯 last-write-wins。两个人同时编辑同一个商品，
**后保存的完整覆盖前保存的**，双方的修改都丢，而且谁都不知道。

**现在**：这 5 张表各加一列 `version INT NOT NULL DEFAULT 0`（迁移：`sql/migrate-optimistic-lock.sql`，幂等可重跑），
实体上是 MyBatis-Plus 的 `@Version`，`MybatisPlusConfig` 注册了 `OptimisticLockerInnerInterceptor`（**必须排在分页之前**），
所以 `updateById(entity)` 生成的是 `UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?`。

### 错误码约定

| 场景 | code | message |
|---|---|---|
| 成功 | `0` | `ok` |
| 缺少版本号 | `400` | `缺少版本号，请刷新页面后重试` |
| 版本冲突（期间被别人改过） | `409` | `数据已被他人修改（可能其他人在编辑），请刷新后重试` |

> `409` 是项目里**唯一**非 `0`/`1` 的业务 code（沿用 HTTP 语义）。前端看到 `409` 就弹「数据已被他人修改，
> 点这里刷新后重试」的那一个对话框，看到 `400` 说明自己没把 version 带回去（代码 bug，不是并发）。
> 两者都是 **HTTP 200 + 业务码**，与项目其它错误一致，不靠 HTTP 状态码分支。

### 接口契约（前端必须带 version）

| 接口 | 版本号从哪来 |
|---|---|
| `PUT /admin/products/{id}` | body `product.version` ← `GET /admin/products`、`GET /admin/products/{id}` 的 `product.version` |
| `PUT /admin/skus/{id}` | body `sku.version` ← `GET /admin/skus?productId=`（列表每行有 `version`）、`GET /admin/skus/{id}` |
| `PUT /admin/orders/{id}` | body `version` ← `GET /admin/orders`（`records[].version`）、`GET /admin/orders/{id}`（`data.order.version`） |
| `PUT /admin/deliveries/{id}` | body `version` ← `GET /admin/deliveries?orderId=`（每行有 `version`）；另一个字段 `confirmCode` 照旧 |
| `PUT /admin/members/{id}` | body `version` ← `GET /admin/members`、`GET /admin/members/{id}` |

**fail-closed**：`version` 为 null 一律拒（`400`），**绝不静默降级成「无版本校验」** —— 那等于白加，
而且外人看不出来（接口照样 code=0）。存量调用方（老前端 / 脚本 / MCP 写接口）会因此拿到 400，属预期。

### `PUT /admin/deliveries/{id}` 的校验顺序（乐观锁不抢跑）

`缺码 → 码无效/已用/过期 → 用途不符 → 目标不符 → 指纹变化 → **缺版本号(400) / 版本过期(409)** → 业务守卫(status=0)`

即：确认码是「这次请求的票」，码不对先说码；版本冲突归入「期间数据被改动」那一档。
确认码指纹只看「这次要改成什么」（仓库 + 明细），有人改了 `source`/`status`/别的字段时指纹未必变，靠 `version` 才能发现 ——
两者是**两层**闸门，不是替代关系。

## 订单状态机

`order.status`：`0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款`（见 `docs/flows/14-订单状态图.mmd`）

| 流转 | 接口 | 守卫 | 联动 |
|---|---|---|---|
| 待付款→待审核 | `POST /portal/orders/{id}/pay` | status=0 | 写支付流水（type=1）；同一次调用内 `0→1已付款→7待审核`，**1 是瞬时态、接口响应中不可观测** |
| 待审核→待发货 | `POST /admin/orders/{id}/audit-pass` **（需 confirmCode）** | status=7 | 自动拆单生成发货单 |
| 待审核→已关闭 | `POST /admin/orders/{id}/audit-reject` **（需 confirmCode）** | status=7 | 回补库存 |
| 待发货→已发货 | （发货单驱动的聚合流转：`dispatch` / `wms-callback` **均需 confirmCode**） | 全部子单≥已出库 | 部分出库时订单=9部分发货 |
| 已发货→已完成 | `POST /portal/orders/{id}/confirm` | status=2 且本人订单 | — |
| 已付款/已发货→退款中 | `POST /portal/returns` | status∈{1,2} 且无待处理申请 | 订单置 5 |
| 退款中→已退款 | `PUT /admin/return-applies/{id}`（通过）**（需 confirmCode）** | 仅 return_apply 待处理 | 退款流水（type=2）+ 库存回补 |
| 退款中→已付款 | 同上（拒绝）**（需 confirmCode）** | 仅 return_apply 待处理 | 订单回到 1 |

- 表中 4 处写操作属 L3 高危，**必须先 `?dryRun=true` 预演拿码、执行时带 `confirmCode`**；裸 POST 会被拒（详见上一节「确认码（confirmCode）」）。

- 通用编辑接口 `PUT /admin/orders/{id}` **会剥离 status**，状态只能经上表的专用接口流转。
- 下单即扣库存，采用 SQL 原子扣减 `UPDATE sku SET stock = stock - ? WHERE id = ? AND stock >= ?`，影响行数 0 视为库存不足（超卖压测见 `benchmark/oversell/README.md`）。
- 支付流水表 `payment_record` 记录每笔支付/退款（`order_sn` 为对账键，索引无外键）。

**演示话术**：支付为**模拟实现**——`POST /portal/orders/{id}/pay` 直接把订单置为已付款并写一条 `channel=0/1/2`、`trade_no=MOCKPAY...` 的流水，不接真实支付网关；退款同理写 `MOCKREF...` 流水。

## 列表分页

- 分页插件：`MybatisPlusConfig` 注册 `PaginationInnerInterceptor(DbType.MYSQL)`，`maxLimit=100`
  —— **不注册插件，`Page` 查询会静默变成全表返回**，这是分页改造的前提。
- C 端我的订单：`GET /portal/orders?page=1&size=10` → `{records,total,current,size,pages}`；`size` 服务端夹在 1~50，按主键倒序（走主键索引）。
- B 端订单列表：同样分页，并支持 `orderSn`（模糊）、`status` 筛选；后台页面带搜索框 + 分页条。
- 前端：`el-pagination` 绑 `page/size/total`，`@current-change` 重新拉取。
- 深分页（`page` 很大时的 `LIMIT offset`）本次未做优化，属后续可选项。
- C 端排序键：`ORDER BY payment_time DESC, id DESC`，配合复合索引 `idx_member_time(member_id, payment_time)`，
  `EXPLAIN` 显示 `key=idx_member_time`、`Extra: Backward index scan`（**无 filesort**）。
  实测 50 万行下接口 30~35ms。

## 图片上传（MinIO）

商品主图、商品相册、SKU 图片支持直接上传（也保留手填 URL）：

- 存储：MinIO，bucket `qiqi-mall`，启动时自动建桶并设置「公开读」，浏览器 `<img src>` 直连拉图，后端不经手图片流量
- 接口：`POST /admin/upload/image`（multipart，字段名 `file`），仅 `jpg/png/webp`、≤5MB，超出返回 413/415
- 命名：`products/yyyy/MM/<uuid>.<ext>`，返回 `{url, objectName}`
- 配置：`app.minio.*`（dev 默认 `http://127.0.0.1:9000`）；部署用 `MINIO_PUBLIC_URL` 指定浏览器可达地址
- 控制台：http://localhost:9001（账号见 `.env.example` 的 `MINIO_ACCESS_KEY/MINIO_SECRET_KEY`）
