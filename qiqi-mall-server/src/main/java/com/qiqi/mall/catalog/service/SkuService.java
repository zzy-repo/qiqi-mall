package com.qiqi.mall.catalog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiqi.mall.catalog.entity.Sku;

public interface SkuService extends IService<Sku> {

    /**
     * 原子扣减库存（落点是 {@code sku_bucket} 的某个桶），返回 false 表示库存不足。
     *
     * <p>实现说明：同一份库存被物理拆成 N 个桶（默认 8），扣减按随机起点轮询桶做
     * {@code UPDATE ... WHERE stock >= qty}，0 行换下一个桶，N 个桶都不行才算库存不足。
     * {@code sku.stock} 是展示缓存，扣减不再写它（见 {@code SkuBucketService}）。
     *
     * <p><b>调用前必须声明台账上下文</b>：
     * {@code StockLedgerContext.open(orderId, StockLedger.REASON_ORDER_CREATE, null, null)}，
     * 否则抛 {@code IllegalStateException}（无台账的库存变更 = 缺陷本体）。
     *
     * @see com.qiqi.mall.stock.service.StockLedgerService#deduct(Long, int)
     */
    boolean deductStock(Long skuId, int quantity);

    /**
     * 回补库存（审核不通过 / 取消包裹 / 退货退款），回到台账里记的那个桶。
     *
     * <p><b>调用前必须声明台账上下文</b>（订单 + reason + 触发单据），否则抛
     * {@code IllegalStateException}。同一个触发重放会被幂等键跳过、不同入口补齐同一件货会被
     * 数量守恒夹紧 —— 两种情况都<b>不会</b>再加库存。
     *
     * @see com.qiqi.mall.stock.service.StockLedgerService#restore(Long, int)
     */
    void restoreStock(Long skuId, int quantity);
}
