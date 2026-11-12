package com.qiqi.mall.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.member.entity.Member;
import com.qiqi.mall.shared.security.JwtService;
import com.qiqi.mall.member.service.MemberService;
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

/** C 端登录/注册：注册成功即自动登录发 token */
@Tag(name = "买家端-登录注册", description = "C 端注册即登录，返回会员令牌")
@RestController
@RequestMapping("/portal/auth")
public class PortalAuthController {

    private static final Logger log = LoggerFactory.getLogger(PortalAuthController.class);

    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public PortalAuthController(MemberService memberService, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.memberService = memberService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Result<Map<String, Object>>> login(@RequestBody AuthRequest request,
                                                            HttpServletRequest httpRequest) {
        if (isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            log.warn("member login fail: username={} ip={} reason=empty-credential",
                    request.getUsername(), ClientIp.resolve(httpRequest));
            return ResponseEntity.badRequest().body(Result.fail("用户名和密码不能为空"));
        }
        Member member = memberService.getOne(
                new LambdaQueryWrapper<Member>().eq(Member::getUsername, request.getUsername()), false);
        if (member == null || isBlank(member.getPassword())
                || !passwordEncoder.matches(request.getPassword(), member.getPassword())
                || member.getStatus() == null || member.getStatus() != 1) {
            log.warn("member login fail: username={} ip={} reason=bad-credential-or-disabled",
                    request.getUsername(), ClientIp.resolve(httpRequest));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("用户名或密码错误"));
        }
        log.info("member login ok: username={} ip={}", request.getUsername(), ClientIp.resolve(httpRequest));
        return ResponseEntity.ok(Result.ok(tokenBody(member)));
    }

    @PostMapping("/register")
    public ResponseEntity<Result<Map<String, Object>>> register(@RequestBody AuthRequest request,
                                                               HttpServletRequest httpRequest) {
        if (isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            return ResponseEntity.badRequest().body(Result.fail("用户名和密码不能为空"));
        }
        Long exists = memberService.count(
                new LambdaQueryWrapper<Member>().eq(Member::getUsername, request.getUsername()));
        if (exists != null && exists > 0) {
            log.warn("member register fail: username={} ip={} reason=username-taken",
                    request.getUsername(), ClientIp.resolve(httpRequest));
            return ResponseEntity.badRequest().body(Result.fail("用户名已被占用"));
        }
        Member member = new Member();
        member.setUsername(request.getUsername());
        member.setPassword(passwordEncoder.encode(request.getPassword()));
        member.setNickname(isBlank(request.getNickname()) ? request.getUsername() : request.getNickname());
        member.setPhone(request.getPhone());
        member.setStatus(1);
        memberService.save(member);
        log.info("member register ok: username={} ip={}", request.getUsername(), ClientIp.resolve(httpRequest));
        // 注册成功自动登录
        return ResponseEntity.ok(Result.ok(tokenBody(member)));
    }

    private Map<String, Object> tokenBody(Member member) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessToken", jwtService.issue(member.getId(), member.getUsername(), JwtService.ROLE_MEMBER));
        data.put("memberId", member.getId());
        data.put("username", member.getUsername());
        data.put("nickname", member.getNickname());
        return data;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static class AuthRequest {
        private String username;
        private String password;
        private String nickname;
        private String phone;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }
}
