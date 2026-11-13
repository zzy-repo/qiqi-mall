package com.qiqi.mall.shared.ledger.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 台账查询的行 DTO（前端「台账查询」页 {@code admin-web/src/views/ledger/index.vue} 直接吃这个结构）。
 *
 * <h3>字段是逐字契约（前端已定稿，别改名/别少字段）</h3>
 * <pre>
 * id, type(ORDER_STATUS|DELIVERY_STATUS|RETURN_STATUS|AUDIT|WMS|STOCK|PAYMENT),
 * bizId, bizSn, fromStatus, toStatus, reason, reasonDetail,
 * operatorType(ADMIN|MEMBER|APIKEY|SYSTEM), operatorId, operatorName,
 * refType, refId, summary, createdAt
 * </pre>
 *
 * <h3>每个 type 的字段来源（同一列在不同分支下的含义，前端只看列名不看来源）</h3>
 * <table>
 *   <tr><th>type</th><th>来源</th><th>bizId / bizSn</th><th>from/to</th><th>reason / reasonDetail</th></tr>
 *   <tr><td>ORDER_STATUS</td><td>v_biz_status_timeline (ORDER)</td><td>订单 id / order_sn</td>
 *       <td>0..9 状态码</td><td>流转原因码 / reason_detail</td></tr>
 *   <tr><td>DELIVERY_STATUS</td><td>v_biz_status_timeline (DELIVERY)</td><td>包裹 id / delivery_sn 快照</td>
 *       <td>0..5,9</td><td>同上</td></tr>
 *   <tr><td>RETURN_STATUS</td><td>v_biz_status_timeline (RETURN)</td><td>售后 id / 所属订单号</td>
 *       <td>0..2</td><td>同上</td></tr>
 *   <tr><td>AUDIT</td><td>admin_audit_log</td><td>target_id / target_sn</td><td>null</td>
 *       <td>动作码 action / 管理员填的原因</td></tr>
 *   <tr><td>WMS</td><td>wms_exchange_ledger</td><td>包裹 id / delivery_sn</td><td>null</td>
 *       <td>REQ|ACK / 回执结果</td></tr>
 *   <tr><td>STOCK</td><td>stock_ledger</td><td>订单 id / order_sn</td><td>null</td>
 *       <td>扣补原因 / 「落点桶 N」</td></tr>
 *   <tr><td>PAYMENT</td><td>payment_record</td><td>订单 id / order_sn（对账键，删单后仍在）</td><td>null</td>
 *       <td>「支付入账 / 退款入账」 / 渠道流水号 trade_no</td></tr>
 * </table>
 *
 * <h3>两个「如实返回、不做推断」的地方</h3>
 * <ul>
 *   <li>{@code fromStatus}/{@code toStatus} 只对三个状态类型有值；审计 / WMS / 库存 / 支付四类台账
 *       本来就不是状态机（没有「从哪到哪」），这里给 {@code null} 而不是硬编一个 0 —— 前端对
 *       null 显示 {@code —}。</li>
 *   <li>{@code id} 只在**同一张台账内**唯一（七支合并后可能重复）。前端只把它当行 key。</li>
 * </ul>
 *
 * <p>时间字段统一按 ISO-8601 返回（{@code LocalDateTime} 的默认序列化，形如
 * {@code 2026-11-13T16:10:12.298}）；页面自己格式化（{@code replace('T',' ').slice(0,19)}）。
 * 入参 {@code from}/{@code to} 反过来按 {@code yyyy-MM-dd HH:mm:ss} 解析 —— 见
 * {@code LedgerQueryService}。
 */
@Data
public class LedgerRow {

    /** 台账行主键（各台账自己的 id，不保证跨类型唯一） */
    private Long id;

    /** 台账类型：ORDER_STATUS / DELIVERY_STATUS / RETURN_STATUS / AUDIT / WMS / STOCK / PAYMENT */
    private String type;

    /** 业务对象 id（含义随 type：订单 id / 包裹 id / 售后 id / target_id / delivery_id / order_id；PAYMENT 为订单 id，历史行可能 null） */
    private Long bizId;

    /** 业务单号快照（订单号 / 包裹号 / 目标单号；对不上的时候为 null，前端显示 —） */
    private String bizSn;

    /** 变更前状态码（仅三个状态类型有值） */
    private Integer fromStatus;

    /** 变更后状态码（仅三个状态类型有值） */
    private Integer toStatus;

    /** 流转原因码 / 审计动作码 / WMS 方向 / 库存扣补原因 / 支付流水的「支付入账 | 退款入账」 */
    private String reason;

    /** 原因补充：状态台账的 reason_detail、审计里管理员填的原因、WMS 回执结果、库存的落点桶、支付流水的渠道流水号 */
    private String reasonDetail;

    /** 操作人类型：ADMIN / MEMBER / APIKEY / SYSTEM */
    private String operatorType;

    /** 操作人 id（系统事件可空） */
    private Long operatorId;

    /** 操作人名快照（登录名 / 密钥名 / 会员昵称） */
    private String operatorName;

    /** 关联单据类型：DELIVERY / RETURN_APPLY / ORDER / PACKAGE … */
    private String refType;

    /** 关联单据 id */
    private Long refId;

    /** 一句人话摘要（列表页直接展示；状态类型由视图的中文状态名拼出） */
    private String summary;

    /** 记账时间（ISO-8601，带毫秒；页面自己格式化） */
    private LocalDateTime createdAt;
}
