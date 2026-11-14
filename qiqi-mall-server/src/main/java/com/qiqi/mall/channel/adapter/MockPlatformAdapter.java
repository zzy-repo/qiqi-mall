package com.qiqi.mall.channel.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.channel.model.PlatformOrder;
import com.qiqi.mall.channel.model.PushResult;
import com.qiqi.mall.channel.model.ShipmentInfo;
import com.qiqi.mall.channel.spi.ChannelAdapter;
import com.qiqi.mall.channel.spi.ChannelException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock 平台适配器（淘宝 / 抖音）：对接本地独立 mock 服务 {@code mock-platform/server.mjs}。
 *
 * <pre>
 *   拉单    GET  {mock.base-url}/mock/{platform}/orders?since=YYYY-MM-DDTHH:mm:ss&amp;shopCode=...
 *   回传    POST {mock.base-url}/mock/{platform}/ship      {platformSn, carrier, trackNo, shopCode}
 *   注入    GET  ...?fail=503                              （mock 侧对本次请求直接回 503）
 *          POST {mock.base-url}/mock/_admin/fail?code=503&amp;count=1 （接下来 N 次外呼回 503）
 * </pre>
 *
 * <p>一个类服务两个平台（只有 {@code platform} 段不同），所以<b>不是</b> Spring 组件：
 * 由 {@code ChannelConfig} 各 new 一个实例注册成两个 bean
 * —— 这样「两个平台」在注册表里是两个适配器，业务层不需要知道它们是同一份代码。
 *
 * <p>关于 {@code ?fail=503}：它是 mock 侧的**故障注入**开关，用来证明
 * 「失败会落 FAIL 日志 + 重试一次」这条路径真的在工作（见 e2e 阶段 4）。
 */
public class MockPlatformAdapter implements ChannelAdapter {

    private static final DateTimeFormatter SINCE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final HttpJson http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    /** channel_shop.platform_type：TAOBAO_MOCK / DOUYIN_MOCK */
    private final String platformType;
    /** mock 服务的路径段：taobao / douyin */
    private final String pathSegment;

    public MockPlatformAdapter(HttpJson http, ObjectMapper mapper, String baseUrl,
                               String platformType, String pathSegment) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.platformType = platformType;
        this.pathSegment = pathSegment;
    }

    @Override
    public String platformType() {
        return platformType;
    }

    @Override
    public List<PlatformOrder> pullOrders(String shopCode, LocalDateTime since) throws ChannelException {
        LocalDateTime from = since != null ? since : LocalDateTime.now().minusDays(1);
        String url = baseUrl + "/mock/" + pathSegment + "/orders?since=" + enc(from.format(SINCE_FORMAT))
                + "&shopCode=" + enc(shopCode);
        HttpJson.Response response;
        try {
            response = http.get(url);
        } catch (HttpJson.CallFailure e) {
            throw unreachable(e);
        }
        if (!response.ok()) {
            throw new ChannelException("平台返回 HTTP " + response.status(), response.status(), response.body());
        }
        return PlatformJson.parseOrders(mapper, response.body(), platformType, shopCode);
    }

    @Override
    public PushResult pushShipment(String shopCode, String platformSn, ShipmentInfo info) throws ChannelException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platformSn", platformSn);
        body.put("carrier", info.carrier());
        body.put("trackNo", info.trackNo());
        body.put("shopCode", shopCode);
        body.put("shippedAt", info.shippedAt() == null ? null : info.shippedAt().toString());

        HttpJson.Response response;
        try {
            response = http.postJson(baseUrl + "/mock/" + pathSegment + "/ship", body);
        } catch (HttpJson.CallFailure e) {
            throw unreachable(e);
        }
        if (!response.ok()) {
            throw new ChannelException("平台返回 HTTP " + response.status(), response.status(), response.body());
        }
        PlatformJson.ParsedShip parsed = PlatformJson.parseShip(mapper, response.body(), response.status());
        return new PushResult(true, parsed.code(), parsed.message(), response.status(), response.body());
    }

    /**
     * 连接层失败 → {@link ChannelException}。
     *
     * <p>message 是给运营看的人话：「平台不可达（连接失败，地址 http://mock-platform:19090）」——
     * 保留「哪个平台不可达 + 打的是哪个地址」这个排障必需信息，但**不放 Java 异常类名**、
     * 也不放 since/shopCode 那串 query（运营不看，还把 toast 撑爆）。
     * 异常类名等细节走 {@link HttpJson.CallFailure#detail()} → {@code ChannelException.getDetail()}，只落日志。
     */
    private ChannelException unreachable(HttpJson.CallFailure e) {
        return new ChannelException(
                "平台不可达（" + e.friendly() + "，地址 " + e.endpoint() + "）", 0, "", e.detail());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
