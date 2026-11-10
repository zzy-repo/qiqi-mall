package com.qiqi.mall.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("member_address")
public class MemberAddress {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** 收件人 */
    private String name;

    private String phone;

    private String province;

    private String city;

    private String region;

    /** 详细地址 */
    private String detail;

    /** 是否默认地址:1是 0否 */
    private Integer defaultStatus;
}
