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

示例账号（密码统一 `123456`，BCrypt 存储）：后台 `admin`，买家 `qiqi` / `xiaoming`（`laowang` 为禁用状态）。

两个脚本都挂到 `/docker-entrypoint-initdb.d/`，数据卷为空时按序自动执行。

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

订单列表行操作：明细 / **发货**（仅「已付款」显示，带确认框）/ 编辑。退货申请行「审核」可选通过或拒绝。

## 订单状态机

`order.status`：`0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款`（见 `docs/flows/14-订单状态图.mmd`）

| 流转 | 接口 | 守卫 | 联动 |
|---|---|---|---|
| 待付款→待审核 | `POST /portal/orders/{id}/pay` | status=0 | 写支付流水（type=1）；同一次调用内 `0→1已付款→7待审核`，**1 是瞬时态、接口响应中不可观测** |
| 待审核→待发货 | `POST /admin/orders/{id}/audit-pass` | status=7 | 自动拆单生成发货单 |
| 待审核→已关闭 | `POST /admin/orders/{id}/audit-reject` | status=7 | 回补库存 |
| 待发货→已发货 | （发货单驱动的聚合流转） | 全部子单≥已出库 | 部分出库时订单=9部分发货 |
| 已发货→已完成 | `POST /portal/orders/{id}/confirm` | status=2 且本人订单 | — |
| 已付款/已发货→退款中 | `POST /portal/returns` | status∈{1,2} 且无待处理申请 | 订单置 5 |
| 退款中→已退款 | `PUT /admin/return-applies/{id}`（通过） | 仅 return_apply 待处理 | 退款流水（type=2）+ 库存回补 |
| 退款中→已付款 | 同上（拒绝） | 仅 return_apply 待处理 | 订单回到 1 |

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
