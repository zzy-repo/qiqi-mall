import { test, expect } from '@playwright/test'
import { PORTAL, API, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import {
  openProducts,
  addProduct,
  editProduct,
  editProductById,
  productRowOf
} from '../../support/catalog-ui.js'
import { expectImageLoaded } from '../../support/images.js'
import { adminTokenOf, getAdminProductsApi, getProductApi } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { newestByName, productDescriptionOf, productNameOf, realGalleryImages, realImage } from '../../support/fixtures.js'

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
    const seed = stamp()
    {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      // MinIO 上的真实种子图（视频里是商品实拍，不是纯色块）
      const { cover, gallery } = realGalleryImages(seed)
      const [g1, g2] = gallery

      const name = productNameOf(seed)
      const desc = productDescriptionOf(seed)
      await openProducts(page)
      await tid(page, 'product-list-create').click()
      const dialog = tid(page, 'product-form-dialog')
      await expect(dialog).toBeVisible()
      await tid(page, 'product-form-name').fill(name)
      await tid(page, 'product-form-description').fill(desc)
      // 分类 / 类型
      await tid(page, 'product-form-category').first().click()
      await page.waitForTimeout(400)
      await page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item').first().click()
      await tid(page, 'product-form-type').first().click()
      await page.waitForTimeout(400)
      await page.locator('.el-select-dropdown__item:visible').first().click()
      // 主图 + 两张相册（排序 1 / 2）
      await tid(page, 'product-form-cover').locator('input[type="text"]').fill(cover)
      await expectImageLoaded(tid(page, 'product-form-cover').locator('.preview img'), '主图预览')
      await tid(page, 'product-form-gallery-add').click()
      await tid(page, 'product-form-gallery-row').first().getByTestId('product-form-gallery-url')
        .locator('input[type="text"]').fill(g1)
      await tid(page, 'product-form-gallery-add').click()
      await tid(page, 'product-form-gallery-row').nth(1).getByTestId('product-form-gallery-url')
        .locator('input[type="text"]').fill(g2)
      await tid(page, 'product-form-gallery-row').nth(1).getByTestId('product-form-gallery-sort').locator('input').fill('2')
      await tid(page, 'product-form-submit').click()
      await expect(page.locator('.el-message--success').first(), '新增商品应成功').toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1000)
      await expect(productRowOf(page, name), '列表应出现新商品').toBeVisible()

      // 落库复核：主图、相册排序、描述
      const saved = newestByName(await getAdminProductsApi(request, token, name), name)
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
      await expect(tid(page, 'product-detail-name')).toContainText(name)
      await expect(tid(page, 'product-detail-desc')).toContainText(desc)
      await expectImageLoaded(tid(page, 'product-detail-cover').locator('img'), 'C 端主图')

      // ---------- 编辑：改名 + 改描述 ----------
      await openProducts(page)
      const newName = productNameOf(`${seed}-edit`)
      await tid(page, 'product-list-create').waitFor() // 确保页面就绪
      await productRowOf(page, name).getByTestId('product-row-edit').click()
      const d2 = tid(page, 'product-form-dialog')
      await expect(d2).toBeVisible()
      await tid(page, 'product-form-name').fill(newName)
      await tid(page, 'product-form-description').fill(desc + '（已改）')
      await tid(page, 'product-form-submit').click()
      await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1000)
      const edited = newestByName(await getAdminProductsApi(request, token, newName), newName)
      expect(edited.description).toContain('（已改）')
    }
  })

  test('上下架切换：下架后 C 端列表与详情都不可见，上架后恢复', async ({ page, request }) => {
    const seed = stamp()
    {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      const { cover } = realGalleryImages(seed)
      const name = productNameOf(`${seed}-pub`)
      await openProducts(page)
      await addProduct(page, { name, categoryName: '手机数码', typeName: '手机' })
      // 拿到本轮这条的 id，后续一律按 id 定位（同名商品在库里会累积，按文本会取到旧那条）
      const created = newestByName(await getAdminProductsApi(request, token, name), name)
      expect(created, '商品应创建成功').toBeTruthy()
      // 补一张主图（新建时不带图也能建，这里补上便于 C 端可见性断言）
      await tid(page, 'product-list-create').waitFor()
      await tid(page, `product-row-${created.id}`).getByTestId('product-row-edit').click()
      await tid(page, 'product-form-cover').locator('input[type="text"]').fill(cover)
      await tid(page, 'product-form-submit').click()
      await page.waitForTimeout(1200)

      // 下架（按 id）
      await openProducts(page)
      await tid(page, `product-row-${created.id}`).waitFor()
      await editProductById(page, created.id, { publish: false })
      const saved = (await getAdminProductsApi(request, token, name)).find((p) => p.id === created.id)
      expect(saved.publishStatus, '后台应为下架(0)').toBe(0)

      // C 端：列表没有、详情被拒
      await page.goto(`${PORTAL}/`)
      await page.waitForTimeout(1200)
      await expect(tid(page, 'home-product-grid'), 'C 端首页不应出现下架商品').not.toContainText(name)
      await page.goto(`${PORTAL}/product/${saved.id}`)
      await page.waitForTimeout(1200)
      await expect(page.locator('.el-message--error, .el-alert').first()).toContainText('商品不存在或已下架', { timeout: 10_000 })

      // 上架恢复
      await openProducts(page)
      await tid(page, `product-row-${created.id}`).waitFor()
      await editProductById(page, created.id, { publish: true })
      expect((await getAdminProductsApi(request, token, name)).find((p) => p.id === created.id).publishStatus, '应恢复上架(1)').toBe(1)
      const cProducts = (await getProductApi(request, saved.id).catch(() => null))
      expect(cProducts, '上架后 C 端应能取到该商品').toBeTruthy()
    }
  })

  test('删除商品：被订单引用时被拒并提示改为下架，无引用可删', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openProducts(page)

    // ① 被订单明细引用的商品（种子商品1）→ 被拒，且提示可改为下架
    // 按 **id=1** 精确取（种子商品1 被演示订单引用）：列表第一条会随着用例不停新建商品而漂移，
    // 全量跑时 [0] 往往已经是本轮新建的空商品，删除会成功 → 断言假失败。
    const seedProduct = (await getAdminProductsApi(request, token)).find((p) => p.id === 1)
    expect(seedProduct, '种子商品1 应存在于后台商品列表').toBeTruthy()
    const usedRow = page.getByTestId('product-row-1')
    await expect(usedRow).toBeVisible()
    await usedRow.getByTestId('product-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    const errText = page.locator('.el-message--error').first()
    await expect(errText, '被订单引用的商品应被拒绝').toContainText('不能删除', { timeout: 15_000 })
    await expect(errText).toContainText('下架')
    expect((await getAdminProductsApi(request, token, seedProduct.name)).length, '商品应仍存在').toBe(1)

    // ② 新建的空商品 → 可以删
    const empty = productNameOf(stamp())
    await page.getByRole('button', { name: '新增' }).click()
    const dialog = page.locator('.el-dialog')
    await dialog.getByTestId('product-form-name').fill(empty)
    await dialog.locator('.el-form-item').filter({ hasText: '分类' }).locator('.el-select__wrapper').click()
    await page.waitForTimeout(400)
    await page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item').first().click()
    await dialog.locator('.el-form-item').filter({ hasText: '类型' }).locator('.el-select__wrapper').click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible').first().click()
    await dialog.getByTestId('product-form-submit').click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(1000)

    await page.locator('.el-table__body tr').filter({ hasText: empty }).first().getByTestId('product-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)
    expect((await getAdminProductsApi(request, token, empty)).length, '空商品应被删除').toBe(0)
  })
})
