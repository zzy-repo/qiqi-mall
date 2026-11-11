package com.qiqi.mall.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档的抬头信息（springdoc-openapi）。
 *
 * 只放「叫什么、哪个版本、怎么用」三句话；接口本身的分组与说明写在各个 Controller 上
 * （@Tag / @Operation），DTO 字段不再逐个加注解 —— 性价比太低。
 *
 * 文档地址：/swagger-ui.html（页面）｜/v3/api-docs（JSON）
 * 可用性：由 application-{dev,test}.yml 的 springdoc.*.enabled 控制。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI qiqiMallOpenApi() {
        return new OpenAPI().info(new Info()
                .title("七七商城 后端接口")
                .version("0.0.1-SNAPSHOT")
                .description("B 端（/admin，需管理员令牌）与 C 端（/portal，需会员令牌或公开浏览）。"
                        + "调试受保护接口时先调登录接口拿 accessToken，再点右上角 Authorize 填入。"));
    }
}
