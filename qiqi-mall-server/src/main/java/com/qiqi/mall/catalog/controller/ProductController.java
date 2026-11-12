package com.qiqi.mall.catalog.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.PreviewResult;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.ProductGallery;
import com.qiqi.mall.catalog.service.ProductGalleryService;
import com.qiqi.mall.catalog.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理后台：商品（SPU，含商品相册，相册聚合于商品） */
@Tag(name = "管理端-商品", description = "商品（SPU）与商品相册维护")
@RestController
@RequestMapping("/admin/products")
public class ProductController {

    private final ProductService productService;
    private final CatalogDeleteGuard deleteGuard;
    private final ProductGalleryService productGalleryService;

    public ProductController(ProductService productService, ProductGalleryService productGalleryService,
                            CatalogDeleteGuard deleteGuard) {
        this.productService = productService;
        this.productGalleryService = productGalleryService;
        this.deleteGuard = deleteGuard;
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
        product.setVersion(0);          // 新建即 0 版：响应里带回 version，前端拿到就能直接编辑
        productService.save(product);
        saveGallery(product.getId(), request.getGallery());
        return Result.ok(product);
    }

    /**
     * 更新商品（**乐观锁，fail-closed**）。
     *
     * <p>必须带 {@code product.version}（列表/详情接口都会返回）：
     * 缺版本号 → code=400「缺少版本号，请刷新页面后重试」；
     * UPDATE 影响行数 0（读之后被别人改过）→ code=409「数据已被他人修改…，请刷新后重试」，
     * 此时**一个字段都不会被覆盖**，并且因为相册替换排在更新之后，也不会动相册。
     */
    @PutMapping("/{id}")
    @Transactional
    public Result<Void> update(@PathVariable Long id, @RequestBody ProductSaveRequest request) {
        Product product = request.getProduct();
        if (product == null) return Result.fail("缺少商品数据");
        product.setId(id);
        // ① 缺版本号直接拒（绝不静默降级成「无版本校验」）
        OptimisticLockGuard.LockError lockError = OptimisticLockGuard.checkVersion(product.getVersion());
        if (lockError != null) return Result.fail(lockError.code(), lockError.message());
        // ② 走 baseMapper 拿**影响行数**（service.updateById 只回 boolean，区分不了 0 行）
        int rows = productService.getBaseMapper().updateById(product);
        lockError = OptimisticLockGuard.checkAffected(rows);
        if (lockError != null) return Result.fail(lockError.code(), lockError.message());
        if (request.getGallery() != null) {
            // 整组替换相册
            productGalleryService.remove(new LambdaQueryWrapper<ProductGallery>()
                    .eq(ProductGallery::getProductId, id));
            saveGallery(id, request.getGallery());
        }
        return Result.ok();
    }

    /**
     * 删除商品：被订单明细引用时不允许删（提示改为下架），购物车引用也不允许。
     *
     * <p>校验顺序：**① 存在性 → ② 引用数守卫 → ③ 执行**。
     * {@code ?dryRun=true} 只算不落库：把「会连带删掉什么、当前被多少条订单明细/购物车引用」
     * 用中文说清；删不掉的（有引用）也照实说「删除会被拒绝，建议改为下架」，
     * 让预演永远 code=0、前端只有一条渲染路径。
     *
     * <p>并发：本接口删的是整行（幂等），引用数由引用守卫兜底，不需要 version。
     */
    @Operation(summary = "删除商品（级联 SKU/相册，被引用时拒绝）；?dryRun=true 只预演")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun) {
        Product product = productService.getById(id);
        if (product == null) return Result.fail("商品不存在");
        long orderItems = deleteGuard.countOrderItemsOfProduct(id);
        long carts = deleteGuard.countCartsOfProduct(id);
        long skus = deleteGuard.countSkusOfProduct(id);
        String blocked = deleteGuard.checkProduct(id);

        String willDo = "将删除商品「" + product.getName() + "」（id=" + product.getId() + "）"
                + (skus > 0 ? "，下辖 " + skus + " 个 SKU 与相册一并删除" : "及其相册")
                + "。当前被 " + orderItems + " 条订单明细、" + carts + " 个购物车引用"
                + (blocked != null ? " —— 删除会被拒绝，建议改为下架。" : "。删除后不可恢复。");

        if (PreviewResult.isDryRun(dryRun)) return Result.ok(PreviewResult.of(willDo));
        if (blocked != null) return Result.fail(blocked);

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
