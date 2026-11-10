package com.qiqi.mall.catalog.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.catalog.entity.ProductGallery;
import com.qiqi.mall.catalog.mapper.ProductGalleryMapper;
import com.qiqi.mall.catalog.service.ProductGalleryService;
import org.springframework.stereotype.Service;

@Service
public class ProductGalleryServiceImpl extends ServiceImpl<ProductGalleryMapper, ProductGallery> implements ProductGalleryService {
}
