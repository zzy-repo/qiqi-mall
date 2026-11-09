# qiqi-mall 七七商城

前后端分离商城项目：Vue 3 + Spring Boot

## 目录结构

```
qiqi-mall/
├── qiqi-mall-web/          # 前端 (Vue 3 + Vite + Element Plus)
│   ├── public/
│   ├── nginx/              # 容器内 nginx 配置模板
│   └── src/
│       ├── api/            # 接口封装
│       ├── assets/         # 静态资源/样式
│       ├── components/     # 公共组件
│       ├── layout/         # 三段式布局
│       ├── router/         # 路由
│       ├── store/          # 状态管理 (Pinia)
│       └── views/          # 页面
├── qiqi-mall-server/       # 后端 (Spring Boot)
│   └── src/main/java/com/qiqi/mall/
│       ├── controller/     # 接口层
│       ├── service/        # 业务层 (impl 为实现)
│       ├── mapper/         # 数据访问层
│       ├── entity/         # 数据库实体
│       ├── dto/            # 传输对象
│       ├── config/         # 配置类
│       ├── common/         # 通用返回/异常
│       └── utils/          # 工具类
├── docker-compose.yml      # 部署编排（生产）
├── sql/                    # 数据库脚本
└── docs/                   # 文档
```

## 数据库

结构见 `sql/schema.sql`（依据 ER 图）：`category` 商品分类（自关联）、`product_type` 商品类型、
`attribute` 属性、`product` 商品SPU、`sku` SKU。

MySQL 8 跑在容器里（compose 的 `mysql` 服务），首次启动自动执行建表脚本：

```bash
docker compose up -d mysql        # 本机 127.0.0.1:3306，root 密码见 .env.example
mysql -h127.0.0.1 -uroot -pqiqi123456 qiqi_mall
```

## 本地开发（日常迭代）

不走容器，直接跑源码，支持热更新。

```bash
# 终端 1：后端 8081
cd qiqi-mall-server
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 终端 2：前端 5173
cd qiqi-mall-web
npm install          # 首次
npm run dev
```

访问 http://localhost:5173

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
docker compose logs -f web        # 日志
docker compose down               # 停止
```

访问 http://localhost:5174

| 环境 | 前端 | 后端 | 说明 |
|------|------|------|------|
| 本地开发 | 5173 | 8081 | 直接跑源码，热更新 |
| 容器部署 | 5174 | 8082 | 后端仅容器内网，不映射宿主机 |

两套可同时运行，端口互不冲突。Tailscale 组网内可用 `http://100.113.135.55:<端口>` 访问。

## 前端布局

引入 Element Plus（中文语言包 + 全量图标），三段式布局：

- **左侧导航栏**：深色侧边栏，支持折叠（220px / 64px）
- **右侧顶部**：折叠按钮 + 面包屑（跟随路由 `meta.title` 自动生成）+ 环境标签 + 用户下拉
- **主内容区**：`router-view`，带淡入过渡，当前为空白占位

当前仅保留 `/dashboard` 一个页面（空白占位），业务页面后续按需添加。

界面截图见 `docs/screenshots/shot-dashboard.png`。
