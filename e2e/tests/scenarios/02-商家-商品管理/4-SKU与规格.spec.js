import { test, expect } from '@playwright/test'
import { PORTAL, API, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import { openProducts, addProduct, openSkus, addSku, editSku } from '../../support/catalog-ui.js'
import { writeTempPng, cleanupTempImages, uploadImageApi, expectImageLoaded } from '../../support/images.js'
import { adminTokenOf, getAdminProductsApi, getAdminSkusApi } from '../../support/assert.js'

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
    await expect(page.locator('.el-table__body tr').first(), '列表应展示新价格').toContainText('1888.00')

    // 规格值整组替换：把内存改成 512G
    await editSku(page, '曜石黑', {})
    // 上一步只做校验会直接保存；这里重新打开编辑改规格值
    const row = page.locator('.el-table__body tr').filter({ hasText: '曜石黑' }).first()
    await row.getByRole('button', { name: '编辑' }).click()
    const dialog = page.locator('.el-dialog')
    await expect(dialog).toBeVisible()
    const specRows = page.locator('.spec-row')
    await expect(specRows, '编辑时应回填 2 行规格值').toHaveCount(2)
    await specRows.nth(1).locator('input').nth(1).fill('512G')
    await dialog.getByRole('button', { name: '确定' }).click()
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
    await expect(page.locator('.el-radio-group'), 'C 端应显示该规格').toContainText('内存:512G')
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
        .getByRole('button', { name: '编辑' }).click()
      const pd = page.locator('.el-dialog')
      await pd.locator('.el-form-item').filter({ hasText: '主图' }).locator('input[type="text"]').fill(cover)
      await pd.getByRole('button', { name: '确定' }).click()
      await page.waitForTimeout(1000)

      // SKU + SKU 图
      await openProducts(page)
      await openSkus(page, name)
      await page.getByRole('button', { name: '新增 SKU' }).click()
      const dialog = page.locator('.el-dialog')
      await expect(dialog).toBeVisible()
      const picItem = dialog.locator('.el-form-item').filter({ hasText: 'SKU 图片' })
      await picItem.locator('input[type="text"]').fill(skuPic)
      await expectImageLoaded(picItem.locator('.preview img'), 'SKU 图预览')
      await page.getByRole('button', { name: '添加规格值' }).click()
      await page.waitForTimeout(300)
      const specRow = page.locator('.spec-row').first()
      await specRow.locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-select-dropdown__item:visible').first().click()
      await specRow.locator('input').nth(1).fill('默认款')
      await dialog.locator('.el-form-item').filter({ hasText: '价格' }).locator('input').first().fill('888')
      await dialog.locator('.el-form-item').filter({ hasText: '库存' }).locator('input').first().fill('10')
      await dialog.getByRole('button', { name: '确定' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1200)

      const skus = await getAdminSkusApi(request, token, product.id)
      expect(skus.length).toBe(1)
      expect(skus[0].pic, 'sku.pic 应为相对路径').toBe(skuPic)
      await expectImageLoaded(page.locator('.el-table__body tr').first().locator('img'), 'SKU 列表缩略图')

      // C 端详情页能打开（主图真实加载）
      await page.goto(`${PORTAL}/product/${product.id}`)
      await page.waitForTimeout(1200)
      await expectImageLoaded(page.locator('.thumb img'), 'C 端主图')
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

    await page.getByRole('button', { name: '新增 SKU' }).click()
    const dialog = page.locator('.el-dialog')
    await expect(dialog).toBeVisible()
    // 价格/库存清空（默认是 0，先清掉再提交，触发必填）
    const priceInput = dialog.locator('.el-form-item').filter({ hasText: '价格' }).locator('input').first()
    const stockInput = dialog.locator('.el-form-item').filter({ hasText: '库存' }).locator('input').first()
    await priceInput.fill('')
    await stockInput.fill('')
    await dialog.getByRole('button', { name: '确定' }).click()
    await page.waitForTimeout(600)
    const errors = dialog.locator('.el-form-item__error')
    await expect(errors.first(), '价格/库存应有必填提示').toBeVisible()
    await expect(dialog, '校验不通过弹窗不应关闭').toBeVisible()
    await dialog.getByRole('button', { name: '取消' }).click()
  })

  test('【已知缺口】SKU 没有删除入口：SKU 页只提供新增与编辑', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    const name = `E2E-SKU删除${stamp()}`
    await newProduct(page, request, token, name)
    await openSkus(page, name)
    await addSku(page, { specs: [{ attrName: '颜色', value: '灰色' }], price: 100, stock: 5 })

    const row = page.locator('.el-table__body tr').first()
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', { name: '编辑' })).toBeVisible()
    await expect(row.getByRole('button', { name: '删除' }), '当前后台没有删除 SKU 的入口').toHaveCount(0)
  })
})
