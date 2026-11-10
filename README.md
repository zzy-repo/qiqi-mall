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
├── docker-compose.yml      # 部署编排（生产）
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

同一用户名连续失败 5 次后要求输入图形验证码（见 `docs/captcha-flow.mmd`）：

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
| `/orders` | 我的订单（详情 / 模拟支付 / 申请退货） | 需登录 |
| `/addresses` | 收货地址管理 | 需登录 |

支付为模拟：`POST /portal/orders/{id}/pay` 把订单从「待付款」置为「已付款」并记录支付时间。

## B 端页面

侧边栏：用户（会员列表 / 管理员）、订单（订单列表 / 退货申请 / 订单设置）、商品（商品列表 / 商品分类 / 商品类型）。
收货地址与购物车聚合在会员行操作里，订单明细在订单详情抽屉，属性在商品类型、SKU 在商品列表 —— 均不占独立菜单。
