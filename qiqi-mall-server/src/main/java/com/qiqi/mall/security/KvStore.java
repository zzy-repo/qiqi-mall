package com.qiqi.mall.security;

import java.time.Duration;

/**
 * 进程外 KV 抽象：验证码与幂等令牌的共同能力（带 TTL 的一次性 KV + 计数器）。
 * 见 docs/multi-instance-plan.md：memory（单实例档，默认）/ redis（多实例共享）。
 */
public interface KvStore {

    /** 写入带 TTL 的值：Redis = SET key value EX ttl */
    void put(String key, String value, Duration ttl);

    /** 原子取出即删：Redis = GETDEL，返回 null 表示不存在/已过期/已被消费 */
    String consume(String key);

    /** 失败计数：Redis = INCR + 首次 EXPIRE，返回自增后的值 */
    long increment(String key, Duration window);

    /** 只读计数：Redis = GET，缺失返回 0（不能自增，否则会误触发验证码） */
    long get(String key);

    /** 清零：Redis = DEL */
    void reset(String key);
}
