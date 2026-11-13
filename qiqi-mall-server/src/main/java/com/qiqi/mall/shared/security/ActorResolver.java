package com.qiqi.mall.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 操作者解析：把「当前这次请求是谁在操作」翻译成台账要的三列
 * {@code operator_type / operator_id / operator_name}。
 *
 * <h3>为什么不能用 {@link CurrentUser}</h3>
 * {@link CurrentUser#get()} 只认 {@link AuthUser}（会员 / 管理员 JWT），而 API Key 调用时
 * principal 是 {@link ApiKeyPrincipal} —— 于是它对 AI 调用<b>返回 null</b>。
 * {@code /admin/**} 对 {@code ROLE_AI} 是开放的（见 {@code ApiKeyAuthFilter}），
 * 所以状态台账若直接写 {@code CurrentUser.get().id()}：
 * <ul>
 *   <li>要么 NPE（AI 调审核接口就直接 500）；</li>
 *   <li>要么被写成 {@code operator_id = NULL} 的「无主台账」，事后无法回答「这单是谁改的」——
 *       而「谁改的」正是台账存在的唯一理由。</li>
 * </ul>
 * 所以三种主体必须分开解析，宁可用 {@code SYSTEM} 说清「没有人的主语」，也不留空。
 *
 * <h3>三种主体</h3>
 * <ol>
 *   <li>SecurityContext 里是 {@link AuthUser} → {@code ADMIN}（role=admin）或 {@code MEMBER}（role=member），
 *       id = 账号 id，name = 用户名；</li>
 *   <li>SecurityContext 里是 {@link ApiKeyPrincipal} → {@code APIKEY}，id = key 的主键 id，
 *       name = key 的名字（人类起的名字，看台账时认得出来是哪把 key）；</li>
 *   <li>两者都没有（定时任务 / 系统自触发 / 不带认证的内部调用）→ {@code SYSTEM}, id = null, name = "system"。</li>
 * </ol>
 *
 * <p>三个 type 取值与 DDL 的 CHECK 闭集逐字一致
 * （{@code ck_*_operator_type}: {@code ADMIN / MEMBER / APIKEY / SYSTEM}），
 * 别处不要再拼字符串。{@code operator_id} 对 API Key 存的是 {@code ai_api_key.id}，
 * 不是 key 明文/前缀 —— 台账里绝不出现凭证。
 *
 * <p><b>注意</b>：本类只「解析」，不写库。第一次落库在状态台账里（P2 第二步），
 * 因此这里没有任何 {@code INSERT}。
 */
public final class ActorResolver {

    /** 操作者类型：与 DDL CHECK 闭集逐字一致 */
    public static final String ADMIN = "ADMIN";
    public static final String MEMBER = "MEMBER";
    public static final String APIKEY = "APIKEY";
    public static final String SYSTEM = "SYSTEM";

    /** 系统主体的固定名字（没有人的主语时用它，绝不留空串） */
    public static final String SYSTEM_NAME = "system";

    /**
     * 一次操作的操作者（不可变）。
     *
     * @param type {@code ADMIN / MEMBER / APIKEY / SYSTEM}
     * @param id   账号 id 或 key id；{@code SYSTEM} 时为 null
     * @param name 用户名 / key 名 / "system"；任何情况下都不为 null、不为空串
     */
    public record Actor(String type, Long id, String name) {

        /** 台账落库前的口径自检：type 必须在闭集内，name 必须有值 */
        public boolean isValid() {
            if (type == null || name == null || name.isBlank()) return false;
            return ADMIN.equals(type) || MEMBER.equals(type) || APIKEY.equals(type) || SYSTEM.equals(type);
        }

        /** 是否「有人的主语」（SYSTEM 不是人：定时任务/系统触发） */
        public boolean isHuman() {
            return ADMIN.equals(type) || MEMBER.equals(type) || APIKEY.equals(type);
        }
    }

    private ActorResolver() {
    }

    /** 解析当前请求的操作者（永不返回 null，永不抛异常） */
    public static Actor resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();

        // ① 人：管理员 / 会员 JWT（TokenAuthFilter 写入）
        if (principal instanceof AuthUser user) {
            String type = isAdmin(user.role()) ? ADMIN : MEMBER;
            return new Actor(type, user.id(), nonBlank(user.username(), type.toLowerCase()));
        }
        // ② 机器：API Key（ApiKeyAuthFilter 写入，principal 不是 AuthUser，所以 CurrentUser.get() 为 null）
        if (principal instanceof ApiKeyPrincipal key) {
            return new Actor(APIKEY, key.keyId(), nonBlank(key.keyName(), "apikey#" + key.keyId()));
        }
        // ③ 没有主体：定时任务 / 系统自触发 / 内部调用
        return new Actor(SYSTEM, null, SYSTEM_NAME);
    }

    /**
     * role 归一化：{@code admin} / {@code ADMIN} / {@code ROLE_ADMIN} 都算管理员。
     *
     * <p>为什么容忍三种写法：JWT 里存的是 {@code JwtService.ROLE_ADMIN = "admin"}，
     * 而 Spring Security 的 authority 是 {@code ROLE_ADMIN}，测试里手搓 Authentication 时
     * 两种写法都有人用 —— 认错一个就会把管理员的操作记成 MEMBER，台账直接失真。
     */
    private static boolean isAdmin(String role) {
        if (role == null) return false;
        String r = role.trim();
        if (r.regionMatches(true, 0, "ROLE_", 0, 5)) r = r.substring(5);
        return r.equalsIgnoreCase(JwtService.ROLE_ADMIN);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
