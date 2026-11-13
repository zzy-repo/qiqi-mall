package com.qiqi.mall.oms.controller;

import com.qiqi.mall.oms.entity.Warehouse;
import com.qiqi.mall.oms.service.WarehouseService;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 仓库字典（方案 Commit 4「仓库管理页」的后端） */
@Tag(name = "管理端-仓库", description = "仓库字典与启停，拆单按仓库归属分配")
@RestController
@RequestMapping("/admin/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;
    /** 后台操作审计（仓库是拆单归属与库存的落点，增删改必须留证） */
    private final AdminAuditWriter auditWriter;

    public WarehouseController(WarehouseService warehouseService, AdminAuditWriter auditWriter) {
        this.warehouseService = warehouseService;
        this.auditWriter = auditWriter;
    }

    @GetMapping
    public Result<List<Warehouse>> list() {
        return Result.ok(warehouseService.list());
    }

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<Warehouse> create(@RequestBody Warehouse body) {
        body.setId(null);
        if (body.getStatus() == null) body.setStatus(1);
        warehouseService.save(body);
        // 审计：与建仓同一事务（audit insert 失败 → 仓也不会建出来，不会出现「改了但没证据」）
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_WAREHOUSE_CREATE, ReasonCodes.TARGET_WAREHOUSE, body.getId(), body.getName(),
                "新建仓库 " + body.getName() + "（id=" + body.getId() + "）",
                null, null,
                null,
                AdminAuditWriter.snapshot("name", body.getName(), "status", body.getStatus())));
        return Result.ok(body);
    }

    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> update(@PathVariable Long id, @RequestBody Warehouse body) {
        Warehouse before = warehouseService.getById(id);       // 审计 before：只取名称与启停
        body.setId(id);
        // 0 行 = 仓库在读取之后被别人删了（warehouse 无 version 列）→ 409「记录已被删除」。
        // 必须拦：仓库是发货单/库存的归属依据，静默改失败会让人以为「这个仓还能用」。
        int rows = warehouseService.getBaseMapper().updateById(body);
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_RECORD_DELETED);
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_WAREHOUSE_UPDATE, ReasonCodes.TARGET_WAREHOUSE, id,
                before == null ? body.getName() : before.getName(),
                "修改仓库 " + (before == null ? body.getName() : before.getName()) + "（id=" + id + "）",
                null, null,
                AdminAuditWriter.snapshot("name", before == null ? null : before.getName(),
                        "status", before == null ? null : before.getStatus()),
                AdminAuditWriter.snapshot("name", body.getName(), "status", body.getStatus())));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(@PathVariable Long id) {
        Warehouse before = warehouseService.getById(id);
        warehouseService.removeById(id);
        // 审计先于/同于删除：仓被删后 name 只在这条审计里还读得到
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_WAREHOUSE_DELETE, ReasonCodes.TARGET_WAREHOUSE, id,
                before == null ? null : before.getName(),
                "删除仓库 " + (before == null ? ("id=" + id) : before.getName() + "（id=" + id + "）"),
                null, null,
                AdminAuditWriter.snapshot("name", before == null ? null : before.getName(),
                        "status", before == null ? null : before.getStatus()),
                AdminAuditWriter.snapshot("deleted", Boolean.TRUE)));
        return Result.ok();
    }
}
