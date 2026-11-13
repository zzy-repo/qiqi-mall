import { test, expect } from '../support/test.js'
import { PORTAL, ADMIN, API, stamp } from '../support/env.js'
import { adminLogin, pickSelect, pickTreeSelect, dismissPoppers } from '../support/ui.js'
import { adminTokenOf } from '../support/assert.js'
import {
  writeTempPng,
  cleanupTempImages,
  uploadImageApi,
  expectImageLoaded,
  expectAllImagesLoaded
} from '../support/images.js'

/**
 * 场景1 商品浏览（docs/flows/3-场景1-商品浏览.mmd）
 *   管理员登录 → 上架商品/SKU/图片
 *   买家打开首页 → 浏览分类/搜索 → 查看商品详情（SPU + SKU规格 + 图片）→ 选中想买的 SKU
 */
test.describe('场景1 商品浏览', () => {
  test('管理员上架商品（主图/相册/SKU规格）后，买家能浏览、搜索、看详情并选中规格', async ({ page, browser, request }) => {
    const suffix = stamp()
    const productName = `E2E场景1商品${suffix}`

    // ---------- 1. 管理员登录 ----------
    await adminLogin(page)
    await expect(page).toHaveURL(/dashboard/)

    // 真实图片：先调一次上传接口把一张真 png 传进 MinIO，拿回相对路径给主图/相册/SKU 复用
    // （以前这里填的是外部不可达域名的假 URL，前端 error 兜底成灰图标，
    //   断言照样绿 —— 那种"图片全挂也发现不了"的口子在这里堵掉）
    const pngPath = writeTempPng()
    const realImageUrl = await uploadImageApi(request, await adminTokenOf(page), pngPath)

    // ---------- 2. 上架商品（主图 + 相册） ----------
    await page.goto(`${ADMIN}/product`)
    await page.getByRole('button', { name: '新增' }).click()
    await page.waitForTimeout(600)

    await page.locator('.el-form-item').filter({ hasText: '商品名称' }).locator('input').fill(productName)
    await pickTreeSelect(page, '分类', '手机数码') // 父分类：验证 check-strictly 修复
    await pickSelect(page, '类型', '手机')
    // 主图现在是「URL 输入 + 上传按钮」组合，填刚上传得到的真实相对路径
    const mainPicItem = page.locator('.el-form-item').filter({ hasText: '主图' })
    await mainPicItem.locator('input[type="text"]').fill(realImageUrl)
    await expectImageLoaded(mainPicItem.locator('.preview img'), '主图预览（真实图片）')
    await page.getByRole('button', { name: '添加图片' }).click()
    await page.locator('.gallery-row input[type="text"]').first().fill(realImageUrl)
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)

    const newRow = page.locator('.el-table__body tr').filter({ hasText: productName }).first()
    await expect(newRow, '新上架商品应出现在商品列表').toBeVisible()

    // P4 回归：选中「父分类」也应真正保存（此前点父节点不生效）
    const api = await page.request
    const token = await page.evaluate(() => localStorage.getItem('qiqi_admin_token'))
    const listRes = await api.get(`${API}/admin/products`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const saved = (await listRes.json()).data.find((p) => p.name === productName)
    expect(saved.categoryId, '父分类「手机数码」应写库成功').toBe(1)

    // ---------- 3. 给该商品加 SKU（规格值 + 图片） ----------
    await newRow.getByRole('button', { name: 'SKU' }).click()
    await page.waitForURL(/\/product\/\d+\/skus/)
    await page.getByRole('button', { name: '新增 SKU' }).click()
    await page.waitForTimeout(800)
    await page.locator('.el-form-item').filter({ hasText: 'SKU 图片' }).locator('input[type="text"]').fill(realImageUrl)

    // 新增 SKU 时规格值为空，先点「添加规格值」创建第一行
    await page.getByRole('button', { name: '添加规格值' }).click()
    await page.waitForTimeout(300)
    const specRows = page.locator('.spec-row')
    // 规格1：颜色 = 深空黑
    await specRows.first().locator('.el-select__wrapper').click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible', { hasText: '颜色' }).first().click()
    await dismissPoppers(page)
    await specRows.first().locator('input').nth(1).fill('深空黑')
    // 规格2：内存 = 512G
    await page.getByRole('button', { name: '添加规格值' }).click()
    await page.waitForTimeout(300)
    await specRows.nth(1).locator('.el-select__wrapper').click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible', { hasText: '内存' }).first().click()
    await dismissPoppers(page)
    await specRows.nth(1).locator('input').nth(1).fill('512G')

    await page.locator('.el-form-item').filter({ hasText: '价格' }).locator('input').first().fill('6666')
    await page.locator('.el-form-item').filter({ hasText: '库存' }).locator('input').first().fill('12')
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)

    const skuRow = page.locator('.el-table__body tr').filter({ hasText: '深空黑' }).first()
    await expect(skuRow, 'SKU 列表应显示由 sku_spec 拼出的规格文本').toBeVisible()
    await expectImageLoaded(skuRow.locator('img'), 'SKU 列表缩略图（真实图片）')
    await page.screenshot({ path: 'report/shots/scenario1-admin-sku.png', fullPage: true })

    // ---------- 4. 买家首页能看到新商品 ----------
    const buyerCtx = await browser.newContext({ baseURL: PORTAL, viewport: { width: 1440, height: 950 } })
    const bp = await buyerCtx.newPage()
    await bp.goto(`${PORTAL}/`)
    await expect(
      bp.locator('.card').filter({ hasText: productName }).first(),
      '买家首页应看到刚上架的商品'
    ).toBeVisible({ timeout: 15_000 })

    // 搜索命中
    await bp.locator('input[placeholder="搜索商品"]').fill(productName)
    await bp.locator('input[placeholder="搜索商品"]').press('Enter')
    await bp.waitForTimeout(1200)
    await expect(bp.locator('.card').filter({ hasText: productName }).first()).toBeVisible()

    // ---------- 5. 详情页：SPU + SKU规格 + 图片 ----------
    await bp.locator('.card').filter({ hasText: productName }).first().click()
    await bp.waitForURL(/\/product\/\d+/)
    await bp.waitForTimeout(1500)

    await expect(bp.locator('h2')).toContainText(productName)
    const specBtn = bp.locator('.el-radio-button', { hasText: '深空黑' }).first()
    await expect(specBtn, '详情页规格按钮应来自 sku_spec').toBeVisible()
    await expect(specBtn).toContainText('512G')
    await expectImageLoaded(bp.locator('.thumb img'), '详情页主图（真实图片）')
    await expect(bp.locator('.gallery-item'), '详情页应展示商品相册').toHaveCount(1)
    await expectAllImagesLoaded(bp.locator('.gallery-item img'), '详情页相册（真实图片）')
    await expect(bp.locator('.price')).toContainText('6666.00')

    // ---------- 6. 选中想买的 SKU ----------
    await specBtn.click()
    await bp.waitForTimeout(500)
    await expect(bp.locator('.stock')).toContainText('12')
    await bp.screenshot({ path: 'report/shots/scenario1-detail.png', fullPage: true })
    await buyerCtx.close()
    cleanupTempImages()
  })
})
