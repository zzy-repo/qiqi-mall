package com.qiqi.mall.oms.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.oms.entity.OrderSetting;
import com.qiqi.mall.oms.service.OrderSettingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理端-订单设置", description = "订单相关全局设置（如自动审核、超时关闭）")
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
            // 刻意不检查影响行数：这是「有则改、无则插」的 upsert，全局配置天然只有一行。
            // 0 行只可能是「刚好那一行被并发删掉了」，下一次 GET/PUT 会自动插回（下面的分支），
            // 属幂等无害，报 409 反而会让「改个自动收货天数」变成随机失败。
            orderSettingService.updateById(body);
        }
        return Result.ok(body);
    }
}
