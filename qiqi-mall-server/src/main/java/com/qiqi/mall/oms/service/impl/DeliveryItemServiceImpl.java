package com.qiqi.mall.oms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.oms.entity.DeliveryItem;
import com.qiqi.mall.oms.mapper.DeliveryItemMapper;
import com.qiqi.mall.oms.service.DeliveryItemService;
import org.springframework.stereotype.Service;

@Service
public class DeliveryItemServiceImpl extends ServiceImpl<DeliveryItemMapper, DeliveryItem> implements DeliveryItemService {
}
