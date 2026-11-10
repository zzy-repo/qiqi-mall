package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("product_gallery")
public class ProductGallery {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    /** 图片URL（指向 MinIO） */
    private String url;

    /** 展示顺序 */
    private Integer sort;
}
