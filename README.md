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
