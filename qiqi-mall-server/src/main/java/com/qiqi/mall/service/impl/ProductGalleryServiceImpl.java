package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.ProductGallery;
import com.qiqi.mall.mapper.ProductGalleryMapper;
import com.qiqi.mall.service.ProductGalleryService;
import org.springframework.stereotype.Service;

@Service
public class ProductGalleryServiceImpl extends ServiceImpl<ProductGalleryMapper, ProductGallery> implements ProductGalleryService {
}
