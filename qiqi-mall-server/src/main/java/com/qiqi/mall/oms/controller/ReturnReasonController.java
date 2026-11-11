package com.qiqi.mall.oms.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.CatalogDeleteGuard;
import com.qiqi.mall.oms.entity.ReturnReason;
import com.qiqi.mall.oms.service.ReturnReasonService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-退货原因字典", description = "C 端「申请退货」下拉用的原因字典，被引用时不允许删除")
@RestController
@RequestMapping("/admin/return-reasons")
public class ReturnReasonController {

    private final ReturnReasonService returnReasonService;
    private final CatalogDeleteGuard deleteGuard;

    public ReturnReasonController(ReturnReasonService returnReasonService, CatalogDeleteGuard deleteGuard) {
        this.returnReasonService = returnReasonService;
        this.deleteGuard = deleteGuard;
    }

    @GetMapping
    public Result<List<ReturnReason>> list() {
        return Result.ok(returnReasonService.list());
    }

    @PostMapping
    public Result<ReturnReason> create(@RequestBody ReturnReason body) {
        body.setId(null);
        returnReasonService.save(body);
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ReturnReason body) {
        body.setId(id);
        returnReasonService.updateById(body);
        return Result.ok();
    }

    /** 删除原因：已被退货申请引用时不允许删（历史申请要能追溯原因） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        String blocked = deleteGuard.checkReturnReason(id);
        if (blocked != null) return Result.fail(blocked);
        returnReasonService.removeById(id);
        return Result.ok();
    }
}
