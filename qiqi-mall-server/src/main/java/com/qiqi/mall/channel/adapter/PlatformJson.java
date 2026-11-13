package com.qiqi.mall.channel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.channel.model.PlatformOrder;
import com.qiqi.mall.channel.model.PlatformOrderItem;
import com.qiqi.mall.channel.spi.ChannelException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台报文的解析/组装（两个适配器共用一份）。
 *
 * <p>为什么把报文解析抽出来：MALL（商城自营开放接口）与两个 mock 平台的返回结构是**同一份**
 * 平台格式 JSON —— 这正是「统一模型」的意义，解析逻辑分两份必然漂移成两种口径。
 *
 * <p>报文格式（平台侧 → 我们）：
 * <pre>
 * { "platformType":"TAOBAO_MOCK", "shopCode":"TB-001",
 *   "orders":[ { "platformSn":"TB-...", "channel":"TAOBAO",
 *                "buyerName":"张三", "receiverName":"张三", "receiverPhone":"139...",
 *                "receiverAddress":"...", "totalAmount":199.00,
 *                "paidAt":"2026-11-13T19:02:11",
 *                "items":[{"platformSkuCode":"SKU-1","productName":"...","qty":1,"price":199.00}] } ] }
 * </pre>
 * 允许的简写：{@code qty} 也可写成 {@code quantity}；时间接受 ISO 与 {@code yyyy-MM-dd HH:mm:ss} 两种。
 */
public final class PlatformJson {

    private static final DateTimeFormatter SPACE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PlatformJson() {
    }

    /** 解析拉单响应；结构不对直接抛 ChannelException（不猜、不静默丢单） */
    public static List<PlatformOrder> parseOrders(ObjectMapper mapper, String body,
                                                  String platformType, String shopCode) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new ChannelException("平台返回不是合法 JSON：" + brief(body), 200, body);
        }
        JsonNode array = root.path("orders");
        if (!array.isArray()) {
            throw new ChannelException("平台返回缺少 orders 数组：" + brief(body), 200, body);
        }
        List<PlatformOrder> orders = new ArrayList<>();
        for (JsonNode node : array) {
            String sn = text(node, "platformSn");
            if (sn == null || sn.isBlank()) {
                throw new ChannelException("平台返回的订单缺少 platformSn：" + brief(node.toString()), 200, body);
            }
            List<PlatformOrderItem> items = new ArrayList<>();
            for (JsonNode item : node.path("items")) {
                items.add(new PlatformOrderItem(
                        text(item, "platformSkuCode"),
                        text(item, "productName"),
                        intOf(item, "qty", intOf(item, "quantity", 1)),
                        decimalOf(item, "price")));
            }
            orders.add(new PlatformOrder(
                    platformType,
                    shopCode,
                    sn,
                    text(node, "channel") == null ? platformType : text(node, "channel"),
                    text(node, "buyerName"),
                    text(node, "receiverName"),
                    text(node, "receiverPhone"),
                    text(node, "receiverAddress"),
                    decimalOf(node, "totalAmount"),
                    parseTime(text(node, "paidAt")),
                    items));
        }
        return orders;
    }

    /** 回传响应解析：平台码 + 说明；没有 body 时按调用成功处理 */
    public static ParsedShip parseShip(ObjectMapper mapper, String body, int httpStatus) {
        if (body == null || body.isBlank()) return new ParsedShip("OK", "平台无响应体（按受理成功处理）");
        try {
            JsonNode root = mapper.readTree(body);
            String code = text(root, "code");
            boolean ok = root.path("ok").isMissingNode() ? root.path("success").asBoolean(true) : root.path("ok").asBoolean(true);
            String message = text(root, "message");
            return new ParsedShip(code == null ? (ok ? "OK" : "FAIL") : code.toUpperCase(), message);
        } catch (Exception e) {
            // 不是 JSON：只要 HTTP 2xx 就认为受理成功（有的平台只回一行纯文本 ACK）
            return new ParsedShip(httpStatus >= 200 && httpStatus < 300 ? "OK" : "FAIL", brief(body));
        }
    }

    /** 回传响应解析结果 */
    public record ParsedShip(String code, String message) {
    }

    /** 时间解析：ISO（含时区/毫秒）与 {@code yyyy-MM-dd HH:mm:ss} 都认；解析不了返回 null（不阻塞落单） */
    public static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) return null;
        String v = text.trim();
        try {
            return LocalDateTime.parse(v);
        } catch (Exception ignore) {
            // 继续试别的格式
        }
        try {
            return LocalDateTime.parse(v, SPACE_TIME);
        } catch (Exception ignore) {
            // 继续
        }
        try {
            return OffsetDateTime.parse(v).toLocalDateTime();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal decimalOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) return null;
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(JsonNode node, String field, int fallback) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) return fallback;
        int parsed = v.asInt(fallback);
        return parsed < 1 ? fallback : parsed;
    }

    /** 日志/异常里只放一小段报文，避免把整批订单塞进 reason */
    public static String brief(String text) {
        if (text == null) return "";
        String v = text.replaceAll("\\s+", " ").trim();
        return v.length() <= 300 ? v : v.substring(0, 300) + "…";
    }
}
