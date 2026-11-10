# Docker 部署

容器只用于**部署**，日常开发直接跑源码（见 README「本地开发」）。

## 端口

| 环境 | 前端 | 后端 | 是否映射宿主机 |
|------|------|------|---------------|
| 本地开发（源码） | 5173 | 8081 | 都是本机进程 |
| 容器部署 | C端 5174 / B端 5176 | 8082 | 仅前端，后端只 `expose` |

两套可以同时跑，端口不冲突。

## 常用命令

```bash
docker compose up -d --build     # 构建并启动
docker compose up -d --build portal-web admin-web   # 只重建前端
docker compose ps                # 状态
docker compose logs -f server    # 后端日志
docker compose down              # 停止并移除
```

## 为什么只开放前端端口

浏览器访问链路：

```
浏览器 → :5174 portal / :5176 admin（nginx）→ /portal/ 或 /admin/ → http://server:8082（compose 内网）
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
| qiqi-portal-web | node:22-alpine + vite build | nginx:alpine | 66 MB |
| qiqi-admin-web | node:22-alpine + vite build | nginx:alpine | 66 MB |
| qiqi-mall-server | maven + temurin-21 | temurin-21-jre-alpine | 248 MB |

## 环境变量传递

```
docker-compose.yml
  ├── server: SPRING_PROFILE=prod          → java -jar app.jar --spring.profiles.active=prod（运行期）
  ├── portal-web: BUILD_MODE=production    → vite build --mode production
  └── admin-web:  BUILD_MODE=production    → vite build --mode production
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

## MySQL

容器化 MySQL 8（compose 里的 `mysql` 服务），不再使用宿主机 MariaDB。

| 项目 | 值 |
|------|-----|
| 镜像 | mysql:8（实际 8.4.13） |
| 宿主机端口 | `127.0.0.1:3306`（仅本机可达） |
| 容器内地址 | `mysql:3306` |
| 数据卷 | `mysql-data` |
| 初始化 | `sql/schema.sql` 挂到 `/docker-entrypoint-initdb.d/`，**数据卷为空时首次启动自动执行** |
| root 密码 | 环境变量 `MYSQL_ROOT_PASSWORD`，默认 `qiqi123456`（见 `.env.example`） |

本地开发的后端连接串：`jdbc:mysql://127.0.0.1:3306/qiqi_mall`
容器内后端连接串：`jdbc:mysql://mysql:3306/qiqi_mall`

### 重新初始化表结构

建表脚本只在**数据卷为空**时执行。改了 `sql/schema.sql` 想重来：

```bash
docker compose down mysql
docker volume rm qiqi-mall_mysql-data
docker compose up -d mysql
```

⚠️ 会清空所有数据。

### 连接

```bash
mysql -h127.0.0.1 -uroot -pqiqi123456 qiqi_mall
```

## 镜像拉取（国内网络）

Docker Hub 直连经常超时，按「先探测、再用可用源」的流程处理：

```bash
# 1) 探测各镜像源是否可用（200 才算通）
for m in docker.1panel.live docker.1ms.run docker.m.daocloud.io dockerproxy.com hub-mirror.c.163.com; do
  echo -n "$m "; curl -s -o /dev/null -m 8 -w "%{http_code}\n" \
    -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
    "https://$m/v2/library/nginx/manifests/alpine"
done

# 2) 用可用源拉取，再打回本地标准名（Dockerfile 里仍是官方名）
docker pull docker.1panel.live/library/nginx:alpine
docker tag  docker.1panel.live/library/nginx:alpine nginx:alpine
```

需要的基础镜像：`nginx:alpine`、`node:22-alpine`、`maven:3.9-eclipse-temurin-21`、`eclipse-temurin:21-jre-alpine`、`mysql:8`。

实测（2026-11-10）：`docker.1panel.live` 可用；`docker.1ms.run` 返回 401/503；其余多数不通。
备选方案：若镜像源均不可用，可走阿里云主机的 HTTP 代理（`HTTP_PROXY`/`HTTPS_PROXY` 配到 Docker daemon 后重启 docker）。
