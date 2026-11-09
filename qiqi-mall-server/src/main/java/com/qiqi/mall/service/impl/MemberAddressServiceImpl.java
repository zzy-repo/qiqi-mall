package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.MemberAddress;
import com.qiqi.mall.mapper.MemberAddressMapper;
import com.qiqi.mall.service.MemberAddressService;
import org.springframework.stereotype.Service;

@Service
public class MemberAddressServiceImpl extends ServiceImpl<MemberAddressMapper, MemberAddress> implements MemberAddressService {
}
