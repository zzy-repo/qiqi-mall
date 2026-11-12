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
     * 乐观锁校验失败：**透出 400 / 409 业务码，并且是真 HTTP 状态码**（不是 200 + body 里的 code）。
     *
     * <p>两道都要有，缺一不可：
     * <ul>
     *   <li>body 里带 {@code code=400/409} —— 前端 admin-web 读的是 body.code，据此弹「刷新后重试」对话框；</li>
     *   <li>**HTTP 状态码也是 400/409** —— 只看 HTTP 的调用方（mcp-server 之外的 curl 脚本、监控、网关/nginx 日志、
     *       将来接入的第三方）才不会被「200 但 body 说失败」骗过去，把冲突记成成功。</li>
     * </ul>
     * 之前这里返回的是裸 {@code Result}，Spring 会包成 HTTP 200 —— 冲突在传输层和成功长得一模一样，属于同一类
     * 「静默失败」。改成 {@link ResponseEntity} 后就对得上了：HTTP 语义与 body 语义一致。
     *
     * <p>日志按 warn 打（这是正常的并发结果，不是故障），不打印堆栈免得刷屏。
     */
    @ExceptionHandler(OptimisticLockGuard.LockException.class)
    public ResponseEntity<Result<Void>> handleOptimisticLock(OptimisticLockGuard.LockException e) {
        log.warn("乐观锁校验未通过 code={} message={}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null || !status.isError()) {
            // 约定只有 400/409 走这里；拿到别的码就当普通失败（不猜 HTTP 语义）
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
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
