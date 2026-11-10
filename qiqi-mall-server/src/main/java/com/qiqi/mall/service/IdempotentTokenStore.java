package com.qiqi.mall.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等令牌存储（内存，单实例足够）。
 * 见 benchmark/oversell/deadlock-fix.mmd：
 * - 进结算页发放 token（TTL 30 分钟）
 * - 提交订单时原子消费（save→consume），重复提交取不到 token 即拒绝
 * - 服务端内部重试不再消费 token，因此重试不会重复下单/重复扣减
 */
@Component
public class IdempotentTokenStore {

    /** 令牌有效期 30 分钟 */
    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    private record Entry(long expireAt) {}

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    /** 发放令牌 */
    public String issue() {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new Entry(System.currentTimeMillis() + TTL_MILLIS));
        return token;
    }

    /** 原子消费：返回 false 表示令牌不存在/已过期/已被消费（视为重复提交） */
    public boolean consume(String token) {
        if (token == null || token.isBlank()) return false;
        Entry entry = tokens.remove(token);
        return entry != null && entry.expireAt() >= System.currentTimeMillis();
    }

    /** 每 10 分钟清扫过期令牌 */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void sweep() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }
}
