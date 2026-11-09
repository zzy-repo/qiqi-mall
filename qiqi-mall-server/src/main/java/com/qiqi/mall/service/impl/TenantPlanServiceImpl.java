package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.TenantPlan;
import com.qiqi.mall.mapper.TenantPlanMapper;
import com.qiqi.mall.service.TenantPlanService;
import org.springframework.stereotype.Service;

@Service
public class TenantPlanServiceImpl extends ServiceImpl<TenantPlanMapper, TenantPlan> implements TenantPlanService {
}
