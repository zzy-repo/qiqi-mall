/**
 * 买家侧（portal-web）真实 UI 操作 —— 按业务域分组，供 01-买家侧 的用例复用。
 *
 * 约定：
 * - 本文件只放 page 上的真实交互（goto / click / fill / 断言），不推进业务状态的接口直调；
 * - 断言读取用 ./assert.js，通用小工具用 ./env.js、图片校验用 ./images.js；
 * - 账号相关（注册/登录）复用 ./ui.js，不重复实现。
 */
import { expect } from '@playwright/test'
import { PORTAL } from './env.js'

// ---------------------------------------------------------------------------
// 我的 → 收货地址
// ---------------------------------------------------------------------------

/** 打开「收货地址」页（需已登录） */
export async function openAddresses(page) {
  await page.goto(`${PORTAL}/addresses`)
  await page.waitForTimeout(1000)
  await expect(page.locator('h3.title')).toContainText('收货地址')
}

/** 地址列表里按「收件人」定位行 */
export const addressRowOf = (page, name) =>
  page.locator('.el-table__body tr').filter({ hasText: name }).first()

/** 地址表单弹窗 */
export const addressDialog = (page) => page.locator('.el-dialog').filter({ hasText: /新增地址|编辑地址/ })

/** 按表单项 label 取输入框（收件人/电话/省/市/区县） */
const fieldOf = (dialog, label) => dialog.locator('.el-form-item').filter({ hasText: label })

/**
 * 在地址弹窗里填一条地址。
 * @param address {name, phone, province, city, region, detail, isDefault}
 */
export async function fillAddressForm(page, address) {
  const dialog = addressDialog(page)
  await expect(dialog).toBeVisible()
  await fieldOf(dialog, '收件人').locator('input').fill(address.name)
  await fieldOf(dialog, '电话').locator('input').fill(address.phone)
  await fieldOf(dialog, '省').locator('input').fill(address.province)
  await fieldOf(dialog, '市').locator('input').fill(address.city)
  await fieldOf(dialog, '区/县').locator('input').fill(address.region)
  await fieldOf(dialog, '详细地址').locator('textarea').fill(address.detail)
  if (address.isDefault) {
    const sw = dialog.locator('.el-form-item').filter({ hasText: '默认地址' }).locator('.el-switch')
    const on = await sw.evaluate((el) => el.classList.contains('is-checked'))
    if (!on) await sw.click()
  }
  return dialog
}

/** 新增地址（开弹窗 → 填 → 确定 → 等保存成功） */
export async function addAddress(page, address) {
  await page.getByRole('button', { name: '新增地址' }).click()
  const dialog = await fillAddressForm(page, address)
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '新增地址应保存成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(addressRowOf(page, address.name), `列表里应出现「${address.name}」`).toBeVisible()
}

/** 编辑地址：按收件人定位，改完保存 */
export async function editAddress(page, currentName, patch) {
  await addressRowOf(page, currentName).getByRole('button', { name: '编辑' }).click()
  const dialog = await fillAddressForm(page, {
    name: patch.name ?? currentName,
    phone: patch.phone ?? '13800001111',
    province: patch.province ?? '广东省',
    city: patch.city ?? '深圳市',
    region: patch.region ?? '南山区',
    detail: patch.detail ?? '科技园 1 号',
    isDefault: patch.isDefault
  })
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '编辑地址应保存成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
}

/** 删除地址：确认框点确定 */
export async function deleteAddress(page, name) {
  await addressRowOf(page, name).getByRole('button', { name: '删除' }).click()
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  await box.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '删除应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
}

/** 列表里该行是否标了「默认」 */
export const isDefaultAddress = async (page, name) =>
  (await addressRowOf(page, name).innerText()).includes('默认')

// ---------------------------------------------------------------------------
// 首页 / 搜索 / 分类
// ---------------------------------------------------------------------------

/** 首页商品卡片 */
export const productCards = (page) => page.locator('.grid .card')

/** 打开首页 */
export async function openHome(page) {
  await page.goto(`${PORTAL}/`)
  await page.waitForTimeout(1200)
}

/** 按关键词搜索（回车触发） */
export async function searchProduct(page, keyword) {
  const input = page.locator('input[placeholder="搜索商品"]')
  await input.fill(keyword)
  await input.press('Enter')
  await page.waitForTimeout(1000)
}

/** 按分类筛选（点分类按钮） */
export async function filterByCategory(page, categoryName) {
  await page.locator('.el-radio-button').filter({ hasText: categoryName }).first().click()
  await page.waitForTimeout(1000)
}

// ---------------------------------------------------------------------------
// 商品详情 / 规格
// ---------------------------------------------------------------------------

/** 详情页规格按钮 */
export const specButtons = (page) => page.locator('.el-radio-group .el-radio-button')

/** 详情页当前价格文本（如 ¥ 1999.99） */
export const detailPrice = (page) => page.locator('.price')

/** 详情页库存文本（如 库存 1000） */
export const detailStock = (page) => page.locator('.stock')

// ---------------------------------------------------------------------------
// 登录页
// ---------------------------------------------------------------------------

/** 登录页错误提示（el-alert） */
export const loginError = (page) => page.locator('.login-card .el-alert')

/** 登录页必填校验提示 */
export const loginFieldError = (page, msg) => page.locator('.el-form-item__error').filter({ hasText: msg })
