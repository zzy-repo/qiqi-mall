package com.qiqi.mall.channel.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 适配器共用的极简 HTTP/JSON 客户端。
 *
 * <p>为什么用 JDK 自带的 {@link HttpClient} 而不是 RestTemplate/WebClient：
 * 接入层只需要「GET/POST 一段 JSON」，引入一套客户端抽象（拦截器、编解码、连接池配置）
 * 对本轮的闭环没有收益；而且这两条外呼都在**请求线程里同步完成**（拉单/回传都要落日志），
 * 用不到响应式的线程模型。
 *
 * <p>超时口径：连接 3s、整请求 10s —— 后台点一下按钮不该无限等；
 * 超时抛出的异常由适配器包成 {@code ChannelException}（httpStatus=0），
 * 后台看到的是「连接超时」而不是 500 堆栈。
 */
@Component
public class HttpJson {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper mapper;

    @Autowired
    public HttpJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** HTTP 响应快照：状态码 + 原始响应体（原样保留，日志里存截断版） */
    public record Response(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public Response get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(request);
    }

    public Response postJson(String url, Object body) {
        String json;
        try {
            json = mapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体无法序列化：" + e.getMessage(), e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CallFailure(Kind.INTERRUPTED, request.uri().toString(), describe(e), e);
        } catch (Exception e) {
            // 连接失败/超时：httpStatus=0，调用方（适配器）会包成 ChannelException。
            // 这里**把「人话」和「技术细节」分开带出去**：人话上后台 toast，技术细节只进日志 ——
            // 运营看到 ConnectException / 一长串 URL 既看不懂也不知道该找谁。
            throw new CallFailure(classify(e), request.uri().toString(), describe(e), e);
        }
    }

    /** 外呼失败的原因分类（决定给运营看的那句话怎么说） */
    public enum Kind {
        /** 连不上：端口没人监听 / 目标容器没起 */
        CONNECT_REFUSED,
        /** 连接超时（{@code connectTimeout} 3s 内没建立连接） */
        CONNECT_TIMEOUT,
        /** 已连上但读响应超时（{@code HttpRequest.timeout} 10s） */
        REQUEST_TIMEOUT,
        /** 域名/服务名解析不到（容器里写错 compose 服务名时就是它） */
        UNKNOWN_HOST,
        /** TLS 握手失败（证书/协议） */
        TLS,
        /** 外呼被中断（停机/线程打断） */
        INTERRUPTED,
        /** 其它网络异常（连接被重置等） */
        OTHER
    }

    /**
     * 外呼失败的**结构化**描述：{@link #getMessage()} 是给运营看的人话，
     * {@link #detail()} 是给排障用的技术细节（异常类名 + 原始 message）。
     *
     * <p>为什么要拆成两份：以前直接把 {@code ConnectException} 和完整 URL 拼进 message，
     * 一路冒到后台 toast 上，运营的观感是「页面报了个 Java 异常」。人话与技术细节从此分开：
     * <ul>
     *   <li>人话（"连接失败（地址 http://mock-platform:19090）"）→ toast / 同步日志 reason；</li>
     *   <li>技术细节（"ConnectException: Connection refused"）→ channel_sync_log 的报文列 + 服务端日志。</li>
     * </ul>
     */
    public static class CallFailure extends RuntimeException {

        private final Kind kind;
        private final String url;
        private final String detail;

        public CallFailure(Kind kind, String url, String detail, Throwable cause) {
            super(friendlyText(kind), cause);
            this.kind = kind;
            this.url = url == null ? "" : url;
            this.detail = detail == null ? "" : detail;
        }

        public Kind kind() {
            return kind;
        }

        /** 完整请求 URL（含 query，排障用；人话里只给 scheme://host:port） */
        public String url() {
            return url;
        }

        /** 只到 scheme://host:port —— 运营需要知道「打的是哪个地址」，不需要 since/shopCode */
        public String endpoint() {
            try {
                URI uri = URI.create(url);
                String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
                return uri.getScheme() + "://" + uri.getHost() + port;
            } catch (RuntimeException e) {
                return url;
            }
        }

        /** 技术细节（异常类名 + 原始 message），只进日志，不进 toast */
        public String detail() {
            return detail;
        }

        /** 给运营看的人话（不含 Java 异常类名、不含 query string） */
        public String friendly() {
            return friendlyText(kind);
        }

        private static String friendlyText(Kind kind) {
            return switch (kind) {
                case CONNECT_REFUSED -> "连接失败（目标端口没有服务在监听）";
                case CONNECT_TIMEOUT -> "连接超时（3 秒内没连上）";
                case REQUEST_TIMEOUT -> "响应超时（10 秒内没返回）";
                case UNKNOWN_HOST -> "域名解析失败（查不到这台主机）";
                case TLS -> "TLS 握手失败（证书或协议不匹配）";
                case INTERRUPTED -> "外呼被中断";
                case OTHER -> "网络异常";
            };
        }
    }

    /** 沿 cause 链认异常类型（JDK HttpClient 会包一层 IOException，不能只看最外层） */
    private static Kind classify(Throwable e) {
        // 深度上限只是防御：cause 链异常自身成环时不会转不出来
        for (int depth = 0; e != null && depth < 10; depth++, e = e.getCause()) {
            // ConnectTimeout 必须先于 ConnectException 判断：它俩是兄弟类，别被顺序带偏
            if (e instanceof java.net.http.HttpConnectTimeoutException
                    || e.getClass().getSimpleName().contains("ConnectTimeout")) {
                return Kind.CONNECT_TIMEOUT;
            }
            if (e instanceof java.net.http.HttpTimeoutException
                    || e.getClass().getSimpleName().contains("Timeout")) {
                return Kind.REQUEST_TIMEOUT;
            }
            if (e instanceof java.net.ConnectException) {
                return Kind.CONNECT_REFUSED;
            }
            if (e instanceof java.net.UnknownHostException) {
                return Kind.UNKNOWN_HOST;
            }
            if (e instanceof javax.net.ssl.SSLException) {
                return Kind.TLS;
            }
        }
        return Kind.OTHER;
    }

    /** 技术细节：异常类名 + 原始 message（仅日志） */
    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage());
    }
}
