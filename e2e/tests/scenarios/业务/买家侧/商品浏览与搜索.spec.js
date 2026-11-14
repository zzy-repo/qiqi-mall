import { test, expect } from '../../../support/test.js'
import { PORTAL, ADMIN } from '../../../support/env.js'
import { adminLogin, pickSelect, pickTreeSelect } from '../../../support/ui.js'
import {
  openHome,
  productCards,
  searchProduct,
  filterByCategory,
  specButtons,
  detailPrice,
  detailStock
} from '../../../support/portal-ui.js'
import { expectImageLoaded, expectAllImagesLoaded } from '../../../support/images.js'
import {
  getProductApi,
  getAdminProductsApi,
  getCategoriesApi,
  getPublishedProductsApi,
  adminTokenOf
} from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'

/**
 * 业务 / 买家侧 / 商品浏览与搜索（docs/flows/02-业务场景/业务/买家侧/商品浏览与搜索）
 *
 * 覆盖：首页列表（含图片真实渲染）/ 分类筛选 / 关键词命中 / 关键词无结果 /
 *       详情页规格切换与价格+库存联动、主图 × 相册缩略图联动 / 下架商品在 C 端不可见（列表与详情都不可见）
 * 断言依据：PortalCatalogController（列表只返回 publish_status=1；详情对下架返回「商品不存在或已下架」）
 */

