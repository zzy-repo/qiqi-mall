package com.qiqi.mall.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道同步日志（{@code channel_sync_log}）：只追加，一次外呼一行（失败/SKIP 也各一行）。
 *
 * <p>为什么 SKIP 也要落行：重复拉单是**正常现象**（水位重叠、平台重推），但它必须留证据，
 * 否则「为什么这批单没进来」永远说不清。{@code reason=IDEMPOTENT} 就是那句话。
 *
 * <p>{@code attempt} 记「同一次业务动作的第几次尝试」：回传失败重试一次，
 * 库里就是 attempt=1 FAIL + attempt=2 OK 两行 —— 重试次数由数据自己说明，不另设统计列。
 *
 * <p><b>没有 {@code idemKey} 字段，这是故意的</b>：库里那一列是生成列（V2 迁移），
 * 值由 {@code action/ref_id/attempt} 自己算出来，写入侧既算不出第二份、也不许指定。
 * 实体里放一个字段，就等于给了「Java 侧算一遍、库侧再算一遍」的分裂机会。
 */
@Data
@TableName("channel_sync_log")
public class ChannelSyncLog {

    public static final String ACTION_PULL = "PULL";
    public static final String ACTION_SHIP_PUSH = "SHIP_PUSH";

    public static final String RESULT_OK = "OK";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_SKIP = "SKIP";

    /** 幂等命中（重复拉单 / 平台侧已存在） */
    public static final String REASON_IDEMPOTENT = "IDEMPOTENT";
    /** 平台商品编码对不上本地 SKU（整单跳过） */
    public static final String REASON_SKU_NOT_MATCHED = "SKU_NOT_MATCHED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** PULL / SHIP_PUSH */
    private String action;

    /** ORDER / PLATFORM_ORDER / DELIVERY / SHOP */
    private String refType;

    private Long refId;

    private String requestPayload;

    private String responsePayload;

    /** OK / FAIL / SKIP */
    private String result;

    private String reason;

    private Integer attempt;

    private Integer latencyMs;

    private LocalDateTime createdAt;
}
