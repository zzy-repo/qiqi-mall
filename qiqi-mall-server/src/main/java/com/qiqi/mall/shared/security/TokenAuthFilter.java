package com.qiqi.mall.shared.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 读取 Authorization: Bearer <token>，校验角色后写入 SecurityContext */
public class TokenAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final String expectedRole;

    public TokenAuthFilter(JwtService jwtService, String expectedRole) {
        this.jwtService = jwtService;
        this.expectedRole = expectedRole;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Claims claims = jwtService.parse(header.substring(7), expectedRole);
            if (claims != null) {
                Long uid = claims.get("uid", Number.class).longValue();
                String username = claims.getSubject();
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        new AuthUser(uid, username, expectedRole), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + expectedRole.toUpperCase())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
