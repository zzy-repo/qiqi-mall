package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.entity.ReturnReason;
import com.qiqi.mall.shared.security.CurrentUser;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.ReturnReasonService;
import com.qiqi.mall.oms.service.ReturnStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** C 端申请退货 */
@Tag(name = "买家端-申请退货", description = "买家提交退货申请，仅已付款/已发货可申请且不能重复申请")
@RestController
@RequestMapping("/portal/returns")
public class PortalReturnController {

    private final ReturnApplyService returnApplyService;
    private final ReturnReasonService returnReasonService;
    private final OrderService orderService;
    /** 订单状态写入的唯一入口（P2 第一步收敛：申请退货把订单写成 5 退款中） */
    private final OrderStatusService orderStatusService;
    /** 退货申请状态写入的唯一入口（出生：新建申请 = 待处理(0)） */
    private final ReturnStatusService returnStatusService;

    public PortalReturnController(ReturnApplyService returnApplyService, ReturnReasonService returnReasonService,
                                  OrderService orderService, OrderStatusService orderStatusService,
                                  ReturnStatusService returnStatusService) {
        this.returnApplyService = returnApplyService;
        this.returnReasonService = returnReasonService;
        this.orderService = orderService;
        this.orderStatusService = orderStatusService;
        this.returnStatusService = returnStatusService;
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
    @Operation(summary = "买家提交退货申请")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
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
        // 出生：申请初始状态「待处理(0)」也走唯一入口（save 之前定初值，不产生 UPDATE）
        returnStatusService.init(apply, 0);
        returnApplyService.save(apply);
        // 出生台账（from_status = NULL + order_id 冗余）：save 之后才拿得到申请 id
        returnStatusService.recordBirth(apply, ReturnStatusService.REASON_CREATE, "买家自助申请");

        // 联动：订单进入「退款中」（状态写入统一走 OrderStatusService）
        // 0 行 = 读出来之后订单被别人改过（管理员刚好发货/关闭、或另一次并发退货）→ 409 整笔回滚，
        // 连刚插入的退货申请一起撤回 —— 否则会留下一条「申请已建、订单没进退款中」的待处理单，
        // 反而把买家自己卡在「该订单已有待处理的退货申请」上，再也不能申请。
        // ref = 刚落的这张申请：订单台账里这一行写得出「是哪个申请把订单推进退款中的」
        int rows = orderStatusService.change(order, 5, OrderStatusService.REASON_RETURN_APPLY, null,
                null, ReasonCodes.REF_RETURN_APPLY, apply.getId());
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_ORDER_STATE_CHANGED);
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
