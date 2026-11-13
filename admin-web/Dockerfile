# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /app

# 先装依赖，利用 layer 缓存
COPY package.json package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com

COPY . .
# 构建模式由 compose 传入：development / test（test = 测试环境，加载 .env.test）
ARG BUILD_MODE=test
# 静态资源基址（前端 JS/CSS 上 CDN 的开关，见 scripts/upload-web-assets.sh）。
# 空 = 现状：index.html 引用 /assets/**，由容器自己提供（本地/e2e/回滚零影响）。
# 非空例：https://cdn.rose7.cn/qiqi-mall/portal-web/ → index.html 里换成 CDN 绝对 URL。
# 只影响构建产物里的引用形式；dist 仍然整目录打进镜像（/assets 留作零成本备份）。
ARG CDN_BASE=""
ENV CDN_BASE=${CDN_BASE}
RUN npx vite build --mode ${BUILD_MODE}

# ---------- 运行阶段 ----------
FROM nginx:alpine
RUN rm -f /etc/nginx/conf.d/default.conf
# 使用官方模板机制，容器启动时用 envsubst 渲染 ${BACKEND_UPSTREAM}
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# 保证静态文件对 nginx worker 可读
RUN chmod -R a+rX /usr/share/nginx/html
# 商品图 /qiqi-mall/** 的重定向目标（test 容器栈 = 阿里云 CDN，源站是 OSS bucket）。
# compose 会用 IMG_CDN_BASE 覆盖；这里给默认值只是保证模板 envsubst 永远有值可用。
ENV BACKEND_UPSTREAM=http://server:8081 \
    IMG_CDN_BASE=https://cdn.rose7.cn
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
