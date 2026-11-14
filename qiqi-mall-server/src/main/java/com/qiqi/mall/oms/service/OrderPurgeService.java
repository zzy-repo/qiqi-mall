package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.stock.StockLedgerContext;
import com.qiqi.mall.stock.entity.StockLedger;
import com.qiqi.mall.stock.mapper.StockLedgerMapper;
import com.qiqi.mall.stock.service.StockLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单删除：把 <b>外键约束翻译成业务语义</b>（不靠 CASCADE 静默销毁证据）。
 *
 * <p><b>为什么不改成 ON DELETE CASCADE</b>：{@code delivery_logistics}（物流回传）、
 * {@code return_apply}（退货申请）、{@code payment_record}（支付流水）三条链都是
 * <b>对账与审计证据</b>。把 NO ACTION 改成 CASCADE（或依赖已有的 CASCADE），删订单就会
 * 顺手、静默地把运单号、退货申请、支付/退款流水一起销毁 —— 单子没了、钱的事也说不清了。
 * 电商里「删订单」本应是关闭/作废，而不是物理删除。所以这里：
 * <ol>
 *   <li><b>有物流记录 / 有退货申请 / 有已出库但物流未回传的包裹 → 拒删</b>，并用中文说清是哪几条
 *       （含运单号、已出库包裹张数），告诉调用方该走「作废物流」「等签收」或「关闭订单」；</li>
 *   <li><b>确实可删的</b> → 按依赖顺序<b>显式</b>逐层删，每层判影响行数（0 行不等于成功）；</li>
 *   <li>{@code payment_record} 按 {@code order_sn} 字符串关联、没有外键，所以必须显式清理，
 *       且删之前先往 {@code admin_audit_log} 写一条 {@code PAYMENT_PURGE}（订单号 / 条数 / 金额合计 /
 *       每条的 {@code tradeNo·type·amount}）留证 —— 流水删了，「这单收过几次钱」在台账七类的
 *       「审计」那一支里仍然查得到（{@link #purgePaymentRecords}）。</li>
 *   <li>{@code stock_ledger}（库存台账）同样**故意不加外键** —— 台账是「同一件货有没有被补两次」
 *       的审计证据，不该被 CASCADE 静默带走。副作用是硬删订单会留下孤儿台账行，
 *       所以在删主单之前显式按 {@code order_id} 清掉，且删前先打一条 warn 日志留摘要
 *       （行数 + 各 reason 的件数合计）。<b>这一支只有应用日志、没有 {@code admin_audit_log} 行</b> ——
 *       与支付流水那一支不同，别把两者当成同一个口径。</li>
 *   <li><b>删台账之前必须先把这单扣掉的货还回去</b>（{@link #settleStockLedger}）：台账一行行记的是
 *       「这单进出过多少件」，删了账却不还货，库存就随删单**永久消失**（「扣了、账也抹了」）。
 *       回补按台账**净额**算，且走库存接口（不裸 UPDATE）。</li>
 * </ol>
 *
 * <p><b>依赖顺序</b>（与库里的引用方向相反，先叶子后父）：
 * 守卫 →（库存回补 + 库存台账）→ 物流 → 退货申请 → 发货单明细 → 发货单 → 订单明细 → 支付流水 → 订单。
 *
 * <p>库存回补必须排在**最前面**（就在守卫之后）：数量守恒的基准是 {@code order_item.product_quantity}，
 * 净额又只能从 {@code stock_ledger} 算出来 —— 走到后面这两样都已经被删掉了。
 *
 * <p>整个 {@link #purge} 跑在调用方（{@code OrderController#delete}）的事务里：
 * 任一层失败即整笔回滚 —— 部分成功比失败更糟（会留下删了一半的订单图）。
 */
@Service
public class OrderPurgeService {

    private static final Logger log = LoggerFactory.getLogger(OrderPurgeService.class);

    /** 拒删时的行动建议：有物流记录 */
    private static final String HINT_LOGISTICS = "；可在履约页先作废物流，或改为关闭订单。";
    /** 拒删时的行动建议：有已出库包裹（货已离开仓库，只能等签收或关单） */
    private static final String HINT_OUTBOUND = "；请先完成签收或改为关闭订单。";
    /** 拒删时的行动建议：有退货申请 */
    private static final String HINT_RETURN = "；退货申请是退款与对账的证据，不能随订单一并删除，请改为关闭订单。";
    /**
     * 删单回补的台账原因（只在本次事务内存在：紧接着该订单的台账行会被整体清掉，
     * 所以这个 reason 永远不会留在库里，仅为了满足库存接口「无上下文不许动库存」的约定）。
     */
    private static final String REASON_ORDER_PURGE_RESTORE = "ORDER_PURGE_RESTORE";

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    private final ReturnApplyService returnApplyService;
    private final PaymentRecordService paymentRecordService;
    private final StockLedgerMapper stockLedgerMapper;
    private final SkuService skuService;
    private final StockLedgerService stockLedgerService;
    /** 后台操作审计（{@code admin_audit_log}）：删单会**物理销毁**支付流水，那条证据只能落在这里 */
    private final AdminAuditWriter auditWriter;

    public OrderPurgeService(OrderService orderService, OrderItemService orderItemService,
                             DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                             DeliveryLogisticsService deliveryLogisticsService,
                             ReturnApplyService returnApplyService,
                             PaymentRecordService paymentRecordService,
                             StockLedgerMapper stockLedgerMapper,
                             SkuService skuService,
                             StockLedgerService stockLedgerService,
                             AdminAuditWriter auditWriter) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.returnApplyService = returnApplyService;
        this.paymentRecordService = paymentRecordService;
        this.stockLedgerMapper = stockLedgerMapper;
        this.skuService = skuService;
        this.stockLedgerService = stockLedgerService;
        this.auditWriter = auditWriter;
    }

    // ------------------------------------------------------------------ 只读：能不能删

    /**
     * 删前检查：返回 {@code null} 表示可以删；否则返回<b>给用户看的中文原因</b>
     * （含运单号、退货申请条数、已出库包裹张数，以及该走哪条路）。
     *
     * <p>这条检查同时给「预演（?dryRun=true）」和「真删」用 —— 两处口径一致，
     * 不会出现「预演说能删、真删被外键拦住」。
     */
    public String blockReason(Order order) {
        List<DeliveryLogistics> logistics = logisticsOf(order.getId());
        long returns = returnApplyService.count(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, order.getId()));
        long outboundParcels = countOutboundParcels(order.getId());
        if (logistics.isEmpty() && returns == 0 && outboundParcels == 0) return null;

        // 已出库但物流还没回传：货其实已经离开商家，删单会把它回补进总池（口径上偏乐观）。
        // 只有这一条理由时用需求钉死的原文（最容易被误删、也最容易被误判成「能删」的那一格）；
        // 与物流/退货并存时并进下面统一的「有 X 和 Y，无法删除」句式，一次说清全部代价。
        if (outboundParcels > 0 && logistics.isEmpty() && returns == 0) {
            return "该订单有 " + outboundParcels + " 个包裹已出库（货已离开仓库），不能删除；"
                    + "请先完成签收或改为关闭订单〔订单 " + order.getOrderSn() + "，id=" + order.getId() + "〕";
        }

        List<String> parts = new ArrayList<>();
        if (!logistics.isEmpty()) {
            List<String> tracks = new ArrayList<>();
            for (DeliveryLogistics row : logistics) {
                tracks.add(row.getTrackNo() == null ? ("物流#" + row.getId()) : row.getTrackNo());
            }
            parts.add(logistics.size() + " 条物流记录（运单号 " + String.join("、", tracks) + "）");
        }
        if (returns > 0) {
            parts.add(returns + " 条退货申请");
        }
        if (outboundParcels > 0) {
            parts.add(outboundParcels + " 个包裹已出库（货已离开仓库）");
        }
        String hint = !logistics.isEmpty() ? HINT_LOGISTICS
                : outboundParcels > 0 ? HINT_OUTBOUND : HINT_RETURN;
        return "该订单 " + order.getOrderSn() + "（id=" + order.getId() + "）有 "
                + String.join("和 ", parts) + "，无法删除" + hint;
    }

    /**
     * 该订单名下**未取消且已出库**（{@code status >= 已出库(3)}）的包裹张数 —— 拒删守卫的第 3 条依据。
     *
     * <p>与 {@code OrderController#countOutboundDeliveries}（ship 闸门）同一个口径：
     * 已取消(9) 不算（取消时货已经回补过），其余 3/4/5（已出库/运输中/已签收）都算
     * 「货已离开仓库」。
     */
    public long countOutboundParcels(Long orderId) {
        return deliveryOrderService.count(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId)
                .ne(DeliveryOrder::getStatus, DeliveryOrder.CANCELLED)
                .ge(DeliveryOrder::getStatus, DeliveryOrder.OUTBOUND));
    }

    /** 该订单名下的物流记录（跨所有发货单）—— 预演文案与拒删原因共用这一份口径 */
    public List<DeliveryLogistics> logisticsOf(Long orderId) {
        List<Long> deliveryIds = deliveryIdsOf(orderId);
        if (deliveryIds.isEmpty()) return List.of();
        return deliveryLogisticsService.list(new LambdaQueryWrapper<DeliveryLogistics>()
                .in(DeliveryLogistics::getDeliveryId, deliveryIds)
                .orderByAsc(DeliveryLogistics::getId));
    }

    /** 该订单名下的支付流水（按 order_sn 字符串关联，没有外键）—— 预演文案用 */
    public List<PaymentRecord> paymentRecordsOf(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) return List.of();
        return paymentRecordService.list(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderSn, orderSn).orderByAsc(PaymentRecord::getId));
    }

    public long countReturnApplies(Long orderId) {
        return returnApplyService.count(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, orderId));
    }

    // ------------------------------------------------------------------ 写：按依赖顺序显式删

    /**
     * 按依赖顺序**显式**删除整张订单图，每层判影响行数。
     *
     * <p>必须由调用方提供事务（{@code @Transactional}）：这些步骤要么全成，要么全回滚。
     * 每层都先数「应该删掉多少」，再删，再比对实际影响行数 —— <b>0 行不等于成功</b>
     * （本项目踩过的坑：UPDATE/DELETE 影响 0 行被当成成功，留下删了一半的数据）。
     * 行数对不上直接抛异常回滚，绝不继续往下删。
     *
     * <p><b>顺序（不可换）</b>：① 守卫 → ② 库存回补 + 库存台账 → ③ 物流 → ④ 退货申请 →
     * ⑤ 发货单明细 → ⑥ 发货单 → ⑦ 订单明细 → ⑧ 支付流水 → ⑨ 订单本体。
     * 守卫必须在回补之前（有物流 / 有已出库包裹 = 货已经出过库，删单回补就不成立了）；
     * 回补必须在删明细/台账之前（净额要从台账算、数量守恒基准在订单明细上）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(Order order) {
        Long orderId = order.getId();

        // ① 守卫先行（与 Controller 里那道是同一个口径，这里再判一次是 fail-closed：
        //    即便将来有人绕过 Controller 直接调 purge，也不会有「先补货、再发现不该删」的半截账）
        String blockReason = blockReason(order);
        if (blockReason != null) throw new IllegalStateException(blockReason);

        // ② 库存回补 + 库存台账清理（先补后删；日志里带上回补件数）
        settleStockLedger(order);

        // ③ 物流（引用发货单，NO ACTION —— 不先删它，发货单一步就过不去）
        List<Long> deliveryIds = deliveryIdsOf(orderId);
        long logistics = countLogistics(deliveryIds);
        int deletedLogistics = deliveryIds.isEmpty() ? 0
                : deliveryLogisticsService.getBaseMapper().delete(new LambdaQueryWrapper<DeliveryLogistics>()
                        .in(DeliveryLogistics::getDeliveryId, deliveryIds));
        requireExactly("物流记录", logistics, deletedLogistics);

        // ④ 退货申请（审计证据；能走到这里的都是 0 条，blockReason 已经拦过）
        long returns = countReturnApplies(orderId);
        int deletedReturns = returnApplyService.getBaseMapper().delete(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, orderId));
        requireExactly("退货申请", returns, deletedReturns);

        // ⑤ 发货单明细（引用发货单）
        long deliveryItems = deliveryIds.isEmpty() ? 0
                : deliveryItemService.count(new LambdaQueryWrapper<DeliveryItem>()
                        .in(DeliveryItem::getDeliveryId, deliveryIds));
        int deletedDeliveryItems = deliveryIds.isEmpty() ? 0
                : deliveryItemService.getBaseMapper().delete(new LambdaQueryWrapper<DeliveryItem>()
                        .in(DeliveryItem::getDeliveryId, deliveryIds));
        requireExactly("发货单明细", deliveryItems, deletedDeliveryItems);

        // ⑥ 发货单（引用订单）
        long deliveries = deliveryOrderService.count(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId));
        int deletedDeliveries = deliveryOrderService.getBaseMapper().delete(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId));
        requireExactly("发货单", deliveries, deletedDeliveries);

        // ⑦ 订单明细
        long items = orderItemService.count(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        int deletedItems = orderItemService.getBaseMapper().delete(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        requireExactly("订单明细", items, deletedItems);

        // ⑧ 支付流水（无外键，按 order_sn 字符串关联）：**先往 admin_audit_log 记 PAYMENT_PURGE 再删**
        purgePaymentRecords(order);

        // ⑨ 订单本体
        int deletedOrder = orderService.getBaseMapper().deleteById(orderId);
        requireExactly("订单", 1, deletedOrder);
    }

    /**
     * 删单前的库存结算：<b>先按台账净额把这单扣掉的货回补，再清掉该订单的台账行</b>。
     *
     * <p><b>它修的是什么</b>：{@code purgeStockLedger} 会按 {@code order_id} 删台账（不留孤儿账，对的），
     * 但原来删单之前没有任何一步把「这单扣掉的货」还回去 —— 后台建单 / 买家下单扣的库存随删单
     * <b>永久消失</b>（「扣了、账也抹了」）。实测：跑完一轮集成测试 sku1 从 1000 掉到 983，全是
     * 「造单 → 删单」来回留下的窟窿。
     *
     * <p><b>回补口径</b>：按 {@code (sku_id)} 求台账 {@code Σ(delta)}：
     * <ul>
     *   <li>{@code net < 0}（这单净扣了货）→ 回补 {@code |net|} 件；</li>
     *   <li>{@code net >= 0}（已经补过，如「审核不通过」-3 +3 = 0；或压根没扣过）→ <b>一件都不回补</b>，
     *       否则删单就变成了第二个「同一件货补两次」的漏洞。</li>
     * </ul>
     * 回补走库存接口（{@link StockLedgerService#restore}，即 {@code SkuService.restoreStock} 的同一实现），
     * <b>绝不裸 UPDATE</b> —— 幂等键与数量守恒两道闸门照旧生效（净额算多了也补不出去）。
     *
     * <p><b>不额外记一行「回补台账」</b>：紧接着台账行就要被整体删掉，专门再记一行毫无意义。
     * 但<i>库存接口内部</i>会为这次回补写一行即时流水（同时动库存才动账），它与本订单其余台账行
     * 一起在下面被删掉，不留残行 —— 所以「删完之后 stock_ledger 里没有任何本单残留」仍然成立。
     * 回补件数由下面那条 {@code log.warn} 审计日志承载。
     *
     * <p><b>SKU 已不存在</b>（商品/规格被删）→ 跳过该规格并 warn，不让整笔删除失败：
     * 那个规格已经没有库存可补，删单本身没有错。
     */
    private void settleStockLedger(Order order) {
        // 回补那一步会写一行即时台账 → 所以台账行必须在**回补之后**重新读，删行闸门才对得上
        List<StockLedger> before = ledgerRowsOf(order.getId());

        Map<Long, Integer> netBySku = new LinkedHashMap<>();
        for (StockLedger row : before) {
            if (row.getSkuId() == null) continue;
            int delta = row.getDelta() == null ? 0 : row.getDelta();
            netBySku.merge(row.getSkuId(), delta, Integer::sum);
        }

        // 按 reason 合计（delta 正=回补、负=扣减）：一行日志就能看出这单各环节进出多少件
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (StockLedger row : before) {
            int delta = row.getDelta() == null ? 0 : row.getDelta();
            byReason.merge(row.getReason() == null ? "-" : row.getReason(), delta, Integer::sum);
        }
        List<String> reasonParts = new ArrayList<>();
        byReason.forEach((reason, sum) -> reasonParts.add(reason + "=" + (sum > 0 ? "+" : "") + sum));

        // 计划回补量 + 逐规格净额明细（让日志自己说明为什么是这个数）
        int planned = 0;
        List<String> netParts = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : netBySku.entrySet()) {
            int net = entry.getValue();
            netParts.add("sku " + entry.getKey() + " 净额 " + (net > 0 ? "+" : "") + net
                    + (net < 0 ? "（回补 " + (-net) + " 件）" : "（不回补）"));
            if (net < 0) planned += -net;
        }

        log.warn("删除订单前清理库存台账：orderSn={} orderId={} 共 {} 行，按 reason 合计 [{}]，"
                        + "按台账净额将回补 {} 件{}（台账是审计证据，删前先把这行摘要打进应用日志；"
                        + "库存台账这一支没有 admin_audit_log 行）",
                order.getOrderSn(), order.getId(), before.size(), String.join(", ", reasonParts),
                planned, netParts.isEmpty() ? "（无台账行）" : "：[" + String.join("、", netParts) + "]");

        int restored = restoreByNet(order, netBySku);
        if (planned > 0) {
            log.warn("删除订单回补库存：orderSn={} orderId={} 按台账净额将回补 {} 件，实际回补 {} 件{}",
                    order.getOrderSn(), order.getId(), planned, restored,
                    restored < planned ? "（差额此前已按其它入口回补过，数量守恒夹紧）" : "");
        }

        if (before.isEmpty()) return;

        // 回补可能刚写过一行即时台账（reason=ORDER_PURGE_RESTORE）：行数以「现在库里还剩几行」为准
        long expected = stockLedgerMapper.selectCount(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getOrderId, order.getId()));
        int deleted = stockLedgerMapper.delete(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getOrderId, order.getId()));
        requireExactly("库存台账", expected, deleted);
    }

    /**
     * 按每个规格的净额回补（{@code net < 0} 才补 {@code |net|} 件），返回实际回补总件数。
     *
     * <p>上下文用一个只在本次事务内存在的 reason 声明（{@link #REASON_ORDER_PURGE_RESTORE}）：
     * 库存接口没有归属就 fail-closed，而这里确实需要一个归属；这不违反「不额外写回补台账」——
     * 接口内部那行即时流水随即被 {@link #settleStockLedger} 一起删掉。
     */
    private int restoreByNet(Order order, Map<Long, Integer> netBySku) {
        int restored = 0;
        StockLedgerContext.Attribution outer = StockLedgerContext.open(
                order.getId(), REASON_ORDER_PURGE_RESTORE, null, null);
        try {
            for (Map.Entry<Long, Integer> entry : netBySku.entrySet()) {
                Long skuId = entry.getKey();
                int net = entry.getValue();
                if (net >= 0) continue;                       // 已补过 / 没扣过 → 不回补
                int want = -net;
                if (skuService.getById(skuId) == null) {
                    log.warn("删除订单回补库存跳过：SKU {} 已不存在（订单 {} 该规格净扣 {} 件），"
                                    + "跳过该规格、不影响本次删除", skuId, order.getOrderSn(), want);
                    continue;
                }
                int done;
                try {
                    done = stockLedgerService.restore(skuId, want);
                } catch (IllegalStateException e) {
                    // 预检之后被并发删掉：库存接口 fail-closed。已不存在的规格没有库存可补，跳过即可
                    if (e.getMessage() != null && e.getMessage().contains("不存在")) {
                        log.warn("删除订单回补库存跳过：SKU {} 回补时已不存在（{}），跳过该规格、不影响本次删除",
                                skuId, e.getMessage());
                        continue;
                    }
                    throw e;
                }
                restored += done;
                if (done < want) {
                    log.warn("删除订单回补库存夹紧：订单 {} 规格 {} 净扣 {} 件，实际只回补 {} 件"
                                    + "（其余此前已按其它入口回补过）", order.getOrderSn(), skuId, want, done);
                }
            }
        } finally {
            StockLedgerContext.close(outer);
        }
        return restored;
    }

    /** 该订单名下的库存台账行（按主键升序）—— 净额、reason 合计、删行闸门都从这一份口径来 */
    private List<StockLedger> ledgerRowsOf(Long orderId) {
        return stockLedgerMapper.selectList(new LambdaQueryWrapper<StockLedger>()
                .eq(StockLedger::getOrderId, orderId)
                .orderByAsc(StockLedger::getId));
    }

    /**
     * 清理该订单号下的支付流水：**先在 {@code admin_audit_log} 记一条 {@code PAYMENT_PURGE}，再删**。
     *
     * <h3>为什么这条审计是必需的（不是「多记一条更保险」）</h3>
     * {@code payment_record} 没有外键，删单必须显式清理它 —— 而它删掉就是**不可恢复**的：
     * 「这单删之前收过几次钱、每次多少钱」在库里只剩这一条 {@code admin_audit_log} 可查
     * （台账七类里的「审计」那一支直接读这张表）。原来这里只有一条 {@code log.warn}（应用日志，
     * 会轮转、会随容器消失、台账页查不到），所以「钱发生过」这件事**在台账里没有凭证**。
     *
     * <h3>顺序与失败后果（先记账后动手，同一事务）</h3>
     * <ol>
     *   <li><b>先</b> {@link AdminAuditWriter#record}（INSERT {@code admin_audit_log}），
     *       <b>再</b> DELETE {@code payment_record}；</li>
     *   <li>两者在**同一个事务**里（{@code purge} 的 {@code @Transactional}）：审计写入失败
     *       （例如序列化炸了 / 列超宽）→ 整笔删除回滚，流水一行不动 ——
     *       「宁可这次删单失败，也不许让台账少一条」；</li>
     *   <li>反过来，若 DELETE 影响行数不符抛异常 → 整笔回滚，**审计行也一起回滚**：
     *       于是「有审计行」恰好等价于「这次清理真的提交了」，不会留下
     *       「审计说删了 3 条、流水其实还在」的假凭证（{@code payment_record} 没删就不可能丢证据链）。</li>
     * </ol>
     *
     * <h3>幂等（为什么不需要业务键幂等）</h3>
     * 审计的 {@code idem_key} 由 {@code request_id} 派生（{@code AdminAuditWriter} 的红线，
     * 这次落在 {@code request_id:序号}），**绝不由 order_sn 派生** —— 理由见该类的说明。
     * 而「重复删单不多写」是靠**业务前置条件**保证的，不是靠幂等键：
     * 流水数为 0 时本方法直接返回（一行不写）；第二次删同一张单在
     * {@code OrderController#delete} 的「订单不存在」处就返回了，连 {@code purge} 都进不去。
     * 两条合起来：一次真删 ⇔ 一条 {@code PAYMENT_PURGE}。
     */
    private void purgePaymentRecords(Order order) {
        List<PaymentRecord> records = paymentRecordsOf(order.getOrderSn());
        if (records.isEmpty()) return;

        // ① 先把证据攒出来：一条人话摘要（台账列表页直接展示）+ 一份逐条快照（金额/流水号/类型的全量事实）
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal amountTotal = BigDecimal.ZERO;
        for (PaymentRecord r : records) {
            lines.add("tradeNo=" + r.getTradeNo() + " type=" + r.getType() + " amount=" + r.getAmount());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("tradeNo", r.getTradeNo());
            row.put("type", r.getType());
            row.put("amount", r.getAmount());
            row.put("channel", r.getChannel());
            rows.add(row);
            if (r.getAmount() != null) amountTotal = amountTotal.add(r.getAmount());
        }

        // ② 先记账（与 DELETE 同事务，且**必定**在 DELETE 之前）：
        //    summary 里带订单号 / 条数 / 金额合计 / 每条的 tradeNo+type+amount（超长按列宽裁，全量在 before_json）
        //    target 记成 ORDER + 订单 id/单号：被清理的是「这张单名下的流水」，且删完之后
        //    只有订单维度还能按 target_id / order_id 反查（payment_record 自己的 id 删完就没有意义了）
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_PAYMENT_PURGE, ReasonCodes.TARGET_ORDER, order.getId(), order.getOrderSn(),
                "删除订单 " + order.getOrderSn() + "（id=" + order.getId() + "）前清理 " + records.size()
                        + " 条支付流水，金额合计 ¥" + amountTotal + "：" + String.join("; ", lines),
                null, order.getId(),
                AdminAuditWriter.snapshot("count", records.size(), "amountTotal", amountTotal, "records", rows),
                AdminAuditWriter.snapshot("purgedCount", records.size(), "deleted", Boolean.TRUE)));

        // ③ 再动手（先记账后动手：删完之后这条事实在库里就只有上面那一行了）
        int deleted = paymentRecordService.getBaseMapper().delete(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderSn, order.getOrderSn()));
        requireExactly("支付流水", records.size(), deleted);

        // 伴随日志（**审计已在上面的 admin_audit_log 里，这条 warn 只是排障时顺眼**）：
        // 文案与实现一致 —— 当初这里写「已先落审计日志再删除」而代码里根本没有审计写入，是句假话
        log.warn("删除订单前清理支付流水：orderSn={} orderId={} 共 {} 条 [{}]（对账证据：已在同一事务内先写入 "
                        + "admin_audit_log 的 PAYMENT_PURGE（含订单号/条数/金额/流水号）再删除）",
                order.getOrderSn(), order.getId(), records.size(), String.join("; ", lines));
    }

    // ------------------------------------------------------------------ 小工具

    /** 影响行数闸门：预期与实际必须相等，否则回滚（0 行不等于成功） */
    private void requireExactly(String label, long expected, int actual) {
        if (expected == actual) return;
        throw new IllegalStateException("删除中断：删除" + label + "影响行数不符（预期 " + expected
                + " 行，实际 " + actual + " 行），本次删除已回滚，未留下半删的订单");
    }

    private List<Long> deliveryIdsOf(Long orderId) {
        return deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                        .select(DeliveryOrder::getId)
                        .eq(DeliveryOrder::getOrderId, orderId))
                .stream().map(DeliveryOrder::getId).toList();
    }

    private long countLogistics(List<Long> deliveryIds) {
        if (deliveryIds.isEmpty()) return 0;
        return deliveryLogisticsService.count(new LambdaQueryWrapper<DeliveryLogistics>()
                .in(DeliveryLogistics::getDeliveryId, deliveryIds));
    }
}
