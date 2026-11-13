package com.qiqi.mall.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.channel.entity.ChannelSyncLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 渠道同步日志 DAO（只追加，无 UPDATE / DELETE）。
 *
 * <p>查询走 MyBatis-Plus 的 {@code Page} + LambdaQueryWrapper（见 {@code ChannelSyncLogWriter#page}），
 * 这里只补两条「按库聚合」的统计 SQL —— 后台卡片上的「今日成功 / 失败数」。
 */
@Mapper
public interface ChannelSyncLogMapper extends BaseMapper<ChannelSyncLog> {

    /**
     * 店铺当日结果分布：{@code [{result: "OK", cnt: 12}, ...]}。
     * 用 GROUP BY 而不是三次 COUNT，一张卡一次查询就够。
     */
    @Select("SELECT result, COUNT(*) AS cnt FROM channel_sync_log "
            + "WHERE shop_id = #{shopId} AND created_at >= #{from} GROUP BY result")
    List<Map<String, Object>> countSince(@Param("shopId") Long shopId, @Param("from") LocalDateTime from);
}
