package com.qiqi.mall.member.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.member.entity.Cart;
import com.qiqi.mall.member.service.CartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-购物车", description = "后台查看会员购物车数据")
@RestController
@RequestMapping("/admin/carts")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Result<List<Cart>> list(@RequestParam(required = false) Long memberId) {
        LambdaQueryWrapper<Cart> query = new LambdaQueryWrapper<>();
        query.eq(memberId != null, Cart::getMemberId, memberId).orderByAsc(Cart::getId);
        return Result.ok(cartService.list(query));
    }

    @PostMapping
    public Result<Cart> create(@RequestBody Cart body) {
        body.setId(null);
        cartService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Cart body) {
        body.setId(id);
        cartService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        cartService.removeById(id);
        return Result.ok();
    }
}
