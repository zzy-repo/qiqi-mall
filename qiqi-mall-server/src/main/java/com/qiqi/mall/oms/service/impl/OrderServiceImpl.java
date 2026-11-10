package com.qiqi.mall.oms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.mapper.OrderMapper;
import com.qiqi.mall.oms.service.OrderService;
import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {
}
