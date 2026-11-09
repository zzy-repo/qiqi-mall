package com.qiqi.mall.common;

/** 当前请求的租户上下文（拦截器从 header X-Tenant-Id 解析后放入） */
public class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
