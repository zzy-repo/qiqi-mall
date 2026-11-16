package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 审核标目录 DAO。
 *
 * <p>写路径只有一条：**新增自定义标**（{@code builtin=0}）。改名 / 停用 / 排序是期 2 的维护页，
 * 本期不提供 —— 但也<b>没有 DELETE</b>：目录项被删掉，历史标行就再也翻不出中文名了
 * （和 {@code audit_freeze_reason} 同一条纪律：停用不删）。
 */
@Mapper
public interface AuditTagCatalogMapper extends BaseMapper<AuditTagCatalog> {

    /** 全量（目录只有几十行，整表缓存 + 维护页都用它） */
    @Select("SELECT * FROM audit_tag_catalog ORDER BY sort_no ASC, id ASC")
    List<AuditTagCatalog> selectAllOrdered();

    @Select("SELECT * FROM audit_tag_catalog WHERE code = #{code} LIMIT 1")
    AuditTagCatalog selectByCode(@Param("code") String code);

    /** 某个 category 下已启用的项（队列第一层压缩显示 / 期 2 的特征标下拉会用） */
    @Select("SELECT * FROM audit_tag_catalog WHERE enabled = 1 AND category = #{category} "
            + "ORDER BY sort_no ASC, id ASC")
    List<AuditTagCatalog> selectEnabledByCategory(@Param("category") String category);
}
