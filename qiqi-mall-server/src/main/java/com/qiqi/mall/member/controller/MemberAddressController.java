package com.qiqi.mall.member.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.member.entity.MemberAddress;
import com.qiqi.mall.member.service.MemberAddressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-收货地址", description = "后台查看会员收货地址数据")
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
        // 0 行 = 地址在读取之后被别人删了（member_address 无 version 列）→ 409「记录已被删除」
        int rows = memberAddressService.getBaseMapper().updateById(body);
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_RECORD_DELETED);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        memberAddressService.removeById(id);
        return Result.ok();
    }
}
