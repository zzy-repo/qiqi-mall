package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Plan;
import com.qiqi.mall.entity.Tenant;
import com.qiqi.mall.entity.TenantPlan;
import com.qiqi.mall.entity.TenantUser;
import com.qiqi.mall.service.PlanService;
import com.qiqi.mall.service.TenantPlanService;
import com.qiqi.mall.service.TenantService;
import com.qiqi.mall.service.TenantUserService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final TenantUserService tenantUserService;
    private final TenantPlanService tenantPlanService;
    private final PlanService planService;

    public TenantController(TenantService tenantService, TenantUserService tenantUserService,
                            TenantPlanService tenantPlanService, PlanService planService) {
        this.tenantService = tenantService;
        this.tenantUserService = tenantUserService;
        this.tenantPlanService = tenantPlanService;
        this.planService = planService;
    }

    @GetMapping
    public Result<List<Tenant>> list(@RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<Tenant> query = new LambdaQueryWrapper<>();
        query.eq(status != null, Tenant::getStatus, status).orderByAsc(Tenant::getId);
        return Result.ok(tenantService.list(query));
    }

    @GetMapping("/{id}")
    public Result<Tenant> get(@PathVariable Long id) {
        return Result.ok(tenantService.getById(id));
    }

    @PostMapping
    public Result<Tenant> create(@RequestBody Tenant body) {
        body.setId(null);
        tenantService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Tenant body) {
        body.setId(id);
        tenantService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tenantService.removeById(id);
        return Result.ok();
    }

    /** 租户详情聚合：用户 + 当前套餐订阅 */
    @GetMapping("/{id}/detail")
    public Result<TenantDetailDTO> detail(@PathVariable Long id) {
        TenantDetailDTO dto = new TenantDetailDTO();
        dto.setTenant(tenantService.getById(id));
        dto.setUsers(tenantUserService.list(new LambdaQueryWrapper<TenantUser>()
                .eq(TenantUser::getTenantId, id)));
        dto.setPlans(tenantPlanService.list(new LambdaQueryWrapper<TenantPlan>()
                .eq(TenantPlan::getTenantId, id)));
        return Result.ok(dto);
    }

    public static class TenantDetailDTO {
        private Tenant tenant;
        private List<TenantUser> users;
        private List<TenantPlan> plans;

        public Tenant getTenant() { return tenant; }
        public void setTenant(Tenant tenant) { this.tenant = tenant; }
        public List<TenantUser> getUsers() { return users; }
        public void setUsers(List<TenantUser> users) { this.users = users; }
        public List<TenantPlan> getPlans() { return plans; }
        public void setPlans(List<TenantPlan> plans) { this.plans = plans; }
    }
}
