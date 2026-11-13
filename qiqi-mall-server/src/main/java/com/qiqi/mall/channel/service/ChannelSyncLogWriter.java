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
 *
 * <p><b>幂等闸</b>（V2 迁移的 {@code uk_sync_idem}）：{@code SHIP_PUSH} 行由库里的生成列
 * {@code idem_key = {shopId}:SHIP_PUSH:{refId}:{attempt}} 兜底 —— 同一个包裹的同一次尝试
 * 被重放（人工连点、调度重入、异常重放）只会留下第一行；第二次插入走 ODKU 的空转分支
 * （不修改任何列、不抛异常），本类把它当「这次尝试已记过账」→ 按 SKIP 处理，<b>不报错</b>。
 * 判断细节见 {@code ChannelSyncLogMapper#insertOrTouch}（影响行数在 JDBC 侧不可用，看自增主键）。
 * PULL 汇总行 / PULL 明细行的 {@code idem_key} 恒为 NULL，不参与唯一约束（有意为之：
 * 连拉两次是两件事，两行都要留），所以本类的幂等只针对回传发货。
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
     * @return {@code true}=新落了一行；{@code false}=该次尝试已记过账（幂等命中，被跳过）
     */
    public boolean write(Long shopId, String action, String refType, Long refId,
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
        int affected = mapper.insertOrTouch(row);
        // 判据见 ChannelSyncLogMapper#insertOrTouch：driver 默认置 CLIENT_FOUND_ROWS，
        // ODKU 命中重复键时影响行数在 JDBC 侧报 1，所以真正的信号是「自增主键有没有被回填」。
        boolean alreadyRecorded = row.getId() == null || affected == 0;
        if (alreadyRecorded) {
            // 撞 uk_sync_idem：这个 (shopId, 包裹, attempt) 已经记过账了。不报错、不覆盖第一行。
            log.debug("[channel] sync_log 幂等跳过（该次尝试已记过账）shop={} action={} ref={}/{} attempt={}",
                    shopId, action, refType, refId, attempt);
            return false;
        }
        log.debug("[channel] sync_log shop={} action={} result={} reason={} attempt={} {}ms",
                shopId, action, result, reason, attempt, latencyMs);
        return true;
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
