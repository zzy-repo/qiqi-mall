package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.PreviewResult;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.DeliveryFlowService;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.ReturnStatusService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.stock.StockLedgerContext;
import com.qiqi.mall.stock.entity.StockLedger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "管理端-退货审核", description = "退货申请审核：通过=退款+回补库存（仅退款中订单可通过），拒绝=订单交回包裹聚合（直发单回已付款）")
@RestController
@RequestMapping("/admin/return-applies")
public class ReturnApplyController {

    private static final Logger log = LoggerFactory.getLogger(ReturnApplyController.class);

    /**
     * 可以受理退货申请的订单状态：钱已付、货没走完（0 待付款 / 4 已关闭 / 6 已退款 都不在内）。
     * 5 退款中也在内 —— 同一张订单可以先后有多条申请（前一条被拒后又申请一次），此时状态不动。
     */
    private static final java.util.Set<Integer> RETURNABLE = java.util.Set.of(1, 2, 5, 7, 8, 9);

    private final ReturnApplyService returnApplyService;
    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final SkuService skuService;
    private final PaymentRecordService paymentRecordService;
    private final DeliveryFlowService deliveryFlowService;
    /** 订单状态写入的唯一入口（P2 第一步收敛：申请退货 5 退款中 / 审核通过 6 已退款 / 拒绝回 1 已付款） */
    private final OrderStatusService orderStatusService;
    /** 退货申请状态写入的唯一入口（含原来那句手写 UPDATE ... WHERE status=0 的原子守卫） */
    private final ReturnStatusService returnStatusService;

