package com.qiqi.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiqi.mall.entity.Sku;

public interface SkuService extends IService<Sku> {

    /** 原子扣减库存，返回 false 表示库存不足 */
    boolean deductStock(Long skuId, int quantity);

    /** 回补库存 */
    void restoreStock(Long skuId, int quantity);
}
