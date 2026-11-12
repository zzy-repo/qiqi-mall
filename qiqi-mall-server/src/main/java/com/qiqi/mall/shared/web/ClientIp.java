package com.qiqi.mall.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 真实客户端 IP 解析。
 *
 * <p>部署链路：公网访客 → ECS Caddy（{@code header_up X-Real-IP {remote_host}}）→ Tailscale
 * → 本机前端 nginx（沿用上游 X-Real-IP，见各 default.conf.template / nginx/qiqi.conf 的
 * {@code $qiqi_client_ip}）→ 网关 nginx → 后端实例。两层代理后 {@code getRemoteAddr()}
 * 只会是上游容器/网关的地址，真实 IP 只在转发头里。</p>
 *
 * <p>解析顺序：{@code X-Forwarded-For} 第一段（最左为真实客户端）→ {@code X-Real-IP}
 * → {@code getRemoteAddr()}。当前只用于日志与后续按 IP 限流的基础设施，不参与任何鉴权或
 * 验证码计数（验证码失败计数仍是 {@code captcha:fail:{username}}，与 IP 无关）。</p>
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** 取真实客户端 IP：优先 X-Forwarded-For 第一段（最左是真实客户端），其次 X-Real-IP，最后 remoteAddr */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) {
                return first;
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && !"unknown".equalsIgnoreCase(real)) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
