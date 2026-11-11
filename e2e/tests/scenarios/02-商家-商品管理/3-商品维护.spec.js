import { test, expect } from '@playwright/test'
import { PORTAL, API, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import {
  openProducts,
  addProduct,
  editProduct,
  productRowOf
} from '../../support/catalog-ui.js'
import { writeTempPng, cleanupTempImages, uploadImageApi, expectImageLoaded } from '../../support/images.js'
import { adminTokenOf, getAdminProductsApi, getProductApi } from '../../support/assert.js'

/**
 * 商家侧 02-商品管理 / 3-商品维护
 *
 * 覆盖：新增（名称/分类/类型/描述/主图/相册含排序）→ 编辑（改名/改描述）→ 上下架切换 →
 *       下架后 C 端列表与详情都不可见 → 上架恢复 → 删除入口缺失（断言现状，见报告）
 * 断言依据：admin/product/index.vue（操作列只有「编辑」「SKU」，没有删除）、
 *          PortalCatalogController 只返回 publish_status=1
 */

test.describe('商家侧 3 商品维护', () => {
  test('新增商品（主图+相册+描述）→ 编辑 → 落库与 C 端展示都正确', async ({ page, request }) => {
    const png = writeTempPng()
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      const cover = await uploadImageApi(request, token, png)
      const g1 = await uploadImageApi(request, token, png)
      const g2 = await uploadImageApi(request, token, png)

      const name = `E2E维护商品${stamp()}`
      const desc = 'E2E 商品描述：用于验证后台新增与编辑链路。'
      await openProducts(page)
      await page.getByRole('button', { name: '新增' }).click()
      const dialog = page.locator('.el-dialog')
      await expect(dialog).toBeVisible()
      await dialog.locator('.el-form-item').filter({ hasText: '商品名称' }).locator('input').fill(name)
      await dialog.locator('.el-form-item').filter({ hasText: '商品描述' }).locator('textarea').fill(desc)
      // 分类 / 类型
      await dialog.locator('.el-form-item').filter({ hasText: '分类' }).locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item').first().click()
      await dialog.locator('.el-form-item').filter({ hasText: '类型' }).locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-select-dropdown__item:visible').first().click()
      // 主图 + 两张相册（排序 1 / 2）
      await dialog.locator('.el-form-item').filter({ hasText: '主图' }).locator('input[type="text"]').fill(cover)
      await expectImageLoaded(dialog.locator('.el-form-item').filter({ hasText: '主图' }).locator('.preview img'), '主图预览')
      await page.getByRole('button', { name: '添加图片' }).click()
      await page.locator('.gallery-row').first().locator('input[type="text"]').fill(g1)
      await page.getByRole('button', { name: '添加图片' }).click()
      await page.locator('.gallery-row').nth(1).locator('input[type="text"]').fill(g2)
      await page.locator('.gallery-row').nth(1).locator('.el-input-number input').fill('2')
      await dialog.getByRole('button', { name: '确定' }).click()
      await expect(page.locator('.el-message--success').first(), '新增商品应成功').toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1000)
      await expect(productRowOf(page, name), '列表应出现新商品').toBeVisible()

      // 落库复核：主图、相册排序、描述
      const saved = (await getAdminProductsApi(request, token, name))[0]
      expect(saved, '商品应落库').toBeTruthy()
      expect(saved.pic, '主图应保存相对路径').toBe(cover)
      expect(saved.description, '描述应保存').toBe(desc)

      const detail = (await (await request.get(`${API}/admin/products/${saved.id}`, {
        headers: { Authorization: `Bearer ${token}` }
      })).json()).data
      expect(detail.gallery.length, '应有 2 张相册').toBe(2)
      expect(detail.gallery.map((g) => g.sort), '相册应按 sort 升序').toEqual([1, 2])

      // C 端详情能看到（在架 + 图片真实加载）
      await page.goto(`${PORTAL}/product/${saved.id}`)
      await page.waitForTimeout(1200)
      await expect(page.locator('h2')).toContainText(name)
      await expect(page.locator('.desc-text')).toContainText(desc)
      await expectImageLoaded(page.locator('.thumb img'), 'C 端主图')

      // ---------- 编辑：改名 + 改描述 ----------
      await openProducts(page)
      const newName = `${name}-改`
      await page.getByRole('button', { name: '新增' }).waitFor() // 确保页面就绪
      await productRowOf(page, name).getByRole('button', { name: '编辑' }).click()
      const d2 = page.locator('.el-dialog')
      await expect(d2).toBeVisible()
      await d2.locator('.el-form-item').filter({ hasText: '商品名称' }).locator('input').fill(newName)
      await d2.locator('.el-form-item').filter({ hasText: '商品描述' }).locator('textarea').fill(desc + '（已改）')
      await d2.getByRole('button', { name: '确定' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1000)
      const edited = (await getAdminProductsApi(request, token, newName))[0]
      expect(edited.description).toContain('（已改）')
    } finally {
      cleanupTempImages()
    }
  })

  test('上下架切换：下架后 C 端列表与详情都不可见，上架后恢复', async ({ page, request }) => {
    const png = writeTempPng()
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      const cover = await uploadImageApi(request, token, png)
      const name = `E2E上下架${stamp()}`
      await openProducts(page)
      await addProduct(page, { name, categoryName: '手机数码', typeName: '手机' })
      // 补一张主图（新建时不带图也能建，这里补上便于 C 端可见性断言）
      await page.getByRole('button', { name: '新增' }).waitFor()
      await productRowOf(page, name).getByRole('button', { name: '编辑' }).click()
      const dialog = page.locator('.el-dialog')
      await dialog.locator('.el-form-item').filter({ hasText: '主图' }).locator('input[type="text"]').fill(cover)
      await dialog.getByRole('button', { name: '确定' }).click()
      await page.waitForTimeout(1200)

      // 下架
      await openProducts(page)
      await productRowOf(page, name).waitFor()
      await editProduct(page, name, { publish: false })
      const saved = (await getAdminProductsApi(request, token, name))[0]
      expect(saved.publishStatus, '后台应为下架(0)').toBe(0)

      // C 端：列表没有、详情被拒
      await page.goto(`${PORTAL}/`)
      await page.waitForTimeout(1200)
      await expect(page.locator('.grid'), 'C 端首页不应出现下架商品').not.toContainText(name)
      await page.goto(`${PORTAL}/product/${saved.id}`)
      await page.waitForTimeout(1200)
      await expect(page.locator('.el-message--error, .el-alert').first()).toContainText('商品不存在或已下架', { timeout: 10_000 })

      // 上架恢复
      await openProducts(page)
      await productRowOf(page, name).waitFor()
      await editProduct(page, name, { publish: true })
      expect((await getAdminProductsApi(request, token, name))[0].publishStatus, '应恢复上架(1)').toBe(1)
      const cProducts = (await getProductApi(request, saved.id).catch(() => null))
      expect(cProducts, '上架后 C 端应能取到该商品').toBeTruthy()
    } finally {
      cleanupTempImages()
    }
  })

  test('【已知缺口】商品没有删除入口：后台只提供编辑与 SKU 入口', async ({ page }) => {
    await adminLogin(page)
    await openProducts(page)
    const row = page.locator('.el-table__body tr').first()
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', { name: '编辑' })).toBeVisible()
    await expect(row.getByRole('button', { name: 'SKU' })).toBeVisible()
    await expect(row.getByRole('button', { name: '删除' }), '当前后台没有删除商品的入口').toHaveCount(0)
  })
})
