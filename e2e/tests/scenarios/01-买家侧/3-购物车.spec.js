import { test, expect } from '../../support/test.js'
import { PORTAL, API, stamp } from '../../support/env.js'
import { addSpecToCart, adminLogin, adminSetSkuStock, memberRegisterAndLogin } from '../../support/ui.js'
import { openHome, searchProduct, productCards } from '../../support/portal-ui.js'
import { getProductApi, getMemberCartApi, memberTokenOf } from '../../support/assert.js'
import { tid, tidNth } from '../../support/tid.js'
import { addressOf, memberNickOf } from '../../support/fixtures.js'
import { demoUsername } from '../../support/images.js'
import { withBackstage } from '../../support/backstage.js'


/**
 * 买家侧 03 购物车（docs/flows/02-业务场景/01-买家侧/3-购物车）
 *
 * 覆盖：加购同 SKU 累加 / 改数量 / 删除 / 合计跟随 / 数量下限守卫 /
 *       超库存（购物车层放行、下单才失败）/ 匿名加购后登录合并（本地清空）/
 *       未登录点结算被拦到登录页
 *
 * 说明：购物车页面**没有**「勾选/取消勾选」列（cart/index.vue 表格列：商品/规格/单价/数量/小计/操作），
 * 全选结算的行为不存在，因此该覆盖点无实现可测 → 已在报告里说明。
 */

/** 表格行由 el-table 渲染，没有行锚点 → 在 cart-list-table 里按 tr 定位（tid.js 未覆盖第 4 条） */
const cartRows = (page) => tid(page, 'cart-list-table').locator('.el-table__body tr')
const cartTotal = (page) => tid(page, 'cart-total').locator('b')
/** 第 index 行的数量输入框（el-input-number，testid 落在容器上） */
const cartQtyInput = (page, index = 0) => tidNth(page, 'cart-row-qty', index).locator('input')

