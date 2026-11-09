package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Admin;
import com.qiqi.mall.service.AdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admins")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public Result<List<Admin>> list(@RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<Admin> query = new LambdaQueryWrapper<>();
        query.eq(status != null, Admin::getStatus, status).orderByAsc(Admin::getId);
        return Result.ok(adminService.list(query));
    }

    @PostMapping
    public Result<Admin> create(@RequestBody Admin body) {
        body.setId(null);
        if (body.getStatus() == null) body.setStatus(1);
        adminService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Admin body) {
        body.setId(id);
        adminService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminService.removeById(id);
        return Result.ok();
    }
}
