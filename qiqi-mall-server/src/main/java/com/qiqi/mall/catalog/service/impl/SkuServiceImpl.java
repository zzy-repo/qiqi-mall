package com.qiqi.mall.catalog.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.mapper.SkuMapper;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.stock.service.StockLedgerService;
import org.springframework.stereotype.Service;

/**
 * SKU 服务。库存的扣/补<b>不在这里直接改库</b>，全量转给 {@link StockLedgerService}：
 *
 * <p>这里曾是「同一件货被回补两次」的源头（{@code restoreStock} 直接
 * {@code UPDATE sku SET stock = stock + N}，无台账、无查重、无互斥）。
 * 现在这里是**库存变更的唯一收口**：每个入口先声明
 * {@link com.qiqi.mall.stock.StockLedgerContext}（订单 + 原因 + 触发单据），
 * 由台账服务记一行账、过幂等键与数量守恒两道闸门，再动**分桶**。
 * 没有上下文会直接抛异常（fail-closed）—— 不允许出现「改了库存但账上没有」。
 *
 * <h3>本次变化：真正的落点是 sku_bucket，不是 sku.stock</h3>
 * 方法签名一个字都没改（调用方无需改动），但内部已经改走库存分桶：
 * <ul>
 *   <li>{@link #deductStock} → {@code StockLedgerService#deduct} → {@code SkuBucketService#deduct}：
 *       随机起点轮询 N 个桶做原子扣减，0 行换下一个桶；</li>
 *   <li>{@link #restoreStock} → {@code StockLedgerService#restore} → {@code SkuBucketService#restore}：
 *       回到台账记的那个桶；</li>
 *   <li>两条路径都<b>不再写 {@code sku.stock}</b> —— 它现在是展示缓存，由
 *       {@code StockCacheRefresher} 按 {@code SUM(桶)} 周期回写。</li>
 * </ul>
 *
 * <p>为什么值得这样改：{@code UPDATE sku SET stock = stock - N WHERE id=? AND stock>=N} 虽然
 * 原子且不超卖，但同一 SKU 的所有并发都排在同一把行锁上，热点 SKU 的 TPS 上限 = 单行行锁吞吐。
 * 拆成 N 行后并发分散到 N 把互不相干的行锁（口径见 {@code sql/_rollback/migrate-sku-bucket.sql}）。
 */
@Service
public class SkuServiceImpl extends ServiceImpl<SkuMapper, Sku> implements SkuService {

    private final StockLedgerService stockLedgerService;

    public SkuServiceImpl(StockLedgerService stockLedgerService) {
        this.stockLedgerService = stockLedgerService;
    }

    @Override
    public boolean deductStock(Long skuId, int quantity) {
        return stockLedgerService.deduct(skuId, quantity);
    }

    @Override
    public void restoreStock(Long skuId, int quantity) {
        stockLedgerService.restore(skuId, quantity);
    }
}
