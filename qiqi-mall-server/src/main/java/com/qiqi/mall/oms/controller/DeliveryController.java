package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.dto.DeliveryMergeRequest;
import com.qiqi.mall.oms.dto.DeliverySplitRequest;
import com.qiqi.mall.oms.dto.DeliveryUpdateRequest;
import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.oms.service.DeliveryEditService;
import com.qiqi.mall.oms.service.DeliveryFlowService;
import com.qiqi.mall.oms.service.DeliveryItemService;
import com.qiqi.mall.oms.service.DeliveryLogisticsService;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.WarehouseService;
import com.qiqi.mall.shared.common.PreviewResult;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.stock.StockLedgerContext;
import com.qiqi.mall.stock.entity.StockLedger;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发货单接口（方案 3b）：下发 / 取消 / WMS 回传 / 查询 + 待下发阶段的人工干预（改仓改量/拆分/合并/删除）。
 *
 * <p>**本接口的 7 个写操作全部是 L3（高影响/不可逆）**，统一契约：
 * <pre>
 * ① 预演  POST/PUT/DELETE ...?dryRun=true → 不落库，只返回 { willDo:"中文描述" }
 * ② 执行  同一地址（不带 dryRun）→ 走下面三道闸门
 * </pre>
 * 预演方法（{@code DeliveryFlowService#previewXxx / DeliveryEditService#previewXxx}）与执行路径共用同一套守卫，
 * 所以预演阶段就会把「该包裹已下发不能改」这类问题直接告诉调用方。
 *
 * <p>**执行时的校验顺序（严格，不许抢跑）**：
 * ① 参数/存在性 → ② version 乐观锁（缺 → 400，冲突 → 409）
 * → ③ 业务守卫（待下发）。**version 必须排在业务守卫前面**：「数据被别人改了」与「状态不对」
 * 是两件事，混在一起会让调用方查错方向。
 *
 * <p>**哪几个接口收 version**：待下发阶段的人工干预四个动作全都收 ——
 * {@code PUT}（body.version）、{@code POST /split}（body.version）、{@code POST /merge}（body.version）、
 * {@code DELETE}（**query 的 ?version=**，DELETE 带 body 过代理/CDN 不可靠）。
 * 状态流转类（dispatch / cancel / wms-callback）不收：它们由「状态守卫 + 影响行数检查」兜住，
 * 且调用方（WMS/运维）拿不到前端页面上的 version。
 * 预演（{@code ?dryRun=true}）一律**不需要** version：不落库就不存在冲突。
 */
@Tag(name = "管理端-发货单与履约", description = "发货单下发/取消/WMS 回传，以及待下发阶段的人工干预：改仓改量、拆分、合并、删除（四个编辑动作均需 version，全部支持 ?dryRun=true 预演）")
@RestController
@RequestMapping("/admin/deliveries")
public class DeliveryController {

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    private final DeliveryFlowService deliveryFlowService;
    private final DeliveryEditService deliveryEditService;
    private final WarehouseService warehouseService;

    public DeliveryController(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                              DeliveryLogisticsService deliveryLogisticsService, DeliveryFlowService deliveryFlowService,
                              DeliveryEditService deliveryEditService, WarehouseService warehouseService) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.deliveryFlowService = deliveryFlowService;
        this.deliveryEditService = deliveryEditService;
        this.warehouseService = warehouseService;
    }

    // ------------------------------------------------------------------ 状态流转（L3，支持 dryRun 预演）

    @Operation(summary = "下发发货单给 WMS（仅待下发）；?dryRun=true 只预演，返回将要发生什么")
    @PostMapping("/{id}/dispatch")
    public Result<?> dispatch(@PathVariable Long id,
                              @RequestParam(required = false) Boolean dryRun) {
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(deliveryFlowService.previewDispatch(id)));
        }
        return Result.ok(deliveryFlowService.dispatch(id));
    }

    @Operation(summary = "取消发货单（仅待下发，回补库存）；?dryRun=true 只预演，返回将要发生什么")
    @PostMapping("/{id}/cancel")
    public Result<?> cancel(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun) {
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(deliveryFlowService.previewCancel(id)));
        }
        // 回补库存要记台账（缺陷：这条入口原来也是裸的 stock = stock + N）。
        // 回补的落点是 DeliveryFlowService#cancel 里那句 skuService.restoreStock(...)，
        // 而那个文件由另一路改动占用 —— 所以在这里（它唯一的入口）声明归属：
        // 「本次回补属于哪张订单、原因 DELIVERY_CANCEL、由这张发货单触发」，
        // 库存接口在同一个线程里按这个上下文记账、查重。发货单不存在时不声明（cancel 自己会报错）。
        DeliveryOrder delivery = deliveryOrderService.getById(id);
        if (delivery == null) {
            return Result.ok(deliveryFlowService.cancel(id));
        }
        StockLedgerContext.Attribution outer = StockLedgerContext.open(delivery.getOrderId(),
                StockLedger.REASON_DELIVERY_CANCEL, StockLedger.REF_TYPE_DELIVERY, delivery.getId());
        try {
            return Result.ok(deliveryFlowService.cancel(id));
        } finally {
            StockLedgerContext.close(outer);
        }
    }

    /** WMS 回传入口：event = pick | outbound | transit | signed（幂等）。 */
    @Operation(summary = "模拟 WMS 回传：pick/outbound/transit/signed；?dryRun=true 只预演，返回将要发生什么")
    @PostMapping("/{id}/wms-callback")
    public Result<?> wmsCallback(@PathVariable Long id,
                                 @RequestParam String event,
                                 @RequestParam(required = false) Boolean dryRun) {
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(deliveryFlowService.previewWmsCallback(id, event)));
        }
        return Result.ok(deliveryFlowService.wmsCallback(id, event));
    }

    // ------------------------------------------------------------------ 待下发人工干预（L3，支持 dryRun 预演）

    /**
     * 编辑待下发包裹：改发货仓 / 改明细数量。守卫：仅 status=0 待下发；改动后 source=1 并重算订单 split_status。
     *
     * <p>**校验顺序（严格，业务守卫绝不抢跑）**：
     * ① 存在性（发货单不存在 → code=1）
     * → ② **version 乐观锁**：缺版本号 → {@code HTTP 400 + code=400「缺少版本号，请刷新页面后重试」}；
     *    与库里当前版本不一致（读之后被别人改过）→
     *    {@code HTTP 409 + code=409「数据已被他人修改（可能其他人在编辑），请刷新后重试」}
     *    （HTTP 状态码与 body.code 一致，见 {@code GlobalExceptionHandler}）
     * → ③ 业务守卫（status=0，service 层）。
     *
     * <p>为什么 version 必须排在业务守卫之前：两者说的是不同的事 —— 409 是「期间被别人改过」，
     * 守卫失败是「这张单当前状态不允许」。顺序反了，「数据被别人改了」会被误报成「状态不对」，用户查错方向。
     * 乐观锁是 **fail-closed**：缺版本号一律拒，绝不静默降级成「无版本校验」。
     */
    @Operation(summary = "编辑发货单（待下发阶段改发货仓/改件数）；需带 version，?dryRun=true 只预演")
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @RequestParam(required = false) Boolean dryRun,
                                              @RequestBody DeliveryUpdateRequest body) {
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(
                    deliveryEditService.previewUpdate(id, body.getWarehouseId(), body.getItems())));
        }
        // ① 存在性
        DeliveryOrder current = deliveryOrderService.getById(id);
        if (current == null) return Result.fail("发货单不存在：" + id);
        // ② 乐观锁：缺版本号 → 400；版本对不上 → 409（都必须在业务守卫之前）
        // 缺版本号 → 真 HTTP 400；与库里版本不一致 → 真 HTTP 409（见 GlobalExceptionHandler）
        OptimisticLockGuard.requireVersion(body.getVersion());
        OptimisticLockGuard.requireSameVersion(body.getVersion(), current.getVersion());
        // ③ 业务守卫（service 里的 requirePending）+ 落库
        return Result.ok(deliveryEditService.update(id, body.getWarehouseId(), body.getItems()));
    }

    /**
     * 拆分待下发包裹：按明细数量拆出一个新的待下发包裹。守卫：仅 status=0；源包与新包 source=1。
     *
     * <p>校验顺序同 {@link #update}：① 存在性 → ② 参数（要拆哪些明细）→ ③ version（缺 400 / 冲突 409）
     * → ④ 业务守卫（status=0，service 层）。**dryRun 分支在版本校验之前**：预演不落库，不需要版本号。
     */
    @Operation(summary = "拆分发货单（把一个包裹拆成两个）；需带 version，?dryRun=true 只预演")
    @PostMapping("/{id}/split")
    public Result<Map<String, Object>> split(@PathVariable Long id,
                                             @RequestParam(required = false) Boolean dryRun,
                                             @RequestBody DeliverySplitRequest body) {
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(
                    deliveryEditService.previewSplit(id, body.getNewWarehouseId(), body.getItems())));
        }
        // ① 存在性
        DeliveryOrder current = deliveryOrderService.getById(id);
        if (current == null) return Result.fail("发货单不存在：" + id);
        // ② 参数
        if (body == null || body.getItems() == null || body.getItems().isEmpty()) {
            return Result.fail("请指定要拆出的明细与数量");
        }
        // ③ 乐观锁：缺失 → 400，版本对不上（期间被别人拆/改过）→ 409；都必须在业务守卫之前
        OptimisticLockGuard.requireVersion(body.getVersion());
        OptimisticLockGuard.requireSameVersion(body.getVersion(), current.getVersion());
        // ④ 业务守卫（service 里的 requirePending）+ 落库
        return Result.ok(deliveryEditService.split(id, body.getNewWarehouseId(), body.getItems()));
    }

    /**
     * 合并待下发包裹：把 {id} 并入 targetId（同订单、同待下发）。
     *
     * <p>校验顺序同上：① 存在性 → ② 参数（targetId 必填、不能与自己合并）→ ③ version → ④ 业务守卫。
     */
    @Operation(summary = "合并发货单（把包裹并到另一个包裹）；需带 version，?dryRun=true 只预演")
    @PostMapping("/{id}/merge")
    public Result<Map<String, Object>> merge(@PathVariable Long id,
                                             @RequestParam(required = false) Boolean dryRun,
                                             @RequestBody DeliveryMergeRequest body) {
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(deliveryEditService.previewMerge(id, body.getTargetId())));
        }
        // ① 存在性
        DeliveryOrder current = deliveryOrderService.getById(id);
        if (current == null) return Result.fail("发货单不存在：" + id);
        // ② 参数
        if (body == null || body.getTargetId() == null) return Result.fail("请指定要并入的目标包裹");
        if (body.getTargetId().equals(id)) return Result.fail("不能与自己合并");
        // ③ 乐观锁
        OptimisticLockGuard.requireVersion(body.getVersion());
        OptimisticLockGuard.requireSameVersion(body.getVersion(), current.getVersion());
        // ④ 业务守卫 + 落库
        return Result.ok(deliveryEditService.merge(id, body.getTargetId()));
    }

    /**
     * 删除待下发包裹：还有明细时必须带 targetId（等同于并入别处）。守卫：仅 status=0 待下发。
     *
     * <p>**version 走 query 而不是 body**：{@code DELETE} 带请求体在部分代理/CDN/网关上是会被丢的
     * （HTTP/1.0 网关、某些 WAF 直接吞 body），会出现「浏览器觉得传了、后端收到 null」→ 莫名其妙 400。
     * 所以这个接口的版本号是 {@code ?version=}，与 {@code ?targetId=} 同一形态。
     * 调用示例：{@code DELETE /admin/deliveries/12?targetId=13&version=3}
     */
    @Operation(summary = "删除发货单（有明细时须给 targetId，等于并到别处）；需带 ?version=，?dryRun=true 只预演")
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id,
                                              @RequestParam(required = false) Long targetId,
                                              @RequestParam(required = false) Integer version,
                                              @RequestParam(required = false) Boolean dryRun) {
        if (PreviewResult.isDryRun(dryRun)) {
            return Result.ok(PreviewResult.of(deliveryEditService.previewDelete(id, targetId)));
        }
        // ① 存在性
        DeliveryOrder current = deliveryOrderService.getById(id);
        if (current == null) return Result.fail("发货单不存在：" + id);
        // ② 参数：targetId 是否必填取决于「还有没有明细」，属于业务守卫（service 里有准确中文原因），此处不抢跑
        // ③ 乐观锁（query 参数）
        OptimisticLockGuard.requireVersion(version);
        OptimisticLockGuard.requireSameVersion(version, current.getVersion());
        // ④ 业务守卫 + 落库（有明细时内部转 merge，同样走 markManual 的影响行数闸门）
        return Result.ok(deliveryEditService.delete(id, targetId));
    }

    // ------------------------------------------------------------------ 查询（只读）

    /**
     * 批量查「包裹数」：订单列表页的「N 个包裹」标签用。
     * 只统计未取消的子单；orderIds 用逗号分隔（一次最多 50 个，够一页用）。
     */
    @GetMapping("/counts")
    public Result<Map<Long, Integer>> counts(@RequestParam String orderIds) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (String part : orderIds.split(",")) {
            if (part.isBlank()) continue;
            Long orderId;
            try {
                orderId = Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            long count = deliveryOrderService.count(new LambdaQueryWrapper<DeliveryOrder>()
                    .eq(DeliveryOrder::getOrderId, orderId)
                    .ne(DeliveryOrder::getStatus, DeliveryOrder.CANCELLED));
            result.put(orderId, (int) count);
        }
        return Result.ok(result);
    }

    /** 按父订单查子发货单（带仓库名、明细、运单号） */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam Long orderId) {
        List<DeliveryOrder> deliveries = deliveryOrderService.list(
                new LambdaQueryWrapper<DeliveryOrder>()
                        .eq(DeliveryOrder::getOrderId, orderId).orderByAsc(DeliveryOrder::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeliveryOrder d : deliveries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("deliverySn", d.getDeliverySn());
            item.put("orderId", d.getOrderId());
            item.put("warehouseId", d.getWarehouseId());
            Warehouse wh = warehouseService.getById(d.getWarehouseId());
            item.put("warehouseName", wh == null ? "" : wh.getName());
            item.put("status", d.getStatus());
            item.put("source", d.getSource());
            item.put("receiverName", d.getReceiverName());
            item.put("createdAt", d.getCreatedAt());
            // 乐观锁版本号必须回给前端：PUT /admin/deliveries/{id} 的 body 要原样带回来
            item.put("version", d.getVersion());
            item.put("items", deliveryItemService.list(new LambdaQueryWrapper<DeliveryItem>()
                    .eq(DeliveryItem::getDeliveryId, d.getId())));
            DeliveryLogistics logistics = deliveryLogisticsService.getOne(
                    new LambdaQueryWrapper<DeliveryLogistics>()
                            .eq(DeliveryLogistics::getDeliveryId, d.getId()), false);
            item.put("company", logistics == null ? null : logistics.getCompany());
            item.put("trackNo", logistics == null ? null : logistics.getTrackNo());
            result.add(item);
        }
        return Result.ok(result);
    }
}
