# qiqi-mall 七七商城

前后端分离商城项目：Vue 3 + Spring Boot

## 目录结构

```
qiqi-mall/
├── qiqi-mall-web/          # 前端 (Vue 3 + Vite + Element Plus)
│   ├── public/
│   └── src/
│       ├── api/            # 接口请求封装
│       ├── assets/         # 静态资源/样式
│       ├── components/     # 公共组件
│       ├── router/         # 路由
│       ├── store/          # 状态管理 (Pinia)
│       └── views/          # 页面
│           ├── home/       # 首页
│           ├── product/    # 商品列表/详情
│           ├── cart/       # 购物车
│           ├── order/      # 订单
│           ├── user/       # 登录/注册/个人中心
│           └── admin/      # 后台管理
├── qiqi-mall-server/       # 后端 (Spring Boot + MyBatis-Plus)
│   └── src/main/java/com/qiqi/mall/
│       ├── controller/     # 接口层
│       ├── service/        # 业务层 (impl 为实现)
│       ├── mapper/         # 数据访问层
│       ├── entity/         # 数据库实体
│       ├── dto/            # 传输对象
│       ├── config/         # 配置类
│       ├── common/         # 通用返回/异常
│       └── utils/          # 工具类
├── sql/                    # 数据库脚本
└── docs/                   # 文档
```

## 运行端口

| 服务 | 开发 | 生产 |
|------|------|------|
| 前端 Vite | 5173 | 5174 |
| 后端 Spring Boot | 8081 | 8082 |

启动命令：
```bash
# 后端
cd qiqi-mall-server
mvn spring-boot:run -Dspring-boot.run.profiles=dev    # 开发 8081
mvn spring-boot:run -Dspring-boot.run.profiles=prod   # 生产 8082

# 前端
cd qiqi-mall-web
npm run dev        # 开发 5173 → 代理 8081
npm run dev:prod   # 生产预览 5174 → 代理 8082
```

前后端均监听 0.0.0.0，Tailscale 组网内可通过 `http://100.113.135.55:<端口>` 访问。

## 前端页面

引入 Element Plus（2.x，中文语言包 + 全量图标），采用三段式布局：

- **左侧导航栏**：可折叠（64px / 220px），多级菜单（控制台、商品、订单、用户、购物车）
- **右侧顶部**：折叠按钮 + 面包屑导航（自动跟随路由 meta.title）+ 环境标签 + 用户下拉
- **主内容区**：路由视图，带淡入过渡

| 路由 | 页面 |
|------|------|
| /dashboard | 数据概览（统计卡片、柱状图、系统状态） |
| /product/list | 商品列表（搜索、表格、分页） |
| /product/category | 商品分类（树 + 列表） |
| /order/list | 订单列表（Tab + 表格） |
| /user/list | 用户列表 |
| /cart | 购物车（数量调整 + 结算汇总） |

界面截图见 `docs/screenshots/`。
