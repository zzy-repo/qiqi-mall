package com.qiqi.mall.channel.spi;

/**
 * 平台接入异常：把「外呼发生了什么」分两层带回来。
 *
 * <p>为什么不用 RuntimeException 直接抛：{@code channel_sync_log} 要落 {@code response_payload}
 * 与 {@code reason}，没有原始响应就只能写「拉单失败」这种没法排查的话。
 * 这里把 {@code httpStatus} 与 {@code rawResponse} 一起带着，业务层写日志时直接用。
 *
 * <p><b>两层口径（别混）</b>：
 * <ul>
 *   <li>{@link #getMessage()} = 给运营看的人话（"平台不可达：连接失败（地址 http://mock-platform:19090）"），
 *       会进后台 toast 与 sync_log 的 reason —— 里面不许出现 Java 异常类名、不许带 query string；</li>
 *   <li>{@link #getDetail()} = 排障用的技术细节（"ConnectException：Connection refused"），
 *       只进 sync_log 的报文列与服务端日志。</li>
 * </ul>
 */
public class ChannelException extends RuntimeException {

    /** HTTP 状态码；0 = 连接层异常（超时/拒绝连接），没有响应体 */
    private final int httpStatus;
    /** 平台原始响应体（可能为空字符串，绝不为 null） */
    private final String rawResponse;
    /** 技术细节（异常类名 + 原始 message），只进日志，绝不给前端 */
    private final String detail;

    public ChannelException(String message) {
        this(message, 0, "");
    }

    public ChannelException(String message, int httpStatus, String rawResponse) {
        this(message, httpStatus, rawResponse, "");
    }

    public ChannelException(String message, int httpStatus, String rawResponse, String detail) {
        super(message);
        this.httpStatus = httpStatus;
        this.rawResponse = rawResponse == null ? "" : rawResponse;
        this.detail = detail == null ? "" : detail;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    /** 技术细节（只有连接层失败才有：适配器从 HttpJson.CallFailure 带过来）；仅日志用 */
    public String getDetail() {
        return detail;
    }
}
