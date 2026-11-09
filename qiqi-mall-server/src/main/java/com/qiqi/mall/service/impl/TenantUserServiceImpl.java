package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.TenantUser;
import com.qiqi.mall.mapper.TenantUserMapper;
import com.qiqi.mall.service.TenantUserService;
import org.springframework.stereotype.Service;

@Service
public class TenantUserServiceImpl extends ServiceImpl<TenantUserMapper, TenantUser> implements TenantUserService {
}
