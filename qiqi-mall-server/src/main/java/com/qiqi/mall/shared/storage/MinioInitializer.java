package com.qiqi.mall.shared.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 bucket 存在，并设置「公开读」策略。
 * 公开读是为了让浏览器 <img src> 能直连 MinIO 拉图（后端不经手图片流量）。
 *
 * 仅当 app.minio.manage-bucket=true 时才动 bucket。test profile 指向阿里云 OSS 的
 * qiqi-mall-static（运维创建、ACL private），此时必须 false：应用不去 makeBucket、
 * 更不去改它的 bucket policy/ACL（那会把私有 bucket 改成公开读）。
 */
@Component
public class MinioInitializer {

    private static final Logger log = LoggerFactory.getLogger(MinioInitializer.class);

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioInitializer(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        String bucket = properties.getBucket();
        if (!properties.isManageBucket()) {
            // 外部托管 bucket（阿里云 OSS）：只读使用，不创建、不设策略、不动 ACL
            log.info("对象存储 bucket 由外部托管（manage-bucket=false），跳过初始化且不改 ACL: {} @ {}",
                    bucket, properties.getEndpoint());
            return;
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO bucket: {}", bucket);
            }
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(bucket)
                    .config(publicReadPolicy(bucket))
                    .build());
            log.info("MinIO bucket 就绪（公开读）: {}", bucket);
        } catch (Exception e) {
            // MinIO 不可用不阻塞服务启动，上传接口会单独报错
            log.warn("MinIO 初始化失败（图片上传将不可用）: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            log.debug("MinIO 初始化异常详情", e);
        }
    }

    private String publicReadPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": ["*"]},
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucket);
    }
}
