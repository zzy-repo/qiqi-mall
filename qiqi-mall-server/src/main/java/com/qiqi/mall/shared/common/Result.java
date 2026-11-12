package com.qiqi.mall.shared.common;

public class Result<T> {

    private int code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(1, message, null);
    }

    /**
     * 带业务码的失败：目前只有乐观锁在用 ——
     * 缺版本号 {@code 400}、版本冲突 {@code 409}（沿用 HTTP 语义，见 {@code OptimisticLockGuard}）。
     * 前端据此弹「刷新后重试」对话框，所以码必须透传，不能一律压成 1。
     *
     * <p><b>别直接 return 它：</b>直接返回它 = HTTP 200 + body 里带 400/409。
     * 乐观锁路径统一走 {@code OptimisticLockGuard.requireXxx(...)} 抛异常，
     * 由 {@code GlobalExceptionHandler} 返回 {@code ResponseEntity(status(code))}，
     * 这样 HTTP 状态码和 body.code 才是同一个意思（只看 HTTP 的调用方才不会被骗）。
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
