package com.qiqi.mall.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.apikey.service.AiApiKeyService;
import com.qiqi.mall.shared.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final AiApiKeyService apiKeyService;

    public SecurityConfig(JwtService jwtService, ObjectMapper objectMapper, AiApiKeyService apiKeyService) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.apiKeyService = apiKeyService;
    }

    /**
     * 两个过滤器的顺序：API Key 过滤器在前，JWT 在后面兜底。
     * 两者都插在 UsernamePasswordAuthenticationFilter 之前（同锚点，Spring 的排序是稳定排序，
     * 先 add 的在前）；功能上互不依赖 —— 只有 X-Api-Key 的请求由前者认证，
     * 只有 Authorization: Bearer 的请求由后者认证，同时带上两者时以 JWT（人工操作）为准。
     */
    private ApiKeyAuthFilter apiKeyFilter() {
        return new ApiKeyAuthFilter(apiKeyService, objectMapper);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 过滤器链 2：/admin/** 认 admin JWT（登录、验证码放行），另外认 API Key（ROLE_AI）。
     *
     * 红线：/admin/admins/**（管理员账号）与 /admin/api-keys/**（密钥自身）只认 JWT ——
     * 这里再收一遍 hasRole("ADMIN")，即使 ApiKeyAuthFilter 将来被误改也进不来。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/admin/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/auth/login", "/admin/auth/captcha", "/admin/auth/captcha-required").permitAll()
                        .requestMatchers("/admin/admins", "/admin/admins/**",
                                "/admin/api-keys", "/admin/api-keys/**").hasRole("ADMIN")
                        .anyRequest().hasAnyRole("ADMIN", ApiKeyAuthFilter.ROLE_AI))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, 401, "未登录或登录已过期"))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, 403, "无权访问")))
                .addFilterBefore(apiKeyFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new TokenAuthFilter(jwtService, JwtService.ROLE_ADMIN), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 过滤器链 1：/portal/** 认 member JWT（商品浏览、登录注册、验证码公开），另外认 API Key（ROLE_AI，只读） */
    @Bean
    @Order(2)
    public SecurityFilterChain portalChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/portal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/portal/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/portal/categories/**", "/portal/products/**", "/portal/skus/**").permitAll()
                        .anyRequest().hasAnyRole("MEMBER", ApiKeyAuthFilter.ROLE_AI))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, 401, "未登录或登录已过期"))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, 403, "无权访问")))
                .addFilterBefore(apiKeyFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new TokenAuthFilter(jwtService, JwtService.ROLE_MEMBER), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 其余路径（健康检查、接口文档等）放行。
     *
     * 接口文档（springdoc-openapi）在这里显式列出，原因有两条：
     *  1) 文档路径既不属于 /admin/** 也不属于 /portal/**，本来就落到这条链上；
     *     显式写出来是为了让「谁能匿名访问」一目了然，不依赖隐式兜底。
     *  2) 文档开关不在这里控制，而在 application-{dev,test}.yml 的
     *     `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled`：
     *     将来加 prod profile 就把它们置 false（路径照样放行，但没有内容可出）。
     */
    @Bean
    @Order(3)
    public SecurityFilterChain openChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/swagger-resources/**").permitAll()
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(message)));
    }
}
