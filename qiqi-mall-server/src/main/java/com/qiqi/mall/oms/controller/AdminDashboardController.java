package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.catalog.service.SpecTextHelper;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.shared.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 后台数据概览（一次请求返回看板需要的全部数字）。
 *
 * 口径（与业务确认过）：
 * - 今日成交额 / 今日订单：按 order.payment_time 落在今天，且状态属于「已付款且未退款」
 *   （1已付款 2已发货 3已完成 7待审核 8待发货 9部分发货）；5退款中/6已退款 不算成交，0/4 未付/关闭 也不算
 * - 趋势：最近 7 天（含今天），同样按 payment_time 归日
 * - 状态分布：全部订单按 status 分组
 * - Top 商品：从 order_item 聚合（按商品名，取前 5）
 * - 库存告警：sku.stock <= 10 的规格，最多 5 条
 *
 * 数据量都在几千行级别，直接在服务端聚合（走真实表、不引入缓存/定时任务）。
 */
@Tag(name = "管理端-数据概览", description = "首页概览指标：订单/销售额/商品/会员等统计")
@RestController
@RequestMapping("/admin/dashboard")
public class AdminDashboardController {

    /** 库存告警阈值 */
    private static final int STOCK_ALERT_THRESHOLD = 10;
    /** 成交口径：已付款之后、未退款的订单状态 */
    private static final List<Integer> DEAL_STATUSES = List.of(1, 2, 3, 7, 8, 9);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ProductService productService;
    private final SkuService skuService;
    private final SpecTextHelper specTextHelper;
    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final ReturnApplyService returnApplyService;

    public AdminDashboardController(ProductService productService, SkuService skuService,
                                    SpecTextHelper specTextHelper, OrderService orderService,
                                    OrderItemService orderItemService, ReturnApplyService returnApplyService) {
        this.productService = productService;
        this.skuService = skuService;
        this.specTextHelper = specTextHelper;
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.returnApplyService = returnApplyService;
    }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new LinkedHashMap<>();

        // ---------- 商品 ----------
        long productTotal = productService.count();
        long productOnline = productService.count(new LambdaQueryWrapper<Product>()
                .eq(Product::getPublishStatus, 1));

        // ---------- 订单（一次性取出来在内存聚合，避免多次往返）----------
        List<Order> orders = orderService.list();
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

        BigDecimal todayAmount = BigDecimal.ZERO;
        int todayOrderCount = 0;
        int pendingAudit = 0;
        int pendingShip = 0;
        Map<Integer, Integer> statusCount = new TreeMap<>();
        // 最近 7 天：日期 -> [订单数, 金额]
        Map<LocalDate, BigDecimal> trendAmount = new LinkedHashMap<>();
        Map<LocalDate, Integer> trendCount = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            trendAmount.put(d, BigDecimal.ZERO);
            trendCount.put(d, 0);
        }

        for (Order o : orders) {
            statusCount.merge(o.getStatus() == null ? -1 : o.getStatus(), 1, Integer::sum);
            if (o.getStatus() != null && o.getStatus() == 7) pendingAudit++;
            if (o.getStatus() != null && o.getStatus() == 8) pendingShip++;

            LocalDateTime pay = o.getPaymentTime();
            if (pay == null || o.getStatus() == null || !DEAL_STATUSES.contains(o.getStatus())) continue;
            if (!pay.isBefore(todayStart) && pay.isBefore(tomorrowStart)) {
                todayOrderCount++;
                todayAmount = todayAmount.add(o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount());
            }
            LocalDate day = pay.toLocalDate();
            if (trendAmount.containsKey(day)) {
                trendAmount.put(day, trendAmount.get(day).add(o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount()));
                trendCount.put(day, trendCount.get(day) + 1);
            }
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate d : trendAmount.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.format(DAY));
            row.put("orderCount", trendCount.get(d));
            row.put("amount", money(trendAmount.get(d)));
            trend.add(row);
        }

        List<Map<String, Object>> statusDistribution = statusCount.entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("status", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .toList();

        // ---------- Top 5 商品（order_item 聚合）----------
        Map<String, Map<String, Object>> topMap = new LinkedHashMap<>();
        for (OrderItem item : orderItemService.list()) {
            String key = item.getProductName() == null ? "未知商品" : item.getProductName();
            Map<String, Object> row = topMap.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productName", k);
                m.put("qty", 0);
                m.put("amount", BigDecimal.ZERO);
                return m;
            });
            int qty = item.getProductQuantity() == null ? 0 : item.getProductQuantity();
            BigDecimal price = item.getProductPrice() == null ? BigDecimal.ZERO : item.getProductPrice();
            row.put("qty", (int) row.get("qty") + qty);
            row.put("amount", ((BigDecimal) row.get("amount")).add(price.multiply(BigDecimal.valueOf(qty))));
        }
        List<Map<String, Object>> topProducts = topMap.values().stream()
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("qty")).reversed())
                .limit(5)
                .peek(m -> m.put("amount", money((BigDecimal) m.get("amount"))))
                .collect(Collectors.toList());

        // ---------- 库存告警 ----------
        List<Sku> lowStock = skuService.list(new LambdaQueryWrapper<Sku>()
                .le(Sku::getStock, STOCK_ALERT_THRESHOLD)
                .orderByAsc(Sku::getStock));
        Map<Long, String> productNames = productService.listByIds(
                        lowStock.stream().map(Sku::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getName));
        Map<Long, String> specTexts = specTextHelper.textMapBySkuIds(
                lowStock.stream().map(Sku::getId).toList());
        List<Map<String, Object>> stockAlerts = lowStock.stream().limit(5).map(sku -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skuId", sku.getId());
            row.put("productName", productNames.getOrDefault(sku.getProductId(), ""));
            row.put("specText", specTexts.getOrDefault(sku.getId(), ""));
            row.put("stock", sku.getStock());
            return row;
        }).toList();

        // ---------- 退货 ----------
        long returnPending = returnApplyService.count(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getStatus, 0));

        data.put("productTotal", productTotal);
        data.put("productOnline", productOnline);
        data.put("orderTotal", (long) orders.size());
        data.put("orderPendingAudit", pendingAudit);
        data.put("orderPendingShip", pendingShip);
        data.put("todayOrderCount", todayOrderCount);
        data.put("todayAmount", money(todayAmount));
        data.put("returnPending", returnPending);
        data.put("stockAlerts", stockAlerts);
        data.put("trend", trend);
        data.put("statusDistribution", statusDistribution);
        data.put("topProducts", topProducts);
        return Result.ok(data);
    }

    private String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
