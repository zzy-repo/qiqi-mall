package com.qiqi.mall.controller;

import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Sku;
import com.qiqi.mall.service.SkuService;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.List;

@RestController
@RequestMapping("/api/skus")
public class SkuController {

    private final SkuService skuService;

    public SkuController(SkuService skuService) {
        this.skuService = skuService;
    }

    @GetMapping
    public Result<List<Sku>> list(@RequestParam(required = false) Long productId) {
        LambdaQueryWrapper<Sku> query = new LambdaQueryWrapper<>();
        query.eq(productId != null, Sku::getProductId, productId).orderByAsc(Sku::getId);
        return Result.ok(skuService.list(query));
    }

    @GetMapping("/{id}")
    public Result<Sku> get(@PathVariable Long id) {
        return Result.ok(skuService.getById(id));
    }

    @PostMapping
    public Result<Sku> create(@RequestBody Sku body) {
        body.setId(null);
        skuService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Sku body) {
        body.setId(id);
        skuService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        skuService.removeById(id);
        return Result.ok();
    }
}
