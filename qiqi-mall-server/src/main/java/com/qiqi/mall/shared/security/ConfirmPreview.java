package com.qiqi.mall.shared.security;

/**
 * 一次「预演」的结果：人话描述 + 与 {@link ConfirmCodeService#hashIntent} 完全一致的指纹组成。
 *
 * <p>为什么把两者绑在一起返回：willDo 和 extras 必须由**同一份数据**算出来 ——
 * 如果分成两次查询，中间数据被别人改了，就会出现「文案说的是 A、指纹算的是 B」。
 * 各 service 的 previewXxx() 用同一套守卫和同一份查询一起产出来，controller 只管转发。
 *
 * @param willDo 给人看的具体描述（要带单号/名称/数量，不要写「将删除商品」这种空话）
 * @param extras 指纹组成：凡是「会被别人改动、或者决定执行结果」的量
 */
public record ConfirmPreview(String willDo, Object... extras) {
}
