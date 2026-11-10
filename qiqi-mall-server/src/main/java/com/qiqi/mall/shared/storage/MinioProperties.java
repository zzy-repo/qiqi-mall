package com.qiqi.mall.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MinIO 配置（app.minio.*） */
@Component
@ConfigurationProperties(prefix = "app.minio")
public class MinioProperties {

    /** 容器内访问地址，如 http://minio:9000 */
    private String endpoint = "http://127.0.0.1:9000";

    /** 浏览器可访问的地址前缀（写进 URL 给前端用），如 http://127.0.0.1:9000 */
    private String publicBaseUrl = "http://127.0.0.1:9000";

    private String accessKey = "qiqiadmin";
    private String secretKey = "qiqi123456";
    private String bucket = "qiqi-mall";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
}
