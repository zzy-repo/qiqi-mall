package com.qiqi.mall.oms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.oms.service.DeliveryFlowService;
import com.qiqi.mall.oms.service.DeliveryItemService;
import com.qiqi.mall.oms.service.DeliveryLogisticsService;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.WarehouseService;
import com.qiqi.mall.shared.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 发货单接口（方案 3b）：下发 / 取消 / WMS 回传 / 查询 */
@RestController
@RequestMapping("/admin/deliveries")
public class DeliveryController {

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    private final DeliveryFlowService deliveryFlowService;
    private final WarehouseService warehouseService;

    public DeliveryController(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                              DeliveryLogisticsService deliveryLogisticsService, DeliveryFlowService deliveryFlowService,
                              WarehouseService warehouseService) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.deliveryFlowService = deliveryFlowService;
        this.warehouseService = warehouseService;
    }

    @PostMapping("/{id}/dispatch")
    public Result<DeliveryOrder> dispatch(@PathVariable Long id) {
        return Result.ok(deliveryFlowService.dispatch(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<DeliveryOrder> cancel(@PathVariable Long id) {
        return Result.ok(deliveryFlowService.cancel(id));
    }

    /** WMS 回传入口：event = pick | outbound | transit | signed（幂等） */
    @PostMapping("/{id}/wms-callback")
    public Result<DeliveryOrder> wmsCallback(@PathVariable Long id, @RequestParam String event) {
        return Result.ok(deliveryFlowService.wmsCallback(id, event));
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
