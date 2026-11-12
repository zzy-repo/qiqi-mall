package com.qiqi.mall.member.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.member.entity.Admin;
import com.qiqi.mall.shared.security.CaptchaService;
import com.qiqi.mall.shared.security.CaptchaStore;
import com.qiqi.mall.shared.security.JwtService;
import com.qiqi.mall.member.service.AdminService;
import com.qiqi.mall.shared.web.ClientIp;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** 后台登录：无注册入口；失败累计 5 次后要求验证码 */
@Tag(name = "管理端-登录鉴权", description = "后台登录、登录失败计数与图形验证码")
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminService adminService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CaptchaStore captchaStore;
    private final CaptchaService captchaService;

    public AdminAuthController(AdminService adminService, PasswordEncoder passwordEncoder, JwtService jwtService,
                               CaptchaStore captchaStore, CaptchaService captchaService) {
        this.adminService = adminService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.captchaStore = captchaStore;
        this.captchaService = captchaService;
    }

    /** 是否需要验证码（失败 ≥5 次） */
    @GetMapping("/captcha-required")
    public Result<Boolean> captchaRequired(@RequestParam String username) {
        return Result.ok(captchaStore.captchaRequired(username));
    }

    /** 获取图形验证码 */
    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        return Result.ok(captchaService.generate());
    }

    /** 登录：{username, password, captchaId?, captchaCode?} */
    @PostMapping("/login")
    public ResponseEntity<Result<Map<String, Object>>> login(@RequestBody LoginRequest request,
                                                            HttpServletRequest httpRequest) {
        String username = request.getUsername();
        if (username == null || username.isBlank() || request.getPassword() == null) {
            log.warn("admin login fail: username={} ip={} reason=empty-credential",
                    username, ClientIp.resolve(httpRequest));
            return ResponseEntity.badRequest().body(Result.fail("用户名和密码不能为空"));
        }

        // 需要验证码时先校验（一次性消费）
        if (captchaStore.captchaRequired(username)) {
            if (!captchaStore.verifyAndConsume(request.getCaptchaId(), request.getCaptchaCode())) {
                log.warn("admin login fail: username={} ip={} reason=captcha-mismatch",
                        username, ClientIp.resolve(httpRequest));
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("验证码错误，请重新获取"));
            }
        }

        Admin admin = adminService.getOne(new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, username), false);
        if (admin == null || !passwordMatches(request.getPassword(), admin.getPassword())
                || admin.getStatus() == null || admin.getStatus() != 1) {
            captchaStore.increaseFail(username);
            log.warn("admin login fail: username={} ip={} reason=bad-credential-or-disabled",
                    username, ClientIp.resolve(httpRequest));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("用户名或密码错误"));
        }

        captchaStore.clearFail(username);
        log.info("admin login ok: username={} ip={}", username, ClientIp.resolve(httpRequest));
        String token = jwtService.issue(admin.getId(), admin.getUsername(), JwtService.ROLE_ADMIN);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessToken", token);
        data.put("username", admin.getUsername());
        data.put("nickname", admin.getNickname());
        return ResponseEntity.ok(Result.ok(data));
    }

    private boolean passwordMatches(String raw, String encoded) {
        if (encoded == null || encoded.isBlank()) return false;
        try {
            return passwordEncoder.matches(raw, encoded);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static class LoginRequest {
        private String username;
        private String password;
        private String captchaId;
        private String captchaCode;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getCaptchaId() { return captchaId; }
        public void setCaptchaId(String captchaId) { this.captchaId = captchaId; }
        public String getCaptchaCode() { return captchaCode; }
        public void setCaptchaCode(String captchaCode) { this.captchaCode = captchaCode; }
    }
}
