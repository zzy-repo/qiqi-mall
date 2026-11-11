package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.oms.service.DeliveryItemService;
import com.qiqi.mall.oms.service.DeliveryLogisticsService;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.WarehouseService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 买家查看自己订单的包裹与物流（方案 3b） */
@Tag(name = "买家端-包裹物流", description = "买家查看自己订单的包裹与运单号")
@RestController
@RequestMapping("/portal/deliveries")
public class PortalDeliveryController {

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryItemService deliveryItemService;
    private final DeliveryLogisticsService deliveryLogisticsService;
    private final WarehouseService warehouseService;
    private final OrderService orderService;

    public PortalDeliveryController(DeliveryOrderService deliveryOrderService, DeliveryItemService deliveryItemService,
                                    DeliveryLogisticsService deliveryLogisticsService, WarehouseService warehouseService,
                                    OrderService orderService) {
        this.deliveryOrderService = deliveryOrderService;
        this.deliveryItemService = deliveryItemService;
        this.deliveryLogisticsService = deliveryLogisticsService;
        this.warehouseService = warehouseService;
        this.orderService = orderService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam Long orderId) {
        Order order = orderService.getById(orderId);
        // 归属校验：只能看自己的订单
        if (order == null || !order.getMemberId().equals(CurrentUser.id())) return Result.fail("订单不存在");

        List<Map<String, Object>> result = new ArrayList<>();
        for (DeliveryOrder d : deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId).orderByAsc(DeliveryOrder::getId))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("deliverySn", d.getDeliverySn());
            item.put("status", d.getStatus());
            Warehouse wh = warehouseService.getById(d.getWarehouseId());
            item.put("warehouseName", wh == null ? "" : wh.getName());
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
