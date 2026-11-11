package com.qiqi.mall.catalog.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.catalog.entity.Category;
import com.qiqi.mall.catalog.service.CategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-商品分类", description = "商品分类树维护，含被商品引用时的删除拦截")
@RestController
@RequestMapping("/admin/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final CatalogDeleteGuard deleteGuard;

    public CategoryController(CategoryService categoryService, CatalogDeleteGuard deleteGuard) {
        this.categoryService = categoryService;
        this.deleteGuard = deleteGuard;
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

    /** 删除分类：有子分类或商品挂在该分类下时不允许删 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        String blocked = deleteGuard.checkCategory(id);
        if (blocked != null) return Result.fail(blocked);
        categoryService.removeById(id);
        return Result.ok();
    }
}
