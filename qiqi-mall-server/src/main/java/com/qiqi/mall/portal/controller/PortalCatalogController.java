package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.catalog.entity.Category;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.ProductGallery;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.service.CategoryService;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.ProductGalleryService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.catalog.service.SpecTextHelper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** C 端商品浏览（游客可访问） */
@Tag(name = "买家端-商品浏览", description = "商品/分类/SKU 浏览，游客可访问")
@RestController
@RequestMapping("/portal")
public class PortalCatalogController {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final SkuService skuService;
    private final ProductGalleryService productGalleryService;
    private final SpecTextHelper specTextHelper;

    public PortalCatalogController(ProductService productService, CategoryService categoryService,
                                   SkuService skuService, ProductGalleryService productGalleryService,
                                   SpecTextHelper specTextHelper) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.skuService = skuService;
        this.productGalleryService = productGalleryService;
        this.specTextHelper = specTextHelper;
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
        List<Sku> skus = skuService.list(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getProductId, id).orderByAsc(Sku::getId));
        Map<Long, String> specTexts = specTextHelper.textMapBySkuIds(skus.stream().map(Sku::getId).toList());
        List<Map<String, Object>> skuViews = new java.util.ArrayList<>();
        for (Sku sku : skus) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", sku.getId());
            view.put("price", sku.getPrice());
            view.put("stock", sku.getStock());
            view.put("pic", sku.getPic());
            view.put("specText", specTexts.getOrDefault(sku.getId(), ""));
            skuViews.add(view);
        }
        List<ProductGallery> gallery = productGalleryService.list(new LambdaQueryWrapper<ProductGallery>()
                .eq(ProductGallery::getProductId, id).orderByAsc(ProductGallery::getSort)
                .orderByAsc(ProductGallery::getId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", product);
        data.put("skus", skuViews);
        data.put("gallery", gallery);
        return Result.ok(data);
    }

    @GetMapping("/skus")
    public Result<List<Sku>> skus(@RequestParam Long productId) {
        return Result.ok(skuService.list(new LambdaQueryWrapper<Sku>().eq(Sku::getProductId, productId)));
    }
}
