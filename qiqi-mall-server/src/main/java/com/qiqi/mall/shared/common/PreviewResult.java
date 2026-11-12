package com.qiqi.mall.shared.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高危写操作「预演」（{@code ?dryRun=true}）的统一口径：**只回答「将要发生什么」，不签发任何凭证**。
 *
 * <p>为什么只要一句中文：预演的唯一用途是让人在动手前看清后果（「将把订单 QQ2026… 审核通过：
 * 生成 2 个发货单…」），它不产生任何状态、也不发凭证 —— 所以响应体就是
 * <pre>{ code:0, data:{ willDo:"中文描述" } }</pre>
 *
 * <p>并发控制不由预演承担：14 个 L3 接口里凡是有 {@code version} 的（5 张热表的更新路径），
 * 都靠乐观锁 fail-closed 校验（缺版本号 {@code 400} / 冲突 {@code 409}，见
 * {@link com.qiqi.mall.shared.guard.OptimisticLockGuard}）；其余接口靠各自的状态守卫。
 *
 * <p>预演**会**跑业务守卫：没资格执行的操作，预演阶段就直接回中文原因，
 * 而不是给出一段「将要发生什么」再让人白点一次确认。
 */
public final class PreviewResult {

    /** dryRun 参数判定：只认布尔 true（{@code "false"} 不会被 Spring 绑成 true，这里再做一次防御） */
    public static boolean isDryRun(Boolean dryRun) {
        return Boolean.TRUE.equals(dryRun);
    }

    /** 预演响应体：{@code {willDo: "中文描述"}} —— 只有这一个字段，没有码、没有有效期 */
    public static Map<String, Object> of(String willDo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("willDo", willDo);
        return data;
    }

    private PreviewResult() {
    }
}
