# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /app

# 先装依赖，利用 layer 缓存
COPY package.json package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com

COPY . .
# 构建模式由 compose 传入：development / production
ARG BUILD_MODE=production
RUN npx vite build --mode ${BUILD_MODE}

# ---------- 运行阶段 ----------
FROM nginx:alpine
RUN rm -f /etc/nginx/conf.d/default.conf
# 使用官方模板机制，容器启动时用 envsubst 渲染 ${BACKEND_UPSTREAM}
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# 保证静态文件对 nginx worker 可读
RUN chmod -R a+rX /usr/share/nginx/html
ENV BACKEND_UPSTREAM=http://server:8081
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
