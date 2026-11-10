package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.Sku;
import com.qiqi.mall.mapper.SkuMapper;
import com.qiqi.mall.service.SkuService;
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