test.describe('买家侧 03 购物车', () => {
  test('加购同 SKU 数量累加，改数量与删除后合计跟随', async ({ page, request }) => {
    const username = demoUsername('七七买家') || `e2e_cart_${stamp()}`
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const { skus } = await getProductApi(request, 1)
    const spec = skus[0].specText
    const price = Number(skus[0].price)

    // 首次加购
    await addSpecToCart(page, 1, spec)
    await expect(cartRows(page), '购物车应有 1 行').toHaveCount(1)
    await expect(cartRows(page).first()).toContainText('1')

    // 再次加同一 SKU → 累加为 2（不新增行）
    await addSpecToCart(page, 1, spec)
    await expect(cartRows(page), '同一 SKU 应累加而不是新增行').toHaveCount(1)
    const token = await memberTokenOf(page)
    let cart = await getMemberCartApi(request, token)
    expect(cart.length).toBe(1)
    expect(cart[0].quantity, '数量应累加为 2').toBe(2)
    await expect(cartTotal(page)).toContainText((price * 2).toFixed(2))

    // 改数量 2 → 3
    const qtyInput = cartQtyInput(page)
    await qtyInput.fill('3')
    await qtyInput.press('Tab')
    await page.waitForTimeout(1000)
    cart = await getMemberCartApi(request, token)
    expect(cart[0].quantity, '改数量应落库').toBe(3)
    await expect(cartTotal(page)).toContainText((price * 3).toFixed(2))

    // 删除 → 清空。
    // 注意顶栏角标只在路由变化/整页重载时重算（layout 里 watch(route.fullPath) + onMounted），
    // 删完车里的件数变了、角标还停在旧数字，所以「空车 + 角标 2」会短暂同框。
    // 演示片里不留这种画面：点完只等服务端确认删掉，马上重进一次购物车（真实用户动作），
    // 让列表和角标一起按新状态重算，再断言「空车」。
    await tidNth(page, 'cart-row-remove', 0).click()
    await expect
      .poll(async () => (await getMemberCartApi(request, token)).length, { message: '服务端应清空购物车' })
      .toBe(0)
    await page.goto(`${PORTAL}/cart`)
    await page.waitForTimeout(800)
    await expect(cartRows(page), '删除后购物车应为空').toHaveCount(0)
  })

  test('数量边界：不能减到 0 或负数；超库存购物车放行、下单才失败', async ({ page, request, browser }) => {
    const username = demoUsername('李思远') || `e2e_cart_edge_${stamp()}`
    const buyer = addressOf(username)      // 收货人/地址会显示在结算页，用真实姓名与地址
    const { skus } = await getProductApi(request, 1)     // 规格文本/价格先拿到（库存稍后再读）
    // 库存先压到 2：种子库存是 999，超库存演示就得放 1000 件、合计九十九万，压测数值外露。
    // 后台改库存走「幕布」上下文（不进买家视角的录像），且**与买家注册/加购并行**，
    // 免得买家页面空转十几秒 —— 真实点击一个不少，只是画在了另一个窗口里。
    const prep = withBackstage(browser, async (admin) => {
      await adminLogin(admin)
      await adminSetSkuStock(admin, 1, 0, 2)
    })
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const token = await memberTokenOf(page)

    await addSpecToCart(page, 1, skus[0].specText)
    const qtyInput = cartQtyInput(page)

    // 点「减号」到下限 → 停在 1
    for (let i = 0; i < 3; i++) {
      await tidNth(page, 'cart-row-qty', 0).locator('.el-input-number__decrease').click()
      await page.waitForTimeout(200)
    }
    expect(await qtyInput.inputValue(), '数量下限是 1，不能被减到 0').toBe('1')

    // 负数手工输入 → 也回到 1
    await qtyInput.fill('-5')
    await qtyInput.press('Tab')
    await page.waitForTimeout(800)
    expect(await qtyInput.inputValue(), '负数应被纠正为 1').toBe('1')

    // 超库存：购物车允许（后端只保证 >=1），下单时才被库存挡住
    await prep                                    // 幕布那边的库存已压到 2
    const stock = (await getProductApi(request, 1)).skus[0].stock
    expect(stock, '前置：库存已被压到 2').toBe(2)
    const overStock = stock + 1
    await qtyInput.fill(String(overStock))
    await qtyInput.press('Tab')
    await page.waitForTimeout(1000)
    let cart = await getMemberCartApi(request, token)
    expect(cart[0].quantity, '购物车层允许超过库存的数量（现状）').toBe(overStock)

    // 下单时失败，且不产生订单
    await page.goto(`${PORTAL}/cart`)
    await page.waitForTimeout(800)
    await tid(page, 'cart-checkout').click()
    await page.waitForURL(/\/checkout/, { timeout: 15_000 })
    await page.waitForTimeout(800)
    await tid(page, 'checkout-input-receiver').fill(buyer.name)
    await tid(page, 'checkout-input-address').fill(`${buyer.province}${buyer.city}${buyer.region}${buyer.detail}`)
    await tid(page, 'checkout-submit').click()
    await page.waitForTimeout(2000)
    await expect(page.locator('.el-message--error').first(), '超库存下单应报错').toBeVisible({ timeout: 15_000 })
    expect(page.url(), '下单失败应停留在结算页').toContain('/checkout')

    // 购物车仍保留（未下单成功）
    cart = await getMemberCartApi(request, token)
    expect(cart.length, '下单失败后购物车条目仍在').toBe(1)

    // 库存还原（幕布上下文），别把后面用例/片子里的库存留在 2
    await withBackstage(browser, async (admin) => {
      await adminLogin(admin)
      await adminSetSkuStock(admin, 1, 0, 50)
    })
  })

  test('匿名加购 → 登录后合并到云端，本地购物车清空', async ({ page, request }) => {
    const username = demoUsername('张小雨') || `e2e_cart_merge_${stamp()}`
    // 先注册出账号，但先不登录（后面从登录页登录）
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    await tid(page, 'header-logout').click()
    await page.waitForTimeout(800)

    // 匿名加购（存本地）
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    await expect(tid(page, 'cart-local-hint'), '未登录时购物车应标注本地暂存').toContainText('未登录')
    const localBefore = await page.evaluate(() => localStorage.getItem('qiqi_local_cart'))
    expect(localBefore, '匿名加购应写入本地购物车').toBeTruthy()

    // 登录 → 合并
    await page.goto(`${PORTAL}/login`)
    await tid(page, 'auth-input-username').fill(username)
    await tid(page, 'auth-input-password').fill('123456')
    await tid(page, 'auth-submit').click()
    await page.waitForTimeout(2500)

    expect(await page.evaluate(() => localStorage.getItem('qiqi_local_cart')), '合并后本地购物车应清空').toBeNull()
    await page.goto(`${PORTAL}/cart`)
    await page.waitForTimeout(1000)
    await expect(cartRows(page), '登录后购物车应有合并进来的 1 行').toHaveCount(1)
    const token = await memberTokenOf(page)
    const cart = await getMemberCartApi(request, token)
    expect(cart.length, '云端购物车应有合并进来的条目').toBe(1)
    expect(cart[0].quantity).toBe(1)
  })

  test('未登录点「去结算」：提示并跳到登录页（带 redirect）', async ({ page }) => {
    // 保证未登录
    await page.goto(`${PORTAL}/`)
    await page.evaluate(() => localStorage.removeItem('qiqi_member_token'))
    const { skus } = await getProductApi(page.request, 1)
    await addSpecToCart(page, 1, skus[0].specText)

    await tid(page, 'cart-checkout').click()
    await page.waitForTimeout(1000)
    await expect(page, '未登录结算应跳到登录页并记住 redirect').toHaveURL(/\/login\?redirect=(%2F|\/)checkout/)
  })
})
