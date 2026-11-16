package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.AuditFreezeReason;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 冻结原因字典 DAO。
 *
 * <p>只读为主：字典的行由迁移 {@code V3__audit_freeze_reason.sql} 种下，运营改的是
 * {@code name / description / enabled / sort_no}（第二期才有维护页）。
 * <b>没有任何地方会写 {@code code}</b> —— 它是稳定键，改它等于换了一条原因，
 * 会让历史 {@code reason_code} 与 P1 的豁免全部失配。
 */
@Mapper
public interface AuditFreezeReasonMapper extends BaseMapper<AuditFreezeReason> {

    /** 全量（字典只有十几行，缓存与维护页都用它） */
    @Select("SELECT * FROM audit_freeze_reason ORDER BY sort_no ASC, id ASC")
    List<AuditFreezeReason> selectAllOrdered();

    /** 下拉用：启用的 + 允许人工选的（{@code 未分类} / {@code 历史未填} 不在其中，但它们仍能被统计到） */
    @Select("SELECT * FROM audit_freeze_reason WHERE enabled = 1 AND selectable = 1 "
            + "ORDER BY sort_no ASC, id ASC")
    List<AuditFreezeReason> selectSelectable();
}
