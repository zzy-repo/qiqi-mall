package com.qiqi.mall.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("member")
public class Member {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名-全局唯一 */
    private String username;

    /** BCrypt（只写不读：接口不会把密码返回给前端） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String nickname;

    private String phone;

    /** 1启用 0禁用 */
    private Integer status;

    private LocalDateTime createTime;

    /** 客户标签（新客/老客/黑名单…）：CUSTOMER_TAG 字段来源 */
    private String customerTag;

    /**
     * 乐观锁版本号（MyBatis-Plus @Version）。
     * updateById 时会生成 {@code SET version = version + 1 WHERE id = ? AND version = ?}；
     * 影响行数 0 = 期间被别人改过 → 接口回 code=409，不静默覆盖。
     */
    @Version
    @TableField("version")
    private Integer version;
}
