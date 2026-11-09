package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.Attribute;
import com.qiqi.mall.mapper.AttributeMapper;
import com.qiqi.mall.service.AttributeService;
import org.springframework.stereotype.Service;

@Service
public class AttributeServiceImpl extends ServiceImpl<AttributeMapper, Attribute> implements AttributeService {
}
