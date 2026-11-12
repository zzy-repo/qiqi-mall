package com.qiqi.mall.shared.guard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.catalog.entity.Attribute;
import com.qiqi.mall.catalog.entity.Category;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.entity.SkuSpec;
import com.qiqi.mall.catalog.service.AttributeService;
import com.qiqi.mall.catalog.service.CategoryService;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.catalog.service.SkuSpecService;
import com.qiqi.mall.member.entity.Cart;
import com.qiqi.mall.member.service.CartService;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.order.entity.OrderItem;
import org.springframework.stereotype.Component;

/**
 * 商品模块删除前的引用检查。
 *
 * 为什么需要：5 个删除接口原来直接 removeById，被引用时数据库抛外键异常，
 * 前端只能显示「数据处理失败」，用户不知道到底删不掉什么。这里在删除前把引用数查出来，
 * 返回一句能看懂的中文原因（返回 null 表示可以删）。
 *
 * 口径（与业务确认过）：
 * - 商品被订单明细引用时提示「改成下架」——历史订单必须能追溯，不能靠删商品解决；
 * - 购物车里的引用不算硬约束（购物车是临时数据，删商品时顺手清掉更合理），
 *   但这里仍提示，避免用户困惑；
 * - SKU 的规格值（sku_spec）是自身明细，随 SKU 级联删除，不作为阻止条件；
 * - 类型/分类的「子分类、属性、商品」都是硬引用，必须改名/迁移后才能删。
 */
@Component
public class CatalogDeleteGuard {

    private final AttributeService attributeService;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final SkuService skuService;
    private final SkuSpecService skuSpecService;
    private final OrderItemService orderItemService;
    private final CartService cartService;
    private final ReturnApplyService returnApplyService;

    public CatalogDeleteGuard(AttributeService attributeService, ProductService productService,
                              CategoryService categoryService, SkuService skuService,
                              SkuSpecService skuSpecService, OrderItemService orderItemService,
                              CartService cartService, ReturnApplyService returnApplyService) {
        this.attributeService = attributeService;
        this.productService = productService;
        this.categoryService = categoryService;
        this.skuService = skuService;
        this.skuSpecService = skuSpecService;
        this.orderItemService = orderItemService;
        this.cartService = cartService;
        this.returnApplyService = returnApplyService;
    }

    /** 商品类型：被「属性」「商品」引用 */
    public String checkProductType(Long typeId) {
        long attrs = attributeService.count(new LambdaQueryWrapper<Attribute>().eq(Attribute::getTypeId, typeId));
        long products = productService.count(new LambdaQueryWrapper<Product>().eq(Product::getTypeId, typeId));
        if (attrs > 0 || products > 0) {
            return String.format("该类型下还有 %d 个属性 / %d 个商品，不能删除（请先迁移或删除它们）", attrs, products);
        }
        return null;
    }

    /** 属性：被「SKU 规格值」引用 */
    public String checkAttribute(Long attributeId) {
        long used = skuSpecService.count(new LambdaQueryWrapper<SkuSpec>().eq(SkuSpec::getAttributeId, attributeId));
        if (used > 0) return String.format("该属性已被 %d 个 SKU 使用，不能删除", used);
        return null;
    }

    /** 商品分类：被「子分类」「商品」引用 */
    public String checkCategory(Long categoryId) {
        long children = categoryService.count(new LambdaQueryWrapper<Category>().eq(Category::getParentId, categoryId));
        long products = productService.count(new LambdaQueryWrapper<Product>().eq(Product::getCategoryId, categoryId));
        if (children > 0 || products > 0) {
            return String.format("该分类下还有 %d 个子分类 / %d 个商品，不能删除", children, products);
        }
        return null;
    }

    /** 商品：被「订单明细」「购物车」引用；有订单时建议改为下架 */
    public String checkProduct(Long productId) {
        long orderItems = countOrderItemsOfProduct(productId);
        if (orderItems > 0) {
            return String.format("该商品已被 %d 条订单明细引用，不能删除（可改为下架）", orderItems);
        }
        long carts = countCartsOfProduct(productId);
        if (carts > 0) {
            return String.format("该商品还在 %d 个买家的购物车里，不能删除（可改为下架）", carts);
        }
        return null;
    }

    /** SKU：被「订单明细」「购物车」引用（规格值随 SKU 级联，不算引用） */
    public String checkSku(Long skuId) {
        long orderItems = countOrderItemsOfSku(skuId);
        if (orderItems > 0) return String.format("该规格已被 %d 条订单明细引用，不能删除", orderItems);
        long carts = countCartsOfSku(skuId);
        if (carts > 0) return String.format("该规格还在 %d 个买家的购物车里，不能删除", carts);
        return null;
    }

    // ---- 引用计数（确认码预演文案 + 指纹都用这几个，保证「说的」和「查的」是同一口径） ----

    /** 商品被多少条订单明细引用 */
    public long countOrderItemsOfProduct(Long productId) {
        return orderItemService.count(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getProductId, productId));
    }

    /** 商品在多少个购物车里 */
    public long countCartsOfProduct(Long productId) {
        return cartService.count(new LambdaQueryWrapper<Cart>().eq(Cart::getProductId, productId));
    }

    /** SKU 被多少条订单明细引用 */
    public long countOrderItemsOfSku(Long skuId) {
        return orderItemService.count(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getProductSkuId, skuId));
    }

    /** SKU 在多少个购物车里 */
    public long countCartsOfSku(Long skuId) {
        return cartService.count(new LambdaQueryWrapper<Cart>().eq(Cart::getProductSkuId, skuId));
    }

    /** 退货原因：被「退货申请」引用（字典项一旦用过就不能删，否则历史申请说不清原因） */
    public String checkReturnReason(Long reasonId) {
        long used = returnApplyService.count(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getReasonId, reasonId));
        if (used > 0) return String.format("该原因已被 %d 条退货申请使用，不能删除", used);
        return null;
    }

    /** 商品下是否还有 SKU（删商品时一并提示，避免误删有规格的商品） */
    public long countSkusOfProduct(Long productId) {
        return skuService.count(new LambdaQueryWrapper<Sku>().eq(Sku::getProductId, productId));
    }
}
