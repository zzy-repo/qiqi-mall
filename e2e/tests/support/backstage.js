/**
 * 「后台幕布」—— 买家侧演示视频里不该出现商家后台画面。
 *
 * 问题（审计 C 项）：买家侧场景需要后台配合造数据（补库存）和推进状态（审核 → 发货单下发 →
 * WMS 出库），这些步骤原来直接在主 page 上点，于是「买家视角」的片子里突然弹出后台订单列表、
 * SKU 编辑弹窗，观众视角很跳。
 *
 * 做法：把这些步骤放到**另一个浏览器上下文**里执行。
 *   · Playwright 的 video 录的是「测试上下文」（playwright.config.js 里的 `use.video`）；
 *     `browser.newContext()` 新建的上下文没有 video 配置 → 里面的点击不会进最终 mp4；
 *   · 步骤一个都没删，仍然是真实 UI 点击（ui.js 的 helper 原样复用），断言也没放宽 ——
 *     变的只是「这些点击画在哪个窗口里」。
 *
 * 用法：
 *   await withBackstage(browser, async (admin) => {
 *     await adminLogin(admin)
 *     await adminSetSkuStock(admin, 1, 0, 50)
 *   })
 *
 * 返回值就是 fn 的返回值（例如要把 admin 令牌带出来做只读断言）。
 */
export async function withBackstage(browser, fn, { viewport = { width: 1280, height: 720 } } = {}) {
  const context = await browser.newContext({ viewport })
  try {
    const page = await context.newPage()
    return await fn(page)
  } finally {
    await context.close()
  }
}
