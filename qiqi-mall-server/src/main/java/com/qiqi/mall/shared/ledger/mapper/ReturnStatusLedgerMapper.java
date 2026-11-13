package com.qiqi.mall.shared.ledger.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 售后状态台账的写入语句（只追加）。
 *
 * <p>比订单台账多一列 {@code order_id} 冗余：{@code return_apply} 对 {@code order} 是
 * {@code ON DELETE CASCADE}，且删单会物理删除申请 —— 没有这列，删单后从订单反查售后流水彻底断链
 * （见 {@code sql/_rollback/migrate-ledger.sql} 的冗余列登记表）。
 *
 * <p>写法口径同 {@link OrderStatusLedgerMapper}：{@code ON DUPLICATE KEY UPDATE id = id} + {@code VALUES}。
 */
@Mapper
public interface ReturnStatusLedgerMapper {

    @Insert("INSERT INTO return_status_ledger "
            + "(return_apply_id, order_id, from_status, to_status, reason, reason_detail, operator_type, "
            + " operator_id, operator_name, ref_type, ref_id, event_key, idem_key, created_at) "
            + "VALUES (#{returnApplyId}, #{orderId,jdbcType=BIGINT}, #{fromStatus,jdbcType=TINYINT}, #{toStatus}, "
            + " #{reason}, #{reasonDetail,jdbcType=VARCHAR}, #{operatorType}, #{operatorId,jdbcType=BIGINT}, "
            + " #{operatorName,jdbcType=VARCHAR}, #{refType,jdbcType=VARCHAR}, #{refId,jdbcType=BIGINT}, "
            + " #{eventKey,jdbcType=VARCHAR}, #{idemKey}, CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insert(long returnApplyId, Long orderId, Integer fromStatus, int toStatus, String reason,
               String reasonDetail, String operatorType, Long operatorId, String operatorName,
               String refType, Long refId, String eventKey, String idemKey);

    /** 兜底回查（正常路径不需要：实体上带着 status_seq） */
    @Select("SELECT status_seq FROM return_apply WHERE id = #{returnApplyId}")
    Integer selectStatusSeq(long returnApplyId);
}
