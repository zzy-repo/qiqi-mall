package com.qiqi.mall.apikey.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 接口访问密钥。
 *
 * 安全约定：明文 key 只在「新建」那一刻返回给调用方，库里只留 sha256（{@link #keyHash}）
 * 与「明文前 8 位」（{@link #keyPrefix}，列表展示用）。因此 keyHash 永远不出现在接口响应里
 * （@JsonIgnore 双保险：即使哪天有人直接把实体丢给前端也漏不出去）。
 */
@Data
@TableName("ai_api_key")
public class AiApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用途名称，如 运营助手-只读 */
    private String name;

    /** 明文前 8 位，列表展示用（如 ak_1a2b3） */
    private String keyPrefix;

    /** sha256(明文) 的十六进制，唯一键；用于每次请求反查 */
    @JsonIgnore
    private String keyHash;

    /** 逗号分隔的权限集合，如 order:read,catalog:read */
    private String scopes;

    /** NULL = 永不过期 */
    private LocalDateTime expiresAt;

    /** 1 启用 / 0 禁用 */
    private Integer status;

    private LocalDateTime lastUsedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
