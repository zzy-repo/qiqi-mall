package com.qiqi.mall.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档的抬头信息（springdoc-openapi）。
 *
 * 只放「叫什么、哪个版本、怎么鉴权」这三件事；接口本身的分组与说明写在各个 Controller 上
 * （@Tag / @Operation），DTO 字段不再逐个加注解 —— 性价比太低。
 *
 * 鉴权：这里只登记 Bearer 方案（让页面右上角出现 Authorize 按钮），**不**做全局
 * SecurityRequirement —— 否则公开接口（商品浏览、登录）也会被标上锁，页面反而误导。
 * 实际放行规则仍以 shared/security/SecurityConfig 为准。
 *
 * 文档地址：/swagger-ui.html（页面）｜/v3/api-docs（JSON）
 * 可用性：由 application-{dev,test}.yml 的 springdoc.*.enabled 控制。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI qiqiMallOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("七七商城 后端接口")
                        .version("0.0.1-SNAPSHOT")
                        .description("B 端（/admin，需管理员令牌）与 C 端（/portal，需会员令牌或公开浏览）。"
                                + "调试受保护接口时：先调登录接口拿 accessToken，再点右上角 Authorize 填入。"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("登录接口返回的 accessToken（后台：/admin/auth/login；买家：/portal/auth/login）")));
    }
}
