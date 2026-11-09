# Docker 部署

方案 A：**一次只跑一套环境**。开发与生产各用一份 compose 文件，端口互不冲突。

## 端口对照

| 服务 | 开发 | 生产 | 容器内端口 | 是否映射宿主机 |
|------|------|------|-----------|---------------|
| 前端（nginx） | 5173 | 5174 | 80 | ✅ 是（唯一入口） |
| 后端（Spring Boot） | — | — | 8081 / 8082 | ❌ 否，仅 compose 内网 |

> 后端不映射宿主机端口，只声明 `expose`。外部一律通过前端 nginx 的 `/api/` 入口访问，
> 避免绕过 nginx 直打接口。

## 常用命令

```bash
# 开发环境（启动）
docker compose up -d --build

# 开发环境（停止）
docker compose down

# 生产环境（启动）
docker compose -f docker-compose.prod.yml up -d --build

# 生产环境（停止）
docker compose -f docker-compose.prod.yml down

# 查看状态 / 日志
docker compose ps
docker compose logs -f web
docker compose logs -f server

# 只重建某个服务
docker compose up -d --build web
```

切换环境前先 `down` 掉另一套，避免端口冲突（两套的容器名和网络名都不同，但宿主机端口会撞）。

## 构建结构

两个镜像都采用**多阶段构建**，运行镜像只保留产物：

| 镜像 | 构建阶段 | 运行阶段 | 大小 |
|------|---------|---------|------|
| qiqi-mall-web | node:22-alpine + vite build | nginx:alpine | 66 MB |
| qiqi-mall-server | maven + eclipse-temurin-21 | eclipse-temurin-21-jre-alpine | 248 MB |

## 环境变量的传递路径

```
docker-compose.yml
  ├── server: SPRING_PROFILE=dev|prod  →  java -jar app.jar --spring.profiles.active=xxx
  └── web:    BUILD_MODE=development|production  →  vite build --mode xxx   （编译期写入）
              BACKEND_UPSTREAM=http://server:808x → nginx 模板 envsubst 渲染（运行期生效）
```

前端的环境变量是**编译期**注入（Vite 特性），所以改环境必须重新 build 镜像；
后端的 profile 是**运行期**生效，只改环境变量重启容器即可。

## 容器内部通信

- 两个服务在同一个 bridge 网络 `qiqi-net` 内，用服务名互访（`http://server:8081`）
- 前端 nginx 把 `/api/` 反代到后端，浏览器只需访问前端端口，无跨域问题
- 后端容器**只声明 `expose`**，不映射宿主机端口：宿主机和 Tailscale 网内都无法直连后端
- 前端是唯一对外入口，`/api/` 由 nginx 反代到 `http://server:808x`

### 为什么只开放前端端口

1. 减少暴露面：后端不经 nginx 就无法被访问，限流/鉴权/访问日志不会被绕过
2. 无端口冲突：方案 A 切换环境时只涉及 5173 / 5174 两个端口
3. 单一入口：外部只需要记住一个地址

**临时需要直连后端**（Postman 联调、Swagger、Prometheus 抓取）时，在 compose 里解开注释：

```yaml
    # ports:
    #   - "127.0.0.1:8081:8081"   # 绑 127.0.0.1，只允许宿主机本地
```

或者统一走 nginx 增加反代路径（如 `/docs/`），保持单一入口。

## 健康检查

- 后端：`wget http://127.0.0.1:808x/api/health`，每 20s 一次，启动宽限 30s
- 前端：`wget http://127.0.0.1/`
- 前端依赖后端 `condition: service_healthy`，保证启动顺序

## 非 root 运行

后端运行阶段创建了 `app` 用户并以该用户启动，容器内不使用 root。

## 与 Tailscale 的关系

容器端口映射到宿主机后，Tailscale 组网内可直接访问：

- 开发：`http://100.113.135.55:5173`
- 生产：`http://100.113.135.55:5174`

无需额外配置，因为是宿主机端口映射（`0.0.0.0` 监听）。

## 后续扩展

引入 MySQL 时，在 compose 里追加：

```yaml
  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: qiqi_mall
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]

volumes:
  mysql-data:
```

后端通过 `jdbc:mysql://mysql:3306/qiqi_mall` 连接。
