package com.qiqi.mall.controller;

import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.OrderSetting;
import com.qiqi.mall.service.OrderSettingService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/order-setting")
public class OrderSettingController {

    private final OrderSettingService orderSettingService;

    public OrderSettingController(OrderSettingService orderSettingService) {
        this.orderSettingService = orderSettingService;
    }

    /** 全局配置只有一行：取第一条，没有则返回空对象 */
    @GetMapping
    public Result<OrderSetting> get() {
        OrderSetting setting = orderSettingService.getOne(null, false);
        return Result.ok(setting != null ? setting : new OrderSetting());
    }

    @PutMapping
    public Result<OrderSetting> update(@RequestBody OrderSetting body) {
        OrderSetting existing = orderSettingService.getOne(null, false);
        if (existing == null) {
            body.setId(null);
            orderSettingService.save(body);
        } else {
            body.setId(existing.getId());
            orderSettingService.updateById(body);
        }
        return Result.ok(body);
    }
}
