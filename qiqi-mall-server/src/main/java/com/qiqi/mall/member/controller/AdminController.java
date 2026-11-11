package com.qiqi.mall.member.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.member.entity.Admin;
import com.qiqi.mall.member.service.AdminService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/admins")
public class AdminController {

    private final AdminService adminService;
    private final PasswordEncoder passwordEncoder;

    public AdminController(AdminService adminService, PasswordEncoder passwordEncoder) {
        this.adminService = adminService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public Result<List<Admin>> list(@RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<Admin> query = new LambdaQueryWrapper<>();
        query.eq(status != null, Admin::getStatus, status).orderByAsc(Admin::getId);
        return Result.ok(adminService.list(query));
    }

    /**
     * 新增管理员：密码必填且必须 BCrypt 加密后入库（否则登录接口的 passwordEncoder.matches 永远不匹配，
     * UI 建出来的账号登不进去 —— 之前就是这么坏的）。
     */
    @PostMapping
    public Result<Admin> create(@RequestBody Admin body) {
        if (body.getUsername() == null || body.getUsername().isBlank()) return Result.fail("登录名不能为空");
        if (body.getPassword() == null || body.getPassword().isBlank()) return Result.fail("密码不能为空");
        long exists = adminService.count(new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, body.getUsername()));
        if (exists > 0) return Result.fail("登录名已被占用");
        body.setId(null);
        body.setPassword(passwordEncoder.encode(body.getPassword()));
        if (body.getStatus() == null) body.setStatus(1);
        adminService.save(body);
        body.setPassword(null);   // 不回传密码
        return Result.ok(body);
    }

    /** 编辑管理员：密码传空表示不修改（只更新其它字段） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Admin body) {
        body.setId(id);
        if (body.getPassword() != null && body.getPassword().isBlank()) {
            body.setPassword(null);   // updateById 会忽略 null，等价于「不改密码」
        } else if (body.getPassword() != null) {
            body.setPassword(passwordEncoder.encode(body.getPassword()));
        }
        adminService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminService.removeById(id);
        return Result.ok();
    }
}
