package com.qiqi.mall.member.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.member.entity.MemberAddress;
import com.qiqi.mall.member.service.MemberAddressService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/member-addresses")
public class MemberAddressController {

    private final MemberAddressService memberAddressService;

    public MemberAddressController(MemberAddressService memberAddressService) {
        this.memberAddressService = memberAddressService;
    }

    @GetMapping
    public Result<List<MemberAddress>> list(@RequestParam(required = false) Long memberId) {
        LambdaQueryWrapper<MemberAddress> query = new LambdaQueryWrapper<>();
        query.eq(memberId != null, MemberAddress::getMemberId, memberId)
                .orderByDesc(MemberAddress::getDefaultStatus)
                .orderByAsc(MemberAddress::getId);
        return Result.ok(memberAddressService.list(query));
    }

    @PostMapping
    public Result<MemberAddress> create(@RequestBody MemberAddress body) {
        body.setId(null);
        memberAddressService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody MemberAddress body) {
        body.setId(id);
        memberAddressService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        memberAddressService.removeById(id);
        return Result.ok();
    }
}
