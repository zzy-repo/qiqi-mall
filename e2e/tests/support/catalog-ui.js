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
import { tid } from './tid.js'

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
  await tid(page, 'product-type-create').click()
  const dialog = tid(page, 'product-type-form-dialog')
  await expect(dialog).toBeVisible()
  await tid(page, 'product-type-form-name').fill(name)
  await tid(page, 'product-type-form-submit').click()
  await expect(page.locator('.el-message--success').first(), '新增类型应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(typeRowOf(page, name), `列表应出现类型「${name}」`).toBeVisible()
}

/** 编辑类型名称 */
export async function renameProductType(page, from, to) {
  await typeRowOf(page, from).getByTestId('product-type-row-edit').click()
  const dialog = tid(page, 'product-type-form-dialog')
  await expect(dialog).toBeVisible()
  await tid(page, 'product-type-form-name').fill(to)
  await tid(page, 'product-type-form-submit').click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(typeRowOf(page, to), `列表应显示新名称「${to}」`).toBeVisible()
}

/** 进入该类型的属性页 */
export async function openAttributesOf(page, typeName) {
  await typeRowOf(page, typeName).getByTestId('product-type-row-attributes').click()
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
  await tid(page, 'attribute-create').click()
  const dialog = tid(page, 'attribute-form-dialog')
  await expect(dialog).toBeVisible()
  await tid(page, 'attribute-form-name').fill(name)
  if (isFilter) {
    const sw = tid(page, 'attribute-form-filter')
    const on = await sw.evaluate((el) => el.classList.contains('is-checked'))
    if (!on) await sw.click()
  }
  await tid(page, 'attribute-form-submit').click()
  await expect(page.locator('.el-message--success').first(), '新增属性应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(attrRowOf(page, name), `属性列表应出现「${name}」`).toBeVisible()
}

/** 编辑属性（改名 / 切换是否可筛选） */
export async function editAttribute(page, from, { name, isFilter } = {}) {
  await attrRowOf(page, from).getByTestId('attribute-row-edit').click()
  const dialog = tid(page, 'attribute-form-dialog')
  await expect(dialog).toBeVisible()
  if (name) {
    await tid(page, 'attribute-form-name').fill(name)
  }
  if (typeof isFilter === 'boolean') {
    const sw = tid(page, 'attribute-form-filter')
    const on = await sw.evaluate((el) => el.classList.contains('is-checked'))
    if (on !== isFilter) await sw.click()
  }
  await tid(page, 'attribute-form-submit').click()
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
  await tid(page, 'category-create').click()
  const dialog = tid(page, 'category-form-dialog')
  await expect(dialog).toBeVisible()
  await tid(page, 'category-form-name').fill(name)
  if (parentName) {
    // 树下拉的浮层节点没有 testid，按节点文本选（tid.js 未覆盖第 3 条）
    await tid(page, 'category-form-parent').first().click()  // tree-select 有两个同名节点（含隐藏树）
    await page.waitForTimeout(400)
    const node = page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item')
      .filter({ hasText: new RegExp(`^${parentName}$`) }).first()
    await node.click()
  }
  await tid(page, 'category-form-submit').click()
  await expect(page.locator('.el-message--success').first(), '新增分类应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(800)
  await expect(categoryRowOf(page, name), `分类树应出现「${name}」`).toBeVisible()
}

/** 改名分类 */
export async function renameCategory(page, from, to) {
  await categoryRowOf(page, from).getByTestId('category-row-edit').click()
  const dialog = tid(page, 'category-form-dialog')
  await expect(dialog).toBeVisible()
  await tid(page, 'category-form-name').fill(to)
  await tid(page, 'category-form-submit').click()
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
  await tid(page, 'product-list-create').click()
  const dialog = tid(page, 'product-form-dialog')
  await expect(dialog).toBeVisible()
  await tid(page, 'product-form-name').fill(name)
  if (description) {
    await tid(page, 'product-form-description').fill(description)
  }
  // 分类用树选择（父分类也可选，check-strictly 回归点）
  await tid(page, 'product-form-category').first().click()
  await page.waitForTimeout(400)
  await page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item')
    .filter({ hasText: new RegExp(`^${categoryName}$`) }).first().click()
  // 类型
  await tid(page, 'product-form-type').first().click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: typeName }).first().click()
  await tid(page, 'product-form-submit').click()
  await expect(page.locator('.el-message--success').first(), '新增商品应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
  await expect(productRowOf(page, name), `商品列表应出现「${name}」`).toBeVisible()
}

/** 编辑商品：改名 / 切换上下架 */
export async function editProduct(page, currentName, { name, publish } = {}) {
  await productRowOf(page, currentName).getByTestId('product-row-edit').click()
  const dialog = tid(page, 'product-form-dialog')
  await expect(dialog).toBeVisible()
  if (name) {
    await tid(page, 'product-form-name').fill(name)
  }
  if (typeof publish === 'boolean') {
    // 上下架是 el-radio-group（有 testid），单个 radio 选项没有 testid → 组内按文案选（未覆盖第 4/5 条同理）
    const label = publish ? '上架' : '下架'
    await tid(page, 'product-form-publish').locator('.el-radio').filter({ hasText: label }).first().click()
  }
  await tid(page, 'product-form-submit').click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
}

/**
 * 按商品 id 编辑（比按名字稳：同名商品在库里会累积，按文本取到的是旧那条）。
 * @param productId 商品 id（data-testid 行锚点 product-row-<id>）
 */
export async function editProductById(page, productId, { name, publish } = {}) {
  await tid(page, `product-row-${productId}`).getByTestId('product-row-edit').click()
  const dialog = tid(page, 'product-form-dialog')
  await expect(dialog).toBeVisible()
  if (name) await tid(page, 'product-form-name').fill(name)
  if (typeof publish === 'boolean') {
    const label = publish ? '上架' : '下架'
    await tid(page, 'product-form-publish').locator('.el-radio').filter({ hasText: label }).first().click()
  }
  await tid(page, 'product-form-submit').click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
}

/** 按商品 id 直接进入该商品的 SKU 管理页（不按名字点行，避免同名商品取错） */
export async function openSkusById(page, productId) {
  await tid(page, `product-row-${productId}`).getByTestId('product-row-skus').click()
  await page.waitForURL(new RegExp(`/product/${productId}/skus`), { timeout: 15_000 })
  await page.waitForTimeout(1200)
  await expect(page.locator('.card-header')).toContainText('SKU 管理')
}

/** 进入商品 SKU 管理页 */
export async function openSkus(page, productName) {
  await productRowOf(page, productName).getByTestId('product-row-skus').click()
  await page.waitForURL(/\/product\/\d+\/skus/, { timeout: 15_000 })
  await page.waitForTimeout(1200)
  await expect(page.locator('.card-header')).toContainText('SKU 管理')
}

/** 新增 SKU：规格值 + 价格 + 库存（规格值传 [{attrName, value}]） */
export async function addSku(page, { specs, price, stock }) {
  await tid(page, 'sku-create').click()
  const dialog = tid(page, 'sku-form-dialog')
  await expect(dialog).toBeVisible()
  // 新增 SKU 时规格值是空列表：**每一行**都要先点「添加规格值」再填
  for (const [i, sp] of specs.entries()) {
    await tid(page, 'sku-form-spec-add').click()
    await page.waitForTimeout(300)
    const row = tid(page, 'sku-form-spec-row').nth(i)
    await row.getByTestId('sku-form-spec-attr').click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: sp.attrName }).first().click()
    await row.getByTestId('sku-form-spec-value').fill(sp.value)
  }
  await tid(page, 'sku-form-price').locator('input').first().fill(String(price))
  await tid(page, 'sku-form-stock').locator('input').first().fill(String(stock))
  await tid(page, 'sku-form-submit').click()
  await expect(page.locator('.el-message--success').first(), '新增 SKU 应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
}

/** 编辑某个 SKU（按规格文本定位）：改价格/库存 */
export async function editSku(page, specText, { price, stock }) {
  // SKU 行没有行锚点 → 按规格文本定位到行（tid.js 未覆盖第 4 条），行内按钮用 testid
  const row = page.locator('.el-table__body tr').filter({ hasText: specText }).first()
  await row.getByTestId('sku-row-edit').click()
  const dialog = tid(page, 'sku-form-dialog')
  await expect(dialog).toBeVisible()
  if (price !== undefined) {
    await tid(page, 'sku-form-price').locator('input').first().fill(String(price))
  }
  if (stock !== undefined) {
    await tid(page, 'sku-form-stock').locator('input').first().fill(String(stock))
  }
  await tid(page, 'sku-form-submit').click()
  await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1000)
}
