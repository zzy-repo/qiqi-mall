package com.qiqi.mall.catalog.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.PreviewResult;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.catalog.entity.Attribute;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.entity.SkuSpec;
import com.qiqi.mall.catalog.service.AttributeService;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.catalog.service.SkuSpecService;
import com.qiqi.mall.catalog.service.SpecTextHelper;
import com.qiqi.mall.stock.StockStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理后台：SKU（含规格值，规格值聚合于 SKU） */
@Tag(name = "管理端-SKU", description = "SKU 与规格值维护，含整组替换与库存/价格")
@RestController
@RequestMapping("/admin/skus")
public class SkuController {

    private final SkuService skuService;
    /**
     * 库存落点（启动时定：legacy = sku.stock / bucket = 分桶）。
     * 管理端「编辑库存」必须走它，不能直接改 sku.stock —— 分桶模式下那样等于没改库存。
     */
    private final StockStore stockStore;
    private final CatalogDeleteGuard deleteGuard;
    private final SkuSpecService skuSpecService;
    private final AttributeService attributeService;
    private final ProductService productService;
    private final SpecTextHelper specTextHelper;
    /** 后台操作审计（改价/改库存是敏感动作，必须留证；开关可一键关掉） */
    private final AdminAuditWriter auditWriter;

    public SkuController(SkuService skuService, StockStore stockStore,
                         SkuSpecService skuSpecService, AttributeService attributeService,
                         ProductService productService, SpecTextHelper specTextHelper,
                         CatalogDeleteGuard deleteGuard, AdminAuditWriter auditWriter) {
        this.skuService = skuService;
        this.stockStore = stockStore;
        this.skuSpecService = skuSpecService;
        this.attributeService = attributeService;
        this.productService = productService;
        this.specTextHelper = specTextHelper;
        this.deleteGuard = deleteGuard;
        this.auditWriter = auditWriter;
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
            // 乐观锁版本号必须回给前端：编辑时原样带回来，后端据此判断「读之后有没有人改过」
            item.put("version", sku.getVersion());
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
        sku.setVersion(0);              // 新建即 0 版：响应里带回 version，前端拿到就能直接编辑
        if (sku.getProductId() == null) return Result.fail("请指定所属商品");
        skuService.save(sku);
        // 库存权威在 sku_bucket：新规格必须立刻把初始库存物化成桶，否则第一次扣减会判「库存不足」。
        // （SkuBucketService#deduct 里还有一份懒初始化兜底，这里显式建一次是为了「新建即正确」。）
        stockStore.resetStock(sku.getId(), sku.getStock() == null ? 0 : sku.getStock());
        saveSpecs(sku.getId(), request.getSpecs());
        return Result.ok(sku);
    }

    /**
     * 更新 SKU（**乐观锁，fail-closed**）。
     *
     * <p>必须带 {@code sku.version}：缺 → code=400；被抢改（影响行数 0）→ code=409，
     * 且此时不会覆盖价格/库存，也不会替换规格值（规格替换排在更新之后）。
     */
    @PutMapping("/{id}")
    @Transactional
    public Result<Void> update(@PathVariable Long id, @RequestBody SkuSaveRequest request) {
        Sku sku = request.getSku();
        if (sku == null) return Result.fail("缺少 SKU 数据");
        sku.setId(id);
        // 缺版本号 → 真 HTTP 400；影响行数 0 → 真 HTTP 409（见 GlobalExceptionHandler）
        OptimisticLockGuard.requireVersion(sku.getVersion());
        // 改价是敏感动作：改之前的售价/库存必须留证（审计只记敏感字段，不 dump 整实体）
        Sku before = sku.getPrice() != null || sku.getStock() != null ? skuService.getById(id) : null;
        int rows = skuService.getBaseMapper().updateById(sku);
        OptimisticLockGuard.requireAffected(rows);
        if (sku.getPrice() != null || sku.getStock() != null) {
            auditWriter.record(new AdminAuditWriter.Event(
                    sku.getPrice() != null ? ReasonCodes.ACTION_SKU_PRICE_CHANGE : ReasonCodes.ACTION_SKU_STOCK_CHANGE,
                    ReasonCodes.TARGET_SKU, id, null,
                    "修改规格价格/库存"
                            + (before != null && sku.getPrice() != null
                                    ? "：售价 ¥" + before.getPrice() + " → ¥" + sku.getPrice() : ""),
                    null, null,
                    AdminAuditWriter.snapshot("price", before == null ? null : before.getPrice(),
                            "stock", before == null ? null : before.getStock()),
                    AdminAuditWriter.snapshot("price", sku.getPrice(), "stock", sku.getStock())));
        }
        // 库存被改过（请求里带了 stock）⇒ 用新总量重铺 N 个桶。
        // 这是「设置绝对量」而不是「扣减」：只改 sku.stock 而不改桶，会出现「界面写 100、实际还能扣老数字」，
        // 那是最坏的不一致。乐观锁失败时上面已经 409 返回，不会走到这里。
        if (sku.getStock() != null) {
            // legacy：直接写 sku.stock（它就是库存）；bucket：用新总量重铺 N 个桶
            stockStore.resetStock(id, sku.getStock());
        }
        if (request.getSpecs() != null) {
            // 整组替换规格值
            skuSpecService.remove(new LambdaQueryWrapper<SkuSpec>().eq(SkuSpec::getSkuId, id));
            saveSpecs(id, request.getSpecs());
        }
        return Result.ok();
    }

    /**
     * 删除规格（SKU）：被订单明细/购物车引用时拒绝。
     *
     * <p>校验顺序：**① 存在性 → ② 引用数守卫 → ③ 执行**；{@code ?dryRun=true} 只算不落库，
     * 返回「将删除哪个 SKU（含所属商品、售价、库存），当前被多少条订单明细/购物车引用」的中文描述。
     */
    @Operation(summary = "删除规格（SKU，被引用时拒绝）；?dryRun=true 只预演，返回将要发生什么")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id,
                            @RequestParam(required = false) Boolean dryRun) {
        Sku sku = skuService.getById(id);
        if (sku == null) return Result.fail("规格不存在");
        long orderItems = deleteGuard.countOrderItemsOfSku(id);
        long carts = deleteGuard.countCartsOfSku(id);
        String blocked = deleteGuard.checkSku(id);
        Product product = sku.getProductId() == null ? null : productService.getById(sku.getProductId());

        String willDo = "将删除规格 SKU #" + sku.getId()
                + (product == null ? "" : "（商品「" + product.getName() + "」）")
                + "：售价 ¥" + sku.getPrice() + "、总池库存 " + sku.getStock() + " 件"
                + "。当前被 " + orderItems + " 条订单明细、" + carts + " 个购物车引用"
                + (blocked != null ? " —— 删除会被拒绝。" : "。删除后不可恢复。");

        if (PreviewResult.isDryRun(dryRun)) return Result.ok(PreviewResult.of(willDo));
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