test.describe('业务 › 买家侧 › 商品浏览与搜索', () => {
  test('首页列表：在架商品可见，卡片图真实渲染', async ({ page, request }) => {
    await openHome(page)
    const cards = productCards(page)
    await expect(cards.first(), '首页应有商品卡片').toBeVisible({ timeout: 15_000 })
    const count = await cards.count()
    expect(count, '首页商品数应大于 0').toBeGreaterThan(0)

    // 卡片上的图必须真的解码出来（不能靠 error 兜底）
    await expectAllImagesLoaded(tid(page, 'home-product-card').locator('img'), '首页商品卡片图')

    // 只返回上架商品（服务端口径复核）
    const { product } = await getProductApi(request, 1)
    await expect(tid(page, 'home-product-grid')).toContainText(product.name)
  })

  test('分类筛选：选中分类后只剩该分类商品', async ({ page, request }) => {
    await openHome(page)
    const allCount = await productCards(page).count()

    // 挑一个「确实有在架商品」的分类（不同环境种子不同，不能写死第几个按钮）
    const categories = await getCategoriesApi(request)
    let cat = null
    for (const c of categories) {
      const items = await getPublishedProductsApi(request, c.id)
      if (items.length > 0 && items.length < allCount) { cat = c; break }
    }
    expect(cat, '应存在一个有在架商品、且不是全量的分类').toBeTruthy()

    await filterByCategory(page, cat.name, cat.id)
    const filtered = productCards(page)
    await expect(filtered.first(), `筛选「${cat.name}」后应有商品`).toBeVisible({ timeout: 15_000 })
    const filteredCount = await filtered.count()
    expect(filteredCount, `筛选「${cat.name}」后数量应少于全部（${allCount}）`).toBeLessThan(allCount)

    // 服务端交叉校验：页面显示的商品名集合 == 该分类下的在架商品
    const expected = (await getPublishedProductsApi(request, cat.id)).map((p) => p.name).sort()
    const shown = (await filtered.allInnerTexts())
      .map((t) => t.split('查看详情')[0].trim())
      .sort()
    expect(shown, '页面展示应与该分类的在架商品一致').toEqual(expected)
  })

  test('关键词搜索：命中显示该商品，搜不到给出空态', async ({ page, request }) => {
    const { product } = await getProductApi(request, 1)
    const keyword = product.name.slice(0, 2)

    await openHome(page)
    await searchProduct(page, keyword)
    await expect(
      productCards(page).filter({ hasText: product.name }).first(),
      `搜索「${keyword}」应命中「${product.name}」`
    ).toBeVisible({ timeout: 15_000 })

    // 搜不到 → 空态
    await searchProduct(page, '不存在的商品ZZZ')
    await expect(tid(page, 'home-empty'), '搜不到应显示空态').toBeVisible({ timeout: 15_000 })
    await expect(tid(page, 'home-empty')).toContainText('暂无商品')
    await expect(productCards(page), '空态下不应有卡片').toHaveCount(0)
  })

  test('详情页规格切换：价格与库存跟着当前 SKU 走', async ({ page, request }) => {
    const { product, skus, gallery } = await getProductApi(request, 1)
    expect(skus.length, '该商品应有多个 SKU').toBeGreaterThan(1)

    await page.goto(`${PORTAL}/product/1`)
    await page.waitForTimeout(1200)
    await expect(tid(page, 'product-detail-name')).toContainText(product.name)

    const buttons = specButtons(page)
    await expect(buttons).toHaveCount(skus.length)

    // 逐个点规格，校验价格/库存与接口数据一致
    for (let i = 0; i < skus.length; i++) {
      await buttons.nth(i).click()
      await page.waitForTimeout(300)
      const sku = skus[i]
      await expect(detailPrice(page), `选第 ${i + 1} 个规格后价格应为 ${sku.price}`)
        .toContainText(Number(sku.price).toFixed(2))
      await expect(detailStock(page), `选第 ${i + 1} 个规格后库存应为 ${sku.stock}`)
        .toContainText(String(sku.stock))
    }

    // ---------- 详情页主图 × 相册缩略图联动 ----------
    // 相册是 SPU 级；主图优先级：用户点的缩略图 > 当前规格图 > 商品主图。
    // 以前主图完全不看选中态，点缩略图只换蓝框、大图不动。
    const cover = tid(page, 'product-detail-cover').locator('img')
    const thumbs = tid(page, 'product-detail-gallery-item')

    await expect(cover, '详情页主图元素应存在').toBeVisible()
    await expect(thumbs, `相册缩略图数量应等于接口相册数（${gallery.length}）`).toHaveCount(gallery.length)

    // 只有一张缩略图就没法验「点第 2 张换主图」——明确跳过，不伪造成两张
    if (gallery.length < 2) {
      test.skip(true, `商品 ${product.id} 相册只有 ${gallery.length} 张图，无法验证「点第 2 张缩略图换主图」（不伪造第二张图）`)
    }

    // 先点第 1 张（对齐选中态）：主图应等于第 1 张的地址
    const firstThumbSrc = await thumbs.nth(0).locator('img').getAttribute('src')
    expect(firstThumbSrc, '第 1 张缩略图应有 src').toBeTruthy()
    await thumbs.nth(0).click()
    await page.waitForTimeout(400)
    await expect(cover, '点第 1 张缩略图后主图 src 应等于第 1 张缩略图的 src').toHaveAttribute(
      'src',
      firstThumbSrc
    )

    // 点第 2 张：主图必须精确等于第 2 张缩略图的 src，且与点之前确实不同（防两张图同址假绿）
    const secondThumbSrc = await thumbs.nth(1).locator('img').getAttribute('src')
    expect(secondThumbSrc, '第 2 张缩略图应有 src').toBeTruthy()
    expect(secondThumbSrc, '第 2 张缩略图应与第 1 张是不同地址，否则这条用例没有意义').not.toBe(firstThumbSrc)

    const coverBeforeClick = await cover.getAttribute('src')
    await thumbs.nth(1).click()
    await page.waitForTimeout(400)
    const coverAfterClick = await cover.getAttribute('src')
    expect(coverAfterClick, '点第 2 张缩略图后主图 src 应精确等于第 2 张缩略图的 src').toBe(secondThumbSrc)
    expect(coverAfterClick, '点缩略图前后主图 src 必须真的不同（不是「非空」就算过）').not.toBe(coverBeforeClick)

    // 切一个配了图的规格：主图跟着规格图走，相册选中态不能还停留在刚才那张
    const skuWithPic = skus.find((s) => s.pic && s.pic !== secondThumbSrc)
    expect(skuWithPic, '该商品应存在一个「配了图且图与当前相册项不同」的规格').toBeTruthy()
    await tid(page, `product-detail-sku-${skuWithPic.id}`).click()
    await page.waitForTimeout(500)
    await expect(cover, `切到规格 ${skuWithPic.id} 后主图应为该规格图`).toHaveAttribute('src', skuWithPic.pic)
  })

  /**
   * 下架商品在 C 端完全不可见：列表不出现、详情页拒绝。
   * 用后台真实 UI 把一个在架商品下架，验完再上架还原（不留脏数据）。
   */
  test('下架商品在 C 端不可见（列表 + 详情），验完还原上架', async ({ page, request }) => {
    const { product } = await getProductApi(request, 1)
    const name = product.name

    // ---------- 后台：下架 ----------
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await page.goto(`${ADMIN}/product`)
    await page.waitForTimeout(1200)
    const row = page.locator('.el-table__body tr').filter({ hasText: name }).first()
    await expect(row).toBeVisible()
    await row.getByTestId('product-row-edit').click()
    const dialog = tid(page, 'product-form-dialog')
    await expect(dialog).toBeVisible()
    await tid(page, 'product-form-publish').locator('.el-radio').filter({ hasText: '下架' }).first().click()
    await tid(page, 'product-form-submit').click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)

    try {
      // 后台口径复核
      const adminList = await getAdminProductsApi(request, token)
      expect(adminList.find((p) => p.id === product.id).publishStatus, '后台应已下架').toBe(0)
      // C 端接口不再返回它
      const names = (await getPublishedProductsApi(request)).map((p) => p.name)
      expect(names, 'C 端商品列表不应包含下架商品').not.toContain(name)

      // ---------- C 端：列表与搜索都看不到 ----------
      await openHome(page)
      await expect(tid(page, 'home-product-grid')).not.toContainText(name)
      await searchProduct(page, name.slice(0, 4))
      await expect(tid(page, 'home-empty'), '搜索下架商品应无结果').toBeVisible({ timeout: 15_000 })

      // 直接开详情页 → 明确拒绝
      await page.goto(`${PORTAL}/product/${product.id}`)
      await page.waitForTimeout(1200)
      await expect(page.locator('.el-message--error, .el-alert').first(), '下架商品详情应报错').toContainText(
        '商品不存在或已下架',
        { timeout: 10_000 }
      )
    } finally {
      // ---------- 还原上架 ----------
      await page.goto(`${ADMIN}/product`)
      await page.waitForTimeout(1200)
      const back = page.locator('.el-table__body tr').filter({ hasText: name }).first()
      await back.getByTestId('product-row-edit').click()
      const d2 = tid(page, 'product-form-dialog')
      await expect(d2).toBeVisible()
      await tid(page, 'product-form-publish').locator('.el-radio').filter({ hasText: '上架' }).first().click()
      await tid(page, 'product-form-submit').click()
      await page.waitForTimeout(1200)
      const adminList2 = await getAdminProductsApi(request, token)
      expect(adminList2.find((p) => p.id === product.id).publishStatus, '验完应还原为在架').toBe(1)
    }
  })
})
