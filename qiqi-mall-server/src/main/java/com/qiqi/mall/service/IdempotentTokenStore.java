package com.qiqi.mall.service;

import com.qiqi.mall.security.KvStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 幂等令牌（对外方法签名不变，内部委托 KvStore）。
 * 见 benchmark/oversell/deadlock-fix.mmd：
 * - 进结算页发放 token（TTL 30 分钟）
 * - 提交订单时原子消费，重复提交取不到 token 即拒绝
 * - 服务端内部重试不再消费 token，因此重试不会重复下单/重复扣减
 * 档位切换：app.kv-store=memory（进程内）/ redis（多实例共享），调用方零感知。
 */
@Component
public class IdempotentTokenStore {

    /** 令牌有效期 30 分钟 */
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String PREFIX = "idem:order:";

    private final KvStore kvStore;

    public IdempotentTokenStore(KvStore kvStore) {
        this.kvStore = kvStore;
    }

    /** 发放令牌 */
    public String issue() {
        String token = UUID.randomUUID().toString().replace("-", "");
        kvStore.put(PREFIX + token, "1", TTL);
        return token;
    }

    /** 原子消费：false 表示不存在/已过期/已被消费（视为重复提交） */
    public boolean consume(String token) {
        if (token == null || token.isBlank()) return false;
        return kvStore.consume(PREFIX + token) != null;
    }
}
