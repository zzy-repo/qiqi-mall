package com.qiqi.mall.channel.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.channel.model.PlatformOrder;
import com.qiqi.mall.channel.model.PushResult;
import com.qiqi.mall.channel.model.ShipmentInfo;
import com.qiqi.mall.channel.spi.ChannelAdapter;
import com.qiqi.mall.channel.spi.ChannelException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MALL 适配器：对接**商城侧**新增的只读开放接口（同一个后端进程里的 {@code /channel-api/**}）。
 *
 * <pre>
 *   拉单    GET  {mall.base-url}/channel-api/orders?since=YYYY-MM-DDTHH:mm:ss&amp;shopCode=...
 *   回传    POST {mall.base-url}/channel-api/shipments  {platformSn, carrier, trackNo, shopCode}
 * </pre>
 *
 * <p><b>为什么调自己也要走 HTTP</b>：这是「平台方给的外部接口」的替身 —— 走一遍网络，
 * 接入层才能按真实形态处理超时/非 2xx/报文异常；如果直接注入 service 方法，
 * 这段代码在真实平台面前一行都跑不通（演示价值归零）。
 *
 * <p>{@code channel.mall.base-url} 默认 {@code http://127.0.0.1:${server.port}}：
 * e2e 后端跑在 8091 时自动指向 8091，不用为每个分片改配置。
 */
@Component
public class MallAdapter implements ChannelAdapter {

    /** 与 {@code channel_shop.platform_type} 同值 */
    public static final String PLATFORM = "MALL";

    private static final DateTimeFormatter SINCE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final HttpJson http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public MallAdapter(HttpJson http, ObjectMapper mapper,
                       @Value("${channel.mall.base-url:http://127.0.0.1:${server.port:8081}}") String baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = trimSlash(baseUrl);
    }

    @Override
    public String platformType() {
        return PLATFORM;
    }

    @Override
    public List<PlatformOrder> pullOrders(String shopCode, LocalDateTime since) throws ChannelException {
        // 首次拉单（水位为空）默认回看 1 天：既不会漏掉昨天的单，也不会把历史全量拉一遍
        LocalDateTime from = since != null ? since : LocalDateTime.now().minusDays(1);
        String url = baseUrl + "/channel-api/orders?since=" + enc(from.format(SINCE_FORMAT))
                + "&shopCode=" + enc(shopCode);
        HttpJson.Response response;
        try {
            response = http.get(url);
        } catch (RuntimeException e) {
            throw new ChannelException("商城开放接口不可达：" + e.getMessage(), 0, "");
        }
        if (!response.ok()) {
            throw new ChannelException("商城开放接口返回 " + response.status(), response.status(), response.body());
        }
        return PlatformJson.parseOrders(mapper, response.body(), PLATFORM, shopCode);
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
            response = http.postJson(baseUrl + "/channel-api/shipments", body);
        } catch (RuntimeException e) {
            throw new ChannelException("商城开放接口不可达：" + e.getMessage(), 0, "");
        }
        if (!response.ok()) {
            throw new ChannelException("商城发货回写返回 " + response.status(), response.status(), response.body());
        }
        // 平台返回的 code 原样带回去：业务层只认 DUPLICATE（幂等命中 → SKIP），其余按 ok 处理
        PlatformJson.ParsedShip parsed = PlatformJson.parseShip(mapper, response.body(), response.status());
        return new PushResult(true, parsed.code(), parsed.message(), response.status(), response.body());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
