package com.qiqi.mall.shared.ledger.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单状态台账的写入语句（只追加，无 UPDATE / DELETE）。
 *
 * <h3>为什么是 {@code ON DUPLICATE KEY UPDATE id = id}，而不是 {@code INSERT IGNORE}</h3>
 * <ul>
 *   <li>{@code INSERT IGNORE} 会把**除了重复键之外**的错误也一起静默吞掉：
 *       1265 数据截断、1048 NOT NULL 违规、1452 外键失败、CHECK 违规…… 台账会在
 *       「最需要证据的失败路径」上悄悄少一条，业务照跑、日志无异常，几个月后对账才发现。</li>
 *   <li>{@code ON DUPLICATE KEY UPDATE id = id} 只容忍**一种**情况：唯一键冲突
 *       （ERROR 1062 → 命中重复键，语义上就是「这一笔已经记过」= 重放，静默跳过）。
 *       其余任何 SQL 异常照常抛出 → 业务事务回滚 → 用户看到失败。
 *       <b>宁可业务失败，也不能让台账少一条</b>：少了的那条没人补得回来。</li>
 * </ul>
 *
 * <h3>为什么是 {@code ... VALUES} 而不是 {@code INSERT ... SELECT}</h3>
 * {@code stock_ledger} 的血泪教训（见 {@code StockLedgerService#insertIfAbsent}）：
 * {@code INSERT ... SELECT} 要对源表做加锁读，在 REPEATABLE READ 下对唯一索引取共享 gap 锁，
 * 与并发插入的插入意向锁成环 → 压测 6 档累计 45,790 次死锁。
 * 这里只走 {@code INSERT ... VALUES}：只取插入意向锁，不读任何源表。
 * 幂等键里的 {@code status_seq} 由调用方（{@code OrderStatusService}）在**同一条 UPDATE 之后**
 * 从实体上拿到的新值直接传进来 —— 不需要回查业务表。
 */
@Mapper
public interface OrderStatusLedgerMapper {

    /**
     * 写一条订单状态台账（幂等键冲突 = 重放，静默跳过；其他错误一律抛出）。
     *
     * @return 1 = 新写入；0 或 2 = 命中 uk_idem（重放）。**不依赖返回值判重**
     *         （ON DUPLICATE KEY UPDATE 的 1/2/0 语义在不同连接参数下不一致），
     *         需要判重时按 idem_key 回查。
     */
    @Insert("INSERT INTO order_status_ledger "
            + "(order_id, from_status, to_status, reason, reason_detail, operator_type, operator_id, operator_name, "
            + " ref_type, ref_id, event_key, idem_key, created_at) "
            + "VALUES (#{orderId}, #{fromStatus,jdbcType=TINYINT}, #{toStatus}, #{reason}, "
            + " #{reasonDetail,jdbcType=VARCHAR}, #{operatorType}, #{operatorId,jdbcType=BIGINT}, "
            + " #{operatorName,jdbcType=VARCHAR}, #{refType,jdbcType=VARCHAR}, #{refId,jdbcType=BIGINT}, "
            + " #{eventKey,jdbcType=VARCHAR}, #{idemKey}, CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insert(long orderId, Integer fromStatus, int toStatus, String reason, String reasonDetail,
               String operatorType, Long operatorId, String operatorName,
               String refType, Long refId, String eventKey, String idemKey);

    /** 该订单当前 status_seq（仅在实体上没有这个值时的兜底回查；正常路径不需要） */
    @org.apache.ibatis.annotations.Select("SELECT status_seq FROM `order` WHERE id = #{orderId}")
    Integer selectStatusSeq(long orderId);
}
