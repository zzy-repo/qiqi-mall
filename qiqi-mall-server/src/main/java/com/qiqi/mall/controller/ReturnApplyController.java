package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.ReturnApply;
import com.qiqi.mall.service.ReturnApplyService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/return-applies")
public class ReturnApplyController {

    private final ReturnApplyService returnApplyService;

    public ReturnApplyController(ReturnApplyService returnApplyService) {
        this.returnApplyService = returnApplyService;
    }

    @GetMapping
    public Result<List<ReturnApply>> list(@RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) Long orderId) {
        LambdaQueryWrapper<ReturnApply> query = new LambdaQueryWrapper<>();
        query.eq(status != null, ReturnApply::getStatus, status)
                .eq(orderId != null, ReturnApply::getOrderId, orderId)
                .orderByDesc(ReturnApply::getId);
        return Result.ok(returnApplyService.list(query));
    }

    @PostMapping
    public Result<ReturnApply> create(@RequestBody ReturnApply body) {
        body.setId(null);
        returnApplyService.save(body);
        return Result.ok(body);
    }

    /** 审核：status 0待处理 1通过 2拒绝 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ReturnApply body) {
        body.setId(id);
        returnApplyService.updateById(body);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        returnApplyService.removeById(id);
        return Result.ok();
    }
}
