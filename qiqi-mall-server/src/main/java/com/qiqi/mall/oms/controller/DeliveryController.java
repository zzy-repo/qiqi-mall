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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 发货单接口（方案 3b）：下发 / 取消 / WMS 回传 / 查询 + 待下发阶段的人工干预（改仓改量/拆分/合并/删除） */
@Tag(name = "管理端-发货单与履约", description = "发货单下发/取消/WMS 回传，以及待下发阶段的人工干预：改仓改量、拆分、合并、删除")
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

    @Operation(summary = "下发发货单给 WMS（仅待下发可下发）")
    @PostMapping("/{id}/dispatch")
    public Result<DeliveryOrder> dispatch(@PathVariable Long id) {
        return Result.ok(deliveryFlowService.dispatch(id));
    }

    @Operation(summary = "取消发货单（仅待下发可取消，联动回补库存）")
    @PostMapping("/{id}/cancel")
    public Result<DeliveryOrder> cancel(@PathVariable Long id) {
        return Result.ok(deliveryFlowService.cancel(id));
    }

    /**
     * 编辑待下发包裹：改发货仓 / 改明细数量。
     * 守卫：仅 status=0 待下发；改动后该单 source=1 并重算订单 split_status。
     */
    @Operation(summary = "编辑发货单（待下发阶段改发货仓 / 改件数）")
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody DeliveryUpdateRequest body) {
        return Result.ok(deliveryEditService.update(id, body.getWarehouseId(), body.getItems()));
    }

    /**
     * 拆分待下发包裹：按明细数量拆出一个新的待下发包裹。
     * 守卫：仅 status=0 待下发；源包与新包 source 都置 1。
     */
    @Operation(summary = "拆分发货单（把一个包裹拆成两个）")
    @PostMapping("/{id}/split")
    public Result<Map<String, Object>> split(@PathVariable Long id, @RequestBody DeliverySplitRequest body) {
        return Result.ok(deliveryEditService.split(id, body.getNewWarehouseId(), body.getItems()));
    }

    /**
     * 合并待下发包裹：把 {id} 并入 targetId（同订单、同待下发）。
     * 守卫：两个包裹都必须是 status=0 待下发。
     */
    @Operation(summary = "合并发货单（把包裹并到另一个包裹）")
    @PostMapping("/{id}/merge")
    public Result<Map<String, Object>> merge(@PathVariable Long id, @RequestBody DeliveryMergeRequest body) {
        return Result.ok(deliveryEditService.merge(id, body.getTargetId()));
    }

    /**
     * 删除待下发包裹：还有明细时必须带 targetId（等同于并入别处）。
     * 守卫：仅 status=0 待下发。
     */
    @Operation(summary = "删除发货单（必须指定并入目标，等于并到别处）")
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id,
                                              @RequestParam(required = false) Long targetId) {
        return Result.ok(deliveryEditService.delete(id, targetId));
    }

    /** WMS 回传入口：event = pick | outbound | transit | signed（幂等） */
    @Operation(summary = "模拟 WMS 回传：pick/outbound/transit/signed，按状态幂等")
    @PostMapping("/{id}/wms-callback")
    public Result<DeliveryOrder> wmsCallback(@PathVariable Long id, @RequestParam String event) {
        return Result.ok(deliveryFlowService.wmsCallback(id, event));
    }

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
