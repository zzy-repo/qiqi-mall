package com.qiqi.mall.member.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.member.entity.Admin;
import com.qiqi.mall.member.mapper.AdminMapper;
import com.qiqi.mall.member.service.AdminService;
import org.springframework.stereotype.Service;

@Service
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements AdminService {
}
