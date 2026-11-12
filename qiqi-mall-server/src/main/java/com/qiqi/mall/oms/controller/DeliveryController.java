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
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.security.ConfirmCodeService;
import com.qiqi.mall.shared.security.ConfirmOps;
import com.qiqi.mall.shared.security.ConfirmPreview;
import com.qiqi.mall.shared.security.ConfirmRequest;
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
 * <p>**本接口的 7 个写操作全部是 L3（高影响/不可逆）→ 一律要确认码**，统一契约：
 * <pre>
 * ① 预演  POST/PUT/DELETE ...?dryRun=true   → 不落库，返回 { willDo, confirmCode, expiresInSec }
 * ② 执行  同一地址（不带 dryRun）+ 带 confirmCode（body 或 ?confirmCode=） → 校验通过才执行
 * </pre>
 * 预演方法（{@code DeliveryFlowService#previewXxx / DeliveryEditService#previewXxx}）与执行路径共用同一套守卫，
 * 所以预演阶段就会把「该包裹已下发不能改」这类问题直接告诉调用方，不会签出一个注定失败的码。
 */
@Tag(name = "管理端-发货单与履约", description = "发货单下发/取消/WMS 回传，以及待下发阶段的人工干预：改仓改量、拆分、合并、删除（均需确认码）")
@RestController
@RequestMapping("/admin/deliveries")
public class DeliveryController {

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    private final DeliveryFlowService deliveryFlowService;
    private final DeliveryEditService deliveryEditService;
    private final WarehouseService warehouseService;
    private final ConfirmCodeService confirmCodeService;

    public DeliveryController(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                              DeliveryLogisticsService deliveryLogisticsService, DeliveryFlowService deliveryFlowService,
                              DeliveryEditService deliveryEditService, WarehouseService warehouseService,
                              ConfirmCodeService confirmCodeService) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.deliveryFlowService = deliveryFlowService;
        this.deliveryEditService = deliveryEditService;
        this.warehouseService = warehouseService;
        this.confirmCodeService = confirmCodeService;
    }

    // ------------------------------------------------------------------ 状态流转（L3，要码）

    @Operation(summary = "下发发货单给 WMS（仅待下发）；需带 confirmCode，?dryRun=true 只预演")
    @PostMapping("/{id}/dispatch")
    public Result<?> dispatch(@PathVariable Long id,
                              @RequestParam(required = false) Boolean dryRun,
                              @RequestBody(required = false) ConfirmRequest req) {
        if (ConfirmCodeService.isDryRun(dryRun)) {
            ConfirmPreview preview = deliveryFlowService.previewDispatch(id);
            return Result.ok(confirmCodeService.preflight(preview.willDo(), ConfirmOps.DISPATCH, id, preview.extras()));
        }
        String error = confirmCodeService.verify(req == null ? null : req.getConfirmCode(),
                ConfirmOps.DISPATCH, id, deliveryFlowService.extrasOfDispatch(id));
        if (error != null) return Result.fail(error);
        return Result.ok(deliveryFlowService.dispatch(id));
    }

    @Operation(summary = "取消发货单（仅待下发，回补库存）；需带 confirmCode，?dryRun=true 只预演")
    @PostMapping("/{id}/cancel")
    public Result<?> cancel(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun,
                            @RequestBody(required = false) ConfirmRequest req) {
        if (ConfirmCodeService.isDryRun(dryRun)) {
            ConfirmPreview preview = deliveryFlowService.previewCancel(id);
            return Result.ok(confirmCodeService.preflight(preview.willDo(), ConfirmOps.DELIVERY_CANCEL, id, preview.extras()));
        }
        String error = confirmCodeService.verify(req == null ? null : req.getConfirmCode(),
                ConfirmOps.DELIVERY_CANCEL, id, deliveryFlowService.extrasOfCancel(id));
        if (error != null) return Result.fail(error);
        return Result.ok(deliveryFlowService.cancel(id));
    }

    /** WMS 回传入口：event = pick | outbound | transit | signed（幂等）。需确认码。 */
    @Operation(summary = "模拟 WMS 回传：pick/outbound/transit/signed；需带 confirmCode，?dryRun=true 只预演")
    @PostMapping("/{id}/wms-callback")
    public Result<?> wmsCallback(@PathVariable Long id,
                                 @RequestParam String event,
                                 @RequestParam(required = false) Boolean dryRun,
                                 @RequestBody(required = false) ConfirmRequest req) {
        if (ConfirmCodeService.isDryRun(dryRun)) {
            ConfirmPreview preview = deliveryFlowService.previewWmsCallback(id, event);
            return Result.ok(confirmCodeService.preflight(preview.willDo(), ConfirmOps.WMS_CALLBACK, id, preview.extras()));
        }
        String error = confirmCodeService.verify(req == null ? null : req.getConfirmCode(),
                ConfirmOps.WMS_CALLBACK, id, deliveryFlowService.extrasOfWmsCallback(id, event));
        if (error != null) return Result.fail(error);
        return Result.ok(deliveryFlowService.wmsCallback(id, event));
    }

    // ------------------------------------------------------------------ 待下发人工干预（L3，要码）

    /**
     * 编辑待下发包裹：改发货仓 / 改明细数量。守卫：仅 status=0 待下发；改动后 source=1 并重算订单 split_status。
     */
    @Operation(summary = "编辑发货单（待下发阶段改发货仓/改件数）；需带 confirmCode，?dryRun=true 只预演")
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @RequestParam(required = false) Boolean dryRun,
                                              @RequestParam(required = false) String confirmCode,
                                              @RequestBody DeliveryUpdateRequest body) {
        String code = ConfirmRequest.pick(body.getConfirmCode(), confirmCode);
        if (ConfirmCodeService.isDryRun(dryRun)) {
            ConfirmPreview preview = deliveryEditService.previewUpdate(id, body.getWarehouseId(), body.getItems());
            return Result.ok(confirmCodeService.preflight(preview.willDo(), ConfirmOps.DELIVERY_EDIT, id, preview.extras()));
        }
        String error = confirmCodeService.verify(code, ConfirmOps.DELIVERY_EDIT, id,
                deliveryEditService.extrasOfUpdate(id, body.getWarehouseId(), body.getItems()));
        if (error != null) return Result.fail(error);
        return Result.ok(deliveryEditService.update(id, body.getWarehouseId(), body.getItems()));
    }

    /**
     * 拆分待下发包裹：按明细数量拆出一个新的待下发包裹。守卫：仅 status=0；源包与新包 source=1。
     */
    @Operation(summary = "拆分发货单（把一个包裹拆成两个）；需带 confirmCode，?dryRun=true 只预演")
    @PostMapping("/{id}/split")
    public Result<Map<String, Object>> split(@PathVariable Long id,
                                             @RequestParam(required = false) Boolean dryRun,
                                             @RequestParam(required = false) String confirmCode,
                                             @RequestBody DeliverySplitRequest body) {
        String code = ConfirmRequest.pick(body.getConfirmCode(), confirmCode);
        if (ConfirmCodeService.isDryRun(dryRun)) {
            ConfirmPreview preview = deliveryEditService.previewSplit(id, body.getNewWarehouseId(), body.getItems());
            return Result.ok(confirmCodeService.preflight(preview.willDo(), ConfirmOps.DELIVERY_SPLIT, id, preview.extras()));
        }
        String error = confirmCodeService.verify(code, ConfirmOps.DELIVERY_SPLIT, id,
                deliveryEditService.extrasOfSplit(id, body.getNewWarehouseId(), body.getItems()));
        if (error != null) return Result.fail(error);
        return Result.ok(deliveryEditService.split(id, body.getNewWarehouseId(), body.getItems()));
    }

    /**
     * 合并待下发包裹：把 {id} 并入 targetId（同订单、同待下发）。
     */
    @Operation(summary = "合并发货单（把包裹并到另一个包裹）；需带 confirmCode，?dryRun=true 只预演")
    @PostMapping("/{id}/merge")
    public Result<Map<String, Object>> merge(@PathVariable Long id,
                                             @RequestParam(required = false) Boolean dryRun,
                                             @RequestParam(required = false) String confirmCode,
                                             @RequestBody DeliveryMergeRequest body) {
        String code = ConfirmRequest.pick(body.getConfirmCode(), confirmCode);
        if (ConfirmCodeService.isDryRun(dryRun)) {
            ConfirmPreview preview = deliveryEditService.previewMerge(id, body.getTargetId());
            return Result.ok(confirmCodeService.preflight(preview.willDo(), ConfirmOps.DELIVERY_MERGE, id, preview.extras()));
        }
        String error = confirmCodeService.verify(code, ConfirmOps.DELIVERY_MERGE, id,
                deliveryEditService.extrasOfMerge(id, body.getTargetId()));
        if (error != null) return Result.fail(error);
        return Result.ok(deliveryEditService.merge(id, body.getTargetId()));
    }

    /**
     * 删除待下发包裹：还有明细时必须带 targetId（等同于并入别处）。守卫：仅 status=0 待下发。
     */
    @Operation(summary = "删除发货单（有明细时须给 targetId，等于并到别处）；需带 confirmCode，?dryRun=true 只预演")
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id,
                                              @RequestParam(required = false) Long targetId,
                                              @RequestParam(required = false) Boolean dryRun,
                                              @RequestParam(required = false) String confirmCode,
                                              @RequestBody(required = false) ConfirmRequest body) {
        String code = ConfirmRequest.pick(body == null ? null : body.getConfirmCode(), confirmCode);
        if (ConfirmCodeService.isDryRun(dryRun)) {
            ConfirmPreview preview = deliveryEditService.previewDelete(id, targetId);
            return Result.ok(confirmCodeService.preflight(preview.willDo(), ConfirmOps.DELIVERY_DELETE, id, preview.extras()));
        }
        String error = confirmCodeService.verify(code, ConfirmOps.DELIVERY_DELETE, id,
                deliveryEditService.extrasOfDelete(id, targetId));
        if (error != null) return Result.fail(error);
        return Result.ok(deliveryEditService.delete(id, targetId));
    }

    // ------------------------------------------------------------------ 查询（只读，不要码）

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
