package com.qiqi.mall.shared.ledger.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 包裹状态台账的写入语句（只追加）。
 *
 * <p>比订单台账多一列 {@code delivery_sn} 快照：{@code OrderPurgeService} 会**物理删除**包裹行，
 * 单号不留快照则删单后证据链断裂（见 {@code sql/migrate-ledger.sql} 的冗余列登记表）。
 *
 * <p>写法口径同 {@link OrderStatusLedgerMapper}：{@code ON DUPLICATE KEY UPDATE id = id}
 * （只容忍重复键，其余错误抛出），{@code INSERT ... VALUES}（不读源表、不产生共享 gap 锁）。
 */
@Mapper
public interface DeliveryStatusLedgerMapper {

    @Insert("INSERT INTO delivery_status_ledger "
            + "(delivery_id, delivery_sn, from_status, to_status, reason, reason_detail, operator_type, "
            + " operator_id, operator_name, ref_type, ref_id, event_key, idem_key, created_at) "
            + "VALUES (#{deliveryId}, #{deliverySn}, #{fromStatus,jdbcType=TINYINT}, #{toStatus}, #{reason}, "
            + " #{reasonDetail,jdbcType=VARCHAR}, #{operatorType}, #{operatorId,jdbcType=BIGINT}, "
            + " #{operatorName,jdbcType=VARCHAR}, #{refType,jdbcType=VARCHAR}, #{refId,jdbcType=BIGINT}, "
            + " #{eventKey,jdbcType=VARCHAR}, #{idemKey}, CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insert(long deliveryId, String deliverySn, Integer fromStatus, int toStatus, String reason,
               String reasonDetail, String operatorType, Long operatorId, String operatorName,
               String refType, Long refId, String eventKey, String idemKey);

    /** 兜底回查（正常路径不需要：实体上带着 status_seq） */
    @Select("SELECT status_seq FROM delivery_order WHERE id = #{deliveryId}")
    Integer selectStatusSeq(long deliveryId);
}
