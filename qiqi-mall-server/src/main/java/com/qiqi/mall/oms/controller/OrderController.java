package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.common.OrderSnGenerator;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.oms.dto.OrderDetailDTO;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.ConfirmCodeService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.SplitOrderService;
import com.qiqi.mall.catalog.service.SkuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-订单", description = "订单查询、建单、审核（通过自动拆单 / 不通过回补库存）、发货与删除")
@RestController
@RequestMapping("/admin/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final ReturnApplyService returnApplyService;
    private final SplitOrderService splitOrderService;
    private final SkuService skuService;
    /** 操作确认码：split-preview 签发、audit-pass 强制校验 */
    private final ConfirmCodeService confirmCodeService;

    public OrderController(OrderService orderService, OrderItemService orderItemService,
                           ReturnApplyService returnApplyService, SplitOrderService splitOrderService,
                           SkuService skuService, ConfirmCodeService confirmCodeService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.returnApplyService = returnApplyService;
        this.splitOrderService = splitOrderService;
        this.skuService = skuService;
        this.confirmCodeService = confirmCodeService;
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
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Order body) {
        body.setId(id);
        body.setStatus(null);
        orderService.updateById(body);
        return Result.ok();
    }

    /**
     * 审核通过：待审核(7) → 待发货(8)，随后触发拆单生成发货单。
     *
     * <p>守卫顺序（每一步都是中文错误，前端可直接展示）：
     * <ol>
     *   <li>confirmCode 为空 → 缺确认码（说明调用方没走拆单预览）</li>
     *   <li>码消费不到 → 无效 / 已用过 / 已过期（一次性，取出即删）</li>
     *   <li>码里的 orderId 与路径 id 不一致 → 码是别的单的</li>
     *   <li>重算当前方案指纹不一致 → 预览之后仓库/库存/明细被改动，拒绝执行</li>
     *   <li>status != 7 → 原有守卫（状态流转只能从待审核出发）</li>
     * </ol>
     */
    @Operation(summary = "审核通过订单：需带 confirmCode（由 split-preview 签发），自动按仓库拆单并生成待下发发货单")
    @PostMapping("/{id}/audit-pass")
    public Result<java.util.List<com.qiqi.mall.oms.entity.DeliveryOrder>> auditPass(
            @PathVariable Long id,
            @RequestBody(required = false) AuditPassRequest req) {
        String confirmCode = req == null ? null : req.getConfirmCode();
        if (confirmCode == null || confirmCode.isBlank()) {
            return Result.fail("缺少确认码：请先预览拆单方案");
        }
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");

        ConfirmCodeService.Consumed consumed = confirmCodeService.consume(confirmCode);
        if (consumed == null) {
            // 文案带上真实有效期（默认 5 分钟，见 app.confirm-code.ttl-minutes），不写死数字
            long ttlMinutes = Math.max(1, confirmCodeService.ttlSeconds() / 60);
            return Result.fail("确认码无效、已使用或已过期（" + ttlMinutes + " 分钟），请重新预览拆单方案");
        }
        if (!id.equals(consumed.orderId())) {
            return Result.fail("确认码与订单不匹配，请重新预览");
        }
        // 重算指纹：方案不可复现（SKU 失去分仓库存等）同样视为「方案已变化」，一律拒绝
        String currentPlanHash;
        try {
            currentPlanHash = confirmCodeService.hashPlan(splitOrderService.recomputePlan(id));
        } catch (RuntimeException e) {
            log.warn("audit-pass 重算拆单方案失败，按方案已变化处理 orderId={}: {}", id, e.getMessage());
            return Result.fail("拆单方案已变化（仓库或库存被改动），拒绝执行，请重新预览");
        }
        if (!currentPlanHash.equals(consumed.planHash())) {
            return Result.fail("拆单方案已变化（仓库或库存被改动），拒绝执行，请重新预览");
        }

        if (order.getStatus() == null || order.getStatus() != 7) return Result.fail("只有待审核订单可以审核通过");
        order.setStatus(8);
        orderService.updateById(order);
        return Result.ok(splitOrderService.split(id));
    }

    /**
     * 拆单方案预览（dry-run）：只算不落库，守卫：仅 status=7 待审核。
     * 返回 {@code {plan:[{warehouseId, warehouseName, items:[...]}], confirmCode:"<uuid>", expiresInSec:300}}。
     *
     * <p>⚠️ 破坏性变更：返回结构从 {@code List<Map>} 变成 {@code Map}（多了 confirmCode / expiresInSec）。
     * confirmCode 就是 audit-pass 的必传票据 —— 只有真正算过方案的人才拿得到码，
     * 这样绕过前端直调 audit-pass 的请求一律被后端拒掉。
     */
    @Operation(summary = "拆单方案预览（dry-run，不落库）：返回方案 + 一次性确认码 confirmCode（5 分钟有效）")
    @PostMapping("/{id}/split-preview")
    public Result<java.util.Map<String, Object>> splitPreview(@PathVariable Long id) {
        java.util.List<java.util.Map<String, Object>> plan = splitOrderService.preview(id);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("plan", plan);
        body.put("confirmCode", confirmCodeService.issue(id, plan));
        body.put("expiresInSec", confirmCodeService.ttlSeconds());
        return Result.ok(body);
    }

    /**
     * 审核不通过：待审核(7) → 已关闭(4)，守卫：仅 status=7；联动回补库存（下单即扣模型）。
     *
     * <p>为什么不加确认码：本批口径是「只给有方案可绑的写操作加码」。audit-reject 不绑任何方案，
     * 后果单一且明确（关闭订单 + 回补库存，可预测、可逆性靠退货/重下单），
     * 硬塞一个码只会让「一键拒绝」多两次往返而没有增加任何防偷换能力。
     */
    @Operation(summary = "审核不通过订单：订单关闭并回补库存")
    @PostMapping("/{id}/audit-reject")
    @org.springframework.transaction.annotation.Transactional
    public Result<Void> auditReject(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        if (order.getStatus() == null || order.getStatus() != 7) return Result.fail("只有待审核订单可以审核不通过");
        order.setStatus(4);
        orderService.updateById(order);
        orderItemService.list(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, id))
                .forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getProductQuantity()));
        return Result.ok();
    }

    /** 发货：已付款(1) → 已发货(2)，守卫：仅 status=1（历史直发路径，拆单链走 audit-pass） */
    @Operation(summary = "订单发货")
    @PostMapping("/{id}/ship")
    public Result<Order> ship(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        if (order.getStatus() != 1) return Result.fail("只有已付款订单可以发货");
        order.setStatus(2);
        orderService.updateById(order);
        return Result.ok(order);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Result<Void> delete(@PathVariable Long id) {
        orderService.removeById(id);
        return Result.ok();
    }

    /** 审核通过的请求体：必传 confirmCode（split-preview 签发的一次性码） */
    public static class AuditPassRequest {
        private String confirmCode;

        public String getConfirmCode() {
            return confirmCode;
        }

        public void setConfirmCode(String confirmCode) {
            this.confirmCode = confirmCode;
        }
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
