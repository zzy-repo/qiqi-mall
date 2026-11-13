package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.AuditRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 一次 run 的 DAO。 */
@Mapper
public interface AuditRunMapper extends BaseMapper<AuditRun> {

    @Select("SELECT * FROM audit_run WHERE idem_key = #{idemKey}")
    AuditRun selectByIdemKey(@Param("idemKey") String idemKey);

    /** 跑完回写五类计数（run 行必须在跑之前就存在：决策行要引用 run_id） */
    @Update("UPDATE audit_run SET total = #{total}, auto_pass = #{autoPass}, manual = #{manual}, "
            + "blocked = #{blocked}, frozen = #{frozen}, no_match = #{noMatch} WHERE id = #{id}")
    int bumpCounters(@Param("id") Long id, @Param("total") int total, @Param("autoPass") int autoPass,
                     @Param("manual") int manual, @Param("blocked") int blocked,
                     @Param("frozen") int frozen, @Param("noMatch") int noMatch);

    @Select("SELECT * FROM audit_run ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditRun> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM audit_run")
    long countAll();

    @Select("SELECT * FROM audit_run ORDER BY id DESC LIMIT 1")
    AuditRun selectLatest();
}
