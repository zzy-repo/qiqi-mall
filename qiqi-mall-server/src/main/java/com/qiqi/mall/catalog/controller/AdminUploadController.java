package com.qiqi.mall.catalog.controller;

import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.storage.MinioStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 后台图片上传（见 docs/flows/9-图片上传时序图）：
 * 独立于商品提交；校验文件类型白名单 + 大小上限；重写文件名；PutObject 到 MinIO；返回可直连 URL。
 */
@Tag(name = "管理端-图片上传", description = "商品图与相册图上传（MinIO），限 5MB、仅图片类型")
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
}
