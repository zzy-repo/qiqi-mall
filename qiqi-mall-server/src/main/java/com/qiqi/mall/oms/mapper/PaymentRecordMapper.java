package com.qiqi.mall.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.oms.entity.PaymentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {

    /**
     * 该订单号下**最近一笔支付流水**的 id —— 退款要能溯源到「退的是哪一笔」。
     *
     * <p>为什么取「最近一笔 type=1」：一张订单在当前主链路上只会成功支付一次（状态闸门 0→7），
     * 历史数据/补单可能有多次；退款对应的永远是最后一次真实入账，取 id 最大的一条与业务口径一致。
     * 查不到（纯后台建单、没有支付流水）返回 {@code null} —— 退款流水的 {@code origin_flow_id}
     * 允许为空，但会在审计里写明「无原支付流水」。
     */
    @Select("SELECT id FROM payment_record WHERE order_sn = #{orderSn} AND type = 1 ORDER BY id DESC LIMIT 1")
    Long selectOriginFlowId(@Param("orderSn") String orderSn);

    /** 幂等键是否已入账（判重不依赖 DuplicateKeyException 的报文内容） */
    @Select("SELECT COUNT(*) FROM payment_record WHERE idem_key = #{idemKey}")
    int countByIdemKey(@Param("idemKey") String idemKey);

    /**
     * 该渠道流水号是否已入账 —— {@code uk_channel_trade(channel, trade_no)} 的回查面。
     *
     * <p>为什么撞键之后要回查它：一次重复通知会**同时**违反两个唯一键
     * （{@code uk_channel_trade} 与 {@code uk_idem}），而 MySQL 只会报其中一个。
     * 「是第三方把同一笔通知又发了一次」（回查命中 → 忽略）与「渠道给同一张单发来了第二笔扣款」
     * （回查不命中 → 人工对账）必须靠这条回查分开，不能靠异常报文猜。
     */
    @Select("SELECT COUNT(*) FROM payment_record WHERE channel = #{channel} AND trade_no = #{tradeNo}")
    int countByChannelTrade(@Param("channel") int channel, @Param("tradeNo") String tradeNo);
}
