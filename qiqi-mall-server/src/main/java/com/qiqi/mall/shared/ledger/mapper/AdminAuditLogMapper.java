package com.qiqi.mall.shared.ledger.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 后台操作审计（{@code admin_audit_log}）：只追加。
 *
 * <p><b>幂等键红线</b>：{@code idem_key} 必须由 {@code request_id} 派生（{@code requestId} 或
 * {@code requestId:n}），**严禁**由业务键派生 —— 业务键派生会让「连点两次」的第二次撞
 * {@code uk_idem} → INSERT 失败 → 事务回滚 → 本该留下的「失败证据」被抹掉。
 * 见 {@code sql/_rollback/migrate-ledger.sql} 里该列的注释与 {@code AdminAuditWriter}。
 *
 * <p>写法口径：{@code ON DUPLICATE KEY UPDATE id = id} —— 只容忍重复键（同一次请求的同一条审计被重放），
 * 其余错误（JSON 不合法、字段超长、CHECK 违规）一律抛出。
 */
@Mapper
public interface AdminAuditLogMapper {

    @Insert("INSERT INTO admin_audit_log "
            + "(operator_type, operator_id, operator_name, action, target_type, target_id, target_sn, summary, "
            + " reason, order_id, before_json, after_json, request_id, idem_key, created_at) "
            + "VALUES (#{operatorType}, #{operatorId,jdbcType=BIGINT}, #{operatorName,jdbcType=VARCHAR}, "
            + " #{action}, #{targetType}, #{targetId,jdbcType=BIGINT}, #{targetSn,jdbcType=VARCHAR}, "
            + " #{summary,jdbcType=VARCHAR}, #{reason,jdbcType=VARCHAR}, #{orderId,jdbcType=BIGINT}, "
            + " #{beforeJson,jdbcType=VARCHAR}, #{afterJson,jdbcType=VARCHAR}, "
            + " #{requestId,jdbcType=VARCHAR}, #{idemKey}, CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insert(@Param("operatorType") String operatorType,
               @Param("operatorId") Long operatorId,
               @Param("operatorName") String operatorName,
               @Param("action") String action,
               @Param("targetType") String targetType,
               @Param("targetId") Long targetId,
               @Param("targetSn") String targetSn,
               @Param("summary") String summary,
               @Param("reason") String reason,
               @Param("orderId") Long orderId,
               @Param("beforeJson") String beforeJson,
               @Param("afterJson") String afterJson,
               @Param("requestId") String requestId,
               @Param("idemKey") String idemKey);

    /** 自检：这个幂等键在不在库里（判重不依赖 ODKU 的 1/2/0 返回值） */
    @Select("SELECT COUNT(*) FROM admin_audit_log WHERE idem_key = #{idemKey}")
    int countByIdemKey(@Param("idemKey") String idemKey);
}
