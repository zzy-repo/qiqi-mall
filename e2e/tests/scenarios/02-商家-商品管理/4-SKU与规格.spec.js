import { test, expect } from '@playwright/test'
import { PORTAL, API, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import { openProducts, addProduct, openSkus, addSku, editSku } from '../../support/catalog-ui.js'
import { writeTempPng, cleanupTempImages, uploadImageApi, expectImageLoaded } from '../../support/images.js'
import { adminTokenOf, getAdminProductsApi, getAdminSkusApi } from '../../support/assert.js'
import { tid, tidPrefix } from '../../support/tid.js'

/**
 * 商家侧 02-商品管理 / 4-SKU 与规格
 *
 * 覆盖：按类型属性生成 SKU（规格值取自该类型的属性）/ 规格值整组替换（编辑时改规格）/
 *       价格与库存编辑 / SKU 图上传与列表展示 / 必填校验 / 删除入口缺失（断言现状）
 * 断言依据：admin/product/skus.vue（规格行 = 属性下拉 + 值输入，提交时整组替换 specs）、
 *          SkuController（保存 sku + specs）
 */

/** 造一个「手机」类型的新商品，返回商品行 */
async function newProduct(page, request, token, name) {
  await openProducts(page)
  await addProduct(page, { name, categoryName: '手机数码', typeName: '手机' })
  const saved = (await getAdminProductsApi(request, token, name))[0]
  expect(saved, '商品应创建成功').toBeTruthy()
  return saved
}

test.describe('商家侧 4 SKU 与规格', () => {
  test('按类型属性生成 SKU → 改价格库存 → 规格值整组替换', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    const name = `E2E-SKU商品${stamp()}`
    const product = await newProduct(page, request, token, name)

    await openSkus(page, name)
    await addSku(page, { specs: [{ attrName: '颜色', value: '曜石黑' }, { attrName: '内存', value: '256G' }], price: 1999, stock: 100 })

    let skus = await getAdminSkusApi(request, token, product.id)
    expect(skus.length, '应有 1 个 SKU').toBe(1)
    expect(Number(skus[0].price), '价格应落库').toBe(1999)
    expect(skus[0].stock, '库存应落库').toBe(100)
    expect(skus[0].specText, '规格文本应由两个属性拼出').toContain('颜色:曜石黑')
    expect(skus[0].specText).toContain('内存:256G')

    // 改价格 / 库存
    await editSku(page, '曜石黑', { price: 1888, stock: 66 })
    skus = await getAdminSkusApi(request, token, product.id)
    expect(Number(skus[0].price), '改价应落库').toBe(1888)
    expect(skus[0].stock, '改库存应落库').toBe(66)
    await expect(tid(page, 'sku-list-table').locator('.el-table__body tr').first(), '列表应展示新价格').toContainText('1888.00')

    // 规格值整组替换：把内存改成 512G
    await editSku(page, '曜石黑', {})
    // 上一步只做校验会直接保存；这里重新打开编辑改规格值
    const row = page.locator('.el-table__body tr').filter({ hasText: '曜石黑' }).first()
    await row.getByTestId('sku-row-edit').click()
    const dialog = tid(page, 'sku-form-dialog')
    await expect(dialog).toBeVisible()
    const specRows = tid(page, 'sku-form-spec-row')
    await expect(specRows, '编辑时应回填 2 行规格值').toHaveCount(2)
    await specRows.nth(1).getByTestId('sku-form-spec-value').fill('512G')
    await tid(page, 'sku-form-submit').click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(1000)

    skus = await getAdminSkusApi(request, token, product.id)
    expect(skus[0].specText, '规格值应被整组替换').toContain('内存:512G')
    expect(skus[0].specText).not.toContain('256G')

    // C 端：该 SKU 的规格与价格对得上
    const cProduct = (await (await request.get(`${API}/portal/products/${product.id}`)).json()).data
    expect(cProduct.skus.length).toBe(1)
    expect(Number(cProduct.skus[0].price)).toBe(1888)
    expect(cProduct.skus[0].specText).toContain('内存:512G')

    await page.goto(`${PORTAL}/product/${product.id}`)
    await page.waitForTimeout(1000)
    await expect(tidPrefix(page, 'product-detail-sku-'), 'C 端应显示该规格').toContainText('内存:512G')
  })

  test('SKU 图：上传后列表与 C 端都能显示', async ({ page, request }) => {
    const png = writeTempPng()
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      const name = `E2E-SKU图${stamp()}`
      const product = await newProduct(page, request, token, name)
      const skuPic = await uploadImageApi(request, token, png)
      const cover = await uploadImageApi(request, token, png)

      // 商品主图（C 端展示用）
      await openProducts(page)
      await page.locator('.el-table__body tr').filter({ hasText: name }).first()
        .getByTestId('product-row-edit').click()
      await tid(page, 'product-form-cover').locator('input[type="text"]').fill(cover)
      await tid(page, 'product-form-submit').click()
      await page.waitForTimeout(1000)

      // SKU + SKU 图
      await openProducts(page)
      await openSkus(page, name)
      await tid(page, 'sku-create').click()
      const dialog = tid(page, 'sku-form-dialog')
      await expect(dialog).toBeVisible()
      await tid(page, 'sku-form-pic').locator('input[type="text"]').fill(skuPic)
      await expectImageLoaded(tid(page, 'sku-form-pic').locator('.preview img'), 'SKU 图预览')
      await tid(page, 'sku-form-spec-add').click()
      await page.waitForTimeout(300)
      const specRow = tid(page, 'sku-form-spec-row').first()
      await specRow.getByTestId('sku-form-spec-attr').click()
      await page.waitForTimeout(400)
      await page.locator('.el-select-dropdown__item:visible').first().click()
      await specRow.getByTestId('sku-form-spec-value').fill('默认款')
      await tid(page, 'sku-form-price').locator('input').first().fill('888')
      await tid(page, 'sku-form-stock').locator('input').first().fill('10')
      await tid(page, 'sku-form-submit').click()
      await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1200)

      const skus = await getAdminSkusApi(request, token, product.id)
      expect(skus.length).toBe(1)
      expect(skus[0].pic, 'sku.pic 应为相对路径').toBe(skuPic)
      await expectImageLoaded(tid(page, 'sku-row-thumb').first().locator('img'), 'SKU 列表缩略图')

      // C 端详情页能打开（主图真实加载）
      await page.goto(`${PORTAL}/product/${product.id}`)
      await page.waitForTimeout(1200)
      await expectImageLoaded(tid(page, 'product-detail-cover').locator('img'), 'C 端主图')
    } finally {
      cleanupTempImages()
    }
  })

  test('必填校验：价格/库存不填不让保存', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    const name = `E2E-SKU必填${stamp()}`
    await newProduct(page, request, token, name)
    await openSkus(page, name)

    await tid(page, 'sku-create').click()
    const dialog = tid(page, 'sku-form-dialog')
    await expect(dialog).toBeVisible()
    // 价格/库存清空（默认是 0，先清掉再提交，触发必填）
    const priceInput = tid(page, 'sku-form-price').locator('input').first()
    const stockInput = tid(page, 'sku-form-stock').locator('input').first()
    await priceInput.fill('')
    await stockInput.fill('')
    await tid(page, 'sku-form-submit').click()
    await page.waitForTimeout(600)
    const errors = dialog.locator('.el-form-item__error')
    await expect(errors.first(), '价格/库存应有必填提示').toBeVisible()
    await expect(dialog, '校验不通过弹窗不应关闭').toBeVisible()
    await tid(page, 'sku-form-cancel').click()
  })

  test('删除 SKU：被订单引用时被拒，新建的无引用 SKU 可删', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)

    // ① 种子商品1 的第一个 SKU 被订单引用 → 被拒
    await openProducts(page)
    await openSkus(page, '七七 Pro 15 智能手机')
    const usedSku = page.locator('.el-table__body tr').first()
    await expect(usedSku).toBeVisible()
    await usedSku.getByTestId('sku-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(page.locator('.el-message--error').first(), '被订单引用的 SKU 应被拒绝').toContainText('不能删除', { timeout: 15_000 })

    // ② 新商品的 SKU（无引用）→ 可以删
    const name = `E2E待删SKU${stamp()}`
    await openProducts(page)
    await addProduct(page, { name, categoryName: '手机数码', typeName: '手机' })
    const product = (await getAdminProductsApi(request, token, name))[0]
    await openProducts(page)
    await openSkus(page, name)
    await addSku(page, { specs: [{ attrName: '颜色', value: '待删灰' }], price: 1, stock: 1 })
    expect((await getAdminSkusApi(request, token, product.id)).length, '应有 1 个 SKU').toBe(1)

    await page.locator('.el-table__body tr').first().getByTestId('sku-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)
    expect((await getAdminSkusApi(request, token, product.id)).length, 'SKU 应被删除').toBe(0)
  })
})
