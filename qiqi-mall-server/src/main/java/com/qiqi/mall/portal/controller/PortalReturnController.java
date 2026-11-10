package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.entity.ReturnReason;
import com.qiqi.mall.shared.security.CurrentUser;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.ReturnReasonService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** C 端申请退货 */
@RestController
@RequestMapping("/portal/returns")
public class PortalReturnController {

    private final ReturnApplyService returnApplyService;
    private final ReturnReasonService returnReasonService;
    private final OrderService orderService;

    public PortalReturnController(ReturnApplyService returnApplyService, ReturnReasonService returnReasonService,
                                  OrderService orderService) {
        this.returnApplyService = returnApplyService;
        this.returnReasonService = returnReasonService;
        this.orderService = orderService;
    }

    /** 退货原因字典 */
    @GetMapping("/reasons")
    public Result<List<ReturnReason>> reasons() {
        return Result.ok(returnReasonService.list());
    }

    @GetMapping
    public Result<List<ReturnApply>> list() {
        return Result.ok(returnApplyService.list(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getMemberId, CurrentUser.id()).orderByDesc(ReturnApply::getId)));
    }

    /** 提交退货申请，守卫：仅「已付款 / 已发货」订单可申请，且不能重复申请 */
    @PostMapping
    public Result<ReturnApply> apply(@RequestBody ApplyRequest request) {
        Order order = orderService.getById(request.getOrderId());
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
        if (order.getStatus() != 1 && order.getStatus() != 2) {
            return Result.fail("当前订单状态不可申请退货（仅已付款/已发货订单可申请）");
        }
        Long pending = returnApplyService.count(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, order.getId())
                .eq(ReturnApply::getStatus, 0));
        if (pending != null && pending > 0) return Result.fail("该订单已有待处理的退货申请");

        ReturnReason reason = returnReasonService.getById(request.getReasonId());
        if (reason == null) return Result.fail("请选择退货原因");

        ReturnApply apply = new ReturnApply();
        apply.setOrderId(order.getId());
        apply.setMemberId(CurrentUser.id());
        apply.setReasonId(reason.getId());
        apply.setReturnReason(reason.getName());
        apply.setDescription(request.getDescription());
        apply.setStatus(0);
        returnApplyService.save(apply);

        // 联动：订单进入「退款中」
        order.setStatus(5);
        orderService.updateById(order);
        return Result.ok(apply);
    }

    public static class ApplyRequest {
        private Long orderId;
        private Long reasonId;
        private String description;

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getReasonId() { return reasonId; }
        public void setReasonId(Long reasonId) { this.reasonId = reasonId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
