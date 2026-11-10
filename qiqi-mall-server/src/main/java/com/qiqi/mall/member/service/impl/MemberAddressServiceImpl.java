package com.qiqi.mall.member.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.member.entity.MemberAddress;
import com.qiqi.mall.member.mapper.MemberAddressMapper;
import com.qiqi.mall.member.service.MemberAddressService;
import org.springframework.stereotype.Service;

@Service
public class MemberAddressServiceImpl extends ServiceImpl<MemberAddressMapper, MemberAddress> implements MemberAddressService {
}
