package com.qiqi.mall.catalog.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.catalog.entity.Attribute;
import com.qiqi.mall.catalog.service.AttributeService;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.List;

@RestController
@RequestMapping("/admin/attributes")
public class AttributeController {

    private final AttributeService attributeService;

    public AttributeController(AttributeService attributeService) {
        this.attributeService = attributeService;
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

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        attributeService.removeById(id);
        return Result.ok();
    }
}
