package com.qiqi.mall.stock.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存对账页里「最近 N 条库存流水」的一行（对应 {@code stock_ledger} 的一行）。
 *
 * <p>字段是前端契约：{@code id, reason, delta, bucketNo, refType, refId, createdAt, idemKey}。
 * 幂等键也返回：对账时「为什么这单只补了一次」的答案就在 {@code idem_key} 里
 * （{@code orderId:skuId:reason:refId} 唯一，重放一律跳过）。
 */
@Data
public class InventoryLedgerRow {

    private Long id;

    /** 扣补原因：ORDER_CREATE / AUDIT_REJECT / DELIVERY_CANCEL / RETURN_REFUND */
    private String reason;

    /** 变化量：负数=扣减，正数=回补 */
    private Integer delta;

    /** 落点桶号（历史台账为空） */
    private Integer bucketNo;

    /** 触发单据类型：DELIVERY / RETURN_APPLY（下单为空） */
    private String refType;

    /** 触发单据 id */
    private Long refId;

    /** 记账时间（ISO-8601，页面自己格式化） */
    private LocalDateTime createdAt;

    /** 幂等键（唯一索引 uk_idem） */
    private String idemKey;
}
