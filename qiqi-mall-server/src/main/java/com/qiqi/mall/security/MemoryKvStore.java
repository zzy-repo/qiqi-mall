package com.qiqi.mall.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例档实现：ConcurrentHashMap + TTL + 定时清扫。
 * app.kv-store=memory（或缺省）时生效，行为与改造前一致。
 */
@Component
@ConditionalOnProperty(name = "app.kv-store", havingValue = "memory", matchIfMissing = true)
public class MemoryKvStore implements KvStore {

    private record Entry(String value, long expireAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        entries.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public String consume(String key) {
        Entry entry = entries.remove(key);
        if (entry == null) return null;
        return entry.expireAt() >= System.currentTimeMillis() ? entry.value() : null;
    }

    @Override
    public long increment(String key, Duration window) {
        long now = System.currentTimeMillis();
        Entry[] updated = new Entry[1];
        Entry result = entries.compute(key, (k, old) -> {
            if (old == null || old.expireAt() < now) {
                updated[0] = new Entry("1", now + window.toMillis());
            } else {
                long next = Long.parseLong(old.value()) + 1;
                // 计数窗口只在首次设置，与 Redis 的 INCR+首次 EXPIRE 语义一致
                updated[0] = new Entry(String.valueOf(next), old.expireAt());
            }
            return updated[0];
        });
        return Long.parseLong(result.value());
    }

    @Override
    public long get(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.expireAt() < System.currentTimeMillis()) return 0;
        return Long.parseLong(entry.value());
    }

    @Override
    public void reset(String key) {
        entries.remove(key);
    }

    /** 单实例档需要自己清扫（Redis 档靠自身过期，不需要） */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void sweep() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }
}
