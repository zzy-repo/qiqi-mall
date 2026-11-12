package com.qiqi.mall.shared.storage;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        // 快速失败：对外部对象存储（test profile = 阿里云 OSS）缺凭证时直接起不来，
        // 绝不静默降级成本地 MinIO —— 否则你以为在写 OSS，实际还在写容器里的 MinIO。
        if (properties.isRequireCredentials()
                && (!StringUtils.hasText(properties.getAccessKey()) || !StringUtils.hasText(properties.getSecretKey()))) {
            throw new IllegalStateException("""
                    对象存储凭证缺失：access-key / secret-key 为空（对应环境变量 OSS_ACCESS_KEY / OSS_SECRET_KEY）。
                    应用拒绝启动，也不会降级到本地 MinIO。请检查：
                      1) 宿主机 env 是否已加载（如 /root/.aliyun-qiqi/credentials.env 或项目根 .env）
                      2) docker-compose.yml 里 server-1/server-2 的 environment 是否透传了这两个变量
                      3) 容器内 `docker exec qiqi-server-1 printenv | grep -c OSS_` 应有输出
                    """);
        }

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey());
        // OSS 必须显式给 region：否则 SDK 用 path-style，OSS 回
        // "Please use virtual hosted style to access"（已实测）。本机 MinIO 留空即可。
        if (StringUtils.hasText(properties.getRegion())) {
            builder.region(properties.getRegion());
        }
        return builder.build();
    }
}
