package com.qiqi.mall.oms.controller;

import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.oms.service.WarehouseService;
import com.qiqi.mall.shared.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 仓库字典（方案 Commit 4「仓库管理页」的后端） */
@Tag(name = "管理端-仓库", description = "仓库字典与启停，拆单按仓库归属分配")
@RestController
@RequestMapping("/admin/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    public Result<List<Warehouse>> list() {
        return Result.ok(warehouseService.list());
    }

    @PostMapping
    public Result<Warehouse> create(@RequestBody Warehouse body) {
        body.setId(null);
        if (body.getStatus() == null) body.setStatus(1);
        warehouseService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Warehouse body) {
        body.setId(id);
        warehouseService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        warehouseService.removeById(id);
        return Result.ok();
    }
}
