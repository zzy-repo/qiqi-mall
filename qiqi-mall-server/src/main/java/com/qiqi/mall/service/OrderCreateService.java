package com.qiqi.mall.service;

import com.qiqi.mall.entity.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 下单入口（非事务）：死锁自动重试。
 * 见 benchmark/oversell/deadlock-fix.mmd 步骤 3~5：
 * 事务整体回滚 → 零残留 → 重试安全；重试沿用同一 HTTP 请求，不再消费幂等令牌；
 * 重试次数用尽仍死锁 → 抛「系统繁忙，请重试」（不暴露 SQL 原文）。
 */
@Service
public class OrderCreateService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateService.class);

    /** 最多重试 3 次（即最多 4 次执行；文档口径：重试 1~3 次） */
    private static final int MAX_RETRY = 3;

    private final OrderTransactionalCreator creator;

    public OrderCreateService(OrderTransactionalCreator creator) {
        this.creator = creator;
    }

    public Order createWithRetry(Long memberId, String receiverName, String receiverPhone, String receiverAddress,
                                 List<OrderTransactionalCreator.ItemInput> inputs, boolean fromCart) {
        for (int attempt = 0; ; attempt++) {
            try {
                return creator.create(memberId, receiverName, receiverPhone, receiverAddress, inputs, fromCart);
            } catch (RuntimeException e) {
                if (!isRetryable(e) || attempt >= MAX_RETRY) {
                    if (isRetryable(e)) {
                        log.warn("下单重试 {} 次后仍遇锁冲突，放弃", attempt);
                        throw new IllegalStateException("系统繁忙，请重试");
                    }
                    throw e;
                }
                // 指数退避 + 抖动，避免重试风暴同时撞同一把锁
                long backoff = 30L * (1L << attempt) + ThreadLocalRandom.current().nextLong(30);
                log.warn("下单遇锁冲突（第 {} 次），{}ms 后重试：{}", attempt + 1, backoff, e.getClass().getSimpleName());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("系统繁忙，请重试");
                }
            }
        }
    }

    /** 死锁 / 锁等待超时 / 悲观锁失败 都属于「可重试」 */
    private boolean isRetryable(RuntimeException e) {
        return e instanceof DeadlockLoserDataAccessException
                || e instanceof CannotAcquireLockException
                || e instanceof PessimisticLockingFailureException
                || (e.getCause() != null && e.getCause() instanceof java.sql.SQLException sql
                    && "40001".equals(sql.getSQLState()));
    }
}
