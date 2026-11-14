import { test, expect } from '../../../support/test.js'
import { PORTAL, stamp } from '../../../support/env.js'
import {
  adminLogin,
  adminSetSkuStock,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder
} from '../../../support/ui.js'
import { memberOrderRow } from '../../../support/ui.js'
import { getProductApi, getMemberOrdersApi, memberTokenOf } from '../../../support/assert.js'
import { tid, tidPrefix } from '../../../support/tid.js'
import { addressOf, memberNickOf, receiverOf } from '../../../support/fixtures.js'
import { demoName, demoUsername } from '../../../support/images.js'
import { withBackstage } from '../../../support/backstage.js'


/**
 * 业务 / 买家侧 / 结算下单与支付（docs/flows/02-业务场景/业务/买家侧/结算下单与支付）
 *
 * 覆盖：结算页清单与合计 / 填收货信息下单 / 库存扣减 / 自动模拟支付 → 待审核(7) /
 *       必填校验（收货人、地址）/ 库存不足整单失败且不产生订单 / 已支付订单不再出现「模拟支付」入口
 * 断言依据：PortalOrderController.create（幂等令牌 + 库存原子扣减）、pay（守卫 status=0）
 */

const checkoutForm = (page) => ({
  name: tid(page, 'checkout-input-receiver'),
  phone: tid(page, 'checkout-input-phone'),
  address: tid(page, 'checkout-input-address')
})

