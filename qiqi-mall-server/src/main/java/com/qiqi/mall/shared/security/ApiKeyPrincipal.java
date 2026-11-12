package com.qiqi.mall.shared.security;

import java.util.Set;

/**
 * API Key 调用方身份：没有会员/管理员账号，只有「这把 key 是谁」和「它能干什么」。
 *
 * <p>注意：它**不是** {@link AuthUser}，所以 {@link CurrentUser#get()} 对 AI 调用返回 null ——
 * 这正是想要的：AI 没有「人」的主语，任何依赖 CurrentUser 的业务（会员资料、购物车、下单）
 * 都不该由 API Key 触发（买家端写操作在过滤器里就直接 403 了）。
 */
public record ApiKeyPrincipal(Long keyId, String keyName, Set<String> scopes) {

    public boolean hasScope(String scope) {
        return scopes != null && scope != null && scopes.contains(scope);
    }
}
