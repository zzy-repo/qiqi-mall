package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Cart;
import com.qiqi.mall.entity.Member;
import com.qiqi.mall.entity.MemberAddress;
import com.qiqi.mall.service.CartService;
import com.qiqi.mall.service.MemberAddressService;
import com.qiqi.mall.service.MemberService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/members")
public class MemberController {

    private final MemberService memberService;
    private final MemberAddressService memberAddressService;
    private final CartService cartService;

    public MemberController(MemberService memberService, MemberAddressService memberAddressService,
                            CartService cartService) {
        this.memberService = memberService;
        this.memberAddressService = memberAddressService;
        this.cartService = cartService;
    }

    @GetMapping
    public Result<List<Member>> list(@RequestParam(required = false) Integer status,
                                     @RequestParam(required = false) String username) {
        LambdaQueryWrapper<Member> query = new LambdaQueryWrapper<>();
        query.eq(status != null, Member::getStatus, status)
                .like(username != null && !username.isBlank(), Member::getUsername, username)
                .orderByDesc(Member::getId);
        return Result.ok(memberService.list(query));
    }

    @GetMapping("/{id}")
    public Result<Member> get(@PathVariable Long id) {
        return Result.ok(memberService.getById(id));
    }

    @PostMapping
    public Result<Member> create(@RequestBody Member body) {
        body.setId(null);
        if (body.getStatus() == null) body.setStatus(1);
        memberService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Member body) {
        body.setId(id);
        memberService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        memberService.removeById(id);
        return Result.ok();
    }

    /** 会员聚合详情：收货地址 + 购物车 */
    @GetMapping("/{id}/detail")
    public Result<MemberDetailDTO> detail(@PathVariable Long id) {
        MemberDetailDTO dto = new MemberDetailDTO();
        dto.setMember(memberService.getById(id));
        dto.setAddresses(memberAddressService.list(new LambdaQueryWrapper<MemberAddress>()
                .eq(MemberAddress::getMemberId, id).orderByDesc(MemberAddress::getDefaultStatus)
                .orderByAsc(MemberAddress::getId)));
        dto.setCarts(cartService.list(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getMemberId, id).orderByAsc(Cart::getId)));
        return Result.ok(dto);
    }

    public static class MemberDetailDTO {
        private Member member;
        private List<MemberAddress> addresses;
        private List<Cart> carts;

        public Member getMember() { return member; }
        public void setMember(Member member) { this.member = member; }
        public List<MemberAddress> getAddresses() { return addresses; }
        public void setAddresses(List<MemberAddress> addresses) { this.addresses = addresses; }
        public List<Cart> getCarts() { return carts; }
        public void setCarts(List<Cart> carts) { this.carts = carts; }
    }
}
