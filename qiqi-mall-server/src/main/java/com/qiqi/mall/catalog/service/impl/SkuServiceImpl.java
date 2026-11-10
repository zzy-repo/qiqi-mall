package com.qiqi.mall.catalog.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.mapper.SkuMapper;
import com.qiqi.mall.catalog.service.SkuService;
import org.springframework.stereotype.Service;

@Service
public class SkuServiceImpl extends ServiceImpl<SkuMapper, Sku> implements SkuService {

    @Override
    public boolean deductStock(Long skuId, int quantity) {
        return baseMapper.deductStock(skuId, quantity) > 0;
    }

    @Override
    public void restoreStock(Long skuId, int quantity) {
        baseMapper.restoreStock(skuId, quantity);
    }
}
