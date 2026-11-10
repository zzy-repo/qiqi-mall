package com.qiqi.mall.oms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.mapper.DeliveryOrderMapper;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import org.springframework.stereotype.Service;

@Service
public class DeliveryOrderServiceImpl extends ServiceImpl<DeliveryOrderMapper, DeliveryOrder> implements DeliveryOrderService {
}
