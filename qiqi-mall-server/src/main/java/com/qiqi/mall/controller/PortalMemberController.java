package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Member;
import com.qiqi.mall.entity.MemberAddress;
import com.qiqi.mall.security.CurrentUser;
import com.qiqi.mall.service.MemberAddressService;
import com.qiqi.mall.service.MemberService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** C 端个人资料与收货地址 */
@RestController
@RequestMapping("/portal/member")
public class PortalMemberController {

    private final MemberService memberService;
    private final MemberAddressService memberAddressService;

    public PortalMemberController(MemberService memberService, MemberAddressService memberAddressService) {
        this.memberService = memberService;
        this.memberAddressService = memberAddressService;
    }

    @GetMapping("/profile")
    public Result<Member> profile() {
        return Result.ok(memberService.getById(CurrentUser.id()));
    }

    @GetMapping("/addresses")
    public Result<List<MemberAddress>> addresses() {
        return Result.ok(memberAddressService.list(new LambdaQueryWrapper<MemberAddress>()
                .eq(MemberAddress::getMemberId, CurrentUser.id())
                .orderByDesc(MemberAddress::getDefaultStatus)
                .orderByAsc(MemberAddress::getId)));
    }

    @PostMapping("/addresses")
    public Result<MemberAddress> addAddress(@RequestBody MemberAddress body) {
        body.setId(null);
        body.setMemberId(CurrentUser.id());
        memberAddressService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/addresses/{id}")
    public Result<Void> updateAddress(@PathVariable Long id, @RequestBody MemberAddress body) {
        MemberAddress exist = memberAddressService.getById(id);
        if (exist == null || !exist.getMemberId().equals(CurrentUser.id())) return Result.fail("地址不存在");
        body.setId(id);
        body.setMemberId(CurrentUser.id());
        memberAddressService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/addresses/{id}")
    public Result<Void> deleteAddress(@PathVariable Long id) {
        MemberAddress exist = memberAddressService.getById(id);
        if (exist == null || !exist.getMemberId().equals(CurrentUser.id())) return Result.fail("地址不存在");
        memberAddressService.removeById(id);
        return Result.ok();
    }
}
