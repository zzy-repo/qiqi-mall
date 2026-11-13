package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.common.OrderSnGenerator;
import com.qiqi.mall.shared.common.PreviewResult;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.oms.dto.OrderDetailDTO;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderPurgeService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.SplitOrderService;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.member.service.MemberService;
import com.qiqi.mall.stock.StockLedgerContext;
import com.qiqi.mall.stock.StockStore;
import com.qiqi.mall.stock.entity.StockLedger;
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
    /** 订单状态写入的唯一入口（P2 第一步收敛：状态只在 OrderStatusService 里写） */
    private final OrderStatusService orderStatusService;
    private final OrderItemService orderItemService;
    private final ReturnApplyService returnApplyService;
    private final SplitOrderService splitOrderService;
    private final SkuService skuService;
    private final ProductService productService;
    private final DeliveryOrderService deliveryOrderService;
    private final OrderPurgeService orderPurgeService;
    private final MemberService memberService;
    /** 库存落点：「只剩 N 件」这类必须说准的地方走它（legacy 读 sku.stock，bucket 读 SUM(桶)） */
    private final StockStore stockStore;
    /** 后台操作审计（删单/发货这类敏感动作要留证；开关可一键关掉） */
    private final AdminAuditWriter auditWriter;

    public OrderController(OrderService orderService, OrderStatusService orderStatusService,
                           OrderItemService orderItemService,
                           ReturnApplyService returnApplyService, SplitOrderService splitOrderService,
                           SkuService skuService, ProductService productService,
                           DeliveryOrderService deliveryOrderService,
                           OrderPurgeService orderPurgeService,
                           MemberService memberService,
                           StockStore stockStore,
                           AdminAuditWriter auditWriter) {
        this.orderService = orderService;
        this.orderStatusService = orderStatusService;
        this.orderItemService = orderItemService;
        this.returnApplyService = returnApplyService;
        this.splitOrderService = splitOrderService;
        this.skuService = skuService;
        this.productService = productService;
        this.deliveryOrderService = deliveryOrderService;
        this.orderPurgeService = orderPurgeService;
        this.memberService = memberService;
        this.stockStore = stockStore;
        this.auditWriter = auditWriter;
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

    /**
     * 创建订单（含明细，快照由前端传入）。
     *
     * <p><b>建单即扣库存，并写 {@code ORDER_CREATE} 台账</b>（缺陷：后台建单原来不扣库存，
     * 而「审核不通过」「取消包裹」「退货退款」三个入口都会按明细回补 —— 这单一旦走到其中任何一个，
     * 库存就净增一次，实测把 sku1 从 47 补到 49）。现在与买家下单（{@link com.qiqi.mall.oms.service.OrderTransactionalCreator}）
     * 同一口径：逐明细走 {@link SkuService#deductStock}，由 {@code StockLedgerService} 先记一行负数台账
     * 再原子扣 {@code sku.stock}；扣不动就抛中文原因，**整笔事务回滚**（订单、明细、台账一起没了），
     * 绝不会出现「库存扣了、订单没建」或「订单建了、库存没扣」。
     *
     * <p>明细为空（{@code items} 为 null / 空数组）时**不扣库存**：没有明细就没有库存变动，
     * 后续回补也按明细走（同样为空），不会留下「扣了没记」。这条路径只对直连 API 存在
     * （后台表单必须选完整明细、MCP {@code create_order} 的 zod 也要求 items 非空）。
     *
     * <p><b>建单入口另外四条收敛（都能造出流程产不出的组合）</b>：
     * <ol>
     *   <li><b>{@code splitStatus} 强制 0</b> —— 建单时一张包裹都没有，请求体传 1/2 也落 0
     *       （「已拆单却零包裹」这类组合从此不可能出现；真拆单只由 {@link SplitOrderService} 写）；</li>
     *   <li><b>{@code status ∈ {0, 7}} 清掉支付痕迹</b> —— {@code paymentTime = null}、{@code payType = 0}。
     *       否则能建出「待付款却有支付时间」「待审核却带着一笔没有流水的支付」；</li>
     *   <li><b>{@code items} 为空 → {@code totalAmount = 0}</b> —— 没有明细就没有金额，
     *       否则能落「总金额 999、0 条明细」的单（金额凭空存在，对账无据）；</li>
     *   <li><b>{@code memberId} / {@code items[].productSkuId} 存在性校验</b> —— 不存在时给中文原因
     *       （「会员不存在（id=X），请先创建会员」/「规格不存在（id=X）」），
     *       而不是掉到 SQL 外键（「数据处理失败」）或「库存不足：规格 X 只剩 0 件」（文案误导）。</li>
     * </ol>
     *
     * <p><b>初始状态白名单（本接口只接受 0 / 1 / 7）</b>：建单原来是「请求体里的 status 原样落库」，
     * 于是能造出任何真实流程都产不出的状态组合 —— 「8 待发货却一张包裹都没有」「6 已退款却没有退款流水」
     * 「3 已完成却没有任何包裹」。这些组合一旦落库，所有按状态聚合的图与文档都必须为它开例外。
     * 所以这里只放行**不需要任何前置事实就成立**的三种初始状态：
     * <ul>
     *   <li>{@code 0 待付款}（默认，请求体不传就是它）；</li>
     *   <li>{@code 1 已付款} —— 不是「流程产不出」：退货被拒且无包裹时订单会落回 1，
     *       而 {@link #ship} 的 1→2 直发通道正是为「被拒了、商家还想发货」保留的；</li>
     *   <li>{@code 7 待审核} —— 后台人工建单后走审核的标准入口。</li>
     * </ul>
     * 其余（2 已发货 / 3 已完成 / 4 已关闭 / 5 退款中 / 6 已退款 / 8 待发货 / 9 部分发货）各自
     * 都需要前置事实（包裹、退款流水、退货申请、审核不通过…），只能由业务流程流转产生，
     * 一律拒单并给出中文原因。
     */
    @Operation(summary = "后台新增订单（初始状态仅 0/1/7；splitStatus 强制 0、0/7 清支付痕迹、明细为空则金额 0；建单即扣库存 + 记 ORDER_CREATE 台账）")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<Order> create(@RequestBody OrderCreateRequest request) {
        Order order = request.getOrder();
        // ① 初始状态白名单（fail-closed）：不许把请求体里的 status 原样落库
        String badStatus = initialStatusProblem(order);
        if (badStatus != null) return Result.fail(badStatus);
        order.setId(null);
        // 新建对象不该带版本号：不论请求体传了什么都强制 0 版（响应里带回，前端拿到就能直接编辑）
        order.setVersion(0);
        // ② 拆单标记强制 0：建单这一刻一张包裹都没有，「已拆单(1)」只能由拆单流程
        //    （SplitOrderService）写进库。原来请求体传什么就落什么，于是能建出
        //    「split_status=1 却零包裹」这种任何真实流程都产不出的组合。
        order.setSplitStatus(INITIAL_SPLIT_STATUS);
        // ③ 待付款(0) / 待审核(7) 清掉支付痕迹：这两个初始状态在流程上**都没有支付流水**
        //    （后台建单不走支付；7 待审核是把单子交给审核，不是「已经付过钱」）。
        //    原来能建出「待付款却有支付时间」和「已付款/待审核却没有任何支付流水」两类组合，
        //    支付时间与支付流水是成对的事实，缺一个就与对账口径分叉。payType 落 0（未支付）。
        if (order.getStatus() == INITIAL_UNPAID || order.getStatus() == INITIAL_PENDING_AUDIT) {
            order.setPaymentTime(null);
            order.setPayType(PAY_TYPE_UNPAID);
        }
        // ④ 会员存在性（先判，别等落库时被 fk_order_member 拦住回一句「数据处理失败，请稍后重试」）
        String memberProblem = memberProblemOf(order.getMemberId());
        if (memberProblem != null) return Result.fail(memberProblem);
        if (order.getOrderSn() == null || order.getOrderSn().isBlank()) {
            order.setOrderSn(OrderSnGenerator.next());
        }
        List<OrderItem> items = request.getItems();
        // ⑤ 明细里的规格必须先存在：存在性在前、库存充足在后。顺序反了就会把
        //    「规格 999999 不存在」说成「库存不足：规格 999999 只剩 0 件」（文案误导，排查方向全错）。
        String skuProblem = skuProblemOf(items);
        if (skuProblem != null) return Result.fail(skuProblem);
        // 总金额以明细为准（快照价 × 数量）；顺手把数量规整成 ≥1 的确定值 ——
        // 后面扣库存和写 order_item 必须用同一个数，不能一边按 null 当 1、一边按 null 落库
        if (items != null && !items.isEmpty()) {
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (OrderItem it : items) {
                java.math.BigDecimal price = it.getProductPrice() == null ? java.math.BigDecimal.ZERO : it.getProductPrice();
                int qty = it.getProductQuantity() == null || it.getProductQuantity() < 1 ? 1 : it.getProductQuantity();
                it.setProductQuantity(qty);
                total = total.add(price.multiply(java.math.BigDecimal.valueOf(qty)));
            }
            order.setTotalAmount(total);
        } else {
            // ③ 没有明细就没有金额：明细为空时总金额必须是 0。
            //    原来这段不重算，于是能落「总金额 999、0 条明细」的订单（金额凭空存在，对账无据可查）。
            order.setTotalAmount(java.math.BigDecimal.ZERO);
        }
        orderService.save(order);
        // 出生台账（from_status = NULL）：save 之后、同一事务内。后台建单同样是「订单的出生事件」
        orderStatusService.recordBirth(order, OrderStatusService.REASON_CREATE, "后台建单");
        if (items != null && !items.isEmpty()) {
            items.forEach(item -> {
                item.setId(null);
                item.setOrderId(order.getId());
            });
            orderItemService.saveBatch(items);
            // 扣库存 + 记台账（与买家下单同一套：StockLedgerContext 声明归属 → SkuService 记账后动库存）。
            // 库存不足时抛中文原因 → 整笔 @Transactional 回滚：订单/明细/已扣的库存/台账全部撤销。
            deductStockForOrder(order, items);
        }
        return Result.ok(order);
    }

    /**
     * 按明细逐 SKU 扣减库存并记 {@code ORDER_CREATE} 台账（幂等键 orderId:skuId:ORDER_CREATE:0）。
     *
     * <p>同一张订单里两行指向同一规格时，{@code StockLedgerService} 会把件数合并进同一行台账
     * （{@code StockLedgerContext.alreadyTouchedInThisRun}），账与实物仍然一一对上。
     */
    private void deductStockForOrder(Order order, List<OrderItem> items) {
        StockLedgerContext.Attribution outer = StockLedgerContext.open(
                order.getId(), StockLedger.REASON_ORDER_CREATE, null, null);
        try {
            for (OrderItem item : items) {
                Long skuId = item.getProductSkuId();
                int qty = item.getProductQuantity() == null ? 1 : item.getProductQuantity();
                if (skuId == null) {
                    throw new IllegalStateException("订单明细缺少规格（未选择商品规格），无法扣减库存");
                }
                if (!skuService.deductStock(skuId, qty)) {
                    throw new IllegalStateException(insufficientStockMessage(item, skuId));
                }
            }
        } finally {
            StockLedgerContext.close(outer);
        }
    }

    /**
     * 库存不足的中文原因：「库存不足：XX 只剩 N 件」（N 取扣减失败后的真实余量）。
     *
     * <p>余量读的是 {@code SUM(sku_bucket.stock)}（权威值），不是 {@code sku.stock}（展示缓存）——
     * 缓存可能滞后几百毫秒，而「告诉用户只剩几件」错一个数就是客服工单。
     * 这条路径只在扣减失败时走（每次 8 行求和），不构成热路径压力。
     */
    private String insufficientStockMessage(OrderItem item, Long skuId) {
        Sku sku = skuService.getById(skuId);
        int left = stockStore.availableStock(skuId);
        String name = item.getProductName();
        if (sku != null && sku.getProductId() != null) {
            Product product = productService.getById(sku.getProductId());
            if (product != null && product.getName() != null && !product.getName().isBlank()) {
                name = product.getName();
            }
        }
        if (name == null || name.isBlank()) name = "规格 " + skuId;
        return "库存不足：" + name + " 只剩 " + left + " 件";
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
        // 缺版本号 → 真 HTTP 400；影响行数 0 → 真 HTTP 409（见 GlobalExceptionHandler）
        OptimisticLockGuard.requireVersion(body.getVersion());
        int rows = orderService.getBaseMapper().updateById(body);
        OptimisticLockGuard.requireAffected(rows);
        return Result.ok();
    }

    /**
     * 审核通过：待审核(7) → 待发货(8)，随后触发拆单生成发货单。
     *
     * <p>校验顺序（严格，业务守卫不许抢跑）：
     * **① 订单存在 → ② 业务守卫（status==7）→ ③ 执行**；{@code ?dryRun=true} 时同样先过 ①②，
     * 再把「将要发生什么」用中文说清楚（{@code {willDo}}），不落库、不发凭证。
     *
     * <p>并发：本接口不带客户端 {@code version}（改的是订单状态，走的是状态守卫这层闸门），
     * 但**写回时校验影响行数**（{@code @Version} → {@code WHERE version = ?}）：0 行说明「读到 status=7
     * 之后、写回之前」被别人改过（另一个审核员抢先审了 / 别的实例改了收货地址），
     * 直接抛 {@code code=409「数据已被他人修改（可能其他人在编辑），请刷新后重试」}，
     * 整个事务回滚 —— **不会出现「订单还是待审核、发货单却已经生成」**。
     * 通用编辑接口 {@code PUT /admin/orders/{id}} 另有客户端 version 乐观锁。
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
        // 状态写入统一走 OrderStatusService（同一条 UPDATE 里带 status_seq 自增与 version 自增）
        // fail-closed：0 行说明「读到 status=7 之后、写回之前」别人改过这张订单
        // （典型：另一个审核员已经审过并发货单已生成）。必须抛 409 让事务回滚，
        // 绝不能往下走 split() —— 否则订单停在待审核、发货单却已生成，只能人工对账。
        int rows = orderStatusService.change(order, 8, OrderStatusService.REASON_AUDIT_PASS, null);
        OptimisticLockGuard.requireAffected(rows);
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

    /**
     * 审核通过的预演文案：说清拆成几包、每包哪个仓几条明细。
     *
     * <p>若某个规格没有分仓库存记录（拆单会走「默认仓」兜底），这里必须一并说清
     * 「该规格未配置分仓库存，已按默认仓 XX 处理」—— 预演就是把将要发生的事提前讲明白，
     * 不能审完才发现货是从哪个仓发的。
     */
    private String auditPassWillDo(Order order, List<Map<String, Object>> plan) {
        List<String> packages = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Map<String, Object> pkg : plan) {
            Object items = pkg.get("items");
            int lines = items instanceof List<?> list ? list.size() : 0;
            packages.add(pkg.get("warehouseName") + " " + lines + " 条明细");
            Object note = pkg.get("note");
            if (note != null && !note.toString().isBlank()) notes.add(note.toString());
        }
        String base = "将把订单 " + order.getOrderSn() + "（id=" + order.getId()
                + "）审核通过：状态 待审核→待发货，并按方案生成 " + plan.size()
                + " 个发货单（" + String.join("、", packages) + "）。";
        return notes.isEmpty() ? base : base + "注意：" + String.join("；", notes) + "。";
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
        // 状态写入统一走 OrderStatusService（同一条 UPDATE 里带 status_seq 自增与 version 自增）
        // fail-closed：0 行 = 读到 status=7 之后别人改过 → 409 回滚，绝不回补库存
        int rows = orderStatusService.change(order, 4, OrderStatusService.REASON_AUDIT_REJECT, null);
        OptimisticLockGuard.requireAffected(rows);

        // 回补必须记台账（缺陷：这条入口原来是裸的 stock = stock + N，和「取消包裹」叠加会补两次）。
        // 声明归属 → 库存接口按幂等键 + 数量守恒记账：同一订单同一规格补过一次，这里就整笔跳过。
        StockLedgerContext.Attribution outer = StockLedgerContext.open(
                order.getId(), StockLedger.REASON_AUDIT_REJECT, null, null);
        try {
            items.forEach(item -> skuService.restoreStock(item.getProductSkuId(), item.getProductQuantity()));
        } finally {
            StockLedgerContext.close(outer);
        }
        return Result.ok();
    }

    private String auditRejectWillDo(Order order, List<OrderItem> items) {
        List<String> restore = skuQtyLines(items);
        return "将把订单 " + order.getOrderSn() + "（id=" + order.getId()
                + "）审核不通过：状态 待审核→已关闭，并把 " + items.size() + " 条明细的总池库存回补（"
                + describeSkuQty(restore) + "，合计 " + totalQty(items) + " 件）。";
    }

    /**
     * 发货：**待发货(8) / 已付款(1)** → 已发货(2)。
     *
     * <p>守卫（3 条）：① 存在性 → ② 业务守卫（status ∈ {8 待发货, 1 已付款}）→ ③ 执行；
     * {@code ?dryRun=true} 先过守卫再返回中文描述（含明细条数与现有发货单数）。
     *
     * <p><b>为什么必须接受 8 待发货</b>（缺陷：这条入口对真实订单是死路）：买家支付后
     * {@code PortalOrderController#pay} 直接把订单写成 7 待审核（不再是 1 已付款），
     * 审核通过 {@code auditPass} 又写成 8 待发货 —— 真实主链路上**没有任何一步会留下 1 已付款**。
     * 旧守卫只放行 1，于是真实订单永远进不了发货：审核通过后卡在 8，只能靠拆单生成的包裹走 WMS。
     * 现在 8 是主链路（审核通过后的正常状态），1 仍然保留（历史直发单 / 手工建的已付款单）。
     *
     * <p><b>8 待发货 ≠ 货已离开商家</b>（缺陷：父单说已发货、子单还在商家手里）：审核通过只生成
     * 「待下发」包裹，货压根没出库。若此时这条入口把父单硬写成 2 已发货，父单与子单事实立刻分叉 ——
     * 买家看到「已发货」，可包裹连 WMS 都没下发，任何一次按子单聚合的回传又会把它写回 8/9（状态倒退）。
     * 所以 status==8 时**必须至少有一张未取消的包裹 ≥ 已出库(3)** 才允许标记发货，否则拒绝并说清出路。
     * status==1 是历史直发单（没有包裹行，直发路径本来就没有子单事实），保持原行为不变。
     */
    @Operation(summary = "订单发货（接受待发货(8)/已付款(1)，不拆单）：?dryRun=true 只预演，返回将要发生什么")
    @PostMapping("/{id}/ship")
    @Transactional
    public Result<?> ship(@PathVariable Long id,
                          @RequestParam(required = false) Boolean dryRun) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");
        // 8 待发货 = 审核通过后的正常状态（真实主链路）；1 已付款 = 历史直发单
        if (order.getStatus() == null || (order.getStatus() != 8 && order.getStatus() != 1)) {
            return Result.fail("只有「待发货」或「已付款」订单可以发货（当前状态：" + statusText(order.getStatus()) + "）");
        }

        // ② 业务守卫（只对 8 待发货）：父单状态是子单的推导值，货没出库就不许把父单写成已发货。
        //    放在 dryRun 之前 —— 预演与真做同一套守卫（不许「预演说能发、真做被拒」）。
        if (order.getStatus() == 8 && countOutboundDeliveries(id) == 0) {
            return Result.fail("该订单还有包裹未出库，不能标记为已发货；请先在履约页下发并等待仓库回传出库");
        }

        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(shipWillDo(order)));
        }
        // 状态写入统一走 OrderStatusService（同一条 UPDATE 里带 status_seq 自增与 version 自增）
        // fail-closed：0 行 = 读到状态之后别人改过（另一个按钮/另一个实例已经发过货）→ 409 回滚
        Integer statusBefore = order.getStatus();          // change() 成功后会把实体上的状态改成 2，先留旧值记审计
        int rows = orderStatusService.change(order, 2, OrderStatusService.REASON_SHIP, null);
        OptimisticLockGuard.requireAffected(rows);
        // 审计（敏感动作：把订单标记为已发货，是「货已离开商家」的对外承诺）
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_ORDER_SHIP, ReasonCodes.TARGET_ORDER, id, order.getOrderSn(),
                "订单 " + order.getOrderSn() + " 标记为已发货（直发路径，不拆单）",
                null, id,
                AdminAuditWriter.snapshot("status", statusBefore),
                AdminAuditWriter.snapshot("status", 2)));
        return Result.ok(order);
    }

    private String shipWillDo(Order order) {
        return "将把订单 " + order.getOrderSn() + "（id=" + order.getId()
                + "）标记为已发货：状态 " + statusText(order.getStatus()) + "→已发货（" + itemCountOf(order.getId())
                + " 条明细，走直发路径不拆单；当前该订单 " + countDeliveries(order.getId()) + " 个发货单）。";
    }

    /**
     * 删除订单（按依赖顺序显式删明细/发货单/流水，不可恢复）。
     *
     * <p>校验顺序：**① 存在性 → ② 业务守卫（依赖检查：有物流记录/退货申请 → 拒删并说清原因）→ ③ 执行**；
     * {@code ?dryRun=true} 跑同一套守卫，只是不落库。
     *
     * <p><b>为什么不再是「removeById 靠 DB 级联」</b>：原来的写法一出事就只剩一句
     * 「数据处理失败，请稍后重试」——{@code delivery_logistics} 对 {@code delivery_order} 是 NO ACTION，
     * 只要订单走过 WMS 出库，删订单（连带删发货单）就被外键拦住，用户完全看不懂；
     * 更糟的是 {@code return_apply} 在级联链上，删订单会<b>静默销毁退货申请</b>，
     * 而 {@code payment_record} 压根没外键，删单后留下孤儿支付流水。
     *
     * <p>现在：**要么删干净，要么说清为什么不能删**。
     * <ul>
     *   <li>有物流记录 / 有退货申请 → 抛业务文案（含运单号），并给出「作废物流 / 关闭订单」的出路；</li>
     *   <li>可删 → {@link OrderPurgeService#purge} 按「物流 → 退货申请 → 发货单明细 → 发货单 →
     *       订单明细 → 支付流水 → 订单」逐层显式删，每层判影响行数，
     *       支付流水删前先打审计日志（单号/金额/流水号）。</li>
     * </ul>
     *
     * <p>整个删除在<b>同一个事务</b>里：任一层影响行数不符就整笔回滚 —— 部分成功比失败更糟。
     */
    @Operation(summary = "删除订单（按依赖顺序显式删；有物流/退货则拒删并说明原因）：?dryRun=true 只预演")
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<?> delete(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun) {
        Order order = orderService.getById(id);
        if (order == null) return Result.fail("订单不存在");

        // ② 业务守卫：外键/依赖先翻译成人话（预演与真删同一口径，绝不「预演说能删、真删被拦住」）
        String blockReason = orderPurgeService.blockReason(order);
        if (blockReason != null) throw new IllegalStateException(blockReason);

        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(deleteWillDo(order)));
        }
        // 审计放在 purge **之前**：purge 会物理删掉订单/包裹/流水，删完再记就查不到单号了。
        // before 只记敏感字段（状态/金额/归属），不 dump 整实体。
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_ORDER_DELETE, ReasonCodes.TARGET_ORDER, id, order.getOrderSn(),
                "删除订单 " + order.getOrderSn() + "（id=" + id + "，原状态 " + statusText(order.getStatus())
                        + "，金额 ¥" + order.getTotalAmount() + "）：级联删明细/发货单/支付流水，不可恢复",
                null, id,
                AdminAuditWriter.snapshot("status", order.getStatus(),
                        "totalAmount", order.getTotalAmount(), "memberId", order.getMemberId()),
                AdminAuditWriter.snapshot("deleted", Boolean.TRUE)));
        orderPurgeService.purge(order);
        return Result.ok();
    }

    private String deleteWillDo(Order order) {
        List<com.qiqi.mall.oms.entity.PaymentRecord> records =
                orderPurgeService.paymentRecordsOf(order.getOrderSn());
        String paymentPart = records.isEmpty()
                ? "该订单号下没有支付流水"
                : "并清理该订单号下 " + records.size() + " 条支付流水（先打审计日志留证再删）";
        return "将删除订单 " + order.getOrderSn() + "（id=" + order.getId() + "，当前状态 "
                + statusText(order.getStatus()) + "）：级联删除 " + itemCountOf(order.getId()) + " 条明细、"
                + countDeliveries(order.getId()) + " 个发货单（含包裹明细与物流记录），" + paymentPart
                + "；删除后不可恢复。";
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
     * 该订单下**未取消且已出库**（≥ 已出库 3）的包裹张数 —— ship 对 8 待发货的唯一放行依据。
     *
     * <p>已取消(9) 不算：取消的包裹货已经回补，不能让一张已取消的包裹替父单「作证已出库」。
     * 只在 8 待发货时被调用（1 已付款是直发单，没有包裹行，没有子单事实可依）。
     */
    private long countOutboundDeliveries(Long orderId) {
        return deliveryOrderService.count(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId)
                .ne(DeliveryOrder::getStatus, DeliveryOrder.CANCELLED)
                .ge(DeliveryOrder::getStatus, DeliveryOrder.OUTBOUND));
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

    /** 建单允许的初始状态：待付款(0) / 已付款(1) / 待审核(7) —— 其余都必须由业务流程流转产生 */
    private static final int INITIAL_UNPAID = 0;
    private static final int INITIAL_PAID = 1;
    private static final int INITIAL_PENDING_AUDIT = 7;

    /** 建单时拆单标记只能是 0（未拆）：一张包裹都还没有，「已拆」只能由拆单流程写入 */
    private static final int INITIAL_SPLIT_STATUS = 0;

    /** 建单时的支付方式默认值：0 未支付（支付方式只由支付接口按真实流水写入） */
    private static final int PAY_TYPE_UNPAID = 0;

    /**
     * 建单前的会员存在性校验：返回 {@code null} 表示通过，否则返回**给用户看的中文原因**。
     *
     * <p>为什么必须先判：{@code order.member_id} 是 NOT NULL + 外键 {@code fk_order_member}，
     * 传一个不存在的 id 时原来的失败点是 SQL 外键 —— 响应只剩一句
     * 「数据处理失败，请稍后重试」，调用方既看不出是会员给错了，也不知道该先建会员。
     */
    private String memberProblemOf(Long memberId) {
        if (memberId == null) {
            return "缺少会员（memberId），请先创建会员，或在请求体里指定 memberId";
        }
        if (memberService.getById(memberId) == null) {
            return "会员不存在（id=" + memberId + "），请先创建会员";
        }
        return null;
    }

    /**
     * 建单前的规格存在性校验（逐明细）：返回 {@code null} 表示通过，否则返回中文原因。
     *
     * <p>存在性必须排在「库存是否足够」之前：不存在的规格查出来的余量必然是 0，
     * 于是「规格不存在（id=999999）」会被说成「库存不足：规格 999999 只剩 0 件」——
     * 文案把「配错了 id」误导成「补货就行」，排查方向全错。
     */
    private String skuProblemOf(List<OrderItem> items) {
        if (items == null || items.isEmpty()) return null;
        for (OrderItem item : items) {
            Long skuId = item.getProductSkuId();
            if (skuId == null) {
                return "订单明细缺少规格（未选择商品规格），无法建单";
            }
            if (skuService.getById(skuId) == null) {
                return "规格不存在（id=" + skuId + "）";
            }
        }
        return null;
    }

    /**
     * 建单初始状态校验（{@link #create} 的第一道闸门）。
     *
     * @return {@code null} = 通过（请求体不传 status 时顺手落成 0 待付款）；
     *         否则返回**给用户看的中文原因** —— 说清收到的是哪个状态、允许哪三个、
     *         以及「其它状态只能由流程产生」和具体流转箭头，不许调用方自己猜。
     */
    private String initialStatusProblem(Order order) {
        Integer status = order.getStatus();
        if (status == null) {
            // 不传 = 待付款：这是「还没发生任何事」的默认口径，不是调用方漏填。
            // 初始状态的写入也走唯一入口 OrderStatusService#init（出生 = save 之前定初值，不产生 UPDATE）
            orderStatusService.init(order, INITIAL_UNPAID);
            return null;
        }
        if (status == INITIAL_UNPAID || status == INITIAL_PAID || status == INITIAL_PENDING_AUDIT) {
            return null;
        }
        return "建单只允许「待付款」「已付款」或「待审核」作为初始状态，收到「" + statusText(status) + "」。\n"
                + "其它状态（已发货 / 已完成 / 已关闭 / 退款中 / 已退款 / 待发货 / 部分发货）"
                + "必须由业务流程流转产生（支付→待审核、审核→待发货、出库→已发货…），不能直接建出来。";
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
