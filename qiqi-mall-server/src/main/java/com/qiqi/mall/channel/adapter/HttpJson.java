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
            throw new IllegalStateException("外呼被中断：" + request.uri(), e);
        } catch (Exception e) {
            // 连接失败/超时：httpStatus=0，调用方（适配器）会包成 ChannelException
            throw new IllegalStateException("外呼失败：" + request.uri() + " → " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()), e);
        }
    }
}
