package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.Admin;
import com.qiqi.mall.mapper.AdminMapper;
import com.qiqi.mall.service.AdminService;
import org.springframework.stereotype.Service;

@Service
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements AdminService {
}
