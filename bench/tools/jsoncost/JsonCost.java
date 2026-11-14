import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.shared.common.Result;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JsonCost —— 下单链路上 JSON 反序列化/序列化的单次耗时（纯 CPU，不碰 HTTP）。
 *
 * <p>口径与生产完全一致：用 {@code shared/config/JacksonConfig} 的同一套定制（LocalDateTime
 * 用 yyyy-MM-dd HH:mm:ss）+ JavaTimeModule，序列化的是**真实响应结构**
 * {@code Result.ok({order:<Order>, items:[<OrderItem>...]})}。
 *
 * <p>为什么单独量：这份成本夹在事务外面（请求体在 Controller 之前反序列化、响应体在 Controller
 * 返回之后序列化），MySQL 侧的 performance_schema 看不见它。
 *
 * <pre>
 *   CP=$REPO/qiqi-mall-server/target/classes:&lt;jackson jars&gt;:&lt;mybatis-plus-annotation&gt;
 *   java -cp "$CP" bench/tools/jsoncost/JsonCost.java &lt;请求体json文件&gt; [迭代次数]
 * </pre>
 */
public final class JsonCost {

    public static void main(String[] args) throws Exception {
        Path reqFile = Path.of(args.length > 0 ? args[0] : "/tmp/loadgen/order-real.json");
        int iters = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;

        // 与 JacksonConfig 同一个定制
        DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        DateTimeFormatter d = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule()
                .addSerializer(new LocalDateTimeSerializer(dt))
                .addSerializer(new LocalDateSerializer(d))
                .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dt))
                .addDeserializer(java.time.LocalDate.class, new LocalDateDeserializer(d)));
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // ---- 请求体：把模板里的 {{TOKEN}} / {{SKU}} 换成真值（形状与压测时逐字节一致）
        String req = Files.readString(reqFile, StandardCharsets.UTF_8)
                .replace("{{TOKEN}}", "0123456789abcdef0123456789abcdef")
                .replace("{{SKU}}", "1");
        byte[] reqBytes = req.getBytes(StandardCharsets.UTF_8);

        // ---- 响应体：一个「有 2 行明细的新订单」—— 与 /portal/orders 建单返回同构
        Order order = new Order();
        order.setId(920003249L);
        order.setOrderSn("QQ20261114093420327615");
        order.setMemberId(988L);
        order.setTotalAmount(new BigDecimal("13.20"));
        order.setStatus(0);
        order.setPayType(0);
        order.setReceiverName("LoadGen");
        order.setReceiverPhone("13800000000");
        order.setReceiverAddress("LoadGen bench address");
        order.setVersion(0);
        order.setStatusSeq(0);
        order.setSplitStatus(0);

        List<OrderItem> items = new ArrayList<>(Arrays.asList(
                item(920003249L, 1L, 1L, "等价性等价性等价性等价性等价性商品", "19.90", 2),
                item(920003249L, 1L, 2L, "商品-B", "0.05", 5)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("items", items);
        Object payload = Result.ok(data);

        byte[] respBytes = mapper.writeValueAsBytes(payload);

        // ---- 预热（JIT）：30000 次
        for (int i = 0; i < 30_000; i++) {
            mapper.readTree(reqBytes);
            mapper.writeValueAsBytes(payload);
            mapper.readValue(reqBytes, Map.class);
        }

        // ---- 反序列化请求体（Controller 参数解析，@RequestBody）
        long[] deser = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            mapper.readTree(reqBytes);      // 与 Spring 的 body 读取最接近的纯解析路径
            deser[i] = System.nanoTime() - t0;
        }
        // POJO 绑定部分单列（Spring 把树绑定到 CreateRequest）
        long[] bind = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            mapper.readValue(reqBytes, Map.class);
            bind[i] = System.nanoTime() - t0;
        }
        // ---- 序列化响应体
        long[] ser = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            mapper.writeValueAsBytes(payload);
            ser[i] = System.nanoTime() - t0;
        }

        System.out.printf("请求体 %,d B   响应体 %,d B   迭代 %,d%n",
                reqBytes.length, respBytes.length, iters);
        report("请求体 解析成树      ", deser);
        report("请求体 解析+bind Map ", bind);
        report("响应体 序列化        ", ser);
        System.out.printf("%n注意：这是**纯 CPU**耗时；不含 socket 读写与内核拷贝。%n");
    }

    private static OrderItem item(Long orderId, Long productId, Long skuId, String name, String price, int qty) {
        OrderItem it = new OrderItem();
        it.setId(skuId + 100);
        it.setOrderId(orderId);
        it.setProductId(productId);
        it.setProductSkuId(skuId);
        it.setProductName(name);
        it.setProductPrice(new BigDecimal(price));
        it.setProductQuantity(qty);
        return it;
    }

    private static void report(String label, long[] ns) {
        long[] s = ns.clone();
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0) / 1000.0;
        System.out.printf("%s p50=%7.2f us  p90=%7.2f us  p99=%7.2f us  mean=%7.2f us%n",
                label, s[s.length / 2] / 1000.0, s[(int) (s.length * 0.90)] / 1000.0,
                s[(int) (s.length * 0.99)] / 1000.0, mean);
    }

    private JsonCost() { }
}
