package com.qiqi.mall.catalog.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.catalog.entity.Attribute;
import com.qiqi.mall.catalog.service.AttributeService;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.List;

@RestController
@RequestMapping("/admin/attributes")
public class AttributeController {

    private final AttributeService attributeService;
    private final CatalogDeleteGuard deleteGuard;

    public AttributeController(AttributeService attributeService, CatalogDeleteGuard deleteGuard) {
        this.attributeService = attributeService;
        this.deleteGuard = deleteGuard;
    }

    @GetMapping
    public Result<List<Attribute>> list(@RequestParam(required = false) Long typeId) {
        LambdaQueryWrapper<Attribute> query = new LambdaQueryWrapper<>();
        query.eq(typeId != null, Attribute::getTypeId, typeId).orderByAsc(Attribute::getId);
        return Result.ok(attributeService.list(query));
    }

    @GetMapping("/{id}")
    public Result<Attribute> get(@PathVariable Long id) {
        return Result.ok(attributeService.getById(id));
    }

    @PostMapping
    public Result<Attribute> create(@RequestBody Attribute body) {
        body.setId(null);
        attributeService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Attribute body) {
        body.setId(id);
        attributeService.updateById(body);
        return Result.ok();
    }

    /** 删除属性：已被 SKU 规格值使用则不允许删 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        String blocked = deleteGuard.checkAttribute(id);
        if (blocked != null) return Result.fail(blocked);
        attributeService.removeById(id);
        return Result.ok();
    }
}
