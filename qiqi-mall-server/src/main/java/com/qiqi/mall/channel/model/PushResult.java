package com.qiqi.mall.channel.model;

/**
 * 回传结果（适配器 → 业务层）。
 *
 * <p>{@code code} 是**平台语义码**，业务层只关心一种：{@code DUPLICATE}（平台侧已存在这条发货信息）
 * —— 那不是失败，是幂等命中，记 {@code SKIP}。其余码一律按 {@code ok} 决定 OK/FAIL。
 *
 * @param ok          平台是否受理成功
 * @param code        平台返回码（OK / DUPLICATE / 平台自定义错误码）
 * @param message     平台返回说明（失败时进 sync_log.reason）
 * @param httpStatus  HTTP 状态码（0 = 没拿到响应，连接层异常）
 * @param rawResponse 原始响应体（截断后进 sync_log.response_payload，排查用）
 */
public record PushResult(boolean ok, String code, String message, int httpStatus, String rawResponse) {

    /** 平台侧已存在（幂等命中）—— 业务层据此记 SKIP 而不是 FAIL */
    public static final String CODE_DUPLICATE = "DUPLICATE";

    public boolean duplicated() {
        return CODE_DUPLICATE.equalsIgnoreCase(code);
    }
}
