package com.qiqi.mall.audit.dto;

import com.qiqi.mall.audit.entity.AuditFreezeReason;

/**
 * 冻结原因字典项（下拉 / 报表 / 将来的维护页共用）。
 *
 * <p>为什么单独出一个 DTO 而不是直接回实体：下拉只需要 {@code code + name}，
 * 但报表与维护页要 {@code enabled / selectable / builtin / sortNo}；
 * 而 {@code id} **只该出现在维护页**（它是库内代理键，跨库不稳定，界面上一律用 code）。
 * 一个 DTO 覆盖三处、字段语义写在下面，比三个窄 DTO 更省事。
 *
 * @param id          库内主键（维护页用；**任何展示给人看的地方都不该显示它**）
 * @param code        稳定键（P1 豁免 / 统计 / 策略配置都以它为准；永不修改）
 * @param name        中文名（这是给人看的那个）
 * @param description 说明：为什么冻、下一步做什么
 * @param enabled     1 启用 0 停用（停用后新冻结不能选，历史行照旧显示）
 * @param selectable  1 可人工选 0 仅系统兜底/迁移归类（可见可统计，但不许选来冻新单）
 * @param builtin     1 系统内置（禁删禁停）
 * @param sortNo      排序
 */
public record FreezeReasonDto(
        Long id,
        String code,
        String name,
        String description,
        Integer enabled,
        Integer selectable,
        Integer builtin,
        Integer sortNo) {

    public static FreezeReasonDto of(AuditFreezeReason r) {
        return new FreezeReasonDto(r.getId(), r.getCode(), r.getName(), r.getDescription(),
                r.getEnabled(), r.getSelectable(), r.getBuiltin(), r.getSortNo());
    }
}
