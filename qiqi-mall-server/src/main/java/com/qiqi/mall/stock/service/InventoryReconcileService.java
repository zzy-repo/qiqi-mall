package com.qiqi.mall.stock.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.catalog.service.SpecTextHelper;
import com.qiqi.mall.stock.StockProperties;
import com.qiqi.mall.stock.dto.InventoryDriftRow;
import com.qiqi.mall.stock.dto.InventoryReconcileDTO;
import com.qiqi.mall.stock.mapper.SkuBucketMapper;
import com.qiqi.mall.stock.mapper.StockLedgerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 库存对账（只读）：把「权威值（SUM 桶）」「展示缓存（sku.stock）」「台账净额（SUM delta）」
 * 摆在一起给人看。
 *
 * <h3>口径红线（这条接口存在的全部意义）</h3>
 * <ul>
 *   <li>{@code bucketSum} 与 {@code skuStock} <b>应当相等</b>，{@code drift} 是它们的差
 *       —— 这一对才需要对平，缓存滞后是唯一的正常偏差；</li>
 *   <li>{@code ledgerNet = SUM(stock_ledger.delta)} <b>不是余额</b>，是全生命周期进出和。
 *       它把该 SKU 历史上所有扣减与回补累加，必然与本仓当前库存差着「那笔没有被台账覆盖过的
 *       初始库存」。所以本类<b>绝不做 {@code ledgerNet == bucketSum} 的比较、校验或修正</b>：
 *       真去做，结果只有两种 —— 要么报一堆假错，要么顺手改掉真库存。</li>
 * </ul>
 *
 * <h3>桶数一致性（复用 check-data-integrity 的 ④）</h3>
 * 该 SKU 的桶数与配置 {@code qiqi.stock.bucket-count} 不等时，分桶模式下的扣减会去找不存在的桶
 * （多出来的尝试全部落空，最终可能假性缺货），所以一并返回
 * {@code expectedBucketCount} 与 {@code bucketCountMatch} 并打一条 warn 日志。
 */
@Service
public class InventoryReconcileService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconcileService.class);

    /** 对账页只展示最近这么多条库存流水（契约固定 20，别改成"按需"） */
    private static final int RECENT_LEDGER_ROWS = 20;

    /** SKU 合成编码前缀：sku 表没有 code 列，编码只能由 id 派生 */
    private static final String SKU_CODE_PREFIX = "SKU-";

    private final SkuService skuService;
    private final ProductService productService;
    private final SpecTextHelper specTextHelper;
    private final SkuBucketMapper skuBucketMapper;
    private final StockLedgerMapper stockLedgerMapper;
    private final StockProperties stockProperties;

    public InventoryReconcileService(SkuService skuService, ProductService productService,
                                     SpecTextHelper specTextHelper, SkuBucketMapper skuBucketMapper,
                                     StockLedgerMapper stockLedgerMapper, StockProperties stockProperties) {
        this.skuService = skuService;
        this.productService = productService;
        this.specTextHelper = specTextHelper;
        this.skuBucketMapper = skuBucketMapper;
        this.stockLedgerMapper = stockLedgerMapper;
        this.stockProperties = stockProperties;
    }

    /**
     * 单 SKU 对账。
     *
     * @throws IllegalArgumentException SKU 不存在（转成 {@code Result.fail("SKU 不存在（id=X）")}，
     *                                  而不是给一张「三个数都是 0」的卡片 —— 那会让人以为库存真的没了）
     */
    public InventoryReconcileDTO reconcile(long skuId) {
        Sku sku = skuService.getById(skuId);
        if (sku == null) {
            throw new IllegalArgumentException("SKU 不存在（id=" + skuId + "）");
        }
        InventoryReconcileDTO dto = new InventoryReconcileDTO();
        dto.setSkuId(skuId);
        dto.setSkuCode(SKU_CODE_PREFIX + skuId);
        dto.setSkuName(skuNameOf(sku));

        // ① 权威值 vs 展示缓存：这一对才是「应当相等」的
        int bucketSum = skuBucketMapper.sumStock(skuId);
        int skuStock = sku.getStock() == null ? 0 : sku.getStock();
        dto.setBucketSum(bucketSum);
        dto.setSkuStock(skuStock);
        dto.setDrift(bucketSum - skuStock);

        // ② 桶数与配置值是否一致（check-data-integrity ④ 的同口径检查，搬进接口里）
        int bucketCount = skuBucketMapper.countBuckets(skuId);
        int expected = stockProperties.getBucketCount();
        dto.setBucketCount(bucketCount);
        dto.setExpectedBucketCount(expected);
        dto.setBucketCountMatch(bucketCount == expected);
        if (bucketCount != expected) {
            log.warn("库存桶数与配置不一致：sku={} 实际={} 配置={}（分桶模式下该 SKU 会去找不存在的桶，可能假性缺货）",
                    skuId, bucketCount, expected);
        }

        // ③ 台账净额与最近流水：只如实返回，不参与上面的对平
        dto.setLedgerNet(stockLedgerMapper.sumDelta(skuId));
        dto.setLedgerRows(stockLedgerMapper.recentBySku(skuId, RECENT_LEDGER_ROWS));
        // ④ 「缓存最后回写」的可观测近似：sku 表没有时间戳列，取桶的最近变动时间
        dto.setLastRefreshAt(skuBucketMapper.lastChangedAt(skuId));
        return dto;
    }

    /**
     * 全库偏差分页：{@code |bucketSum - skuStock| > threshold} 的 SKU，偏差大的在前。
     *
     * @param threshold 阈值（件）；负数按 0 处理（0 = 任何非零偏差都要列出来）
     */
    public Page<InventoryDriftRow> pageDrift(long page, long size, int threshold) {
        Page<InventoryDriftRow> result = new Page<>(page, size);
        skuBucketMapper.pageCacheDrift(result, Math.max(threshold, 0));
        return result;
    }

    /**
     * SKU 名称：{@code product.name}（+ 规格文本）。
     *
     * <p>为什么必须由后端拼：{@code sku} 表只有 product_id / price / stock / pic，
     * <b>没有 name</b>；页面直接读 {@code row.skuName}，缺了就显示「（无名称）」。
     * 规格文本取自 {@code sku_spec}（如 {@code 颜色:黑,内存:256G}），两者拼成
     * {@code iPhone 15 · 颜色:黑,内存:256G}。
     * 全都没有时返回 {@code null}（页面对 null 显示「（无名称）」而不是空字符串）。
     */
    private String skuNameOf(Sku sku) {
        String productName = null;
        if (sku.getProductId() != null) {
            Product product = productService.getById(sku.getProductId());
            if (product != null && product.getName() != null && !product.getName().isBlank()) {
                productName = product.getName().trim();
            }
        }
        String specText = specTextHelper.textOf(sku.getId());
        boolean hasSpec = specText != null && !specText.isBlank();
        if (productName == null) return hasSpec ? specText : null;
        return hasSpec ? productName + " · " + specText : productName;
    }
}
