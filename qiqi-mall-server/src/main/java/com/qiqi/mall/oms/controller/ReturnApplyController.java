package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.shared.security.ConfirmCodeService;
import com.qiqi.mall.shared.security.ConfirmOps;
import com.qiqi.mall.shared.security.ConfirmRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "管理端-退货审核", description = "退货申请审核：通过=退款+回补库存，拒绝=订单回已付款")
@RestController
@RequestMapping("/admin/return-applies")
public class ReturnApplyController {

    private final ReturnApplyService returnApplyService;
    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final SkuService skuService;
    private final PaymentRecordService paymentRecordService;
    private final ConfirmCodeService confirmCodeService;

    public ReturnApplyController(ReturnApplyService returnApplyService, OrderService orderService,
                                 OrderItemService orderItemService, SkuService skuService,
                                 PaymentRecordService paymentRecordService,
                                 ConfirmCodeService confirmCodeService) {
        this.returnApplyService = returnApplyService;
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.skuService = skuService;
        this.paymentRecordService = paymentRecordService;
        this.confirmCodeService = confirmCodeService;
    }

    /** 列表：附带订单号，方便后台直接辨认（原本只能看到 orderId） */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) Integer status,
                                                  @RequestParam(required = false) Long orderId) {
        LambdaQueryWrapper<ReturnApply> query = new LambdaQueryWrapper<>();
        query.eq(status != null, ReturnApply::getStatus, status)
                .eq(orderId != null, ReturnApply::getOrderId, orderId)
                .orderByDesc(ReturnApply::getId);
        List<ReturnApply> rows = returnApplyService.list(query);

        Map<Long, String> orderSns = new LinkedHashMap<>();
        List<Long> orderIds = rows.stream().map(ReturnApply::getOrderId).distinct().toList();
        if (!orderIds.isEmpty()) {
            orderService.listByIds(orderIds).forEach(o -> orderSns.put(o.getId(), o.getOrderSn()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ReturnApply row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("orderId", row.getOrderId());
            item.put("orderSn", orderSns.getOrDefault(row.getOrderId(), ""));
            item.put("memberId", row.getMemberId());
            item.put("returnReason", row.getReturnReason());
            item.put("description", row.getDescription());
            item.put("status", row.getStatus());
            item.put("reasonId", row.getReasonId());
            result.add(item);
        }
        return Result.ok(result);
    }

    @PostMapping
    public Result<ReturnApply> create(@RequestBody ReturnApply body) {
        body.setId(null);
        returnApplyService.save(body);
        return Result.ok(body);
    }

    /**
     * 审核退货：status 0待处理 1通过 2拒绝。
     * 守门：仅「待处理」可审核；审核通过 → 订单退款中(5)→已退款(6) + 退款流水 + 库存回补；
     *       审核拒绝 → 订单退回已付款(1)。
     *
     * <p>确认码：?dryRun=true 预演；指纹 = 结论(1/2) + 订单金额 + 回补总件数 ——
     * 预演后订单金额或明细被改过，执行会被拒（避免「确认退 3999.98、实际退别的数」）。
     */
    @Operation(summary = "审核退货申请（status 1 通过 / 2 拒绝）；需带 confirmCode，?dryRun=true 只预演")
    @PutMapping("/{id}")
    @Transactional
    public Result<?> update(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun,
                            @RequestParam(required = false) String confirmCode,
                            @RequestBody ReturnApply body) {
        ReturnApply exist = returnApplyService.getById(id);
        if (exist == null) return Result.fail("退货申请不存在");
        if (exist.getStatus() != 0) return Result.fail("该申请已处理，不能重复审核");
        Integer target = body.getStatus();
        if (target == null || (target != 1 && target != 2)) return Result.fail("审核结果只能是 1通过 / 2拒绝");

        Order order = orderService.getById(exist.getOrderId());
        List<OrderItem> items = order == null ? List.of() : orderItemService.list(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        int totalQty = items.stream().mapToInt(OrderItem::getProductQuantity).sum();
        java.math.BigDecimal refund = order == null || order.getTotalAmount() == null
                ? java.math.BigDecimal.ZERO : order.getTotalAmount();
        String orderSn = order == null ? ("#" + exist.getOrderId()) : order.getOrderSn();

        String willDo = target == 1
                ? "将把退货申请 #" + exist.getId() + "（订单 " + orderSn + "）审核为「通过」：申请 待处理→已通过，"
                + "订单 " + statusText(order == null ? null : order.getStatus()) + "→已退款，退款 ¥" + refund
                + " 并记一条退款流水，同时回补 " + items.size() + " 条明细的总池库存（合计 " + totalQty + " 件）。"
                : "将把退货申请 #" + exist.getId() + "（订单 " + orderSn + "）审核为「拒绝」：申请 待处理→已拒绝，"
                + "订单 " + statusText(order == null ? null : order.getStatus()) + "→已付款，不回补库存、不退款。";

        if (ConfirmCodeService.isDryRun(dryRun)) {
            return Result.ok(confirmCodeService.preflight(willDo, ConfirmOps.RETURN_AUDIT, id,
                    target, refund, totalQty));
        }
        String error = confirmCodeService.verify(ConfirmRequest.pick(body.getConfirmCode(), confirmCode),
                ConfirmOps.RETURN_AUDIT, id, target, refund, totalQty);
        if (error != null) return Result.fail(error);

        exist.setStatus(target);
        if (body.getDescription() != null) exist.setDescription(body.getDescription());
        returnApplyService.updateById(exist);

        if (order == null) return Result.ok();
        if (target == 1) {
            // 通过：退款 + 回补库存
            order.setStatus(6);
            orderService.updateById(order);
            paymentRecordService.record(order.getOrderSn(), order.getTotalAmount(),
                    PaymentRecord.TYPE_REFUND, order.getPayType() == null ? 0 : order.getPayType());
            items.forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getProductQuantity()));
        } else {
            // 拒绝：订单回到已付款
            order.setStatus(1);
            orderService.updateById(order);
        }
        return Result.ok();
    }

    /** 订单状态中文名（预演文案用；局部小工具，避免为一句文案去依赖别的模块） */
    private static String statusText(Integer status) {
        String[] text = {"待付款", "已付款", "已发货", "已完成", "已关闭", "退款中", "已退款", "待审核", "待发货", "部分发货"};
        if (status == null || status < 0 || status >= text.length) return String.valueOf(status);
        return text[status];
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        returnApplyService.removeById(id);
        return Result.ok();
    }
}
