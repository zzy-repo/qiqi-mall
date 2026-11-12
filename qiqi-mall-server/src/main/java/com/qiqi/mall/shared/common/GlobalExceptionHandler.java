package com.qiqi.mall.shared.common;

import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /**
     * 乐观锁校验失败：**原样透出 400 / 409 业务码**（缺版本号 / 版本冲突）。
     *
     * <p>为什么单列一条：{@link #handle(Exception)} 会把一切压成 code=1，
     * 而前端要靠 409 识别「别人改过了，弹刷新对话框」，压成 1 就退化成普通报错了。
     * 日志按 warn 打（这是正常的并发结果，不是故障），不打印堆栈免得刷屏。
     */
    @ExceptionHandler(OptimisticLockGuard.LockException.class)
    public Result<Void> handleOptimisticLock(OptimisticLockGuard.LockException e) {
        log.warn("乐观锁校验未通过 code={} message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 上传文件超限：返回真正的 HTTP 413（不是 200 + 错误码），前端 el-upload 才能按体积错误处理。
     * 上限见 application-*.yml 的 spring.servlet.multipart.max-file-size（5MB）。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超出限制: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Result.fail("图片不能超过 5MB"));
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
