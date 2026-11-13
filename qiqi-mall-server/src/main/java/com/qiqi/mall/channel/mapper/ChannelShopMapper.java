package com.qiqi.mall.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.channel.entity.ChannelShop;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 渠道店铺 DAO。
 *
 * <p>三条自定义语句都刻意不用 {@code UPDATE ... WHERE id=?} 之外的条件：
 * 水位推进是「拉单已经成功」之后的事，不需要乐观锁（同一店铺被两个人同时点拉单，
 * 后写的覆盖先写的只是水位抖动，下一次拉单重叠一次，被幂等闸门吃掉）。
 */
@Mapper
public interface ChannelShopMapper extends BaseMapper<ChannelShop> {

    /**
     * 店铺种子（幂等）：{@code ON DUPLICATE KEY UPDATE id = id} —— 已存在就原地不动。
     *
     * <p>为什么不用 {@code INSERT IGNORE}：它会把「ENUM 取值写错 / 字段超长」这类真错误一起吞掉，
     * 表现为「迁移跑了但店铺没建出来」。这里只容忍重复键，其余错误照常抛。
     */
    @Insert("INSERT INTO channel_shop (platform_type, shop_code, shop_name) "
            + "VALUES (#{platformType}, #{shopCode}, #{shopName}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertSeed(@Param("platformType") String platformType,
                   @Param("shopCode") String shopCode,
                   @Param("shopName") String shopName);

    /** 拉单成功后推进水位 + 记录本次拉单结束时刻 */
    @Update("UPDATE channel_shop SET watermark_at = #{watermarkAt}, last_pull_at = #{lastPullAt} WHERE id = #{id}")
    int touchPull(@Param("id") Long id,
                  @Param("watermarkAt") LocalDateTime watermarkAt,
                  @Param("lastPullAt") LocalDateTime lastPullAt);
}
