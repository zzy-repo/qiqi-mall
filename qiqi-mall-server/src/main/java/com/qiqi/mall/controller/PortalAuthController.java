package com.qiqi.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.common.Result;
import com.qiqi.mall.entity.Member;
import com.qiqi.mall.security.JwtService;
import com.qiqi.mall.service.MemberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** C 端登录/注册：注册成功即自动登录发 token */
@RestController
@RequestMapping("/portal/auth")
public class PortalAuthController {

    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public PortalAuthController(MemberService memberService, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.memberService = memberService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Result<Map<String, Object>>> login(@RequestBody AuthRequest request) {
        if (isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            return ResponseEntity.badRequest().body(Result.fail("用户名和密码不能为空"));
        }
        Member member = memberService.getOne(
                new LambdaQueryWrapper<Member>().eq(Member::getUsername, request.getUsername()), false);
        if (member == null || isBlank(member.getPassword())
                || !passwordEncoder.matches(request.getPassword(), member.getPassword())
                || member.getStatus() == null || member.getStatus() != 1) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail("用户名或密码错误"));
        }
        return ResponseEntity.ok(Result.ok(tokenBody(member)));
    }

    @PostMapping("/register")
    public ResponseEntity<Result<Map<String, Object>>> register(@RequestBody AuthRequest request) {
        if (isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            return ResponseEntity.badRequest().body(Result.fail("用户名和密码不能为空"));
        }
        Long exists = memberService.count(
                new LambdaQueryWrapper<Member>().eq(Member::getUsername, request.getUsername()));
        if (exists != null && exists > 0) {
            return ResponseEntity.badRequest().body(Result.fail("用户名已被占用"));
        }
        Member member = new Member();
        member.setUsername(request.getUsername());
        member.setPassword(passwordEncoder.encode(request.getPassword()));
        member.setNickname(isBlank(request.getNickname()) ? request.getUsername() : request.getNickname());
        member.setPhone(request.getPhone());
        member.setStatus(1);
        memberService.save(member);
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
