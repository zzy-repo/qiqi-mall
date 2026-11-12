package com.qiqi.mall.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SKU 库存桶（一条 = 某个 SKU 的某一份库存）。
 *
 * <h3>为什么会有这张表</h3>
 * 原来扣减只有一条 SQL：{@code UPDATE sku SET stock = stock - N WHERE id = ? AND stock >= N}。
 * 它本身是原子的、不超卖的，但「同一个 SKU 的所有并发」都挤在同一行的行锁上 ——
 * 热点 SKU 的 TPS 上限 = 单行行锁的吞吐，跟机器有几个核没关系。
 * 于是把**同一份库存**物理拆成 N 行（默认 8），并发就分散到 N 把互不相干的行锁上。
 *
 * <h3>谁是权威</h3>
 * <b>这张表是库存的唯一裁决处</b>：扣减 = 对某个桶做
 * {@code UPDATE sku_bucket SET stock = stock - N WHERE sku_id=? AND bucket_no=? AND stock>=N}，
 * 0 行就换下一个桶重试（最多 N 次，全失败才算库存不足）。
 * {@code sku.stock} 降级为**展示缓存**（由 {@code SkuBucketService#refreshSkuStockCache} 按
 * {@code SUM(stock)} 回写），扣减/回补都不写它，也不拿它裁决。
 *
 * <p>表结构见 {@code sql/migrate-sku-bucket.sql}；{@code CHECK (stock >= 0)} 保证
 * 单桶永远非负 ⇒ 任何时刻 {@code SUM(stock) >= 0}，「SUM 为负 / 超卖」在库层面就写不进去。
 */
@Data
@TableName("sku_bucket")
public class SkuBucket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    /** 桶号 0..N-1（N = qiqi.stock.bucket-count） */
    private Integer bucketNo;

    /** 本桶可用库存（权威值；sku.stock 只是它的缓存） */
    private Integer stock;

    private LocalDateTime updatedAt;
}
