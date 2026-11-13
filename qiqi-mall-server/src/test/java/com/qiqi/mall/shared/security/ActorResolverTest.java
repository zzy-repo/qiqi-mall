package com.qiqi.mall.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActorResolver} 的三主体单测（纯内存，不起 Spring、不碰库）。
 *
 * <p>为什么必须覆盖「API Key」这一格：{@link CurrentUser#get()} 对 API Key 调用返回 null
 * （principal 是 {@link ApiKeyPrincipal} 而不是 {@link AuthUser}），而 {@code /admin/**} 对
 * {@code ROLE_AI} 是开放的 —— 状态台账若依赖 CurrentUser，AI 一调审核接口就是 NPE 或
 * 「operator_id 为空」的无主台账。本测把这条差异钉死：同样是「认证通过」，两种 principal
 * 解析出的操作者类型不同，且都不为 null。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=ActorResolverTest
 */
class ActorResolverTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Object principal, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    @DisplayName("① 管理员 JWT → ADMIN / 账号 id / 用户名")
    void adminJwt() {
        authenticateAs(new AuthUser(7L, "root-admin", JwtService.ROLE_ADMIN), "ROLE_ADMIN");

        ActorResolver.Actor actor = ActorResolver.resolve();

        assertThat(actor.type()).isEqualTo(ActorResolver.ADMIN);
        assertThat(actor.id()).isEqualTo(7L);
        assertThat(actor.name()).isEqualTo("root-admin");
        assertThat(actor.isHuman()).isTrue();
        assertThat(actor.isValid()).isTrue();
    }

    @Test
    @DisplayName("② 会员 JWT → MEMBER（不能把买家操作记成管理员）")
    void memberJwt() {
        authenticateAs(new AuthUser(42L, "buyer01", JwtService.ROLE_MEMBER), "ROLE_MEMBER");

        ActorResolver.Actor actor = ActorResolver.resolve();

        assertThat(actor.type()).isEqualTo(ActorResolver.MEMBER);
        assertThat(actor.id()).isEqualTo(42L);
        assertThat(actor.name()).isEqualTo("buyer01");
    }

    @Test
    @DisplayName("③ role 写成 ROLE_ADMIN / ADMIN 也认成管理员（Spring authority 与 JWT 两种写法）")
    void roleNormalization() {
        authenticateAs(new AuthUser(1L, "a", "ROLE_ADMIN"));
        assertThat(ActorResolver.resolve().type()).isEqualTo(ActorResolver.ADMIN);

        authenticateAs(new AuthUser(2L, "b", "ADMIN"));
        assertThat(ActorResolver.resolve().type()).isEqualTo(ActorResolver.ADMIN);
    }

    @Test
    @DisplayName("④ API Key → APIKEY / key id / key 名；同时证明 CurrentUser 对它返回 null")
    void apiKeyPrincipal() {
        authenticateAs(new ApiKeyPrincipal(9L, "ai-fulfilment", Set.of("order:write")), "ROLE_AI");

        // 这就是缺口本身：CurrentUser 看不见 API Key 调用方
        assertThat(CurrentUser.get()).isNull();

        ActorResolver.Actor actor = ActorResolver.resolve();
        assertThat(actor.type()).isEqualTo(ActorResolver.APIKEY);
        assertThat(actor.id()).isEqualTo(9L);
        assertThat(actor.name()).isEqualTo("ai-fulfilment");
        assertThat(actor.isValid()).isTrue();
    }

    @Test
    @DisplayName("⑤ 没有认证（定时任务 / 系统触发）→ SYSTEM / null / system，绝不 NPE")
    void noAuthentication() {
        SecurityContextHolder.clearContext();

        ActorResolver.Actor actor = ActorResolver.resolve();

        assertThat(actor.type()).isEqualTo(ActorResolver.SYSTEM);
        assertThat(actor.id()).isNull();
        assertThat(actor.name()).isEqualTo(ActorResolver.SYSTEM_NAME);
        assertThat(actor.isHuman()).isFalse();
        assertThat(actor.isValid()).isTrue();
    }

    @Test
    @DisplayName("⑥ 匿名 principal（字符串 anonymousUser）同样落到 SYSTEM，不猜成人")
    void anonymousPrincipal() {
        authenticateAs("anonymousUser");

        assertThat(ActorResolver.resolve().type()).isEqualTo(ActorResolver.SYSTEM);
    }

    @Test
    @DisplayName("⑦ 名字为空时不留空串：API Key 退化成 apikey#id，账号退化成类型名")
    void blankNameFallback() {
        authenticateAs(new ApiKeyPrincipal(11L, "  ", Set.of()), "ROLE_AI");
        assertThat(ActorResolver.resolve().name()).isEqualTo("apikey#11");

        authenticateAs(new AuthUser(12L, "", JwtService.ROLE_MEMBER), "ROLE_MEMBER");
        assertThat(ActorResolver.resolve().name()).isEqualTo("member");
    }
}
