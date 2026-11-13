package com.qiqi.mall.stock.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.stock.dto.InventoryDriftRow;
import com.qiqi.mall.stock.dto.InventoryReconcileDTO;
import com.qiqi.mall.stock.service.InventoryReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「库存对账」：桶（权威）↔ {@code sku.stock}（展示缓存）↔ 台账净额（全生命周期进出和）。
 *
 * <p>契约（前端 {@code admin-web/src/api/inventory.js} 已定稿）：
 * <pre>
 * GET /admin/inventory/reconcile?skuId=
 *   → Result&lt;{skuId, skuCode, skuName, bucketSum, skuStock, drift, lastRefreshAt,
 *              bucketCount, ledgerNet, ledgerRows[]}&gt;
 * GET /admin/inventory/reconcile/drift?page=&amp;size=&amp;threshold=100
 *   → Result&lt;Page&lt;{skuId, skuCode, bucketSum, skuStock, drift}&gt;&gt;
 * </pre>
 *
 * <h3>三个数不是一回事（接口只如实返回）</h3>
 * <ul>
 *   <li>{@code bucketSum = SUM(sku_bucket.stock)} —— 权威值；</li>
 *   <li>{@code skuStock = sku.stock} —— 展示缓存；{@code drift = bucketSum - skuStock}，
 *       这一对才「应当相等」；</li>
 *   <li>{@code ledgerNet = SUM(stock_ledger.delta)} —— <b>全生命周期进出和，不是余额</b>。
 *       后端<b>不做</b> {@code ledgerNet == bucketSum} 的校验/修正（详见
 *       {@link InventoryReconcileService} 的口径说明）。</li>
 * </ul>
 *
 * <p>两条都是只读：桶只由扣减/回补路径写，缓存只由 {@code StockCacheRefresher} 回写，
 * 台账只追加 —— 对账接口不许「顺手修一下」，那会把证据改成结论。
 *
 * <p>API Key（AI）访问：<b>刻意没有登记到 {@code ApiKeyAuthFilter.ADMIN_RULES}</b>
 * （现有 scope 里没有 stock:read 这一档，挂到 catalog:read 上等于让「只能改商品」的密钥
 * 读出全库库存与偏差），AI 调用会被兜底规则拦成 403。理由见交付报告。
 */
@Tag(name = "管理端-库存对账", description = "桶 vs 展示缓存的偏差、台账净额与最近流水、全库偏差 TOP")
@RestController
@RequestMapping("/admin/inventory")
public class AdminInventoryController {

    /** 单页上限：与订单/台账列表同一口径（前端每页 10 行，50 是给手工 curl 的余地） */
    private static final long MAX_SIZE = 50;

    /** 偏差阈值默认值（与前端 {@code threshold = ref(100)} 一致：100 件以内的滞后不值得报警） */
    private static final int DEFAULT_DRIFT_THRESHOLD = 100;

    private final InventoryReconcileService reconcileService;

    public AdminInventoryController(InventoryReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    /** 单 SKU 对账（三个数 + 桶数一致性 + 最近 20 条库存流水） */
    @Operation(summary = "单 SKU 库存对账：桶权威值 / 缓存 / 偏差 / 台账净额 / 最近 20 条流水")
    @GetMapping("/reconcile")
    public Result<InventoryReconcileDTO> reconcile(@RequestParam long skuId) {
        return Result.ok(reconcileService.reconcile(skuId));
    }

    /**
     * 全库偏差分页：{@code |bucketSum - skuStock| > threshold}，偏差大的在前。
     *
     * <p>{@code page}/{@code size} 与其它列表同口径（size 夹 1..50）；{@code threshold} 默认 100，
     * 传 0 = 任何非零偏差都列出来（排查缓存回写是否在工作时用它）。
     */
    @Operation(summary = "全库偏差分页：|SUM(桶) − sku.stock| > threshold，按偏差倒序")
    @GetMapping("/reconcile/drift")
    public Result<Page<InventoryDriftRow>> drift(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(defaultValue = "100") int threshold) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return Result.ok(reconcileService.pageDrift(safePage, safeSize, threshold));
    }
}
