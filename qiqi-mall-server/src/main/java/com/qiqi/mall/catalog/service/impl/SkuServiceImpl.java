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
 * 由台账服务记一行账、过幂等键与数量守恒两道闸门，再动 {@code sku.stock}。
 * 没有上下文会直接抛异常（fail-closed）—— 不允许出现「改了库存但账上没有」。
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
