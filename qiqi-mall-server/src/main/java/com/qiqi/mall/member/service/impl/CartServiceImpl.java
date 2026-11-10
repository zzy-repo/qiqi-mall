package com.qiqi.mall.member.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.member.entity.Cart;
import com.qiqi.mall.member.mapper.CartMapper;
import com.qiqi.mall.member.service.CartService;
import org.springframework.stereotype.Service;

@Service
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements CartService {
}
