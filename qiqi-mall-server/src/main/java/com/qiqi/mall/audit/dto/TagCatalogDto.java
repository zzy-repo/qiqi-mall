package com.qiqi.mall.audit.dto;

import com.qiqi.mall.audit.entity.AuditTagCatalog;

/**
 * 标目录项（队列/抽屉/报表/维护页共用）。
 *
 * <p>为什么不直接回实体：{@code id} 是库内代理键，<b>跨库不稳定</b>，界面上一律用 {@code code}；
 * 展示给人看的地方永远只该出现 {@code code + name + description}。
 * 这里连 {@code id} 都不给 —— 本期没有任何页面需要它（维护页是期 2 的事）。
 *
 * @param code        稳定键（队列 {@code ?tag=} 筛选、报表聚合都以它为准；永不修改）
 * @param name        中文名（业务看的那一句）
 * @param description 一句话说明：这标什么意思、下一步做什么
 * @param category    {@code 结论} / {@code 特征}
 * @param blocking    1 = 会拦住下单往下走
 * @param visible     1 = 业务可见
 * @param builtin     1 = 内置（不可删）；0 = 运行期新增的自定义值
 * @param sortNo      排序
 * @param enabled     1 启用 0 停用（停用项历史标行照旧显示中文名）
 */
public record TagCatalogDto(
        String code,
        String name,
        String description,
        String category,
        Integer blocking,
        Integer visible,
        Integer builtin,
        Integer sortNo,
        Integer enabled) {

    public static TagCatalogDto of(AuditTagCatalog c) {
        return new TagCatalogDto(c.getCode(), c.getName(), c.getDescription(), c.getCategory(),
                c.getBlocking(), c.getVisible(), c.getBuiltin(), c.getSortNo(), c.getEnabled());
    }
}
