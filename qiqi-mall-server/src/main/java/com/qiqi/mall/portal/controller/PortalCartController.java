package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.member.entity.Cart;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.shared.security.CurrentUser;
import com.qiqi.mall.member.service.CartService;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.catalog.service.SpecTextHelper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** C 端购物车（登录后云端购物车；支持匿名本地购物车合并） */
@RestController
@RequestMapping("/portal/cart")
public class PortalCartController {

    private final CartService cartService;
    private final ProductService productService;
    private final SkuService skuService;
    private final SpecTextHelper specTextHelper;

    public PortalCartController(CartService cartService, ProductService productService, SkuService skuService,
                                SpecTextHelper specTextHelper) {
        this.cartService = cartService;
        this.productService = productService;
        this.skuService = skuService;
        this.specTextHelper = specTextHelper;
    }

    /** 我的购物车（带商品名与规格展示字段） */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        Long memberId = CurrentUser.id();
        List<Cart> rows = cartService.list(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getMemberId, memberId).orderByAsc(Cart::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Cart cart : rows) {
            Product product = productService.getById(cart.getProductId());
            Sku sku = skuService.getById(cart.getProductSkuId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", cart.getId());
            item.put("productId", cart.getProductId());
            item.put("productSkuId", cart.getProductSkuId());
            item.put("quantity", cart.getQuantity());
            item.put("productName", product == null ? "" : product.getName());
            item.put("specText", specTextHelper.textOf(cart.getProductSkuId()));
            item.put("pic", sku == null ? null : sku.getPic());
            item.put("price", sku == null ? 0 : sku.getPrice());
            result.add(item);
        }
        return Result.ok(result);
    }

    /** 加入购物车：同一 SKU 已存在则累加数量 */
    @PostMapping
    public Result<Cart> add(@RequestBody CartRequest request) {
        Long memberId = CurrentUser.id();
        Sku sku = skuService.getById(request.getProductSkuId());
        if (sku == null) return Result.fail("规格不存在");
        Cart exist = cartService.getOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getMemberId, memberId)
                .eq(Cart::getProductSkuId, request.getProductSkuId()), false);
        int qty = request.getQuantity() == null || request.getQuantity() < 1 ? 1 : request.getQuantity();
        if (exist != null) {
            exist.setQuantity(exist.getQuantity() + qty);
            cartService.updateById(exist);
            return Result.ok(exist);
        }
        Cart cart = new Cart();
        cart.setMemberId(memberId);
        cart.setProductId(sku.getProductId());
        cart.setProductSkuId(sku.getId());
        cart.setQuantity(qty);
        cartService.save(cart);
        return Result.ok(cart);
    }

    @PutMapping("/{id}")
    public Result<Void> updateQuantity(@PathVariable Long id, @RequestBody CartRequest request) {
        Cart cart = cartService.getById(id);
        if (cart == null || !cart.getMemberId().equals(CurrentUser.id())) return Result.fail("购物车条目不存在");
        cart.setQuantity(request.getQuantity() == null || request.getQuantity() < 1 ? 1 : request.getQuantity());
        cartService.updateById(cart);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        Cart cart = cartService.getById(id);
        if (cart == null || !cart.getMemberId().equals(CurrentUser.id())) return Result.fail("购物车条目不存在");
        cartService.removeById(id);
        return Result.ok();
    }

    /** 登录成功后把浏览器本地购物车合并到云端 */
    @PostMapping("/merge")
    public Result<Integer> merge(@RequestBody List<CartRequest> localItems) {
        Long memberId = CurrentUser.id();
        int merged = 0;
        if (localItems != null) {
            for (CartRequest item : localItems) {
                if (item.getProductSkuId() == null) continue;
                Sku sku = skuService.getById(item.getProductSkuId());
                if (sku == null) continue;
                Cart exist = cartService.getOne(new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getMemberId, memberId)
                        .eq(Cart::getProductSkuId, item.getProductSkuId()), false);
                int qty = item.getQuantity() == null || item.getQuantity() < 1 ? 1 : item.getQuantity();
                if (exist != null) {
                    exist.setQuantity(exist.getQuantity() + qty);
                    cartService.updateById(exist);
                } else {
                    Cart cart = new Cart();
                    cart.setMemberId(memberId);
                    cart.setProductId(sku.getProductId());
                    cart.setProductSkuId(sku.getId());
                    cart.setQuantity(qty);
                    cartService.save(cart);
                }
                merged++;
            }
        }
        return Result.ok(merged);
    }

    public static class CartRequest {
        private Long productSkuId;
        private Integer quantity;

        public Long getProductSkuId() { return productSkuId; }
        public void setProductSkuId(Long productSkuId) { this.productSkuId = productSkuId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
