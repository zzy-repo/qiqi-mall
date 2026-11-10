package com.qiqi.mall.oms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.oms.entity.OrderSetting;
import com.qiqi.mall.oms.mapper.OrderSettingMapper;
import com.qiqi.mall.oms.service.OrderSettingService;
import org.springframework.stereotype.Service;

@Service
public class OrderSettingServiceImpl extends ServiceImpl<OrderSettingMapper, OrderSetting> implements OrderSettingService {
}
