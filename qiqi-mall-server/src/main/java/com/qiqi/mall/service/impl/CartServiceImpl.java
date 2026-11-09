package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.Cart;
import com.qiqi.mall.mapper.CartMapper;
import com.qiqi.mall.service.CartService;
import org.springframework.stereotype.Service;

@Service
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements CartService {
}
