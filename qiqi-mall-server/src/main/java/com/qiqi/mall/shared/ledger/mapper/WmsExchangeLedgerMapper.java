package com.qiqi.mall.shared.ledger.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * WMS 下发 / 回传台账（{@code wms_exchange_ledger}）：REQ / ACK 两阶段，只追加。
 *
 * <p>幂等键 = {@code {deliveryId}:{direction}:{attemptNo}}，{@code direction ∈ REQ|ACK}：
 * 一次外发最多两行（先 REQ、后 ACK），两行同表 ⇒ 键里必须含 direction，否则 REQ 与 ACK 会互相撞键。
 * {@code attempt_no} 按 (delivery_id, direction) 各自递增：同方向第 N 次交换 = attempt_no N。
 *
 * <p>写法口径：{@code ON DUPLICATE KEY UPDATE id = id}（只容忍重复键）。
 */
@Mapper
public interface WmsExchangeLedgerMapper {

    @Insert("INSERT INTO wms_exchange_ledger "
            + "(delivery_id, direction, attempt_no, request_payload, response_payload, result, latency_ms, "
            + " operator_type, operator_id, operator_name, idem_key, created_at) "
            + "VALUES (#{deliveryId}, #{direction}, #{attemptNo}, #{requestPayload,jdbcType=VARCHAR}, "
            + " #{responsePayload,jdbcType=VARCHAR}, #{result,jdbcType=VARCHAR}, #{latencyMs,jdbcType=INTEGER}, "
            + " #{operatorType}, #{operatorId,jdbcType=BIGINT}, #{operatorName,jdbcType=VARCHAR}, "
            + " #{idemKey}, CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insert(@Param("deliveryId") long deliveryId,
               @Param("direction") String direction,
               @Param("attemptNo") int attemptNo,
               @Param("requestPayload") String requestPayload,
               @Param("responsePayload") String responsePayload,
               @Param("result") String result,
               @Param("latencyMs") Integer latencyMs,
               @Param("operatorType") String operatorType,
               @Param("operatorId") Long operatorId,
               @Param("operatorName") String operatorName,
               @Param("idemKey") String idemKey);

    /** 同方向下一次尝试号 = 该方向已有行数 + 1（重试/多次回传各自独立计数） */
    @Select("SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM wms_exchange_ledger "
            + "WHERE delivery_id = #{deliveryId} AND direction = #{direction}")
    int nextAttemptNo(@Param("deliveryId") long deliveryId, @Param("direction") String direction);
}