    public ReturnApplyController(ReturnApplyService returnApplyService, OrderService orderService,
                                 OrderItemService orderItemService, SkuService skuService,
                                 PaymentRecordService paymentRecordService,
                                 DeliveryFlowService deliveryFlowService,
                                 OrderStatusService orderStatusService,
                                 ReturnStatusService returnStatusService) {
        this.returnApplyService = returnApplyService;
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.skuService = skuService;
        this.paymentRecordService = paymentRecordService;
        this.deliveryFlowService = deliveryFlowService;
        this.orderStatusService = orderStatusService;
        this.returnStatusService = returnStatusService;
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

    /**
     * 后台代客登记退货申请。
     *
     * <p><b>登记一条「待处理」申请 = 退款流程开始</b>：与买家自助申请（{@code PortalReturnController#apply}）
     * 同一口径，订单必须同时进入「5 退款中」。旧实现只是裸 save —— 申请建了、订单状态一动不动，
     * 于是库里会出现「订单还是待审核/待发货，却挂着一条待处理退货申请」这种**任何正常流程都产不出**的组合；
     * 而 {@link #update} 现在要求「只有退款中才能审核通过」，这种申请点通过只会被拒（永远批不了）。
     * 所以这里补上联动，让「申请 待处理」与「订单 退款中」永远成对出现。
     *
     * <p>订单已是终局（4 已关闭 / 6 已退款）或状态本就不该退货时 → 直接拒绝建申请（不再制造脏数据）。
     * 非 0（待处理）的历史结论由管理端直接写入时不联动订单状态，保持原语义。
     */
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<ReturnApply> create(@RequestBody ReturnApply body) {
        body.setId(null);
        // 出生：不传状态 = 「待处理(0)」，走唯一入口（save 之前定初值，不产生 UPDATE）。
        // 传了 1 通过 / 2 拒绝 是「历史结论直接写入」，原样保留、不联动订单（保持原语义）。
        if (body.getStatus() == null) returnStatusService.init(body, 0);
        Order order = body.getOrderId() == null ? null : orderService.getById(body.getOrderId());
        if (order == null) return Result.fail("退货申请必须指向一张存在的订单");
        if (body.getStatus() == 0) {
            if (order.getStatus() == null || !RETURNABLE.contains(order.getStatus())) {
                return Result.fail("当前订单状态不可受理退货申请（当前：" + statusText(order.getStatus())
                        + "；只有已付款/已发货/待审核/待发货/部分发货的订单可以）");
            }
            if (order.getStatus() != 5) {          // 已经在退款中：状态不动（不白涨一次 version）
                // 状态写入统一走 OrderStatusService（同一条 UPDATE 里带 status_seq 自增与 version 自增）
                // 0 行 = 读到状态之后订单被别人改过 → 409 整笔回滚，申请也不会落库
                int rows = orderStatusService.change(order, 5, OrderStatusService.REASON_RETURN_APPLY, null);
                OptimisticLockGuard.requireAffected(rows);
            }
        }
        returnApplyService.save(body);
        return Result.ok(body);
    }

    /**
     * 审核退货：status 0待处理 1通过 2拒绝。
     * 守门：仅「待处理」可审核；审核通过 → 订单退款中(5)→已退款(6) + 退款流水 + 库存回补
     *       （**订单当前必须是 5 退款中**，否则拒绝并说明原因 —— 不许把已关闭(4) 之类的单写成已退款）；
     *       审核拒绝 → **有在途包裹时交回包裹聚合**（口径②：子单是事实源），无包裹的直发单才回已付款(1)。
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

        // ② 审核**通过**（写 6 已退款）必须先校验订单原状态：只允许从「5 退款中」进入 6。
        //    旧实现无条件 setStatus(6)：一张已经「4 已关闭」的订单照样会被写成「已退款」——
        //    钱没退、货没回、单子却显示退款完成，是最难查的那类错账。
        //    5 的唯一写者是 PortalReturnController#apply（买家申请退货），所以「当前必须是 5」
        //    等价于「这张订单确实有一笔申请在审批中」，这也正是本接口处理的那笔申请。
        //    **白名单只有 5**：代码里没有任何其它入口会把订单写成 6，也没有别的状态能合法地直接变 6。
        //    放在 dryRun 之前：预演与真做同一套守卫（预演不许说「将通过」而后真做被拒）。
        if (target == 1 && order != null && (order.getStatus() == null || order.getStatus() != 5)) {
            return Result.fail("只有「退款中」的订单可以审核通过为「已退款」：该订单当前是「"
                    + statusText(order.getStatus()) + "」，请先由买家提交退货申请把订单置为退款中(5)后再审核");
        }

        if (PreviewResult.isDryRun(dryRun)) {
            String willDo = target == 1
                    ? "将把退货申请 #" + exist.getId() + "（订单 " + orderSn + "）审核为「通过」：申请 待处理→已通过，"
                    + "订单 " + statusText(order == null ? null : order.getStatus()) + "→已退款，退款 ¥" + refund
                    + " 并记一条退款流水，同时回补 " + items.size() + " 条明细的总池库存（合计 " + totalQty + " 件）。"
                    : "将把退货申请 #" + exist.getId() + "（订单 " + orderSn + "）审核为「拒绝」：申请 待处理→已拒绝，"
                    + "订单 " + statusText(order == null ? null : order.getStatus()) + "→"
                    + (order != null && deliveryFlowService.activeDeliveryCount(order.getId()) > 0
                            ? "按包裹重新聚合（该订单有在途包裹，父单状态由子单推导，不再硬写已付款）"
                            : "已付款（该订单没有在途包裹，走直发路径）")
                    + "，不回补库存、不退款。";
            return Result.ok(PreviewResult.of(willDo));
        }

        // ① 申请行：return_apply 没有 version 列，所以把「待处理」这条守卫**写成 UPDATE 的条件** ——
        //    `UPDATE return_apply SET status=? WHERE id=? AND status=0`。
        //    这是「读-改-写」的原子版：两个管理员同时审同一张申请时，InnoDB 用行锁把两次 UPDATE 排成先后，
        //    后一个的 WHERE status=0 不再命中 → 影响 0 行 → 409 + 「数据已被他人修改…请刷新后重试」。
        //    如果只用上面那句 if (exist.getStatus() != 0) 判断，两个请求都能通过（读到的都是 0），
        //    随后就会各退一次款、各回补一次库存 —— 那是真金白银的重复。
        //    0 行还有第二种原因：这条申请被并发删了 —— 同样不能继续（往不存在的申请上记账），一样 fail-closed。
        //    现在这句 SQL 收敛进 ReturnStatusService#change：入口里同样是
        //    `SET status=?, status_seq=status_seq+1 WHERE id=? AND status=<读到的状态>` ——
        //    「待处理」这条守卫仍然写在 WHERE 里（不是只做一次 Java if），原子性一点没丢。
        String description = body.getDescription();
        int applyRows = returnStatusService.change(exist, target,
                target == 1 ? ReturnStatusService.REASON_APPROVE : ReturnStatusService.REASON_REJECT,
                description,
                description == null ? null : w -> w.set(ReturnApply::getDescription, description));
        OptimisticLockGuard.requireAffected(applyRows);

        if (order == null) return Result.ok();
        if (target == 1) {
            // 通过：退款 + 回补库存
            // ② 订单（有 version）：0 行 = 另一个管理员同时审了同一张单 / 订单被别的流程改过 →
            //    抛 409 **整笔回滚**（申请状态一起退回去）。绝不能「申请已通过、订单没退款」只回滚一半。
            int rows = orderStatusService.change(order, 6, OrderStatusService.REASON_RETURN_APPROVE, null);
            OptimisticLockGuard.requireAffected(rows);
            paymentRecordService.record(order.getOrderSn(), order.getTotalAmount(),
                    PaymentRecord.TYPE_REFUND, order.getPayType() == null ? 0 : order.getPayType());
            // 回补必须记台账（缺陷：这条入口原来是裸的 stock = stock + N，和「审核不通过 / 取消包裹」
            // 叠加就会给同一件货补第二次）。refType/refId 记到这张退货申请上 —— 同一张申请被重放，
            // 幂等键命中即跳过；不同入口补同一件货则由数量守恒夹紧。
            StockLedgerContext.Attribution outer = StockLedgerContext.open(order.getId(),
                    StockLedger.REASON_RETURN_REFUND, StockLedger.REF_TYPE_RETURN_APPLY, exist.getId());
            try {
                items.forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getProductQuantity()));
            } finally {
                StockLedgerContext.close(outer);
            }
        } else {
            // ④ 拒绝：不再无条件硬写 1 已付款 —— 口径②（子单是事实源）下，父单状态该由包裹推导。
            //    旧行为：订单的包裹可能已经出库/签收，硬写 1 就造出「已付款却挂着已签收包裹」的分叉，
            //    之后任何一次包裹回传按子单重新聚合又会把它改回 2/9（真实的状态倒退序列）。
            if (deliveryFlowService.activeDeliveryCount(order.getId()) > 0) {
                // 有在途包裹 → 交给聚合：包裹都出库 → 2 已发货；部分出库 → 9 部分发货；
                // 都签收 → 3 已完成；一张都没出库 → 回到 8 待发货（见 recomputeOrderStatus 内的说明）。
                // 必须走「拒绝专用入口」：普通 recomputeOrderStatus 会跳过 5 退款中，
                // 而这里正是唯一有权把订单从 5 拿走的地方。
                deliveryFlowService.recomputeOrderStatusAfterReturnReject(order.getId());
            } else {
                // 直发单（没有任何未取消的包裹）：没有子单事实可依，订单回到已付款 1（保持原行为）
                int rows = orderStatusService.change(order, 1, OrderStatusService.REASON_RETURN_REJECT, null);
                OptimisticLockGuard.requireAffected(rows);
            }
        }
        return Result.ok();
    }

    /** 订单状态中文名（预演文案用；局部小工具，避免为一句文案去依赖别的模块） */
    private static String statusText(Integer status) {
        String[] text = {"待付款", "已付款", "已发货", "已完成", "已关闭", "退款中", "已退款", "待审核", "待发货", "部分发货"};
        if (status == null || status < 0 || status >= text.length) return String.valueOf(status);
        return text[status];
    }

    /**
     * 删除退货申请。
     *
     * <p><b>删掉「待处理」申请必须顺手把订单从 5 退款中交回来</b>（缺陷：这是条真死路）。
     * 订单进 5 的唯一写者是「登记退货申请」；出 5 只有两条 —— 审核通过写 6、审核拒绝交回包裹聚合。
     * 而「删除待处理申请」这条入口原来只删申请、不动订单，于是订单卡在 5：
     * 聚合跳过 5（进行中的退款不许被包裹回传改写）、审核通过又要求「订单必须是 5 且这条申请待处理」
     * （申请已经没了）→ **没有任何写者能把它带出来**，管理员只能手工改库。
     *
     * <p>现在三个条件同时成立时才回退订单（都写进同一个 {@code @Transactional}，删申请与状态回退同生共死）：
     * <ol>
     *   <li>被删申请原状态是「待处理(0)」—— 已通过(1)/已拒绝(2) 是历史结论，删它不改变「退款是否还在进行中」；</li>
     *   <li>该订单当前 status == 5 退款中 —— 否则这单本来就没卡住，不该被这次删除顺手改状态；</li>
     *   <li>删完之后该订单**不再有**其它待处理申请 —— 还有别的申请在审批中，退款流程并没结束，状态仍是 5。</li>
     * </ol>
     * 满足时**不硬写某个固定状态**：调用
     * {@link DeliveryFlowService#recomputeOrderStatusAfterReturnApplyDeleted} 交回聚合推导
     * （有在途包裹就按子单聚合出 2/9/3/8；没有任何包裹的直发单回到 7 待审核 + 拆单标记归零），
     * 保持「子单是事实源」这唯一口径。
     */
    @Operation(summary = "删除退货申请（删掉待处理申请后订单交回包裹聚合，不再卡在退款中）")
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(@PathVariable Long id) {
        ReturnApply exist = returnApplyService.getById(id);
        if (exist == null) return Result.ok();          // 幂等：删一条不存在的申请不报错（保持原行为）
        boolean wasPending = exist.getStatus() != null && exist.getStatus() == 0;
        Long orderId = exist.getOrderId();
        returnApplyService.removeById(id);
        if (!wasPending || orderId == null) return Result.ok();

        Order order = orderService.getById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() != 5) return Result.ok();
        long pendingLeft = returnApplyService.count(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, orderId)
                .eq(ReturnApply::getStatus, 0));
        if (pendingLeft > 0) return Result.ok();        // 还有别的申请在审批中：退款流程没结束，状态不动

        Integer before = order.getStatus();
        deliveryFlowService.recomputeOrderStatusAfterReturnApplyDeleted(orderId);
        Order after = orderService.getById(orderId);
        log.info("删除待处理退货申请 #{}（订单 {}）后订单已回退：状态 {} → {}"
                        + "（该订单已无其它待处理申请，交回包裹聚合推导，不再硬写某个固定状态）",
                id, orderId, before, after == null ? null : after.getStatus());
        return Result.ok();
    }
}
