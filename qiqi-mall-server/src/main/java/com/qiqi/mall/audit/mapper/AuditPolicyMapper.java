package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.AuditPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 策略主体 DAO。排序口径统一在 SQL 里写死：类型固定序 + priority 升序 + id 升序。 */
@Mapper
public interface AuditPolicyMapper extends BaseMapper<AuditPolicy> {

    /**
     * 全部策略（列表页与引擎共用）。
     *
     * <p>类型序刻意按判定链排（BLOCK → EXCEPTION → AUTO），不是字母序：运营打开配置页时
     * 看到的顺序应该与「实际执行的顺序」一致，否则很容易以为 AUTO 排在 BLOCK 前面。
     */
    @Select("SELECT * FROM audit_policy "
            + "ORDER BY FIELD(type, 'BLOCK','EXCEPTION','AUTO'), priority ASC, id ASC")
    List<AuditPolicy> selectAllOrdered();

    /** 同类型里排在当前策略前面（priority 更小，或同 priority 但 id 更小）的最近一条 */
    @Select("SELECT * FROM audit_policy WHERE type = #{type} AND id <> #{id} "
            + "AND (priority < #{priority} OR (priority = #{priority} AND id < #{id})) "
            + "ORDER BY priority DESC, id DESC LIMIT 1")
    AuditPolicy selectPrev(@Param("type") String type, @Param("id") Long id, @Param("priority") Integer priority);

    /** 同类型里排在当前策略后面（priority 更大，或同 priority 但 id 更大）的最近一条 */
    @Select("SELECT * FROM audit_policy WHERE type = #{type} AND id <> #{id} "
            + "AND (priority > #{priority} OR (priority = #{priority} AND id > #{id})) "
            + "ORDER BY priority ASC, id ASC LIMIT 1")
    AuditPolicy selectNext(@Param("type") String type, @Param("id") Long id, @Param("priority") Integer priority);
}
