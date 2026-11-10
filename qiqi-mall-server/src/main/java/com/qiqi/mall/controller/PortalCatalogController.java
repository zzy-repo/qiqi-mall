package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Category;
import com.qiqi.mall.entity.Product;
import com.qiqi.mall.entity.Sku;
import com.qiqi.mall.service.CategoryService;
import com.qiqi.mall.service.ProductService;
import com.qiqi.mall.service.SkuService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** C 端商品浏览（游客可访问） */
@RestController
@RequestMapping("/portal")
public class PortalCatalogController {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final SkuService skuService;

    public PortalCatalogController(ProductService productService, CategoryService categoryService,
                                   SkuService skuService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.skuService = skuService;
    }

    @GetMapping("/categories")
    public Result<List<Category>> categories() {
        return Result.ok(categoryService.list(new LambdaQueryWrapper<Category>().orderByAsc(Category::getId)));
    }

    /** 只展示上架商品，可按分类/关键词过滤 */
    @GetMapping("/products")
    public Result<List<Product>> products(@RequestParam(required = false) Long categoryId,
                                          @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Product> query = new LambdaQueryWrapper<>();
        query.eq(Product::getPublishStatus, 1)
                .eq(categoryId != null, Product::getCategoryId, categoryId)
                .like(keyword != null && !keyword.isBlank(), Product::getName, keyword)
                .orderByDesc(Product::getId);
        return Result.ok(productService.list(query));
    }

    /** 商品详情：SPU + 全部 SKU */
    @GetMapping("/products/{id}")
    public Result<Map<String, Object>> productDetail(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null || product.getPublishStatus() == null || product.getPublishStatus() != 1) {
            return Result.fail("商品不存在或已下架");
        }
        List<Sku> skus = skuService.list(new LambdaQueryWrapper<Sku>().eq(Sku::getProductId, id).orderByAsc(Sku::getId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", product);
        data.put("skus", skus);
        return Result.ok(data);
    }

    @GetMapping("/skus")
    public Result<List<Sku>> skus(@RequestParam Long productId) {
        return Result.ok(skuService.list(new LambdaQueryWrapper<Sku>().eq(Sku::getProductId, productId)));
    }
}
