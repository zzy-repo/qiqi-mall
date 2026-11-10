# 多实例改造实施方案（KvStore抽象 + Redis + nginx upstream）

> 依据：docs/multi-instance-plan.md（方案）+ benchmark/3-滚动发布实验/README.md（验证）
> 工作量：1~2天 ｜ 原则：对齐文档口径，接口不变换实现，配置开关默认memory

## Commit 1：Redis 容器 + 依赖 + 配置

**1a. docker-compose.yml 追加：**
```yaml
redis:
  image: redis:7-alpine
  ports: ["127.0.0.1:6379:6379"]
  volumes: [redis-data:/data]
```

**1b. qiqi-mall-server/pom.xml 追加：**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**1c. application-dev.yml / application-prod.yml 追加：**
```yaml
app:
  kv-store: ${KV_STORE:memory}      # memory | redis,默认memory(单实例档)
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: 6379
```
prod 的 REDIS_HOST 默认值应为 `redis`（compose 内部网络名）。

## Commit 2：KvStore 抽象（核心改造）

**2a. 新增 `security/KvStore.java` 接口**（CaptchaStore 与 IdempotentTokenStore 的共同能力）：
```java
public interface KvStore {
    void put(String key, Duration ttl);          // 一次性凭证写入
    boolean consume(String key);                 // 原子取出即删(返回false=不存在/已消费)
    long increment(String key, Duration window); // 失败计数:首次INCR时EXPIRE,返回当前计数
    void reset(String key);                      // 登录成功清零
}
```

**2b. 新增 `security/RedisKvStore.java`**（@ConditionalOnProperty(name="app.kv-store", havingValue="redis")）：
| 方法 | Redis 命令 |
|---|---|
| put | `SET key val EX ttl秒` |
| consume | `GETDEL key`(Redis 6.2+,返回null即false) |
| increment | `INCR key` + 首次(INCR返回1时)`EXPIRE key window秒` |
| reset | `DEL key` |
实现基于 StringRedisTemplate。**注意：Redis 自带过期，原内存版的定时清扫任务在 redis 档下跳过。**

**2c. 重命名改造现有两个 Store：**
- `CaptchaStore` → 实现 KvStore 接口，保留现有 ConcurrentHashMap 逻辑作为 `MemoryKvStore`（@ConditionalOnProperty havingValue="memory", matchIfMissing=true）
- `IdempotentTokenStore` 同理接入（issue/consume 方法内部委托给注入的 KvStore）
- 对外方法签名不变——**PortalOrderController/AdminAuthController 等调用方零改动**

## Commit 3：多实例启动 + nginx upstream

**3a. application-dev/prod.yml：** `server.port: ${SERVER_PORT:8081}`

**3b. docker-compose.yml：**
```yaml
server-1: # 原server改名,环境变量 SERVER_PORT=8082, KV_STORE=redis, REDIS_HOST=redis
server-2: # 同镜像新增,SERVER_PORT=8083, 其余同上
nginx:
  image: nginx:alpine
  ports: ["127.0.0.1:8080:80"]
  volumes: [./nginx/qiqi.conf:/etc/nginx/conf.d/default.conf:ro]
```

**3c. 新增 nginx/qiqi.conf：**
```nginx
upstream qiqi_backend {
    server qiqi-server-1:8082;
    server qiqi-server-2:8083;
}
server {
    location /api/ { proxy_pass http://qiqi_backend; }
}
```
（两个前端 web 容器的 /api 代理目标同步改为该 nginx，或前端直连 upstream 的 nginx 端口——与现有 web 容器代理配置保持一致即可）

## Commit 4：滚动发布实验（benchmark/3 执行 + 记录）

按 `benchmark/3-滚动发布实验/README.md` 执行并记录：

1. **修复前对照（KV_STORE=memory + 双实例）**：
   - 实例1 `GET /admin/auth/captcha` 生成 → 轮询路由下登录请求打到实例2 → 预期 401 验证码错误（约50%请求）
   - 同一 idempotentToken 两次下单分别路由两实例 → 预期**两个订单都成功**（防线失效,记录重复下单数）
2. **修复后复跑（KV_STORE=redis + 双实例）**：
   - 验证码/幂等全部跨实例生效,重复订单=0
   - kill server-1 → server-2 独立服务,发布窗口无失败
3. 结果(修复前/后数据)写入 `benchmark/3-滚动发布实验/README.md`,状态改为"已实验闭环"

## 明确不做（防止范围扩散）

- 注册中心（Nacos/Eureka）——upstream 写死两个实例即可
- 分布式 session——JWT 无状态
- Redis 集群/哨兵——单机档
- 业务代码任何改动——调用方零感知,只动 Store 内部
- 死锁重试逻辑——已实装(OrderCreateService),不动

## 交付标准

- 4 个 commit 按序推送,每个 commit 自含可编译通过
- 最终态:KV_STORE=redis 双实例下 e2e 全绿(portal-web/admin-web 都经 nginx 转发)
- benchmark/3 的实验数据表补全(修复前/后两组)
- 遇到与本文档冲突的实现细节(如 CaptchaStore 还有窗口清扫等私有方法),停下来说明,不要自行设计
