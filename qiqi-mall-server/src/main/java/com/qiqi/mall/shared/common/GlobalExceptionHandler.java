package com.qiqi.mall.shared.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public Result<Void> handle(Exception e) {
        log.error("请求处理失败", e);
        return Result.fail(e.getMessage());
    }

    /** 唯一约束冲突（如用户名重复）给出可读提示 */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.fail("已存在相同记录，请检查唯一字段");
    }

    /** 死锁 / 锁等待超时 / 悲观锁失败：给「系统繁忙」而不是 SQL 原文 */
    @ExceptionHandler({DeadlockLoserDataAccessException.class, CannotAcquireLockException.class,
            PessimisticLockingFailureException.class, QueryTimeoutException.class})
    public Result<Void> handleLockConflict(Exception e) {
        log.warn("数据库锁冲突（已由重试/上层兜底处理）: {}", e.getMessage());
        return Result.fail("系统繁忙，请重试");
    }

    /** 其它数据访问异常：日志留原文，响应只给可读提示，避免 SQL 细节透出 */
    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDataAccess(DataAccessException e) {
        log.error("数据库操作失败", e);
        return Result.fail("数据处理失败，请稍后重试");
    }
}
