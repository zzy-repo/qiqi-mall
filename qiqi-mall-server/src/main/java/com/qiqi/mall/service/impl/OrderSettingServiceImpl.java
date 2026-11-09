package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.OrderSetting;
import com.qiqi.mall.mapper.OrderSettingMapper;
import com.qiqi.mall.service.OrderSettingService;
import org.springframework.stereotype.Service;

@Service
public class OrderSettingServiceImpl extends ServiceImpl<OrderSettingMapper, OrderSetting> implements OrderSettingService {
}
