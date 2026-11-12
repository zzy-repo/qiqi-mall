package com.qiqi.mall.catalog.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.shared.security.ConfirmCodeService;
import com.qiqi.mall.shared.security.ConfirmOps;
import com.qiqi.mall.shared.security.ConfirmRequest;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.ProductGallery;
import com.qiqi.mall.catalog.service.ProductGalleryService;
import com.qiqi.mall.catalog.service.ProductService;
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
    private final ConfirmCodeService confirmCodeService;

    public ProductController(ProductService productService, ProductGalleryService productGalleryService,
                            CatalogDeleteGuard deleteGuard, ConfirmCodeService confirmCodeService) {
        this.productService = productService;
        this.productGalleryService = productGalleryService;
        this.deleteGuard = deleteGuard;
        this.confirmCodeService = confirmCodeService;
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

    /**
     * 删除商品：被订单明细引用时不允许删（提示改为下架），购物车引用也不允许。
     *
     * <p>确认码：?dryRun=true 预演（删不了的直接回中文原因，不会签出一个注定失败的码）；
     * 指纹 = 订单明细引用数 + 购物车引用数 + 下辖 SKU 数 —— 预演后引用数变了，执行会被拒。
     */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun,
                            @RequestBody(required = false) ConfirmRequest req) {
        Product product = productService.getById(id);
        if (product == null) return Result.fail("商品不存在");
        long orderItems = deleteGuard.countOrderItemsOfProduct(id);
        long carts = deleteGuard.countCartsOfProduct(id);
        long skus = deleteGuard.countSkusOfProduct(id);
        String blocked = deleteGuard.checkProduct(id);

        // 文案口径（用户定的样例）：把「删不掉」和「建议改下架」直接写进预演描述，
        // 而不是让 dryRun 直接失败 —— 预演永远 code=0，前端只有一条渲染路径；
        // 真正执行时 blocked 仍会在码校验之后拦下（顺序：码 → 引用守卫）。
        String willDo = "将删除商品「" + product.getName() + "」（id=" + product.getId() + "）"
                + (skus > 0 ? "，下辖 " + skus + " 个 SKU 与相册一并删除" : "及其相册")
                + "。当前被 " + orderItems + " 条订单明细、" + carts + " 个购物车引用"
                + (blocked != null ? " —— 删除会被拒绝，建议改为下架。" : "。删除后不可恢复。");

        if (ConfirmCodeService.isDryRun(dryRun)) {
            return Result.ok(confirmCodeService.preflight(willDo, ConfirmOps.PRODUCT_DELETE, id,
                    orderItems, carts, skus));
        }
        String error = confirmCodeService.verify(req == null ? null : req.getConfirmCode(),
                ConfirmOps.PRODUCT_DELETE, id, orderItems, carts, skus);
        if (error != null) return Result.fail(error);
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
