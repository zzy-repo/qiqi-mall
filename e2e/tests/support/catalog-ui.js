/**
 * 商家侧「商品管理」域的真实 UI 操作（商品类型/属性/分类/商品/SKU）。
 *
 * 与 support/admin-ui.js 的分工：那个是履约/基础配置域的（归另一个 agent，我只读）；
 * 这里只放 02-商家-商品管理 用得到的操作，避免两边抢同一个文件。
 *
 * 只允许 page 交互；断言读取走 ./assert.js；图片校验走 ./images.js。
 */
import { expect } from '@playwright/test'
import { ADMIN } from './env.js'

// ---------------------------------------------------------------------------
// 商品类型
// ---------------------------------------------------------------------------

export async function openProductTypes(page) {
  await page.goto(`${ADMIN}/product-type`)
  await page.waitForTimeout(1000)
  await expect(page.locator('.card-header')).toContainText('商品类型')
}

export const typeRowOf = (page, name) =>
  page.locator('.el-table__body tr').filter({ hasText: name }).first()

/** 新增类型 */
export async function addProductType(page, name) {
  await page.getByRole('button', { name: '新增' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item').filter({ hasText: '类型名称' }).locator('input').fill(name)
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '新增类型应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(typeRowOf(page, name), `列表应出现类型「${name}」`).toBeVisible()
}

/** 编辑类型名称 */
export async function renameProductType(page, from, to) {
  await typeRowOf(page, from).getByRole('button', { name: '编辑' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  const input = dialog.locator('.el-form-item').filter({ hasText: '类型名称' }).locator('input')
  await input.fill(to)
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(typeRowOf(page, to), `列表应显示新名称「${to}」`).toBeVisible()
}

/** 进入该类型的属性页 */
export async function openAttributesOf(page, typeName) {
  await typeRowOf(page, typeName).getByRole('button', { name: '属性' }).click()
  await page.waitForURL(/\/product-type\/\d+\/attributes/, { timeout: 15_000 })
  await page.waitForTimeout(1000)
  await expect(page.locator('.card-header')).toContainText('属性管理')
}

// ---------------------------------------------------------------------------
// 属性
// ---------------------------------------------------------------------------

export const attrRowOf = (page, name) =>
  page.locator('.el-table__body tr').filter({ hasText: name }).first()

/** 新增属性（可选 isFilter） */
export async function addAttribute(page, name, isFilter = false) {
  await page.getByRole('button', { name: '新增属性' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item').filter({ hasText: '属性名称' }).locator('input').fill(name)
  if (isFilter) {
    const sw = dialog.locator('.el-form-item').filter({ hasText: '是否可筛选' }).locator('.el-switch')
    const on = await sw.evaluate((el) => el.classList.contains('is-checked'))
    if (!on) await sw.click()
  }
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '新增属性应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(attrRowOf(page, name), `属性列表应出现「${name}」`).toBeVisible()
}

/** 编辑属性（改名 / 切换是否可筛选） */
export async function editAttribute(page, from, { name, isFilter } = {}) {
  await attrRowOf(page, from).getByRole('button', { name: '编辑' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  if (name) {
    await dialog.locator('.el-form-item').filter({ hasText: '属性名称' }).locator('input').fill(name)
  }
  if (typeof isFilter === 'boolean') {
    const sw = dialog.locator('.el-form-item').filter({ hasText: '是否可筛选' }).locator('.el-switch')
    const on = await sw.evaluate((el) => el.classList.contains('is-checked'))
    if (on !== isFilter) await sw.click()
  }
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
}

// ---------------------------------------------------------------------------
// 商品分类
// ---------------------------------------------------------------------------

export async function openCategories(page) {
  await page.goto(`${ADMIN}/category`)
  await page.waitForTimeout(1000)
  await expect(page.locator('.card-header')).toContainText('商品分类')
}

export const categoryRowOf = (page, name) =>
  page.locator('.el-table__body tr').filter({ hasText: name }).first()

/** 新增分类（parentName 为空 = 顶级） */
export async function addCategory(page, name, parentName) {
  await page.getByRole('button', { name: '新增' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item').filter({ hasText: '分类名称' }).locator('input').fill(name)
  if (parentName) {
    await dialog.locator('.el-form-item').filter({ hasText: '父分类' }).locator('.el-select__wrapper').click()
    await page.waitForTimeout(400)
    const node = page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item')
      .filter({ hasText: new RegExp(`^${parentName}$`) }).first()
    await node.click()
  }
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '新增分类应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(categoryRowOf(page, name), `分类树应出现「${name}」`).toBeVisible()
}

/** 改名分类 */
export async function renameCategory(page, from, to) {
  await categoryRowOf(page, from).getByRole('button', { name: '编辑' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item').filter({ hasText: '分类名称' }).locator('input').fill(to)
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(categoryRowOf(page, to)).toBeVisible()
}

// ---------------------------------------------------------------------------
// 商品（新增/编辑/上下架）
// ---------------------------------------------------------------------------

/** 打开商品列表并定位行 */
export async function openProducts(page) {
  await page.goto(`${ADMIN}/product`)
  await page.waitForTimeout(1200)
}

export const productRowOf = (page, name) =>
  page.locator('.el-table__body tr').filter({ hasText: name }).first()

/** 新增商品（名称/分类/类型/可选描述） */
export async function addProduct(page, { name, categoryName, typeName, description }) {
  await page.getByRole('button', { name: '新增' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item').filter({ hasText: '商品名称' }).locator('input').fill(name)
  if (description) {
    await dialog.locator('.el-form-item').filter({ hasText: '商品描述' }).locator('textarea').fill(description)
  }
  // 分类用树选择（父分类也可选，check-strictly 回归点）
  await dialog.locator('.el-form-item').filter({ hasText: '分类' }).locator('.el-select__wrapper').click()
  await page.waitForTimeout(400)
  await page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item')
    .filter({ hasText: new RegExp(`^${categoryName}$`) }).first().click()
  // 类型
  await dialog.locator('.el-form-item').filter({ hasText: '类型' }).locator('.el-select__wrapper').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: typeName }).first().click()
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '新增商品应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
  await expect(productRowOf(page, name), `商品列表应出现「${name}」`).toBeVisible()
}

/** 编辑商品：改名 / 切换上下架 */
export async function editProduct(page, currentName, { name, publish } = {}) {
  await productRowOf(page, currentName).getByRole('button', { name: '编辑' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  if (name) {
    await dialog.locator('.el-form-item').filter({ hasText: '商品名称' }).locator('input').fill(name)
  }
  if (typeof publish === 'boolean') {
    const label = publish ? '上架' : '下架'
    await dialog.locator('.el-form-item').filter({ hasText: '上下架' })
      .locator('.el-radio').filter({ hasText: label }).first().click()
  }
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
}

/** 进入商品 SKU 管理页 */
export async function openSkus(page, productName) {
  await productRowOf(page, productName).getByRole('button', { name: 'SKU' }).click()
  await page.waitForURL(/\/product\/\d+\/skus/, { timeout: 15_000 })
  await page.waitForTimeout(1200)
  await expect(page.locator('.card-header')).toContainText('SKU 管理')
}

/** 新增 SKU：规格值 + 价格 + 库存（规格值传 [{attrName, value}]） */
export async function addSku(page, { specs, price, stock }) {
  await page.getByRole('button', { name: '新增 SKU' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  // 新增 SKU 时规格值是空列表：**每一行**都要先点「添加规格值」再填
  for (const [i, sp] of specs.entries()) {
    await page.getByRole('button', { name: '添加规格值' }).click()
    await page.waitForTimeout(300)
    const row = page.locator('.spec-row').nth(i)
    await row.locator('.el-select__wrapper').click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: sp.attrName }).first().click()
    await row.locator('input').nth(1).fill(sp.value)
  }
  await dialog.locator('.el-form-item').filter({ hasText: '价格' }).locator('input').first().fill(String(price))
  await dialog.locator('.el-form-item').filter({ hasText: '库存' }).locator('input').first().fill(String(stock))
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '新增 SKU 应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
}

/** 编辑某个 SKU（按规格文本定位）：改价格/库存 */
export async function editSku(page, specText, { price, stock }) {
  const row = page.locator('.el-table__body tr').filter({ hasText: specText }).first()
  await row.getByRole('button', { name: '编辑' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  if (price !== undefined) {
    await dialog.locator('.el-form-item').filter({ hasText: '价格' }).locator('input').first().fill(String(price))
  }
  if (stock !== undefined) {
    await dialog.locator('.el-form-item').filter({ hasText: '库存' }).locator('input').first().fill(String(stock))
  }
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
}
