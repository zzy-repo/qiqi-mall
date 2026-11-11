package com.qiqi.mall.shared.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "公共-健康检查", description = "健康检查与版本信息，供容器探针使用")
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
