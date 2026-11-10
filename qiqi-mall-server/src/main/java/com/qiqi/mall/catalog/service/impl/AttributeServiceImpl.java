package com.qiqi.mall.catalog.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.catalog.entity.Attribute;
import com.qiqi.mall.catalog.mapper.AttributeMapper;
import com.qiqi.mall.catalog.service.AttributeService;
import org.springframework.stereotype.Service;

@Service
public class AttributeServiceImpl extends ServiceImpl<AttributeMapper, Attribute> implements AttributeService {
}
