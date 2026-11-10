package com.qiqi.mall.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 多实例档实现：Redis 单线程保证原子性，且自带过期回收（无需清扫任务）。
 * app.kv-store=redis 时生效。
 */
@Component
@ConditionalOnProperty(name = "app.kv-store", havingValue = "redis")
public class RedisKvStore implements KvStore {

    private final StringRedisTemplate redis;

    public RedisKvStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    /** GETDEL：原子取出即删（Redis 6.2+） */
    @Override
    public String consume(String key) {
        return redis.opsForValue().getAndDelete(key);
    }

    @Override
    public long increment(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 首次 INCR 才设置过期，形成固定窗口
            redis.expire(key, window);
        }
        return count == null ? 0 : count;
    }

    @Override
    public long get(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    @Override
    public void reset(String key) {
        redis.delete(key);
    }
}
