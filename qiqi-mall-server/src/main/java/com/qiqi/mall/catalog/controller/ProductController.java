package com.qiqi.mall.catalog.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.ProductGallery;
import com.qiqi.mall.catalog.service.ProductGalleryService;
import com.qiqi.mall.catalog.service.ProductService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理后台：商品（SPU，含商品相册，相册聚合于商品） */
@RestController
@RequestMapping("/admin/products")
public class ProductController {

    private final ProductService productService;
    private final ProductGalleryService productGalleryService;

    public ProductController(ProductService productService, ProductGalleryService productGalleryService) {
        this.productService = productService;
        this.productGalleryService = productGalleryService;
    }

    @GetMapping
    public Result<List<Product>> list(@RequestParam(required = false) Integer publishStatus) {
        LambdaQueryWrapper<Product> query = new LambdaQueryWrapper<>();
        query.eq(publishStatus != null, Product::getPublishStatus, publishStatus).orderByAsc(Product::getId);
        return Result.ok(productService.list(query));
    }

    /** 详情：商品 + 相册 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) return Result.fail("商品不存在");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", product);
        data.put("gallery", productGalleryService.list(new LambdaQueryWrapper<ProductGallery>()
                .eq(ProductGallery::getProductId, id).orderByAsc(ProductGallery::getSort)
                .orderByAsc(ProductGallery::getId)));
        return Result.ok(data);
    }

    @PostMapping
    @Transactional
    public Result<Product> create(@RequestBody ProductSaveRequest request) {
        Product product = request.getProduct();
        product.setId(null);
        productService.save(product);
        saveGallery(product.getId(), request.getGallery());
        return Result.ok(product);
    }

    @PutMapping("/{id}")
    @Transactional
    public Result<Void> update(@PathVariable Long id, @RequestBody ProductSaveRequest request) {
        Product product = request.getProduct();
        product.setId(id);
        productService.updateById(product);
        if (request.getGallery() != null) {
            // 整组替换相册
            productGalleryService.remove(new LambdaQueryWrapper<ProductGallery>()
                    .eq(ProductGallery::getProductId, id));
            saveGallery(id, request.getGallery());
        }
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.removeById(id);
        return Result.ok();
    }

    private void saveGallery(Long productId, List<ProductGallery> gallery) {
        if (gallery == null) return;
        int index = 0;
        for (ProductGallery item : gallery) {
            if (item.getUrl() == null || item.getUrl().isBlank()) continue;
            item.setId(null);
            item.setProductId(productId);
            if (item.getSort() == null) item.setSort(++index);
            productGalleryService.save(item);
        }
    }

    public static class ProductSaveRequest {
        private Product product;
        private List<ProductGallery> gallery;

        public Product getProduct() { return product; }
        public void setProduct(Product product) { this.product = product; }
        public List<ProductGallery> getGallery() { return gallery; }
        public void setGallery(List<ProductGallery> gallery) { this.gallery = gallery; }
    }
}
