package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.AuditPolicyCondition;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 策略条件 DAO。{@code field_code / op_code} 的闭集校验在 Java（FieldRegistry / OpCode），不在 DDL。 */
@Mapper
public interface AuditPolicyConditionMapper extends BaseMapper<AuditPolicyCondition> {

    /** 某策略自己的条件（scope=POLICY），按 seq 升序 */
    @Select("SELECT * FROM audit_policy_condition WHERE policy_id = #{policyId} AND scope = 'POLICY' "
            + "ORDER BY seq ASC, id ASC")
    List<AuditPolicyCondition> selectByPolicy(@Param("policyId") Long policyId);

    /**
     * 全局排除（scope=ALL_AUTO，policy_id 为 NULL）：所有 AUTO 策略共用的例外。
     * 决策 C1：「客服备注含改」不属于任何一条策略，删任何策略都不该把它带走。
     */
    @Select("SELECT * FROM audit_policy_condition WHERE scope = 'ALL_AUTO' AND kind = 'EXCLUDE' "
            + "ORDER BY seq ASC, id ASC")
    List<AuditPolicyCondition> selectGlobalExclusions();

    /** 改策略 = 整套条件重写（先删后插，条件没有「部分更新」的语义） */
    @Delete("DELETE FROM audit_policy_condition WHERE policy_id = #{policyId} AND scope = 'POLICY'")
    int deleteByPolicy(@Param("policyId") Long policyId);

    /** 写全局排除前先清空旧集合（只在请求显式带了 globalExclusions 时调用） */
    @Delete("DELETE FROM audit_policy_condition WHERE scope = 'ALL_AUTO'")
    int deleteGlobalExclusions();
}
