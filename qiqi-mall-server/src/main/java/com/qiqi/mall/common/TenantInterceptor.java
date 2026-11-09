package com.qiqi.mall.common;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 从请求头 X-Tenant-Id 读取租户ID，写入上下文 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Tenant-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String v = request.getHeader(HEADER);
        if (v != null && !v.isBlank()) {
            try {
                TenantContext.set(Long.parseLong(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
