package com.qiqi.mall.shared.security;

/**
 * 高危写操作「执行阶段」的请求体：只带确认码。
 *
 * <p>统一契约（14 个 L3 写接口一致）：
 * <pre>
 * ① 预演  POST/PUT/DELETE /admin/xxx/{id}/yyy?dryRun=true        （不落库）
 *        → { code:0, data:{ willDo:"中文描述", confirmCode:"uuid", expiresInSec:300 } }
 * ② 执行  同一个地址（不带 dryRun），body { "confirmCode":"uuid" }
 * </pre>
 *
 * <p>少数接口本身有业务请求体（如 PUT /admin/deliveries/{id}、PUT /admin/return-applies/{id}），
 * 那些接口在自己的请求体里带 {@code confirmCode} 字段，同时也接受查询参数
 * {@code ?confirmCode=} 兜底（见 {@link #pick(String, String)}）。
 */
public class ConfirmRequest {

    /** 一键一用的一次性确认码（split-preview / dryRun=true 签发） */
    private String confirmCode;

    public String getConfirmCode() {
        return confirmCode;
    }

    public void setConfirmCode(String confirmCode) {
        this.confirmCode = confirmCode;
    }

    /** 取码：请求体里的优先，其次查询参数（给「请求体是业务对象」的接口留的后路） */
    public static String pick(String bodyCode, String queryCode) {
        if (bodyCode != null && !bodyCode.isBlank()) return bodyCode;
        return queryCode;
    }
}
