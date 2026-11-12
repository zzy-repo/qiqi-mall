package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.member.entity.Member;
import com.qiqi.mall.member.entity.MemberAddress;
import com.qiqi.mall.shared.security.CurrentUser;
import com.qiqi.mall.member.service.MemberAddressService;
import com.qiqi.mall.member.service.MemberService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** C 端个人资料与收货地址 */
@Tag(name = "买家端-个人资料与地址", description = "会员个人资料与收货地址簿")
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

    /**
     * 新增地址。
     * 业务口径：一个账号最多一条默认地址 —— 入参要设默认时，先清掉该会员其它地址的默认标记。
     */
    @PostMapping("/addresses")
    @Transactional(rollbackFor = Exception.class)
    public Result<MemberAddress> addAddress(@RequestBody MemberAddress body) {
        Long memberId = CurrentUser.id();
        body.setId(null);
        body.setMemberId(memberId);
        if (Integer.valueOf(1).equals(body.getDefaultStatus())) {
            clearOtherDefault(memberId, null);
        }
        memberAddressService.save(body);
        return Result.ok(body);
    }

    /** 编辑地址：同样保证「设默认就取消原默认」 */
    @PutMapping("/addresses/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateAddress(@PathVariable Long id, @RequestBody MemberAddress body) {
        MemberAddress exist = memberAddressService.getById(id);
        if (exist == null || !exist.getMemberId().equals(CurrentUser.id())) return Result.fail("地址不存在");
        Long memberId = CurrentUser.id();
        body.setId(id);
        body.setMemberId(memberId);
        if (Integer.valueOf(1).equals(body.getDefaultStatus())) {
            clearOtherDefault(memberId, id);
        }
        // 0 行 = 这条地址在读取之后被并发删了（member_address 无 version 列）→ 409 整笔回滚。
        // 必须回滚：上面的 clearOtherDefault 已经把这个会员其它地址的「默认」清掉了，
        // 若在此静默返回成功，会员就会出现「一条默认地址都没有」的不一致状态。
        int rows = memberAddressService.getBaseMapper().updateById(body);
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_RECORD_DELETED);
        return Result.ok();
    }

    /**
     * 删除地址。
     * 业务口径：有地址就一定有默认 —— 删掉的正好是默认地址且还有其它地址时，
     * 自动把最早创建（id 最小）的那条提升为默认；删光则保持无默认。
     */
    @DeleteMapping("/addresses/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteAddress(@PathVariable Long id) {
        MemberAddress exist = memberAddressService.getById(id);
        if (exist == null || !exist.getMemberId().equals(CurrentUser.id())) return Result.fail("地址不存在");
        Long memberId = CurrentUser.id();
        boolean wasDefault = Integer.valueOf(1).equals(exist.getDefaultStatus());
        memberAddressService.removeById(id);
        if (wasDefault) {
            MemberAddress oldest = memberAddressService.getOne(new LambdaQueryWrapper<MemberAddress>()
                    .eq(MemberAddress::getMemberId, memberId)
                    .orderByAsc(MemberAddress::getId)
                    .last("limit 1"), false);
            if (oldest != null) {
                oldest.setDefaultStatus(1);
                // 刻意不检查影响行数：0 行 = 这条「最早地址」恰在此刻被同账号另一端点删掉，
                // 此时也没有别的行可提升；地址不是账务数据（订单下单时已快照收货地址），
                // 下一次新增/编辑地址会重新算默认，保持原样即可。
                memberAddressService.updateById(oldest);
            }
        }
        return Result.ok();
    }

    /** 把该会员其它地址的默认标记清零（exceptId 为当前正在设默认的地址，可为 null） */
    private void clearOtherDefault(Long memberId, Long exceptId) {
        memberAddressService.update(new LambdaUpdateWrapper<MemberAddress>()
                .eq(MemberAddress::getMemberId, memberId)
                .ne(exceptId != null, MemberAddress::getId, exceptId)
                .set(MemberAddress::getDefaultStatus, 0));
    }
}
