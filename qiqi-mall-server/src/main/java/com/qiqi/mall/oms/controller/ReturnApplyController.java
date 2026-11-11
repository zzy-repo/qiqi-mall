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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public ReturnApplyController(ReturnApplyService returnApplyService, OrderService orderService,
                                 OrderItemService orderItemService, SkuService skuService,
                                 PaymentRecordService paymentRecordService) {
        this.returnApplyService = returnApplyService;
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.skuService = skuService;
        this.paymentRecordService = paymentRecordService;
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
     */
    @Operation(summary = "审核退货申请（status 1 通过 / 2 拒绝；仅待处理可审）")
    @PutMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public Result<Void> update(@PathVariable Long id, @RequestBody ReturnApply body) {
        ReturnApply exist = returnApplyService.getById(id);
        if (exist == null) return Result.fail("退货申请不存在");
        if (exist.getStatus() != 0) return Result.fail("该申请已处理，不能重复审核");
        Integer target = body.getStatus();
        if (target == null || (target != 1 && target != 2)) return Result.fail("审核结果只能是 1通过 / 2拒绝");

        exist.setStatus(target);
        if (body.getDescription() != null) exist.setDescription(body.getDescription());
        returnApplyService.updateById(exist);

        Order order = orderService.getById(exist.getOrderId());
        if (order == null) return Result.ok();
        if (target == 1) {
            // 通过：退款 + 回补库存
            order.setStatus(6);
            orderService.updateById(order);
            paymentRecordService.record(order.getOrderSn(), order.getTotalAmount(),
                    PaymentRecord.TYPE_REFUND, order.getPayType() == null ? 0 : order.getPayType());
            orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getOrderId, order.getId()))
                    .forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getProductQuantity()));
        } else {
            // 拒绝：订单回到已付款
            order.setStatus(1);
            orderService.updateById(order);
        }
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        returnApplyService.removeById(id);
        return Result.ok();
    }
}
