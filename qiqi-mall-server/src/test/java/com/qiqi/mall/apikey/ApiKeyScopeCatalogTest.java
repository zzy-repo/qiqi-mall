package com.qiqi.mall.apikey;

import com.qiqi.mall.apikey.service.AiApiKeyService;
import com.qiqi.mall.shared.security.ApiKeyAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限目录的**只读闸门**单测（不连库）。
 *
 * <p>背景：{@code ApiKeyAuthFilter} 只能按路径前缀匹配。为了让
 * {@code /admin/order-audit/**}（读 queue/runs/trail、写 run/freeze/unfreeze）对 API Key
 * 保持「可读不可写」，实现方式是给写方法一个**永不授予**的 scope（{@code audit:write} 等，
 * 不在 {@link AiApiKeyService#ALL_SCOPES} 里 ⇒ 任何 key 都拿不到 ⇒ 写操作永远 403）。
 *
 * <p>这个机制唯一的破法就是「有人把 audit:write 加进权限目录」。那一刻界面上就多出一个
 * 可以被勾上的权限，勾上之后 AI 就能解冻订单 —— 而代码编译、启动、其它测试全绿。
 * 所以这里把三条钉死：写 scope 不可授予、目录里不存在这三个域的写权限、三个只读域确实存在。
 */
class ApiKeyScopeCatalogTest {

    @Test
    @DisplayName("只读规则的「写 scope」永远不在可授予目录里（否则 AI 就能冻结/解冻订单）")
    void unGrantableWriteScopesAreNotGrantable() {
        assertThat(ApiKeyAuthFilter.unGrantableWriteScopes()).isNotEmpty();
        for (String scope : ApiKeyAuthFilter.unGrantableWriteScopes()) {
            assertThat(AiApiKeyService.ALL_SCOPES)
                    .as("一旦 %s 进入权限目录，/admin/order-audit 的写接口就对 AI 打开了", scope)
                    .doesNotContain(scope);
        }
    }

    @Test
    @DisplayName("证据/库存/审核三个域只有读权限，没有写权限")
    void evidenceStockAuditAreReadOnly() {
        assertThat(AiApiKeyService.ALL_SCOPES).contains("ledger:read", "stock:read", "audit:read");
        assertThat(AiApiKeyService.ALL_SCOPES)
                .noneMatch(s -> s.equals("ledger:write") || s.equals("stock:write") || s.equals("audit:write"));
    }
}
