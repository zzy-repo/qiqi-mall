package com.qiqi.mall.catalog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiqi.mall.catalog.entity.Sku;

public interface SkuService extends IService<Sku> {

    /**
     * 原子扣减库存，返回 false 表示库存不足。
     *
     * <p><b>调用前必须声明台账上下文</b>：
     * {@code StockLedgerContext.open(orderId, StockLedger.REASON_ORDER_CREATE, null, null)}，
     * 否则抛 {@code IllegalStateException}（无台账的库存变更 = 缺陷本体）。
     *
     * @see com.qiqi.mall.stock.service.StockLedgerService#deduct(Long, int)
     */
    boolean deductStock(Long skuId, int quantity);

    /**
     * 回补库存（审核不通过 / 取消包裹 / 退货退款）。
     *
     * <p><b>调用前必须声明台账上下文</b>（订单 + reason + 触发单据），否则抛
     * {@code IllegalStateException}。同一个触发重放会被幂等键跳过、不同入口补齐同一件货会被
     * 数量守恒夹紧 —— 两种情况都<b>不会</b>再加库存。
     *
     * @see com.qiqi.mall.stock.service.StockLedgerService#restore(Long, int)
     */
    void restoreStock(Long skuId, int quantity);
}
