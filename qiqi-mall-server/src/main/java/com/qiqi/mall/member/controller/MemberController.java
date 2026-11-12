package com.qiqi.mall.member.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.member.entity.Cart;
import com.qiqi.mall.member.entity.Member;
import com.qiqi.mall.member.entity.MemberAddress;
import com.qiqi.mall.member.service.CartService;
import com.qiqi.mall.member.service.MemberAddressService;
import com.qiqi.mall.member.service.MemberService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-会员", description = "会员账号维护")
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
        body.setVersion(0);             // 新建即 0 版：响应里带回 version，前端拿到就能直接编辑
        if (body.getStatus() == null) body.setStatus(1);
        memberService.save(body);
        return Result.ok(body);
    }

    /**
     * 更新会员（**乐观锁，fail-closed**）。
     *
     * <p>必须带 {@code body.version}：缺 → code=400；被抢改（影响行数 0）→ code=409。
     * 注意 status 也是并发敏感字段（禁用/启用被覆盖 = 有人被错误放行/拦截），
     * 所以这里同样不允许「无版本校验」的更新。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Member body) {
        body.setId(id);
        // 缺版本号 → 真 HTTP 400；影响行数 0 → 真 HTTP 409（见 GlobalExceptionHandler）
        OptimisticLockGuard.requireVersion(body.getVersion());
        int rows = memberService.getBaseMapper().updateById(body);
        OptimisticLockGuard.requireAffected(rows);
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
