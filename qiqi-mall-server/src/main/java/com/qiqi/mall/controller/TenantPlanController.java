package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.TenantPlan;
import com.qiqi.mall.service.TenantPlanService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tenant-plans")
public class TenantPlanController {

    private final TenantPlanService tenantPlanService;

    public TenantPlanController(TenantPlanService tenantPlanService) {
        this.tenantPlanService = tenantPlanService;
    }

    @GetMapping
    public Result<List<TenantPlan>> list(@RequestParam(required = false) Long tenantId) {
        LambdaQueryWrapper<TenantPlan> query = new LambdaQueryWrapper<>();
        query.eq(tenantId != null, TenantPlan::getTenantId, tenantId).orderByAsc(TenantPlan::getId);
        return Result.ok(tenantPlanService.list(query));
    }

    @PostMapping
    public Result<TenantPlan> create(@RequestBody TenantPlan body) {
        body.setId(null);
        tenantPlanService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody TenantPlan body) {
        body.setId(id);
        tenantPlanService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tenantPlanService.removeById(id);
        return Result.ok();
    }
}
