package com.qiqi.mall.catalog.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.catalog.entity.ProductType;
import com.qiqi.mall.catalog.service.ProductTypeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/product-types")
public class ProductTypeController {

    private final ProductTypeService productTypeService;

    public ProductTypeController(ProductTypeService productTypeService) {
        this.productTypeService = productTypeService;
    }

    @GetMapping
    public Result<List<ProductType>> list() {
        return Result.ok(productTypeService.list());
    }

    @GetMapping("/{id}")
    public Result<ProductType> get(@PathVariable Long id) {
        return Result.ok(productTypeService.getById(id));
    }

    @PostMapping
    public Result<ProductType> create(@RequestBody ProductType body) {
        body.setId(null);
        productTypeService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ProductType body) {
        body.setId(id);
        productTypeService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productTypeService.removeById(id);
        return Result.ok();
    }
}
