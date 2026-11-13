package com.qiqi.mall.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.channel.entity.ChannelSyncLog;
import com.qiqi.mall.channel.mapper.ChannelSyncLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同步日志写入口（{@code channel_sync_log} 的唯一写者）。
 *
 * <p>口径：
 * <ul>
 *   <li><b>一条外呼一行</b>：拉单一次一条汇总行 + 每张「跳过/失败」的单各一条明细行；
 *       回传每个包裹、每次尝试各一条。宁可多记，也不要出现「库里没有、但事情发生了」；</li>
 *   <li>报文截断到 {@link #MAX_PAYLOAD} 字符：这列是排查用的证据，不是备份，整批订单全文塞进来
 *       只会让这张表被 IO 拖死；</li>
 *   <li>写日志失败<b>不吞异常</b>：证据链断了要立刻暴露（调用方的事务/返回会一起失败），
 *       而不是「业务成功、日志悄悄丢了」。</li>
 * </ul>
 */
@Component
public class ChannelSyncLogWriter {

    private static final Logger log = LoggerFactory.getLogger(ChannelSyncLogWriter.class);

    /** 单列报文上限（TEXT 能存 64KB，但排查时没人看得完 64KB） */
    private static final int MAX_PAYLOAD = 4000;
    /** reason 列宽（VARCHAR(255)） */
    private static final int MAX_REASON = 255;

    private final ChannelSyncLogMapper mapper;
    private final ObjectMapper objectMapper;

    public ChannelSyncLogWriter(ChannelSyncLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 写一行日志。
     *
     * @param shopId  店铺 id
     * @param action  {@link ChannelSyncLog#ACTION_PULL} / {@link ChannelSyncLog#ACTION_SHIP_PUSH}
     * @param refType ORDER / DELIVERY / PLATFORM_ORDER / SHOP，可为 null
     * @param refId   关联 id，可为 null
     * @param request 请求侧信息（对象会转 JSON；null 存 null）
     * @param response 响应侧信息（同上）
     * @param result  OK / FAIL / SKIP
     * @param reason  原因（FAIL 写异常信息，SKIP 写 IDEMPOTENT）
     * @param attempt 第几次尝试（1 起）
     * @param latencyMs 外呼耗时（毫秒，没有外呼就传 0）
     */
    public void write(Long shopId, String action, String refType, Long refId,
                      Object request, Object response, String result, String reason,
                      int attempt, long latencyMs) {
        ChannelSyncLog row = new ChannelSyncLog();
        row.setShopId(shopId);
        row.setAction(action);
        row.setRefType(refType);
        row.setRefId(refId);
        row.setRequestPayload(json(request));
        row.setResponsePayload(json(response));
        row.setResult(result);
        row.setReason(truncate(reason, MAX_REASON));
        row.setAttempt(Math.max(attempt, 1));
        row.setLatencyMs((int) Math.min(Math.max(latencyMs, 0), Integer.MAX_VALUE));
        mapper.insert(row);
        log.debug("[channel] sync_log shop={} action={} result={} reason={} attempt={} {}ms",
                shopId, action, result, reason, attempt, latencyMs);
    }

    /** 店铺今日结果分布：{OK: n, FAIL: n, SKIP: n}（卡片用） */
    public Map<String, Integer> todayCounts(Long shopId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(ChannelSyncLog.RESULT_OK, 0);
        counts.put(ChannelSyncLog.RESULT_FAIL, 0);
        counts.put(ChannelSyncLog.RESULT_SKIP, 0);
        for (Map<String, Object> row : mapper.countSince(shopId, LocalDate.now().atStartOfDay())) {
            Object result = row.get("result");
            Object cnt = row.get("cnt");
            if (result != null && cnt instanceof Number n) {
                counts.put(String.valueOf(result), n.intValue());
            }
        }
        return counts;
    }

    private String json(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return truncate(s, MAX_PAYLOAD);
        try {
            return truncate(objectMapper.writeValueAsString(value), MAX_PAYLOAD);
        } catch (Exception e) {
            return truncate(String.valueOf(value), MAX_PAYLOAD);
        }
    }

    private static String truncate(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max) + "…(truncated)";
    }
}
