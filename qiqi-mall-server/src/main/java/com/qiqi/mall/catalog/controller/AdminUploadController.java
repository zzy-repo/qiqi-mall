package com.qiqi.mall.catalog.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.storage.MinioStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 后台图片上传（见 docs/flows/9-图片上传时序图）：
 * 独立于商品提交；校验文件类型白名单 + 大小上限；重写文件名；PutObject 到 MinIO；返回可直连 URL。
 *
 * <p>两个入口、**同一套落地规则**（同一个 bucket / objectKey 前缀 / products/年/月 归档 / 同一个返回结构）：
 * <ul>
 *   <li>{@code POST /admin/upload/image} —— multipart/form-data，供后台页面人工上传；</li>
 *   <li>{@code POST /admin/upload/image-from-url} —— JSON <code>{"url": "https://..."}</code>，
 *       供 AI（MCP）补商品图：模型产不出 5MB 的 base64，也不该把服务器本地路径当上传通道，
 *       所以由后端自己按 URL 去拉（SSRF 防护全在 {@link MinioStorageService#fetchFromUrl}）。</li>
 * </ul>
 */
@Tag(name = "管理端-图片上传", description = "商品图与相册图上传（MinIO），限 5MB、仅图片类型；支持文件上传与按 URL 拉取")
@RestController
@RequestMapping("/admin/upload")
public class AdminUploadController {

    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM");

    private final MinioStorageService storage;

    public AdminUploadController(MinioStorageService storage) {
        this.storage = storage;
    }

    @Operation(summary = "上传图片到对象存储，返回可直接访问的地址")
    @PostMapping("/image")
    public ResponseEntity<Result<java.util.Map<String, String>>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Result.fail("请选择要上传的图片"));
        }
        if (file.getSize() > MinioStorageService.MAX_SIZE) {
            // 413 语义：内容过大
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Result.fail("图片不能超过 5MB"));
        }
        if (!storage.isAllowed(file.getContentType())) {
            // 415 语义：媒体类型不支持
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Result.fail("仅支持 jpg / png / webp"));
        }

        String ext = storage.extensionOf(file.getContentType());
        String objectName = String.format("products/%s/%s/%s.%s",
                LocalDate.now().format(YEAR), LocalDate.now().format(MONTH), UUID.randomUUID(), ext);

        try (InputStream in = file.getInputStream()) {
            String url = storage.upload(objectName, in, file.getSize(), file.getContentType());
            return ResponseEntity.ok(Result.ok(storage.result(url, objectName)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.fail("上传失败：" + e.getMessage()));
        }
    }

    /**
     * 按 URL 拉取图片并落到对象存储（AI / MCP 用，人类 JWT 也能用）。
     *
     * <p>与 {@link #uploadImage} 的区别只有「图片从哪来」：一个来自 multipart 文件流，一个由后端按 URL 下载；
     * 之后的校验（白名单 / 5MB）、归档路径（products/yyyy/MM/uuid.ext）、返回值（{url, objectName}）完全一致。
     *
     * <p>失败一律是**明确的中文原因 + 对应 HTTP 状态码**（400 参数/目标站问题、403 内网地址、
     * 413 超 5MB、415 不是图片、502/504 网络与超时），绝不回 500 —— MCP 侧会把 message 原样透给模型。
     */
    @Operation(summary = "按 URL 拉取图片并上传（仅 https、单图 ≤5MB、禁内网与重定向）")
    @PostMapping("/image-from-url")
    public ResponseEntity<Result<Map<String, String>>> uploadImageFromUrl(
            @RequestBody(required = false) Map<String, String> body) {
        String rawUrl = body == null ? null : body.get("url");
        if (rawUrl == null || rawUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Result.fail("请提供图片地址：请求体形如 {\"url\":\"https://example.com/a.png\"}"));
        }

        MinioStorageService.FetchedImage image;
        try {
            image = storage.fetchFromUrl(rawUrl);
        } catch (MinioStorageService.ImageFetchRejected e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            if (status == null) status = HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Result.fail(e.getMessage()));
        }

        // 落地规则与 /admin/upload/image 完全一致（扩展名按**嗅探出的真实类型**取，不看服务端 Content-Type）
        String ext = storage.extensionOf(image.contentType());
        String objectName = String.format("products/%s/%s/%s.%s",
                LocalDate.now().format(YEAR), LocalDate.now().format(MONTH), UUID.randomUUID(), ext);

        try {
            String url = storage.upload(objectName, new ByteArrayInputStream(image.data()), image.size(), image.contentType());
            return ResponseEntity.ok(Result.ok(storage.result(url, objectName)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.fail("上传失败：" + e.getMessage()));
        }
    }
}
