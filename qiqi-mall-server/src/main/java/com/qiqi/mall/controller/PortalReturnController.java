package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Order;
import com.qiqi.mall.entity.ReturnApply;
import com.qiqi.mall.entity.ReturnReason;
import com.qiqi.mall.security.CurrentUser;
import com.qiqi.mall.service.OrderService;
import com.qiqi.mall.service.ReturnApplyService;
import com.qiqi.mall.service.ReturnReasonService;
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

    /** 提交退货申请 */
    @PostMapping
    public Result<ReturnApply> apply(@RequestBody ApplyRequest request) {
        Order order = orderService.getById(request.getOrderId());
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");
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
