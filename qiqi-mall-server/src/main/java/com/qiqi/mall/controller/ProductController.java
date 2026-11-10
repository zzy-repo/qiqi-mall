package com.qiqi.mall.controller;

import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Product;
import com.qiqi.mall.service.ProductService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Result<List<Product>> list() {
        return Result.ok(productService.list());
    }

    @GetMapping("/{id}")
    public Result<Product> get(@PathVariable Long id) {
        return Result.ok(productService.getById(id));
    }

    @PostMapping
    public Result<Product> create(@RequestBody Product body) {
        body.setId(null);
        productService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Product body) {
        body.setId(id);
        productService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.removeById(id);
        return Result.ok();
    }
}