test.describe('业务 › 买家侧 › 结算下单与支付', () => {
  test('结算页信息一致 → 下单 → 自动支付 → 订单待审核 + 库存扣减', async ({ page, request, browser }) => {
    const username = demoUsername('张小雨') || `e2e_order_${stamp()}`
    const buyer = addressOf(username)            // 收货人/地址会显示在订单里，用真实姓名与地址
    // 补库存是后台数据准备，放到「幕布」上下文里点（不进买家视角的录像，步骤与断言都不变）；
    // 它和买家注册/加购**并行**，买家页面就不必空转等后台。
    const prep = withBackstage(browser, async (admin) => {
      await adminLogin(admin)
      await adminSetSkuStock(admin, 1, 0, 50)
    })
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    await prep                                   // 库存补好之后再读，断言用的就是补完的真实库存
    const sku = (await getProductApi(request, 1)).skus[0]
    await page.goto(`${PORTAL}/cart`)
    await page.waitForTimeout(800)
    await tid(page, 'cart-checkout').click()
    await page.waitForURL(/\/checkout/, { timeout: 15_000 })
    await page.waitForTimeout(1000)

    // 结算页清单 = 购物车条目，合计 = 单价 × 数量
    await expect(tidPrefix(page, 'checkout-item-row-'), '结算清单应有 1 行').toHaveCount(1)
    await expect(page.locator('.block').last()).toContainText(Number(sku.price).toFixed(2))

    const form = checkoutForm(page)
    await form.name.fill(buyer.name)
    await form.phone.fill(buyer.phone)
    await form.address.fill(`${buyer.province}${buyer.city}${buyer.region}${buyer.detail}`)
    await tid(page, 'checkout-submit').click()

    await expect(page, '下单后应进入我的订单并自动支付').toHaveURL(/\/orders/, { timeout: 25_000 })
    await page.waitForTimeout(2000)
    const row = page.locator('.el-table__body tr').first()
    await expect(row, '支付成功后订单应进入待审核').toContainText('待审核')

    // 服务端复核：订单 + 明细快照 + 库存扣减
    const token = await memberTokenOf(page)
    const pageData = await getMemberOrdersApi(request, token)
    expect(pageData.records.length, '应只产生 1 个订单').toBe(1)
    const order = pageData.records[0]
    expect(order.status, '订单状态应为待审核(7)').toBe(7)
    expect(order.receiverName).toBe(buyer.name)
    expect(Number(order.totalAmount)).toBe(Number(sku.price))
    expect(order.paymentTime, '应已落支付时间').toBeTruthy()

    // sku.stock 是展示缓存（分桶模式下由后台任务按 SUM(桶) 每 250ms 回写），所以轮询等它收敛；
    // legacy 模式下第一次就命中，等价于原来的同步断言（没有放宽，只是不再跟缓存赛跑）
    await expect.poll(async () => (await getProductApi(request, 1)).skus.find((s) => s.id === sku.id).stock,
      { timeout: 2_000, intervals: [50, 100, 200, 400], message: '下单应扣 1 件库存' }
    ).toBe(sku.stock - 1)
  })

  test('必填校验：不填收货人/地址不让下单', async ({ page, request }) => {
    const username = demoUsername('李思远') || `e2e_order_required_${stamp()}`
    const buyer = addressOf(username)
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    await page.goto(`${PORTAL}/cart`)
    await page.waitForTimeout(800)
    await tid(page, 'cart-checkout').click()
    await page.waitForURL(/\/checkout/, { timeout: 15_000 })
    await page.waitForTimeout(800)

    await tid(page, 'checkout-submit').click()
    await page.waitForTimeout(1200)
    await expect(page.locator('.el-message--warning').first(), '缺收货信息应提示').toContainText('请填写收货人和收货地址')
    expect(page.url(), '校验不通过应停在结算页').toContain('/checkout')

    // 只填收货人、不填地址 → 仍然拦住
    await checkoutForm(page).name.fill(buyer.name)
    await tid(page, 'checkout-submit').click()
    await page.waitForTimeout(1000)
    await expect(page.locator('.el-message--warning').first()).toContainText('请填写收货人和收货地址')

    // 服务端：一个订单都没产生
    const token = await memberTokenOf(page)
    const pageData = await getMemberOrdersApi(request, token)
    expect(pageData.records.length, '校验不通过不应产生订单').toBe(0)
  })

  test('库存不足：整单失败、不产生订单、库存不变', async ({ page, request, browser }) => {
    const username = demoUsername('陈可') || `e2e_order_oos_${stamp()}`
    const buyer = addressOf(username)
    // 把商品1 第一个 SKU 库存改小到 1（真实 UI，在幕布上下文里点，买家视角的片子里不出现后台）；
    // 与买家注册并行，买家页面不用干等。
    const prep = withBackstage(browser, async (admin) => {
      await adminLogin(admin)
      await adminSetSkuStock(admin, 1, 0, 1)
    })

    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    await prep
    const { skus } = await getProductApi(request, 1)
    const sku = skus[0]
    expect(sku.stock, '前置：库存应为 1').toBe(1)

    // 购物车放 2 件（购物车层不校验库存）
    await addSpecToCart(page, 1, sku.specText)
    const qtyInput = tid(page, 'cart-row-qty').first().locator('input')
    await qtyInput.fill('2')
    await qtyInput.press('Tab')
    await page.waitForTimeout(1000)

    await tid(page, 'cart-checkout').click()
    await page.waitForURL(/\/checkout/, { timeout: 15_000 })
    await page.waitForTimeout(800)
    const form = checkoutForm(page)
    await form.name.fill(buyer.name)
    await form.address.fill(`${buyer.province}${buyer.city}${buyer.region}${buyer.detail}`)
    await tid(page, 'checkout-submit').click()
    await page.waitForTimeout(2500)

    await expect(page.locator('.el-message--error').first(), '库存不足应报错').toBeVisible({ timeout: 20_000 })
    expect(page.url(), '失败应停在结算页').toContain('/checkout')

    const token = await memberTokenOf(page)
    expect((await getMemberOrdersApi(request, token)).records.length, '库存不足不应产生订单').toBe(0)
    expect((await getProductApi(request, 1)).skus.find((s) => s.id === sku.id).stock,
      '失败后库存应保持不变').toBe(1)

    // 还原库存，避免影响后续用例（同样走幕布，别在买家片子尾巴上留一段后台画面）
    await withBackstage(browser, async (admin) => {
      await adminLogin(admin)
      await adminSetSkuStock(admin, 1, 0, 50)
    })
  })

  test('已支付订单不再显示「模拟支付」入口（支付守卫）', async ({ page, request }) => {
    const username = demoUsername('王建国') || `e2e_order_paid_${stamp()}`
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    // 收货人走 fixtures 的自然姓名/地址（submitCartOrder 的默认值是「E2E买家」，会显示在订单里）
    const orderSn = await submitCartOrder(page, receiverOf(username))

    const row = await memberOrderRow(page, orderSn)
    await expect(row, '已支付订单应为待审核').toContainText('待审核')
    await expect(row.getByTestId('orders-row-pay'), '待审核订单不应再有支付入口').toHaveCount(0)
    // 对照：待付款(0)的订单才会有支付按钮（订单行「模拟支付」按 status===0 渲染）
    expect((await getMemberOrdersApi(request, await memberTokenOf(page))).records[0].status).toBe(7)
  })
})
