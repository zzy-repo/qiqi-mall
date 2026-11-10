# 多实例改造规划（滚动发布实验的前置方案）

> 目标：让同一份代码能起 N 个实例协同服务，为滚动发布实验铺路。
> 核心认知：**代码已经 90% 就绪**——MySQL 是共享的、JWT 是无状态的、业务无状态——
> 只有两处"进程私有内存"需要外移，外加一个流量入口。

## 现状盘点：多实例只差三件事

| 类别 | 现状 | 多实例下的问题 | 改造 |
|---|---|---|---|
| 数据 | MySQL 共享 ✅ | 无 | 不动 |
| 会话 | JWT 无状态 ✅ | 无（token 自带身份，任何实例可验） | 不动——这是当初选 JWT 的红利 |
| 验证码 | CaptchaStore 进程内存 | 实例1发的码，实例2验不过 | 外移 Redis |
| 幂等令牌 | IdempotentTokenStore 进程内存 | 跨实例防线失效 | 外移 Redis |
| 流量入口 | 无（直连单实例） | 多实例必须有统一入口分发 | nginx upstream |

## 改造步骤（四步，每步可独立验证）

### 第 1 步：Redis 容器（基础设施）

```yaml
# docker-compose.yml 追加
redis:
  image: redis:7-alpine
  ports: ["127.0.0.1:6379:6379"]
  volumes: [redis-data:/data]
```

### 第 2 步：Store 抽象（代码改造核心）

**关键设计：接口不变，实现分层**——当初 CaptchaStore/IdempotentTokenStore 独立成类就是为了这一天。

```java
// 统一抽象:两个Store的共同能力 = "带TTL的一次性KV"
public interface KvStore {
    void put(String key, Duration ttl);
    boolean consume(String key);      // 原子取出即删(验证码/令牌共用语义)
    long counter(String key, Duration window);  // 失败计数(验证码用)
}
// 实现1: MemoryKvStore(现有ConcurrentHashMap逻辑平移,保留单实例档)
// 实现2: RedisKvStore(SET EX / GETDEL / INCR+EXPIRE 一一对应)
// 选择: 配置项 app.kv-store=memory|redis,默认memory
```

迁移对应关系：
| 现有方法 | Redis 命令 | 原子性来源 |
|---|---|---|
| put + TTL | `SET key val EX 300` | Redis 单线程 |
| consume(取出即删) | `GETDEL key`(Redis 6.2+) | 同上 |
| 失败计数+窗口 | `INCR key` + `EXPIRE key 300`(首次) | 同上 |
| 定时清扫 | **不需要**——Redis 自带过期回收 | 免维护 |

### 第 3 步：多实例启动与流量分发

```yaml
# compose: server 抽成可复制端口
# 实例1: 8081, 实例2: 8083, 环境变量注入 app.kv-store=redis
nginx:
  # upstream 轮询两个实例
  upstream qiqi_backend { server 127.0.0.1:8081; server 127.0.0.1:8083; }
  location /api/ { proxy_pass http://qiqi_backend; }
```

改造点：`server.port` 从写死改为 `${SERVER_PORT:8081}`，compose 用同一镜像起两个服务（或 `deploy.replicas`）。

### 第 4 步：验证（滚动发布实验）

照 `benchmark/3-滚动发布实验/README.md` 执行：
- 轮询流量下验证码/幂等全通过（Redis 共享）
- kill 实例1 → 实例2 独立服务，发布窗口无失败

## 明确不做（本期）

- 服务注册发现（Nacos/Eureka）——两台固定实例，upstream 写死即可，别为演示引注册中心
- 分布式 session —— JWT 无状态，本来就不需要
- Redis 集群/哨兵 —— 单机 Redis 挂了顶多退回内存档，单点可接受

## 遗留联动

- 自动关单定时任务：多实例下会重复执行 → 加 Redis SETNX 分布式锁（等 order.create_time 一起做）
EOF
mv /tmp/multi-instance-plan.md /root/qiqi-mall/docs/multi-instance-plan.md 2>/dev/null; cp /dev/null /dev/null; cat > /root/qiqi-mall/docs/multi-instance-plan.md <<'EOF'
# 多实例改造规划（滚动发布实验的前置方案）

> 目标：让同一份代码能起 N 个实例协同服务，为滚动发布实验铺路。
> 核心认知：**代码已经 90% 就绪**——MySQL 是共享的、JWT 是无状态的、业务无状态——
> 只有两处"进程私有内存"需要外移，外加一个流量入口。

## 现状盘点：多实例只差三件事

| 类别 | 现状 | 多实例下的问题 | 改造 |
|---|---|---|---|
| 数据 | MySQL 共享 ✅ | 无 | 不动 |
| 会话 | JWT 无状态 ✅ | 无（token 自带身份，任何实例可验） | 不动——这是当初选 JWT 的红利 |
| 验证码 | CaptchaStore 进程内存 | 实例1发的码，实例2验不过 | 外移 Redis |
| 幂等令牌 | IdempotentTokenStore 进程内存 | 跨实例防线失效 | 外移 Redis |
| 流量入口 | 无（直连单实例） | 多实例必须有统一入口分发 | nginx upstream |

## 改造步骤（四步，每步可独立验证）

### 第 1 步：Redis 容器（基础设施）

```yaml
# docker-compose.yml 追加
redis:
  image: redis:7-alpine
  ports: ["127.0.0.1:6379:6379"]
  volumes: [redis-data:/data]
```

### 第 2 步：Store 抽象（代码改造核心）

**关键设计：接口不变，实现分层**——当初 CaptchaStore/IdempotentTokenStore 独立成类就是为了这一天。

```java
// 统一抽象:两个Store的共同能力 = "带TTL的一次性KV"
public interface KvStore {
    void put(String key, Duration ttl);
    boolean consume(String key);      // 原子取出即删(验证码/令牌共用语义)
    long counter(String key, Duration window);  // 失败计数(验证码用)
}
// 实现1: MemoryKvStore(现有ConcurrentHashMap逻辑平移,保留单实例档)
// 实现2: RedisKvStore(SET EX / GETDEL / INCR+EXPIRE 一一对应)
// 选择: 配置项 app.kv-store=memory|redis,默认memory
```

迁移对应关系：
| 现有方法 | Redis 命令 | 原子性来源 |
|---|---|---|
| put + TTL | `SET key val EX 300` | Redis 单线程 |
| consume(取出即删) | `GETDEL key`(Redis 6.2+) | 同上 |
| 失败计数+窗口 | `INCR key` + `EXPIRE key 300`(首次) | 同上 |
| 定时清扫 | **不需要**——Redis 自带过期回收 | 免维护 |

### 第 3 步：多实例启动与流量分发

```yaml
# server.port 改为 ${SERVER_PORT:8081},compose 用同一镜像起两个实例
nginx upstream 轮询 8081/8083
```

### 第 4 步：验证（滚动发布实验）

照 `benchmark/3-滚动发布实验/README.md` 执行：
- 轮询流量下验证码/幂等全通过（Redis 共享）
- kill 实例1 → 实例2 独立服务，发布窗口无失败

## 明确不做（本期）

- 服务注册发现（Nacos/Eureka）——固定实例 upstream 写死即可
- 分布式 session —— JWT 无状态，本来就不需要
- Redis 集群/哨兵 —— 单机 Redis 单点可接受（挂了退回内存档）

## 遗留联动

- 自动关单定时任务：多实例下会重复执行 → Redis SETNX 分布式锁（等 order.create_time 一起做）
EOF
echo done