package com.qiqi.mall.shared.ledger.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 包裹状态台账的写入语句（只追加）。
 *
 * <p>比订单台账多**两列已登记冗余**（见 {@code sql/migrate-ledger.sql} 的冗余列登记表）：
 * <ul>
 *   <li>{@code delivery_sn} 快照 —— {@code OrderPurgeService} 会**物理删除**包裹行，单号不留快照则删除后证据链断裂；</li>
 *   <li>{@code order_id} —— 同理：包裹行被物理删除后 {@code delivery_order} 这座「台账 → 订单」的桥就断了，
 *       不冗余订单 id 的话「这张订单的几个包裹是怎么走的」永久查不到（与 {@code return_status_ledger.order_id} 对称）。</li>
 * </ul>
 *
 * <p>写法口径同 {@link OrderStatusLedgerMapper}：{@code ON DUPLICATE KEY UPDATE id = id}
 * （只容忍重复键，其余错误抛出），{@code INSERT ... VALUES}（不读源表、不产生共享 gap 锁）。
 */
@Mapper
public interface DeliveryStatusLedgerMapper {

    @Insert("INSERT INTO delivery_status_ledger "
            + "(delivery_id, delivery_sn, order_id, from_status, to_status, reason, reason_detail, operator_type, "
            + " operator_id, operator_name, ref_type, ref_id, event_key, idem_key, created_at) "
            + "VALUES (#{deliveryId}, #{deliverySn}, #{orderId,jdbcType=BIGINT}, #{fromStatus,jdbcType=TINYINT}, "
            + " #{toStatus}, #{reason}, "
            + " #{reasonDetail,jdbcType=VARCHAR}, #{operatorType}, #{operatorId,jdbcType=BIGINT}, "
            + " #{operatorName,jdbcType=VARCHAR}, #{refType,jdbcType=VARCHAR}, #{refId,jdbcType=BIGINT}, "
            + " #{eventKey,jdbcType=VARCHAR}, #{idemKey}, CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insert(long deliveryId, String deliverySn, Long orderId, Integer fromStatus, int toStatus, String reason,
               String reasonDetail, String operatorType, Long operatorId, String operatorName,
               String refType, Long refId, String eventKey, String idemKey);

    /** 兜底回查（正常路径不需要：实体上带着 status_seq） */
    @Select("SELECT status_seq FROM delivery_order WHERE id = #{deliveryId}")
    Integer selectStatusSeq(long deliveryId);

    /**
     * 兜底回查订单 id（正常路径不需要：实体上带着 {@code orderId}）。
     * {@code delivery_order.order_id} 是 NOT NULL，回查不到说明包裹行已不存在 —— 调用方据此抛错。
     */
    @Select("SELECT order_id FROM delivery_order WHERE id = #{deliveryId}")
    Long selectOrderId(long deliveryId);
}
