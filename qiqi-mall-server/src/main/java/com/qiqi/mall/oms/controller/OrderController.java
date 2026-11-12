package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.common.OrderSnGenerator;
import com.qiqi.mall.shared.common.PreviewResult;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.oms.dto.OrderDetailDTO;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.SplitOrderService;
import com.qiqi.mall.catalog.service.SkuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Tag(name = "管理端-订单", description = "订单查询、建单、审核（通过自动拆单 / 不通过回补库存）、发货与删除")
@RestController
@RequestMapping("/admin/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final ReturnApplyService returnApplyService;
    private final SplitOrderService splitOrderService;
    private final SkuService skuService;
    private final DeliveryOrderService deliveryOrderService;

    public OrderController(OrderService orderService, OrderItemService orderItemService,
                           ReturnApplyService returnApplyService, SplitOrderService splitOrderService,
                           SkuService skuService, DeliveryOrderService deliveryOrderService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.returnApplyService = returnApplyService;
        this.splitOrderService = splitOrderService;
        this.skuService = skuService;
        this.deliveryOrderService = deliveryOrderService;
    }

    /**
     * 订单列表（分页 + 按状态/订单号筛选）。
     * 订单量大时必须分页：size 夹在 1~50，按主键倒序。
     */
    @GetMapping
    public Result<Page<Order>> list(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(required = false) String orderSn) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 50);
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<>();
        query.eq(status != null, Order::getStatus, status)
                .like(orderSn != null && !orderSn.isBlank(), Order::getOrderSn, orderSn)
                .orderByDesc(Order::getId);
        return Result.ok(orderService.page(new Page<>(safePage, safeSize), query));
    }

    /** 订单详情（含明细与退货申请，聚合展示） */
    @GetMapping("/{id}")
    public Result<OrderDetailDTO> detail(@PathVariable Long id) {
        OrderDetailDTO dto = new OrderDetailDTO();
        dto.setOrder(orderService.getById(id));
        dto.setItems(orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, id).orderByAsc(OrderItem::getId)));
        dto.setReturnApplies(returnApplyService.list(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, id).orderByAsc(ReturnApply::getId)));
        return Result.ok(dto);
    }

    /** 创建订单（含明细，快照由前端传入） */
    @Operation(summary = "后台新增订单")
    @PostMapping
    @Transactional
    public Result<Order> create(@RequestBody OrderCreateRequest request) {
        Order order = request.getOrder();
        order.setId(null);
        order.setVersion(0);            // 新建即 0 版：响应里带回 version，前端拿到就能直接编辑
        if (order.getOrderSn() == null || order.getOrderSn().isBlank()) {
            order.setOrderSn(OrderSnGenerator.next());
        }
        List<OrderItem> items = request.getItems();
        // 总金额以明细为准（快照价 × 数量）
        if (items != null && !items.isEmpty()) {
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (OrderItem it : items) {
                java.math.BigDecimal price = it.getProductPrice() == null ? java.math.BigDecimal.ZERO : it.getProductPrice();
                int qty = it.getProductQuantity() == null ? 1 : it.getProductQuantity();
                total = total.add(price.multiply(java.math.BigDecimal.valueOf(qty)));
            }
            order.setTotalAmount(total);
        }
        orderService.save(order);
        if (items != null) {
            items.forEach(item -> {
                item.setId(null);
                item.setOrderId(order.getId());
            });
            orderItemService.saveBatch(items);
        }
        return Result.ok(order);
    }

    /**
     * 更新订单基础信息（收货人/地址等）。
     * 守卫：status 不通过本接口变更——统一置空，状态只能经专用接口 + 前置校验流转。
     *
     * <p>**乐观锁，fail-closed**：必须带 {@code body.version}（列表/详情都会返回）。
     * 缺版本号 → code=400；读之后被别人改过（UPDATE 影响行数 0）→ code=409，
     * 收货人/地址一个字段都不会被覆盖。注意 status 在这里被置空、不参与 SET，
     * 所以「订单状态」不会被本接口的乐观锁波及（状态只走专用接口）。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Order body) {
        body.setId(id);
        body.setStatus(null);
        OptimisticLockGuard.LockError lockError = OptimisticLockGuard.checkVersion(body.getVersion());
        if (lockError != null) return Result.fail(lockError.code(), lockError.message());
        int rows = orderService.getBaseMapper().updateById(body);
        lockError = OptimisticLockGuard.checkAffected(rows);
        if (lockError != null) return Result.fail(lockError.code(), lockError.message());
        return Result.ok();
    }

    /**
     * 审核通过：待审核(7) → 待发货(8)，随后触发拆单生成发货单。
     *
     * <p>校验顺序（严格，业务守卫不许抢跑）：
     * **① 订单存在 → ② 业务守卫（status==7）→ ③ 执行**；{@code ?dryRun=true} 时同样先过 ①②，
     * 再把「将要发生什么」用中文说清楚（{@code {willDo}}），不落库、不发凭证。
     *
     * <p>并发：本接口不带客户端 {@code version}（改的是订单状态，走的是状态守卫这层闸门）；
     * 通用编辑接口 {@code PUT /admin/orders/{id}} 才有 version 乐观锁。
     */
    @Operation(summary = "审核通过订单（自动拆单）：?dryRun=true 只预演，返回将要发生什么")
    @PostMapping("/{id}/audit-pass")
    @Transactional
    public Result<?> auditPass(@PathVariable Long id,
                               @RequestParam(required = false) Boolean dryRun) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        if (order.getStatus() == null || order.getStatus() != 7) return Result.fail("只有待审核订单可以审核通过");

        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(auditPassWillDo(order, splitOrderService.preview(id))));
        }
        order.setStatus(8);
        orderService.updateById(order);
        return Result.ok(splitOrderService.split(id));
    }

    /**
     * 拆单方案预览（dry-run，不落库）：守卫同 {@link #auditPass}（仅 status=7 待审核）。
     *
     * <p>返回 {@code {willDo, plan:[{warehouseId, warehouseName, items:[...]}]}} ——
     * 比 {@code ?dryRun=true} 多了结构化的 {@code plan}，后台「查看拆单方案」抽屉用它渲染；
     * 两者都只算不落库，都不签发凭证。
     */
    @Operation(summary = "拆单方案预览（dry-run，不落库）：方案 + 将要发生什么")
    @PostMapping("/{id}/split-preview")
    public Result<Map<String, Object>> splitPreview(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        if (order.getStatus() == null || order.getStatus() != 7) return Result.fail("只有待审核订单可以审核通过");
        List<Map<String, Object>> plan = splitOrderService.preview(id);
        Map<String, Object> data = PreviewResult.of(auditPassWillDo(order, plan));
        data.put("plan", plan);
        return Result.ok(data);
    }

    /** 审核通过的预演文案：说清拆成几包、每包哪个仓几条明细 */
    private String auditPassWillDo(Order order, List<Map<String, Object>> plan) {
        List<String> packages = new ArrayList<>();
        for (Map<String, Object> pkg : plan) {
            Object items = pkg.get("items");
            int lines = items instanceof List<?> list ? list.size() : 0;
            packages.add(pkg.get("warehouseName") + " " + lines + " 条明细");
        }
        return "将把订单 " + order.getOrderSn() + "（id=" + order.getId()
                + "）审核通过：状态 待审核→待发货，并按方案生成 " + plan.size()
                + " 个发货单（" + String.join("、", packages) + "）。";
    }

    /**
     * 审核不通过：待审核(7) → 已关闭(4)，守卫：仅 status=7；联动回补库存（下单即扣模型）。
     *
     * <p>校验顺序：**① 存在性 → ② 业务守卫（status==7）→ ③ 执行**；
     * {@code ?dryRun=true} 同样先过守卫，再返回「将回补哪些 SKU 多少件」的中文描述。
     */
    @Operation(summary = "审核不通过订单（关闭并回补库存）：?dryRun=true 只预演，返回将要发生什么")
    @PostMapping("/{id}/audit-reject")
    @Transactional
    public Result<?> auditReject(@PathVariable Long id,
                                 @RequestParam(required = false) Boolean dryRun) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        if (order.getStatus() == null || order.getStatus() != 7) return Result.fail("只有待审核订单可以审核不通过");

        List<OrderItem> items = itemsOf(id);
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(auditRejectWillDo(order, items)));
        }
        order.setStatus(4);
        orderService.updateById(order);
        items.forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getProductQuantity()));
        return Result.ok();
    }

    private String auditRejectWillDo(Order order, List<OrderItem> items) {
        List<String> restore = skuQtyLines(items);
        return "将把订单 " + order.getOrderSn() + "（id=" + order.getId()
                + "）审核不通过：状态 待审核→已关闭，并把 " + items.size() + " 条明细的总池库存回补（"
                + describeSkuQty(restore) + "，合计 " + totalQty(items) + " 件）。";
    }

    /**
     * 发货：已付款(1) → 已发货(2)，守卫：仅 status=1（历史直发路径，拆单链走 audit-pass）。
     *
     * <p>校验顺序：**① 存在性 → ② 业务守卫（status==1）→ ③ 执行**；
     * {@code ?dryRun=true} 先过守卫再返回中文描述（含明细条数与现有发货单数，让人看清走的是直发路径）。
     */
    @Operation(summary = "订单发货（直发路径，不拆单）：?dryRun=true 只预演，返回将要发生什么")
    @PostMapping("/{id}/ship")
    @Transactional
    public Result<?> ship(@PathVariable Long id,
                          @RequestParam(required = false) Boolean dryRun) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        if (order.getStatus() == null || order.getStatus() != 1) return Result.fail("只有已付款订单可以发货");

        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(shipWillDo(order)));
        }
        order.setStatus(2);
        orderService.updateById(order);
        return Result.ok(order);
    }

    private String shipWillDo(Order order) {
        return "将把订单 " + order.getOrderSn() + "（id=" + order.getId()
                + "）标记为已发货：状态 " + statusText(order.getStatus()) + "→已发货（" + itemCountOf(order.getId())
                + " 条明细，走直发路径不拆单；当前该订单 " + countDeliveries(order.getId()) + " 个发货单）。";
    }

    /**
     * 删除订单（级联删明细/发货单，不可恢复）。
     *
     * <p>校验顺序：**① 存在性 → ② 业务守卫（无额外状态限制）→ ③ 执行**；
     * {@code ?dryRun=true} 返回「要级联删掉多少明细、多少发货单」的中文描述。
     */
    @Operation(summary = "删除订单（级联）：?dryRun=true 只预演，返回将要发生什么")
    @DeleteMapping("/{id}")
    @Transactional
    public Result<?> delete(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");

        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(deleteWillDo(order)));
        }
        orderService.removeById(id);
        return Result.ok();
    }

    private String deleteWillDo(Order order) {
        return "将删除订单 " + order.getOrderSn() + "（id=" + order.getId() + "，当前状态 "
                + statusText(order.getStatus()) + "）：级联删除 " + itemCountOf(order.getId()) + " 条明细、"
                + countDeliveries(order.getId()) + " 个发货单（含包裹明细与物流记录），删除后不可恢复。";
    }

    // ------------------------------------------------------------------ 小工具

    private List<OrderItem> itemsOf(Long orderId) {
        return orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).orderByAsc(OrderItem::getId));
    }

    private long itemCountOf(Long orderId) {
        return orderItemService.count(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
    }

    private static int totalQty(List<OrderItem> items) {
        return items.stream().mapToInt(i -> i.getProductQuantity() == null ? 0 : i.getProductQuantity()).sum();
    }

    /** 该订单现有发货单数（删除订单的 willDo 要说明会级联删掉几个包裹） */
    private long countDeliveries(Long orderId) {
        return deliveryOrderService.count(new LambdaQueryWrapper<DeliveryOrder>().eq(DeliveryOrder::getOrderId, orderId));
    }

    /**
     * 回补明细摘要：按 skuId 汇总件数（TreeMap 天然有序）→ {@code ["skuId:qty", ...]}。
     * 预演文案从这里取，避免「明细 3 条、回补台账却算成 2 件」这类对不上的话。
     */
    private static List<String> skuQtyLines(List<OrderItem> items) {
        java.util.TreeMap<Long, Integer> bySku = new java.util.TreeMap<>();
        for (OrderItem item : items) {
            bySku.merge(item.getProductSkuId(), item.getProductQuantity() == null ? 0 : item.getProductQuantity(),
                    Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        bySku.forEach((sku, qty) -> lines.add(sku + ":" + qty));
        return lines;
    }

    /** {@code ["3:2", "7:1"]} → "sku 3 回补 2 件、sku 7 回补 1 件"（willDo 用） */
    private static String describeSkuQty(List<String> skuQtyLines) {
        if (skuQtyLines.isEmpty()) return "无明细可回补";
        List<String> parts = new ArrayList<>();
        for (String line : skuQtyLines) {
            String[] kv = line.split(":");
            parts.add("sku " + kv[0] + " 回补 " + kv[1] + " 件");
        }
        return String.join("、", parts);
    }

    /** 订单状态中文名（预演文案用） */
    private static final String[] STATUS_TEXT =
            {"待付款", "已付款", "已发货", "已完成", "已关闭", "退款中", "已退款", "待审核", "待发货", "部分发货"};

    private static String statusText(Integer status) {
        if (status == null || status < 0 || status >= STATUS_TEXT.length) return String.valueOf(status);
        return STATUS_TEXT[status];
    }

    /** 创建/更新订单时的请求体：订单 + 明细 */
    public static class OrderCreateRequest {
        private Order order;
        private List<OrderItem> items;

        public Order getOrder() {
            return order;
        }

        public void setOrder(Order order) {
            this.order = order;
        }

        public List<OrderItem> getItems() {
            return items;
        }

        public void setItems(List<OrderItem> items) {
            this.items = items;
        }
    }
}
