package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.OrderAuditTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 审核标 DAO。写路径只有两条：**加标**（insert，靠 uk_active 防重复）与**解标**（active=0）。
 * 没有 DELETE —— 冻结/解冻是历史事实，证据不删。
 */
@Mapper
public interface OrderAuditTagMapper extends BaseMapper<OrderAuditTag> {

    @Select("SELECT * FROM order_audit_tag WHERE order_id = #{orderId} AND active = 1 ORDER BY id ASC")
    List<OrderAuditTag> selectActive(@Param("orderId") Long orderId);

    /** 冻结判定：FROZEN 类标（tag_code 以 FROZEN 开头，允许 FROZEN_RETURN_APPLY 这种细分） */
    @Select("SELECT * FROM order_audit_tag WHERE order_id = #{orderId} AND active = 1 "
            + "AND tag_code LIKE 'FROZEN%' ORDER BY id ASC LIMIT 1")
    OrderAuditTag selectActiveFrozen(@Param("orderId") Long orderId);

    @Select("SELECT * FROM order_audit_tag WHERE order_id = #{orderId} AND tag_code = #{tagCode} "
            + "AND active = 1 LIMIT 1")
    OrderAuditTag selectActiveByCode(@Param("orderId") Long orderId, @Param("tagCode") String tagCode);

    /** 解标（解冻 / 恢复）：只改 active 与解除信息，行保留 */
    @Update("UPDATE order_audit_tag SET active = 0, released_at = CURRENT_TIMESTAMP(3), released_by = #{releasedBy} "
            + "WHERE order_id = #{orderId} AND tag_code = #{tagCode} AND active = 1")
    int release(@Param("orderId") Long orderId, @Param("tagCode") String tagCode,
                @Param("releasedBy") String releasedBy);

    /** 解冻：一次放掉所有 FROZEN 类标（人工只点一次「解冻」，不该按细分标逐个点） */
    @Update("UPDATE order_audit_tag SET active = 0, released_at = CURRENT_TIMESTAMP(3), released_by = #{releasedBy} "
            + "WHERE order_id = #{orderId} AND tag_code LIKE 'FROZEN%' AND active = 1")
    int releaseFrozen(@Param("orderId") Long orderId, @Param("releasedBy") String releasedBy);
}
