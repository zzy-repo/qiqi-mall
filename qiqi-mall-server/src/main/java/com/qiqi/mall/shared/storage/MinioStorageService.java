package com.qiqi.mall.shared.storage;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** 图片上传：校验 → 重写文件名 → PutObject → 返回可直连的 URL */
@Service
public class MinioStorageService {

    /** 白名单 content-type → 扩展名 */
    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    /** 单文件上限 5MB */
    public static final long MAX_SIZE = 5L * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public boolean isAllowed(String contentType) {
        return contentType != null && ALLOWED.containsKey(contentType.toLowerCase());
    }

    /**
     * @param objectName 形如 products/2026/11/uuid.jpg
     * @return 浏览器可直接访问的 URL
     */
    public String upload(String objectName, InputStream stream, long size, String contentType) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectName)
                .stream(stream, size, -1)
                .contentType(contentType)
                .build());
        return properties.getPublicBaseUrl() + "/" + properties.getBucket() + "/" + objectName;
    }

    public String extensionOf(String contentType) {
        return ALLOWED.getOrDefault(contentType == null ? "" : contentType.toLowerCase(), "bin");
    }

    /** 供 controller 返回统一结构 */
    public Map<String, String> result(String url, String objectName) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("objectName", objectName);
        return body;
    }
}
