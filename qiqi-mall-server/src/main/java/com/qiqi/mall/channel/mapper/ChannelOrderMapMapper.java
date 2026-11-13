package com.qiqi.mall.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.channel.entity.ChannelOrderMap;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 平台单号映射 DAO —— <b>幂等闸门在这里</b>。
 *
 * <p>{@link #insertIfAbsent} 的返回值是有语义的：
 * <ul>
 *   <li>{@code 1} = 插入成功（这单是第一次进来）；</li>
 *   <li>{@code 0} = 撞了 {@code uk_map}（{@code ON DUPLICATE KEY UPDATE id = id} 没改动任何列，
 *       MySQL 对「无变化的更新」返回 0 行）→ 说明并发路径已经先进来了。</li>
 * </ul>
 * 所以调用方可以只凭影响行数判定「这次到底有没有真的建单」，不必再回查一次。
 *
 * <p><b>为什么不用 INSERT IGNORE</b>：它把「字段超长 / ENUM 违规 / 空值约束」等真实错误
 * 一起降级成「忽略」，结果是订单建了、映射没建 —— 下一次拉单再建一张同样的单。
 * 这里只容忍重复键这一种情况，其余错误必须抛出去让事务回滚。
 */
@Mapper
public interface ChannelOrderMapMapper extends BaseMapper<ChannelOrderMap> {

    /**
     * 幂等插入映射（重复键视为「已存在」，不影响任何列）。
     *
     * @return 1 = 新插入；0 = 已存在
     */
    @Insert("INSERT INTO channel_order_map (shop_id, platform_sn, order_id, created_at) "
            + "VALUES (#{shopId}, #{platformSn}, #{orderId}, CURRENT_TIMESTAMP) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIfAbsent(@Param("shopId") Long shopId,
                       @Param("platformSn") String platformSn,
                       @Param("orderId") Long orderId);

    /** 幂等判重（拉单第一步的「先查」；与 uk_map 同一口径） */
    @Select("SELECT COUNT(*) FROM channel_order_map WHERE shop_id = #{shopId} AND platform_sn = #{platformSn}")
    int countByKey(@Param("shopId") Long shopId, @Param("platformSn") String platformSn);

    /** 回传时按内部订单反查平台单号（一个内部订单只可能对应一条映射） */
    @Select("SELECT id, shop_id, platform_sn, order_id, created_at FROM channel_order_map "
            + "WHERE order_id = #{orderId} LIMIT 1")
    ChannelOrderMap findByOrderId(@Param("orderId") Long orderId);
}
