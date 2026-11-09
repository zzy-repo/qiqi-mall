package com.qiqi.mall.controller;

import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.ReturnReason;
import com.qiqi.mall.service.ReturnReasonService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/return-reasons")
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
