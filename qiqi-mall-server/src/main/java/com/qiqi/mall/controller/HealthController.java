package com.qiqi.mall.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${spring.profiles.active:unknown}")
    private String env;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "env", env, "service", "qiqi-mall-server");
    }
}
