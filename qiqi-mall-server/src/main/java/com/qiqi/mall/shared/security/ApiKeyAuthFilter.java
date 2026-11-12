package com.qiqi.mall.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.apikey.entity.AiApiKey;
import com.qiqi.mall.apikey.service.AiApiKeyService;
import com.qiqi.mall.shared.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 调接口用的 API Key 认证过滤器（挂在 /admin/** 与 /portal/** 两条链上，位于 JWT 过滤器之前）。
 *
 * <pre>
 * X-Api-Key: ak_xxx
 *   → sha256 后查表（60 秒 Redis 缓存，见 AiApiKeyService）
 *   → 不存在 / status=0 / 已过期        → 401「API Key 无效或已过期」
 *   → 本次接口所需 scope 不在 scopes 里 → 403「API Key 缺少权限：order:write」
 *   → 通过：SecurityContext 写入 ROLE_AI + ApiKeyPrincipal(scopes, keyName)
 * 没有 X-Api-Key 头 → 直接放行，交给后面的 JWT 过滤器（人用 JWT 完全不受影响）
 * </pre>
 *
 * <p>路径 → 所需 scope 的映射刻意写死在本类（静态表），不在 Controller 上加注解：
 * 认证与授权是横切关注点，30+ 个 Controller 挨个标注既容易漏、也会把权限散落到各业务包里。
 *
 * <h3>两条红线（API Key 一律 403，只能人工 JWT 操作）</h3>
 * <ul>
 *   <li>{@code /admin/admins**}：管理员账号 CRUD —— AI 能建管理员等于能给自己发任何权限；</li>
 *   <li>{@code /admin/api-keys**}：密钥自身的管理 —— AI 能改自己的 scopes 就等于没有授权体系。</li>
 * </ul>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    public static final String HEADER = "X-Api-Key";
    public static final String ROLE_AI = "AI";

    /** 前缀 → 读权限 / 写权限（GET、HEAD 视为读，其余视为写） */
    private record Rule(String prefix, String readScope, String writeScope) {}

    /** 管理端映射（严格按需求单，不按接口细化） */
    private static final List<Rule> ADMIN_RULES = sortByLongestPrefix(List.of(
            // 订单域：订单 / 发货单 / 退货（退货申请接口实际路径是 /admin/return-applies，"returns" 一并兜住）
            new Rule("/admin/orders", "order:read", "order:write"),
            new Rule("/admin/deliveries", "order:read", "order:write"),
            new Rule("/admin/returns", "order:read", "order:write"),
            new Rule("/admin/return-applies", "order:read", "order:write"),
            // 商品域：商品 / 分类 / 类型 / 属性 / SKU / 图片上传
            new Rule("/admin/products", "catalog:read", "catalog:write"),
            new Rule("/admin/categories", "catalog:read", "catalog:write"),
            new Rule("/admin/product-types", "catalog:read", "catalog:write"),
            new Rule("/admin/attributes", "catalog:read", "catalog:write"),
            new Rule("/admin/skus", "catalog:read", "catalog:write"),
            new Rule("/admin/upload", "catalog:read", "catalog:write"),
            // 按 URL 拉图（AI 补商品图用）：与 multipart 上传同一类权限 —— 它也是「往对象存储里写图」
            new Rule("/admin/upload/image-from-url", "catalog:read", "catalog:write"),
            // 会员域：会员 / 购物车 / 收货地址
            new Rule("/admin/members", "member:read", "member:write"),
            new Rule("/admin/carts", "member:read", "member:write"),
            new Rule("/admin/member-addresses", "member:read", "member:write"),
            // 配置域：仓库 / 订单设置 / 退货原因
            new Rule("/admin/warehouses", "config:read", "config:write"),
            new Rule("/admin/order-setting", "config:read", "config:write"),
            new Rule("/admin/return-reasons", "config:read", "config:write"),
            // 看板：只有读
            new Rule("/admin/dashboard", "dashboard:read", "dashboard:read")));

    /**
     * 买家端（portal）：复用 order:/catalog:/member: 三套权限，不另造 portal:*。
     *
     * 取舍说明：多一套 portal:* 意味着运营要维护「同一件事两个权限名」，
     * 而 portal 的读接口（商品、分类、SKU）与后台读的是同一份数据，授 catalog:read 就该两个都能读。
     * 但 portal 的**写**操作（购物车、地址、下单、支付）依赖买家身份（CurrentUser.id()），
     * API Key 没有会员主语，写进去的数据无主，所以 portal 写操作对 API Key 一律 403。
     */
    private static final List<Rule> PORTAL_RULES = sortByLongestPrefix(List.of(
            new Rule("/portal/categories", "catalog:read", null),
            new Rule("/portal/products", "catalog:read", null),
            new Rule("/portal/skus", "catalog:read", null),
            new Rule("/portal/orders", "order:read", null),
            new Rule("/portal/deliveries", "order:read", null),
            new Rule("/portal/returns", "order:read", null),
            new Rule("/portal/member", "member:read", null),
            new Rule("/portal/cart", "member:read", null)));

    /** 红线：只认人工 JWT */
    private static final List<String> JWT_ONLY_PREFIXES = List.of("/admin/admins", "/admin/api-keys");

    /** 匿名即可的路径：登录/注册/验证码，API Key 在这类路径上不做认证（也不需要） */
    private static final List<String> ANONYMOUS_PREFIXES = List.of("/admin/auth", "/portal/auth");

    private final AiApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(AiApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    private enum Outcome { AUTHENTICATE, SKIP, DENY }

    private record Decision(Outcome outcome, String scope, int status, String message) {
        static Decision need(String scope) { return new Decision(Outcome.AUTHENTICATE, scope, 0, null); }
        static Decision pass() { return new Decision(Outcome.SKIP, null, 0, null); }
        static Decision deny(int status, String message) { return new Decision(Outcome.DENY, null, status, message); }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawKey = request.getHeader(HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            // 没有 X-Api-Key：完全不插手，后面按 JWT 逻辑走
            chain.doFilter(request, response);
            return;
        }

        Decision decision = decide(request);
        if (decision.outcome() == Outcome.SKIP) {
            chain.doFilter(request, response);
            return;
        }
        if (decision.outcome() == Outcome.DENY) {
            log.warn("API Key 访问被拒（黑/白名单）: {} {} - {}", request.getMethod(), request.getRequestURI(), decision.message());
            writeError(response, decision.status(), decision.message());
            return;
        }

        AiApiKey key = apiKeyService.findUsableByPlaintext(rawKey.trim());
        if (key == null) {
            log.warn("API Key 校验失败（不存在/禁用/过期）: {} {}", request.getMethod(), request.getRequestURI());
            writeError(response, 401, "API Key 无效或已过期");
            return;
        }

        Set<String> scopes = new LinkedHashSet<>(AiApiKeyService.splitScopes(key.getScopes()));
        if (!scopes.contains(decision.scope())) {
            log.warn("API Key 缺少权限: key={} 需要={} 实际={}", key.getKeyPrefix(), decision.scope(), scopes);
            writeError(response, 403, "API Key 缺少权限：" + decision.scope());
            return;
        }

        ApiKeyPrincipal principal = new ApiKeyPrincipal(key.getId(), key.getName(), scopes);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE_AI)));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 使用痕迹：节流写库（同一 key 60 秒内只写一次），失败不影响本次请求
        apiKeyService.touchLastUsed(key.getId());

        chain.doFilter(request, response);
    }

    /** 路径 + 方法 → 本次请求需要什么 */
    private Decision decide(HttpServletRequest request) {
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) return Decision.pass();   // CORS 预检不查权限

        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);

        if (underAny(path, JWT_ONLY_PREFIXES)) {
            return Decision.deny(403, "API Key 禁止访问：" + path + "（管理员账号与密钥管理只能人工 JWT 操作）");
        }
        if (underAny(path, ANONYMOUS_PREFIXES)) return Decision.pass();   // 登录/注册/验证码

        Rule adminRule = match(path, ADMIN_RULES);
        if (adminRule != null) {
            return Decision.need(read ? adminRule.readScope() : adminRule.writeScope());
        }

        Rule portalRule = match(path, PORTAL_RULES);
        if (portalRule != null) {
            if (!read) {
                return Decision.deny(403, "API Key 不支持买家端写操作：" + path + "（买家写入需要会员身份，API Key 没有会员主语）");
            }
            return Decision.need(portalRule.readScope());
        }

        // 兜底：未登记路径一律拒绝（fail-closed，新接口忘了配映射不会「意外开放给 AI」）
        return Decision.deny(403, "API Key 无权访问该接口：" + path);
    }

    private static boolean underAny(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private static Rule match(String path, List<Rule> rules) {
        for (Rule rule : rules) {   // rules 已按前缀长度倒序，取最具体的一条
            if (path.equals(rule.prefix()) || path.startsWith(rule.prefix() + "/")) return rule;
        }
        return null;
    }

    private static List<Rule> sortByLongestPrefix(List<Rule> rules) {
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort((a, b) -> b.prefix().length() - a.prefix().length());
        return List.copyOf(sorted);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(message)));
    }
}
