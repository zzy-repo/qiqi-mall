package com.qiqi.mall.catalog.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.catalog.entity.ProductType;
import com.qiqi.mall.catalog.service.ProductTypeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-商品类型", description = "商品类型维护，决定该类型下有哪些属性与规格")
@RestController
@RequestMapping("/admin/product-types")
public class ProductTypeController {

    private final ProductTypeService productTypeService;
    private final CatalogDeleteGuard deleteGuard;

    public ProductTypeController(ProductTypeService productTypeService, CatalogDeleteGuard deleteGuard) {
        this.productTypeService = productTypeService;
        this.deleteGuard = deleteGuard;
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
        // 0 行 = 这条类型在读取之后被别人删了（product_type 无 version 列）→ 必须 fail-closed，
        // 否则管理员拿到「保存成功」但其实什么都没有改。
        int rows = productTypeService.getBaseMapper().updateById(body);
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_RECORD_DELETED);
        return Result.ok();
    }

    /** 删除类型：被属性或商品引用时不允许删，返回可读原因 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        String blocked = deleteGuard.checkProductType(id);
        if (blocked != null) return Result.fail(blocked);
        productTypeService.removeById(id);
        return Result.ok();
    }
}
