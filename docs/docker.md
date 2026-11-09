# Docker 部署

容器只用于**部署**，日常开发直接跑源码（见 README「本地开发」）。

## 端口

| 环境 | 前端 | 后端 | 是否映射宿主机 |
|------|------|------|---------------|
| 本地开发（源码） | 5173 | 8081 | 都是本机进程 |
| 容器部署 | 5174 | 8082 | 仅前端，后端只 `expose` |

两套可以同时跑，端口不冲突。

## 常用命令

```bash
docker compose up -d --build     # 构建并启动
docker compose up -d --build web # 只重建前端
docker compose ps                # 状态
docker compose logs -f server    # 后端日志
docker compose down              # 停止并移除
```

## 为什么只开放前端端口

浏览器访问链路：

```
浏览器 → :5174 (nginx) → /api/ → http://server:8082（compose 内网）
```

后端不需要宿主机端口。好处：

1. 减少暴露面：后端不经 nginx 无法访问，限流/鉴权/访问日志不会被绕过
2. 无端口冲突：开发占 8081，部署后端在容器内 8082，互不影响
3. 单一入口：外部只记一个地址

临时需要直连后端（Postman、Swagger、Prometheus 抓取）时，解开 compose 里的注释：

```yaml
    # ports:
    #   - "127.0.0.1:8082:8082"   # 绑 127.0.0.1，只允许宿主机本地
```

## 构建结构

两个镜像都采用多阶段构建，运行镜像只留产物：

| 镜像 | 构建阶段 | 运行阶段 | 大小 |
|------|---------|---------|------|
| qiqi-mall-web | node:22-alpine + vite build | nginx:alpine | 66 MB |
| qiqi-mall-server | maven + temurin-21 | temurin-21-jre-alpine | 248 MB |

## 环境变量传递

```
docker-compose.yml
  ├── server: SPRING_PROFILE=prod          → java -jar app.jar --spring.profiles.active=prod（运行期）
  └── web:    BUILD_MODE=production        → vite build --mode production（编译期，改环境需重建镜像）
              BACKEND_UPSTREAM=http://server:8082 → nginx 模板 envsubst 渲染（运行期）
```

前端环境变量是编译期注入（Vite 特性），后端 profile 运行期生效。

## 容器内部通信

- 同属 bridge 网络 `qiqi-net`，用服务名互访
- 前端 nginx 反代 `/api/` 到后端，无跨域
- 后端非 root（`app` 用户）运行

## 健康检查

- 后端：`wget http://127.0.0.1:8082/api/health`，20s 间隔，启动宽限 30s
- 前端：`wget http://127.0.0.1/`
- 前端 `depends_on: service_healthy`，保证启动顺序

## 后续扩展 MySQL

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
