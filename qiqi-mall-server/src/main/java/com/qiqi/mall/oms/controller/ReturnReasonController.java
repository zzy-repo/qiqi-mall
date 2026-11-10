package com.qiqi.mall.oms.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.oms.entity.ReturnReason;
import com.qiqi.mall.oms.service.ReturnReasonService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/return-reasons")
public class ReturnReasonController {

    private final ReturnReasonService returnReasonService;

    public ReturnReasonController(ReturnReasonService returnReasonService) {
        this.returnReasonService = returnReasonService;
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

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        returnReasonService.removeById(id);
        return Result.ok();
    }
}
