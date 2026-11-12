package com.qiqi.mall.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置（app.minio.*）。
 *
 * 名字历史遗留：底层客户端是 MinIO Java SDK，走 S3 兼容协议，所以同一套配置既能连本机
 * MinIO（dev profile），也能连阿里云 OSS 的 S3 兼容端点（test profile）。
 * 两套环境的差异全部通过下面这些字段表达，代码不分支。
 */
@Component
@ConfigurationProperties(prefix = "app.minio")
public class MinioProperties {

    /** 访问地址，如 http://minio:9000（dev）；https://s3.oss-cn-beijing.aliyuncs.com（test/OSS） */
    private String endpoint = "http://127.0.0.1:9000";

    /**
     * S3 region。本机 MinIO 不需要（留空 = 不传）；
     * 阿里云 OSS 必须给（如 cn-beijing），否则 SDK 会退回 path-style 请求，
     * OSS 直接报 "Please use virtual hosted style to access"（已实测）。
     */
    private String region = "";

    /** 浏览器可访问的地址前缀（写进 URL 给前端用），如 http://127.0.0.1:9000；留空 = 返回相对路径 */
    private String publicBaseUrl = "http://127.0.0.1:9000";

    private String accessKey = "qiqiadmin";
    private String secretKey = "qiqi123456";

    /** SDK 侧 bucket 名（dev: qiqi-mall；test: qiqi-mall-static） */
    private String bucket = "qiqi-mall";

    /**
     * 对象键前缀（bucket 内目录），如 qiqi-mall/。
     * 阿里云 OSS 的 bucket 里历史图都在 qiqi-mall/ 下面，上传也必须落到同一前缀，
     * 否则 CDN 上新旧地址形式不一致。留空 = 不前缀（dev 的 MinIO 就是直接放 bucket 根）。
     */
    private String objectKeyPrefix = "";

    /**
     * 返回给前端的 URL 路径前缀，留空则用 bucket 名。
     * dev：bucket=qiqi-mall → /qiqi-mall/**；
     * test：bucket=qiqi-mall-static，但库里历史数据一律是 /qiqi-mall/**，
     *       所以这里显式写 qiqi-mall，保证新旧地址形式完全一致。
     */
    private String publicPathPrefix = "";

    /**
     * 启动时是否由应用自己确保 bucket 存在并设置「公开读」策略。
     * 本机 MinIO = true；阿里云 OSS = false（bucket 由运维创建、ACL private 必须保持，
     * 应用不许去改它的 ACL/策略）。
     */
    private boolean manageBucket = true;

    /**
     * 是否要求 accessKey/secretKey 必须非空（缺失则启动即失败）。
     * test profile 开 true：少了凭证要立刻炸，不能静默降级成连不上/连错存储。
     */
    private boolean requireCredentials = false;

    /** SDK 里真正写入的对象键：objectKeyPrefix + objectName */
    public String objectKey(String objectName) {
        String prefix = objectKeyPrefix == null ? "" : objectKeyPrefix.trim();
        if (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + objectName;
    }

    /** URL 里的路径前缀：留空回落到 bucket 名 */
    public String pathPrefix() {
        String prefix = publicPathPrefix == null ? "" : publicPathPrefix.trim();
        if (prefix.isEmpty()) {
            prefix = bucket;
        }
        if (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        return prefix;
    }

    /**
     * 返回给前端的图片地址。
     * publicBaseUrl 留空 ⇒ 相对路径 /qiqi-mall/products/2026/11/uuid.png
     * （库里存相对路径，由前端 nginx / vite 反代到 CDN 或 MinIO）。
     */
    public String publicUrl(String objectName) {
        return publicBaseUrl + "/" + pathPrefix() + "/" + objectName;
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getObjectKeyPrefix() { return objectKeyPrefix; }
    public void setObjectKeyPrefix(String objectKeyPrefix) { this.objectKeyPrefix = objectKeyPrefix; }
    public String getPublicPathPrefix() { return publicPathPrefix; }
    public void setPublicPathPrefix(String publicPathPrefix) { this.publicPathPrefix = publicPathPrefix; }
    public boolean isManageBucket() { return manageBucket; }
    public void setManageBucket(boolean manageBucket) { this.manageBucket = manageBucket; }
    public boolean isRequireCredentials() { return requireCredentials; }
    public void setRequireCredentials(boolean requireCredentials) { this.requireCredentials = requireCredentials; }
}
