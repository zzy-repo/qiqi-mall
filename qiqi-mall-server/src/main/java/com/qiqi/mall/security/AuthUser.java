package com.qiqi.mall.security;

/** 登录主体：member 或 admin */
public record AuthUser(Long id, String username, String role) {
}
