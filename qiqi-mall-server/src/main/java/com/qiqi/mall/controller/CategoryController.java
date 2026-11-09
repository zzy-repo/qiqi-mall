package com.qiqi.mall.controller;

import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Category;
import com.qiqi.mall.service.CategoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<List<Category>> list() {
        return Result.ok(categoryService.list());
    }

    @GetMapping("/{id}")
    public Result<Category> get(@PathVariable Long id) {
        return Result.ok(categoryService.getById(id));
    }

    @PostMapping
    public Result<Category> create(@RequestBody Category body) {
        body.setId(null);
        categoryService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Category body) {
        body.setId(id);
        categoryService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.removeById(id);
        return Result.ok();
    }
}
