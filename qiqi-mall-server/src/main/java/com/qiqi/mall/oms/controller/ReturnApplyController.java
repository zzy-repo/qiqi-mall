package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.shared.common.PreviewResult;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
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
     *
     * <p>校验顺序：**① 参数/存在性（申请不存在 / 已处理 / 结论非法）→ ② 业务守卫（status=0）→ ③ 执行**。
     * 这几条属于「这次请求本身就不成立」，先回原因比别的都清楚。
     *
     * <p>并发：{@code return_apply} 表没有 version 列，靠**两道影响行数闸门**（都在本方法同一个
     * {@code @Transactional} 里，失败即整笔回滚）：
     * <ol>
     *   <li>申请行：{@code UPDATE ... WHERE id=? AND status=0}（把「待处理」守卫写进 SQL）影响 0 行
     *       → 另一个管理员刚审过 / 申请被删 → 409 {@value OptimisticLockGuard#MSG_VERSION_CONFLICT}</li>
     *   <li>订单行（order 表有 {@code @Version}）0 行 → 另一个管理员同时审了同一张单 / 订单被别的流程改过
     *       → 409 {@value OptimisticLockGuard#MSG_VERSION_CONFLICT}，申请状态一并回滚
     *       —— 绝不能出现「申请已通过、订单没退款、库存回补了两次」这种只回滚一半的结果</li>
     * </ol>
     */
    @Operation(summary = "审核退货申请（status 1 通过 / 2 拒绝）；?dryRun=true 只预演，返回将要发生什么")
    @PutMapping("/{id}")
    @Transactional
    public Result<?> update(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun,
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

        if (PreviewResult.isDryRun(dryRun)) {
            String willDo = target == 1
                    ? "将把退货申请 #" + exist.getId() + "（订单 " + orderSn + "）审核为「通过」：申请 待处理→已通过，"
                    + "订单 " + statusText(order == null ? null : order.getStatus()) + "→已退款，退款 ¥" + refund
                    + " 并记一条退款流水，同时回补 " + items.size() + " 条明细的总池库存（合计 " + totalQty + " 件）。"
                    : "将把退货申请 #" + exist.getId() + "（订单 " + orderSn + "）审核为「拒绝」：申请 待处理→已拒绝，"
                    + "订单 " + statusText(order == null ? null : order.getStatus()) + "→已付款，不回补库存、不退款。";
            return Result.ok(PreviewResult.of(willDo));
        }

        // ① 申请行：return_apply 没有 version 列，所以把「待处理」这条守卫**写成 UPDATE 的条件** ——
        //    `UPDATE return_apply SET status=? WHERE id=? AND status=0`。
        //    这是「读-改-写」的原子版：两个管理员同时审同一张申请时，InnoDB 用行锁把两次 UPDATE 排成先后，
        //    后一个的 WHERE status=0 不再命中 → 影响 0 行 → 409 + 「数据已被他人修改…请刷新后重试」。
        //    如果只用上面那句 if (exist.getStatus() != 0) 判断，两个请求都能通过（读到的都是 0），
        //    随后就会各退一次款、各回补一次库存 —— 那是真金白银的重复。
        //    0 行还有第二种原因：这条申请被并发删了 —— 同样不能继续（往不存在的申请上记账），一样 fail-closed。
        LambdaUpdateWrapper<ReturnApply> applyGuard = new LambdaUpdateWrapper<ReturnApply>()
                .eq(ReturnApply::getId, exist.getId())
                .eq(ReturnApply::getStatus, 0)
                .set(ReturnApply::getStatus, target);
        if (body.getDescription() != null) {
            applyGuard.set(ReturnApply::getDescription, body.getDescription());
        }
        int applyRows = returnApplyService.getBaseMapper().update(null, applyGuard);
        OptimisticLockGuard.requireAffected(applyRows);

        if (order == null) return Result.ok();
        if (target == 1) {
            // 通过：退款 + 回补库存
            order.setStatus(6);
            // ② 订单（有 version）：0 行 = 另一个管理员同时审了同一张单 / 订单被别的流程改过 →
            //    抛 409 **整笔回滚**（申请状态一起退回去）。绝不能「申请已通过、订单没退款」只回滚一半。
            int rows = orderService.getBaseMapper().updateById(order);
            OptimisticLockGuard.requireAffected(rows);
            paymentRecordService.record(order.getOrderSn(), order.getTotalAmount(),
                    PaymentRecord.TYPE_REFUND, order.getPayType() == null ? 0 : order.getPayType());
            items.forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getProductQuantity()));
        } else {
            // 拒绝：订单回到已付款
            order.setStatus(1);
            int rows = orderService.getBaseMapper().updateById(order);
            OptimisticLockGuard.requireAffected(rows);
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
