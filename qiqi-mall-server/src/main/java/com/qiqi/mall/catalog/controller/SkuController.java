package com.qiqi.mall.catalog.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.catalog.entity.Attribute;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.entity.SkuSpec;
import com.qiqi.mall.catalog.service.AttributeService;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.catalog.service.SkuSpecService;
import com.qiqi.mall.catalog.service.SpecTextHelper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理后台：SKU（含规格值，规格值聚合于 SKU） */
@RestController
@RequestMapping("/admin/skus")
public class SkuController {

    private final SkuService skuService;
    private final CatalogDeleteGuard deleteGuard;
    private final SkuSpecService skuSpecService;
    private final AttributeService attributeService;
    private final ProductService productService;
    private final SpecTextHelper specTextHelper;

    public SkuController(SkuService skuService, SkuSpecService skuSpecService, AttributeService attributeService,
                         ProductService productService, SpecTextHelper specTextHelper,
                         CatalogDeleteGuard deleteGuard) {
        this.skuService = skuService;
        this.skuSpecService = skuSpecService;
        this.attributeService = attributeService;
        this.productService = productService;
        this.specTextHelper = specTextHelper;
        this.deleteGuard = deleteGuard;
    }

    /** 列表：带规格文本，可按商品过滤 */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) Long productId) {
        LambdaQueryWrapper<Sku> query = new LambdaQueryWrapper<>();
        query.eq(productId != null, Sku::getProductId, productId).orderByAsc(Sku::getId);
        List<Sku> rows = skuService.list(query);
        Map<Long, String> specTexts = specTextHelper.textMapBySkuIds(rows.stream().map(Sku::getId).toList());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Sku sku : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", sku.getId());
            item.put("productId", sku.getProductId());
            item.put("price", sku.getPrice());
            item.put("stock", sku.getStock());
            item.put("pic", sku.getPic());
            item.put("specText", specTexts.getOrDefault(sku.getId(), ""));
            result.add(item);
        }
        return Result.ok(result);
    }

    /** 详情：SKU + 规格值明细 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Long id) {
        Sku sku = skuService.getById(id);
        if (sku == null) return Result.fail("SKU 不存在");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sku", sku);
        data.put("specs", skuSpecService.list(new LambdaQueryWrapper<SkuSpec>()
                .eq(SkuSpec::getSkuId, id).orderByAsc(SkuSpec::getId)));
        return Result.ok(data);
    }

    @PostMapping
    @Transactional
    public Result<Sku> create(@RequestBody SkuSaveRequest request) {
        Sku sku = request.getSku();
        sku.setId(null);
        if (sku.getProductId() == null) return Result.fail("请指定所属商品");
        skuService.save(sku);
        saveSpecs(sku.getId(), request.getSpecs());
        return Result.ok(sku);
    }

    @PutMapping("/{id}")
    @Transactional
    public Result<Void> update(@PathVariable Long id, @RequestBody SkuSaveRequest request) {
        Sku sku = request.getSku();
        sku.setId(id);
        skuService.updateById(sku);
        if (request.getSpecs() != null) {
            // 整组替换规格值
            skuSpecService.remove(new LambdaQueryWrapper<SkuSpec>().eq(SkuSpec::getSkuId, id));
            saveSpecs(id, request.getSpecs());
        }
        return Result.ok();
    }

    /** 删除 SKU：被订单明细 / 购物车引用时不允许删 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        String blocked = deleteGuard.checkSku(id);
        if (blocked != null) return Result.fail(blocked);
        skuService.removeById(id);
        return Result.ok();
    }

    /** 该商品所属类型下的属性（供前端选择规格名） */
    @GetMapping("/attributes")
    public Result<List<Attribute>> attributes(@RequestParam Long productId) {
        Product product = productService.getById(productId);
        if (product == null) return Result.fail("商品不存在");
        return Result.ok(attributeService.list(new LambdaQueryWrapper<Attribute>()
                .eq(Attribute::getTypeId, product.getTypeId()).orderByAsc(Attribute::getId)));
    }

    private void saveSpecs(Long skuId, List<SkuSpec> specs) {
        if (specs == null) return;
        for (SkuSpec spec : specs) {
            if (spec.getAttributeId() == null || spec.getValue() == null || spec.getValue().isBlank()) continue;
            spec.setId(null);
            spec.setSkuId(skuId);
            skuSpecService.save(spec);
        }
    }

    public static class SkuSaveRequest {
        private Sku sku;
        private List<SkuSpec> specs;

        public Sku getSku() { return sku; }
        public void setSku(Sku sku) { this.sku = sku; }
        public List<SkuSpec> getSpecs() { return specs; }
        public void setSpecs(List<SkuSpec> specs) { this.specs = specs; }
    }
}
