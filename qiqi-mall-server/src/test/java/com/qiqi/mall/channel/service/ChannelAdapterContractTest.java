package com.qiqi.mall.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.channel.adapter.MockPlatformAdapter;
import com.qiqi.mall.channel.adapter.PlatformJson;
import com.qiqi.mall.channel.model.PlatformOrder;
import com.qiqi.mall.channel.model.PushResult;
import com.qiqi.mall.channel.model.ShipmentInfo;
import com.qiqi.mall.channel.spi.ChannelException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 接入层「契约」单测（不连库、不起 Spring）：平台报文解析、SKU 编码反解、失败口径。
 *
 * <p>为什么这几个点值得单测（而不是只靠 e2e）：
 * <ul>
 *   <li>{@link ChannelOrderImporter#parseSkuId} 是「平台商品编码 → 本地 sku」唯一的桥，
 *       写错一个前缀匹配就等于全量 {@code SKU_NOT_MATCHED}；</li>
 *   <li>报文解析决定 {@code platformSn} 有没有被正确取到 —— 它是幂等闸门的一半，
 *       取错（或退化成空串）会让同一张单被反复导入；</li>
 *   <li>「非 2xx 必须抛 {@link ChannelException} 而不是返回空列表」这条口径，
 *       直接决定 sync_log 里是 FAIL 还是「成功但 0 单」——那两件事完全不同。</li>
 * </ul>
 */
class ChannelAdapterContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------ SKU 编码

    @Test
    @DisplayName("平台商品编码 → sku.id：SKU-<id> / 纯数字 / 大小写都不敏感；非法返回 null（走 SKU_NOT_MATCHED）")
    void parseSkuIdVariants() {
        assertThat(ChannelOrderImporter.parseSkuId("SKU-12")).isEqualTo(12L);
        assertThat(ChannelOrderImporter.parseSkuId("sku-12")).isEqualTo(12L);
        assertThat(ChannelOrderImporter.parseSkuId("  SKU-12  ")).isEqualTo(12L);
        assertThat(ChannelOrderImporter.parseSkuId("12")).isEqualTo(12L);
        assertThat(ChannelOrderImporter.parseSkuId("SKU-NOT-EXIST-999999")).isNull();
        assertThat(ChannelOrderImporter.parseSkuId("ABC")).isNull();
        assertThat(ChannelOrderImporter.parseSkuId("SKU-0")).isNull();
        assertThat(ChannelOrderImporter.parseSkuId("")).isNull();
        assertThat(ChannelOrderImporter.parseSkuId(null)).isNull();
    }

    // ------------------------------------------------------------------ 报文解析

    @Test
    @DisplayName("平台报文：platformSn 原样取用、两种时间格式都认、qty/quantity 都认、缺字段不炸")
    void parseOrdersToleratesRealWorldShapes() {
        String body = """
                {"platformType":"TAOBAO_MOCK","shopCode":"TB-001","orders":[
                  {"platformSn":"TB-1","buyerName":"张三","receiverName":"张三","receiverPhone":"139",
                   "receiverAddress":"杭州","totalAmount":119.9,"paidAt":"2026-11-13T19:02:11",
                   "items":[{"platformSkuCode":"SKU-1","productName":"A","qty":1,"price":99.9}]},
                  {"platformSn":"TB-2","paidAt":"2026-11-13 19:02:12",
                   "items":[{"platformSkuCode":"2","productName":"B","quantity":2,"price":10}]}
                ]}
                """;
        List<PlatformOrder> orders = PlatformJson.parseOrders(MAPPER, body, "TAOBAO_MOCK", "TB-001");

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).platformSn()).isEqualTo("TB-1");
        assertThat(orders.get(0).paidAt()).isEqualTo(LocalDateTime.of(2026, 11, 13, 19, 2, 11));
        assertThat(orders.get(0).items()).hasSize(1);
        assertThat(orders.get(0).items().get(0).qty()).isEqualTo(1);
        // 简写与空格时间格式
        assertThat(orders.get(1).paidAt()).isEqualTo(LocalDateTime.of(2026, 11, 13, 19, 2, 12));
        assertThat(orders.get(1).items().get(0).qty()).isEqualTo(2);
        // 平台没给 channel 时回落成平台类型（落 order.channel 用）
        assertThat(orders.get(1).channel()).isEqualTo("TAOBAO_MOCK");
    }

    @Test
    @DisplayName("非法报文必须抛 ChannelException（不能静默返回空列表冒充「没有新单」）")
    void malformedPayloadFailsLoudly() {
        assertThatThrownBy(() -> PlatformJson.parseOrders(MAPPER, "not-json", "TAOBAO_MOCK", "TB-001"))
                .isInstanceOf(ChannelException.class)
                .hasMessageContaining("JSON");
        assertThatThrownBy(() -> PlatformJson.parseOrders(MAPPER, "{\"orders\":{}}", "TAOBAO_MOCK", "TB-001"))
                .isInstanceOf(ChannelException.class)
                .hasMessageContaining("orders");
        assertThatThrownBy(() -> PlatformJson.parseOrders(MAPPER, "{\"orders\":[{\"items\":[]}]}",
                "TAOBAO_MOCK", "TB-001"))
                .isInstanceOf(ChannelException.class)
                .hasMessageContaining("platformSn");
    }

    @Test
    @DisplayName("回传响应：code=DUPLICATE 是幂等命中（业务层记 SKIP，不是 FAIL）")
    void duplicatePushIsNotFailure() {
        PlatformJson.ParsedShip dup = PlatformJson.parseShip(MAPPER, "{\"ok\":true,\"code\":\"DUPLICATE\"}", 200);
        assertThat(dup.code()).isEqualTo("DUPLICATE");
        assertThat(new PushResult(true, dup.code(), dup.message(), 200, "{}").duplicated()).isTrue();

        // 平台只回一行纯文本 ACK：2xx 仍按受理成功处理
        assertThat(PlatformJson.parseShip(MAPPER, "OK", 200).code()).isEqualTo("OK");
    }

    // ------------------------------------------------------------------ 适配器（真跑一次本地 HTTP）

    @Test
    @DisplayName("MockPlatformAdapter：拉单解析正确；平台 503 抛 ChannelException 且带回原始报文")
    void mockAdapterTalksHttp() throws Exception {
        AtomicInteger failNext = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/mock/taobao/orders", exchange -> {
            if (failNext.getAndDecrement() > 0) {
                byte[] err = "{\"code\":\"HTTP_503\",\"message\":\"注入故障\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, err.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(err);
                }
                return;
            }
            byte[] body = ("{\"platformType\":\"TAOBAO_MOCK\",\"orders\":[{\"platformSn\":\"TB-X-1\","
                    + "\"paidAt\":\"2026-11-13T19:00:00\",\"items\":[{\"platformSkuCode\":\"SKU-1\","
                    + "\"productName\":\"A\",\"qty\":1,\"price\":9.9}]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.createContext("/mock/taobao/ship", exchange -> {
            byte[] body = "{\"ok\":true,\"code\":\"DUPLICATE\",\"message\":\"已回传过\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        MockPlatformAdapter adapter = new MockPlatformAdapter(
                new com.qiqi.mall.channel.adapter.HttpJson(MAPPER), MAPPER, base, "TAOBAO_MOCK", "taobao");

        // ① 正常拉单
        List<PlatformOrder> orders = adapter.pullOrders("TB-001", LocalDateTime.now().minusDays(1));
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).platformSn()).isEqualTo("TB-X-1");
        assertThat(orders.get(0).platformType()).isEqualTo("TAOBAO_MOCK");

        // ② 平台 503：必须抛异常，并把状态码与原始报文带回来（日志里要写清为什么失败）
        failNext.set(1);
        assertThatThrownBy(() -> adapter.pullOrders("TB-001", LocalDateTime.now().minusDays(1)))
                .isInstanceOf(ChannelException.class)
                .satisfies(e -> {
                    ChannelException ce = (ChannelException) e;
                    assertThat(ce.getHttpStatus()).isEqualTo(503);
                    assertThat(ce.getRawResponse()).contains("HTTP_503");
                });

        // ③ 回传命中幂等：ok=true + code=DUPLICATE
        PushResult result = adapter.pushShipment("TB-001", "TB-X-1",
                new ShipmentInfo("TB-X-1", "顺丰", "SF1", LocalDateTime.now()));
        assertThat(result.ok()).isTrue();
        assertThat(result.duplicated()).isTrue();
    }
}
