package com.qiqi.mall.channel.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.channel.entity.ChannelSyncLog;
import com.qiqi.mall.channel.service.ChannelShopService;
import com.qiqi.mall.channel.service.ChannelSyncService;
import com.qiqi.mall.shared.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端「多平台接入」：三个店铺的拉单入口 + 回传发货 + 同步日志。
 *
 * <p><b>接口清单（与 admin-web/src/api/channel.js 一一对应，别改参数名）</b>
 * <pre>
 * GET  /admin/channel-shops                                 渠道店铺 + 今日成功/失败数（channel:read）
 * POST /admin/channel-shops/{id}/pull                        立即拉单（channel:write，人工 JWT）
 * POST /admin/channel/push-shipment?orderId=                 回传发货（channel:write，人工 JWT）
 * GET  /admin/channel-sync-logs?shopId=&amp;action=&amp;result=&amp;page=&amp;size=   同步日志分页（channel:read）
 * </pre>
 *
 * <p><b>为什么回传不写成 {@code /admin/orders/{id}/push-shipment}</b>：
 * {@code ApiKeyAuthFilter} 按**路径前缀**匹配，{@code /admin/orders} 那条规则会把
 * {@code /admin/orders/{id}/push-shipment} 一起吃进 {@code order:write}。那样「回传发货」对
 * 持 {@code order:write} 的 API Key 就悄悄开放了 —— 而它是一次**外呼**（改动在平台侧，回不来）。
 * 挪到 {@code /admin/channel/**} 之后，写操作单独要 {@code channel:write}（不进 ALL_SCOPES，
 * 结构上没有任何 key 拿得到），AI 最多只能读店铺与日志。
 */
@Tag(name = "管理端-多平台接入", description = "渠道店铺、立即拉单、回传发货、同步日志")
@RestController
@RequestMapping("/admin")
public class ChannelAdminController {

    private final ChannelShopService shopService;
    private final ChannelSyncService syncService;

    public ChannelAdminController(ChannelShopService shopService, ChannelSyncService syncService) {
        this.shopService = shopService;
        this.syncService = syncService;
    }

    /** 三个店铺：平台类型 / 启用 / 水位 / 上次拉单 / 今日 OK·FAIL·SKIP */
    @Operation(summary = "渠道店铺列表（含今日成功/失败/跳过计数）")
    @GetMapping("/channel-shops")
    public Result<List<Map<String, Object>>> shops() {
        return Result.ok(shopService.listWithTodayCounts());
    }

    /**
     * 立即拉单（人工触发，本轮**没有**定时调度 —— 限流/调度在范围外）。
     *
     * <p>返回汇总：{@code total / created / skipped / failed / orderIds / platformSns}。
     * 失败抛业务异常，由 GlobalExceptionHandler 转成中文提示。
     */
    @Operation(summary = "立即拉单：平台 → 待审核订单(7)，重复单靠 channel_order_map 幂等闸门跳过")
    @PostMapping("/channel-shops/{id}/pull")
    public Result<Map<String, Object>> pull(@PathVariable Long id) {
        return Result.ok(syncService.pull(id));
    }

    /**
     * 回传发货：把该订单**已出库**的包裹逐个回传平台（失败自动重试一次）。
     *
     * <p>参数走 query（{@code ?orderId=}）而不是路径变量，也不带请求体：
     * 这条路径挂在 {@code /admin/channel} 下就是为了让 scope 干净，param 形式最省事。
     */
    @Operation(summary = "回传发货：已出库包裹 → 平台（失败重试一次，全程写 channel_sync_log）")
    @PostMapping("/channel/push-shipment")
    public Result<Map<String, Object>> pushShipment(@RequestParam Long orderId) {
        return Result.ok(syncService.pushShipment(orderId));
    }

    /** 同步日志分页（page/size，size 夹在 1..50；按 id 倒序 = 时间倒序） */
    @Operation(summary = "同步日志分页查询（可按店铺/动作/结果过滤）")
    @GetMapping("/channel-sync-logs")
    public Result<Page<ChannelSyncLog>> syncLogs(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) Long shopId,
                                                 @RequestParam(required = false) String action,
                                                 @RequestParam(required = false) String result) {
        return Result.ok(shopService.pageLogs(shopId, action, result, page, size));
    }
}
