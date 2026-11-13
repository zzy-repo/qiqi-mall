package com.qiqi.mall.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.channel.entity.ChannelSyncLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 渠道同步日志 DAO（只追加，无 UPDATE / DELETE）。
 *
 * <p>写入**只走** {@link #insertOrTouch(ChannelSyncLog)}，不走 MyBatis-Plus 的
 * {@code BaseMapper#insert}：后者撞上 {@code uk_sync_idem} 会直接抛
 * {@code DuplicateKeyException}，而「同一次尝试被重放」是**正常现象**，不该报错。
 *
 * <p>查询走 MyBatis-Plus 的 {@code Page} + LambdaQueryWrapper（见 {@code ChannelSyncLogWriter#page}），
 * 这里只补两条「按库聚合」的统计 SQL —— 后台卡片上的「今日成功 / 失败数」。
 */
@Mapper
public interface ChannelSyncLogMapper extends BaseMapper<ChannelSyncLog> {

    /**
     * 追加一行；撞 {@code uk_sync_idem}（幂等键）时**什么都不做**。
     *
     * <p>为什么是 {@code ON DUPLICATE KEY UPDATE id = id} 而不是 {@code INSERT IGNORE}：
     * INSERT IGNORE 会把「列超长 / 枚举非法 / 非空缺失」等真正的数据错误一并吞成警告
     * （项目铁律，与 shared/ledger 下那几个台账 mapper 同一口径），而这行日志是证据链 ——
     * 写坏了必须暴露。{@code id = id} 是唯一能把「我改动的值」写成「原值」的写法：
     * 命中唯一键时语句不修改任何列，只吞掉重复键这一种错误。
     *
     * <p>{@code idem_key} 是库里的生成列（V2 迁移），**不参与 INSERT**：只由
     * {@code action/ref_id/attempt} 决定，写入侧无权也不该指定。
     *
     * <p><b>怎么判断「这次尝试已记过账」—— 看自增主键有没有回填，别看返回的影响行数。</b>
     * 实测（MySQL 8.4 + Connector/J 8.x 默认参数）：数据源 URL 没设
     * {@code useAffectedRows=true}，驱动会置 {@code CLIENT_FOUND_ROWS}，于是
     * ODKU 的「无变化」在 JDBC 侧报的是 **1（匹配行数）而不是 0**，连 {@code ROW_COUNT()}
     * 也是 1 —— 拿影响行数当判据在这里永远判不出重放。可用的信号是 OK 包里的
     * {@code last_insert_id}：真插入时它等于新自增 id（→ {@code row#id} 被回填），
     * 走 UPDATE 分支时服务端发的是 0（→ {@code row#id} 为 null）。
     * 两个判据都留着：万一将来有人把 {@code useAffectedRows} 打开，影响行数 0 同样成立。
     *
     * @return 驱动报的影响行数（**不要拿它判重放**：默认参数下新插入与重复命中都是 1）。
     *         判据是 {@code row#id}：非 null = 新落一行，null = 该次尝试已记过账（按 SKIP 处理，不报错）
     */
    @Insert("INSERT INTO channel_sync_log "
            + "(shop_id, action, ref_type, ref_id, request_payload, response_payload, "
            + " result, reason, attempt, latency_ms) VALUES "
            + "(#{shopId}, #{action}, #{refType}, #{refId}, #{requestPayload}, #{responsePayload}, "
            + " #{result}, #{reason}, #{attempt}, #{latencyMs}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertOrTouch(ChannelSyncLog row);

    /**
     * 店铺当日结果分布：{@code [{result: "OK", cnt: 12}, ...]}。
     * 用 GROUP BY 而不是三次 COUNT，一张卡一次查询就够。
     */
    @Select("SELECT result, COUNT(*) AS cnt FROM channel_sync_log "
            + "WHERE shop_id = #{shopId} AND created_at >= #{from} GROUP BY result")
    List<Map<String, Object>> countSince(@Param("shopId") Long shopId, @Param("from") LocalDateTime from);
}
