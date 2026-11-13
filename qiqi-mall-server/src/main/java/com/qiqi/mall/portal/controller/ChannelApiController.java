package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.member.service.MemberService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商城侧「平台开放接口」的替身（{@code MALL} 平台的对外面）。
 *
 * <p>它扮演的是**外部商城**给我们的接口，而不是内部业务接口 —— 所以：
 * <ul>
 *   <li>路径在 {@code /channel-api/**}（不在 /admin 也不在 /portal），走 {@code SecurityConfig}
 *       的放行链，不需要登录态（真实场景由平台侧的 appKey/签名保证，本轮不引入，见文件尾的风险说明）；</li>
 *   <li>报文是**平台格式**（camelCase 的扁平 JSON），不是本项目的 {@code Result<T>} 包装 ——
 *       接入层的 {@code MallAdapter} 必须和对接真实平台一样去解析它；</li>
 *   <li>只有两件事：读单（{@link #orders}）+ 一个回写标记（{@link #shipments}）。</li>
 * </ul>
 *
 * <h3>为什么读单要带三档过滤</h3>
 * <ol>
 *   <li>{@code status IN (1,7)}：只给「已付款、还没进入履约」的单。已发货/已完成不给 ——
 *       那边已经在履约了，再拉进来会造成重复发货；</li>
 *   <li>{@code pay_type IN (1,2)}：只给**真的在本商城付过款**的单。这条同时挡住了
 *       「渠道拉进来的单被再次拉出去」的回环：接入层建的订单 {@code pay_type=0}（钱在平台侧付的），
 *       不会被自己的开放接口当成新单返回；</li>
 *   <li>{@code payment_time >= since}：按水位增量给单，配合接入层的水位，正常情况每次只给新单。</li>
 * </ol>
 */
@Tag(name = "商城开放接口-渠道对接", description = "商城侧只读开放接口（拉单）+ 回传发货标记")
@RestController
@RequestMapping("/channel-api")
public class ChannelApiController {

    private static final Logger log = LoggerFactory.getLogger(ChannelApiController.class);

    /** 单次最多返回多少单：接入层按批处理，接口侧给个上限避免一次拉几万单 */
    private static final int MAX_ORDERS = 50;
    /** 回写标记前缀（写在 order.platform_remark 里，幂等判定也认它） */
    private static final String SHIPPED_MARK = "渠道回传发货";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final MemberService memberService;

    public ChannelApiController(OrderService orderService, OrderItemService orderItemService,
                                MemberService memberService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.memberService = memberService;
    }

    /**
     * 拉单（只读）。
     *
     * <pre>GET /channel-api/orders?since=YYYY-MM-DDTHH:mm:ss&amp;shopCode=MALL-001</pre>
     */
    @Operation(summary = "商城開放接口-拉单：since 之后已付款/待审核的商城订单（平台格式 JSON）")
    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String since,
                                      @RequestParam(required = false) String shopCode) {
        LocalDateTime from = parseSince(since);
        if (from == null) from = LocalDateTime.now().minusDays(1);

        List<Order> orders = orderService.list(new LambdaQueryWrapper<Order>()
                .in(Order::getStatus, 1, 7)
                .in(Order::getPayType, 1, 2)
                .isNotNull(Order::getPaymentTime)
                .ge(Order::getPaymentTime, from)
                .orderByAsc(Order::getPaymentTime)
                .last("LIMIT " + MAX_ORDERS));

        List<Map<String, Object>> payload = new ArrayList<>();
        for (Order order : orders) {
            payload.add(toPlatformOrder(order));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platformType", "MALL");
        body.put("shopCode", shopCode);
        body.put("orders", payload);
        log.debug("[channel-api] 拉单 since={} shopCode={} → {} 单", from, shopCode, payload.size());
        return body;
    }

    /**
     * 回传发货（唯一的写：一个回写标记）。
     *
     * <p>幂等：同一 {@code carrier+trackNo} 重复回传 → 返回 {@code code=DUPLICATE}，
     * 接入层据此记 SKIP（平台侧「已经知道了」不是失败）。
     */
    @Operation(summary = "商城开放接口-回传发货：把发货信息标记到商城订单上（同运单号重复回传返回 DUPLICATE）")
    @PostMapping("/shipments")
    public Map<String, Object> shipments(@RequestBody Map<String, Object> body) {
        String platformSn = str(body.get("platformSn"));
        String carrier = str(body.get("carrier"));
        String trackNo = str(body.get("trackNo"));
        if (platformSn == null || platformSn.isBlank()) {
            return reply(false, "PLATFORM_SN_REQUIRED", "缺少 platformSn");
        }
        Order order = orderService.getOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderSn, platformSn).last("LIMIT 1"));
        if (order == null) {
            return reply(false, "ORDER_NOT_FOUND", "商城侧没有订单 " + platformSn);
        }
        String mark = SHIPPED_MARK + " " + (carrier == null ? "" : carrier) + " " + (trackNo == null ? "" : trackNo);
        String remark = order.getPlatformRemark() == null ? "" : order.getPlatformRemark();
        if (remark.contains(mark.trim())) {
            return reply(true, "DUPLICATE", "该运单号已回传过（幂等命中）");
        }
        String merged = remark.isBlank() ? mark : remark + " | " + mark;
        // 只写这一个标记列：不改状态、不动金额 —— 商城侧对「平台回传」的唯一反应就是留痕
        orderService.update(new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, order.getId())
                .set(Order::getPlatformRemark, merged));
        log.info("[channel-api] 回传发货已标记：订单 {} / {} {}", platformSn, carrier, trackNo);
        return reply(true, "OK", "已标记发货：" + carrier + " " + trackNo);
    }

    private Map<String, Object> toPlatformOrder(Order order) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("platformSn", order.getOrderSn());
        row.put("channel", "MALL");
        row.put("buyerName", buyerNameOf(order.getMemberId()));
        row.put("receiverName", order.getReceiverName());
        row.put("receiverPhone", order.getReceiverPhone());
        row.put("receiverAddress", order.getReceiverAddress());
        row.put("totalAmount", order.getTotalAmount());
        row.put("paidAt", order.getPaymentTime() == null ? null : order.getPaymentTime().format(ISO));
        row.put("items", itemsOf(order.getId()));
        return row;
    }

    private List<Map<String, Object>> itemsOf(Long orderId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (OrderItem item : orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).orderByAsc(OrderItem::getId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            // 平台商品编码口径：SKU-<本地 sku id>（接入层按同一口径反解，见 ChannelOrderImporter#parseSkuId）
            row.put("platformSkuCode", "SKU-" + item.getProductSkuId());
            row.put("productName", item.getProductName());
            row.put("qty", item.getProductQuantity());
            row.put("price", item.getProductPrice());
            items.add(row);
        }
        return items;
    }

    private String buyerNameOf(Long memberId) {
        if (memberId == null) return null;
        try {
            var member = memberService.getById(memberId);
            if (member == null) return null;
            return member.getNickname() != null ? member.getNickname() : member.getUsername();
        } catch (Exception e) {
            return null;   // 买家档案查不到不影响拉单
        }
    }

    private static Map<String, Object> reply(boolean ok, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("code", code);
        body.put("message", message);
        return body;
    }

    /**
     * since 解析：ISO（{@code 2026-11-13T19:02:11}）与 {@code yyyy-MM-dd HH:mm:ss} 都认。
     *
     * <p>刻意在本类里自带而不是复用接入层的解析工具：这条 controller 扮演的是**平台方**，
     * 它和接入层是两个独立的系统，连「时间格式解析」都不该共用一份实现（否则平台改格式时
     * 会被本地代码悄悄迁就，真实对接时才发现对不上）。
     */
    private static LocalDateTime parseSince(String since) {
        if (since == null || since.isBlank()) return null;
        String v = since.trim();
        try {
            return LocalDateTime.parse(v);
        } catch (Exception ignore) {
            // 继续
        }
        try {
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
