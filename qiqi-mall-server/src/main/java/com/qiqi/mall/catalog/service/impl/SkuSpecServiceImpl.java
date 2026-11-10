package com.qiqi.mall.catalog.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.catalog.entity.SkuSpec;
import com.qiqi.mall.catalog.mapper.SkuSpecMapper;
import com.qiqi.mall.catalog.service.SkuSpecService;
import org.springframework.stereotype.Service;

@Service
public class SkuSpecServiceImpl extends ServiceImpl<SkuSpecMapper, SkuSpec> implements SkuSpecService {
}
