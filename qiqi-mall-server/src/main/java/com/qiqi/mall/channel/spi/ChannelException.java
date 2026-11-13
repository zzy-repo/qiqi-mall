package com.qiqi.mall.channel.spi;

/**
 * 平台接入异常：把「外呼发生了什么」原样带回来。
 *
 * <p>为什么不用 RuntimeException 直接抛：{@code channel_sync_log} 要落 {@code response_payload}
 * 与 {@code reason}，没有原始响应就只能写「拉单失败」这种没法排查的话。
 * 这里把 {@code httpStatus} 与 {@code rawResponse} 一起带着，业务层写日志时直接用。
 */
public class ChannelException extends RuntimeException {

    /** HTTP 状态码；0 = 连接层异常（超时/拒绝连接），没有响应体 */
    private final int httpStatus;
    /** 平台原始响应体（可能为空字符串，绝不为 null） */
    private final String rawResponse;

    public ChannelException(String message) {
        this(message, 0, "");
    }

    public ChannelException(String message, int httpStatus, String rawResponse) {
        super(message);
        this.httpStatus = httpStatus;
        this.rawResponse = rawResponse == null ? "" : rawResponse;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getRawResponse() {
        return rawResponse;
    }
}
