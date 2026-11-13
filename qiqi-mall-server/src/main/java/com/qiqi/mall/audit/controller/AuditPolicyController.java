package com.qiqi.mall.audit.controller;

import com.qiqi.mall.audit.dto.PolicyDto;
import com.qiqi.mall.audit.dto.PolicyListDto;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.service.AuditPolicyService;
import com.qiqi.mall.shared.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-审核策略配置。
 *
 * <p>写操作全部走 {@link AuditPolicyService}（同事务 + 版本快照 + 审计），Controller 只做入参转发，
 * 不在这里做业务校验 —— 校验必须在服务层，否则「别处也调服务」时就会绕过。
 */
@Tag(name = "管理端-审核策略", description = "自动审核的 AUTO/BLOCK/EXCEPTION 策略与条件（行模型）")
@RestController
@RequestMapping("/admin/audit-policies")
public class AuditPolicyController {

    private final AuditPolicyService policyService;

    public AuditPolicyController(AuditPolicyService policyService) {
        this.policyService = policyService;
    }

    /** 列表：策略 + 全局排除 + 字段/操作符元数据（前端条件编辑器据此渲染） */
    @GetMapping
    public Result<PolicyListDto> list() {
        return Result.ok(policyService.list());
    }

    @PostMapping
    public Result<PolicyDto> create(@RequestBody PolicyRequest body) {
        return Result.ok(policyService.create(body));
    }

    @PutMapping("/{id}")
    public Result<PolicyDto> update(@PathVariable Long id, @RequestBody PolicyRequest body) {
        return Result.ok(policyService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        policyService.delete(id);
        return Result.ok();
    }

    /** 上移/下移（只与同类型相邻策略换位） */
    @PostMapping("/{id}/move")
    public Result<PolicyDto> move(@PathVariable Long id, @RequestBody MoveRequest body) {
        return Result.ok(policyService.move(id, body == null ? null : body.direction()));
    }

    /** 排序请求体：direction = UP | DOWN */
    public record MoveRequest(String direction) {
    }
}
