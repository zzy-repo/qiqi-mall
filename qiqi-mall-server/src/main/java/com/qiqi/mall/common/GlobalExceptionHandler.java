package com.qiqi.mall.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.dao.DuplicateKeyException;
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
        return Result.fail("已存在相同记录，请检查唯一字段（如用户名）");
    }
}
