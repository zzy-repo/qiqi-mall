package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
}
