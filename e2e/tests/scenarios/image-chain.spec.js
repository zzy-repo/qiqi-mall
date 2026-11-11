import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { PORTAL, ADMIN, stamp } from '../support/env.js'
import { adminLogin, pickSelect, pickTreeSelect, dismissPoppers } from '../support/ui.js'
import { adminTokenOf, getProductApi, getAdminProductsApi, getAdminSkusApi } from '../support/assert.js'
import {
  writeTempPng,
  writeOversizePng,
  cleanupTempImages,
  uploadImageApi,
  expectImageLoaded,
  expectAllImagesLoaded
} from '../support/images.js'

/**
 * 图片链路（此前零覆盖）
 *
 * 为什么单列一个用例组：前端 el-image 有 error 兜底（灰图标 / "无法加载"），
 * 只断言「DOM 里有 <img>」等于没测——图全挂了测试照样绿。这里一律断言
 * `complete === true && naturalWidth > 0`，真的把图解码出来才算过。
 *
 * 覆盖：
 *   1) 种子商品详情页 主图 + 相册 真实渲染，且库里存的是相对路径（不绑 127.0.0.1 这类访问地址）
 *   2) 后台上传（setInputFiles）→ 预览 → 保存商品 → 详情页能显示；上传返回的 URL 可 GET 200
 *   3) 上传边界：未登录 401 / 非图片类型 415 / 超 5MB 413（负向断言，不产生任何数据）
 *   4) SKU 图：后台设图后 SKU 列表真实渲染，且 sku.pic 也是相对路径
 */
test.describe('图片链路', () => {
  test('种子商品详情页：主图与相册真实渲染，库里存相对路径', async ({ page, request }) => {
    const { product, gallery } = await getProductApi(request, 1)
    expect(product.pic, '主图应存相对路径 /qiqi-mall/**，不能是 http://127.0.0.1:9000 这类绝对地址').toMatch(/^\/qiqi-mall\//)
    expect(gallery.length, '种子商品应带相册').toBeGreaterThan(0)

    await page.goto(`${PORTAL}/product/1`)
    await page.waitForTimeout(1200)

    // 主图：.thumb 里的 <img> 必须真的解码成功
    await expectImageLoaded(page.locator('.thumb img'), '详情页主图')
    // 相册：每一张都要加载出来
    await expectAllImagesLoaded(page.locator('.gallery-item img'), '详情页相册')

    await page.screenshot({ path: 'report/shots/image-chain-detail.png', fullPage: true })
  })

  test('后台上传图片 → 预览 → 保存商品 → 详情页显示，且 URL 可 GET 200', async ({ page, request }) => {
    const png = writeTempPng()
    const productName = `E2E图片链路${stamp()}`
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)

      await page.goto(`${ADMIN}/product`)
      await page.waitForTimeout(1000)
      await page.getByRole('button', { name: '新增' }).click()
      await page.waitForTimeout(600)
      await page.locator('.el-form-item').filter({ hasText: '商品名称' }).locator('input').fill(productName)
      await pickTreeSelect(page, '分类', '手机数码')
      await pickSelect(page, '类型', '手机')

      // ---- 主图：真实选文件上传（走 POST /admin/upload/image）----
      const mainItem = page.locator('.el-form-item').filter({ hasText: '主图' })
      await mainItem.locator('input[type="file"]').setInputFiles(png)
      await expect(
        page.locator('.el-message--success').first(),
        '上传成功应弹提示'
      ).toBeVisible({ timeout: 20_000 })
      const mainUrl = await mainItem.locator('input[type="text"]').inputValue()
      expect(mainUrl, '上传后应回填相对路径').toMatch(/^\/qiqi-mall\//)
      await expectImageLoaded(mainItem.locator('.preview img'), '主图上传后的预览图')

      // 上传返回的地址能真的取到图（相对路径经前端 nginx 反代到 MinIO）
      const imgRes = await request.get(`${ADMIN}${mainUrl}`)
      expect(imgRes.status(), '上传后的图片 URL 应可直接 GET 200').toBe(200)
      expect(imgRes.headers()['content-type'] || '', '应返回图片类型').toContain('image/')

      // ---- 相册：再传一张 ----
      await page.getByRole('button', { name: '添加图片' }).click()
      await page.waitForTimeout(300)
      const galleryRow = page.locator('.gallery-row').first()
      await galleryRow.locator('input[type="file"]').setInputFiles(png)
      await expect
        .poll(async () => (await galleryRow.locator('input[type="text"]').inputValue()), {
          message: '相册图上传后应回填 URL',
          timeout: 20_000
        })
        .toMatch(/^\/qiqi-mall\//)
      await expectImageLoaded(galleryRow.locator('.preview img'), '相册图预览')

      // ---- 保存 ----
      await page.getByRole('button', { name: '确定' }).click()
      const saved = await (async () => {
        for (let i = 0; i < 20; i++) {
          const hit = await getAdminProductsApi(request, token, productName)
          if (hit.length) return hit[0]
          await page.waitForTimeout(500)
        }
        return null
      })()
      expect(saved, '新商品应保存成功').toBeTruthy()
      expect(saved.pic, '库里的主图应是相对路径').toMatch(/^\/qiqi-mall\//)

      // ---- 买家详情页能显示 ----
      await page.goto(`${PORTAL}/product/${saved.id}`)
      await page.waitForTimeout(1200)
      await expect(page.locator('h2')).toContainText(productName)
      await expectImageLoaded(page.locator('.thumb img'), '新商品详情页主图')
      await expectAllImagesLoaded(page.locator('.gallery-item img'), '新商品详情页相册')
      await page.screenshot({ path: 'report/shots/image-chain-upload.png', fullPage: true })
    } finally {
      cleanupTempImages()
    }
  })

  test('上传接口边界：未登录 401 / 非图片 415 / 超 5MB 413', async ({ page, request }) => {
    const png = writeTempPng()
    const big = writeOversizePng()
    try {
      // ① 未登录 → 401
      const noAuth = await request.post(`${ADMIN}/admin/upload/image`, {
        multipart: { file: { name: 'a.png', mimeType: 'image/png', buffer: readFileSync(png) } }
      })
      expect(noAuth.status(), '未登录上传应 401').toBe(401)

      // 登录后才有资格谈 415 / 413
      await adminLogin(page)
      const token = await adminTokenOf(page)

      // ② 非图片类型 → 415
      const wrongType = await request.post(`${ADMIN}/admin/upload/image`, {
        headers: { Authorization: `Bearer ${token}` },
        multipart: { file: { name: 'a.txt', mimeType: 'text/plain', buffer: Buffer.from('not an image') } }
      })
      expect(wrongType.status(), '非图片类型应 415').toBe(415)
      expect((await wrongType.json()).message).toContain('jpg')

      // ③ 超过 5MB 上限 → 真正的 HTTP 413（不是 200 + 错误码）
      //    上限对齐：application-*.yml 的 spring.servlet.multipart.max-file-size=5MB，
      //    前端 nginx client_max_body_size=6m，GlobalExceptionHandler 把
      //    MaxUploadSizeExceededException 映射成 413 + 「图片不能超过 5MB」
      const tooBig = await request.post(`${ADMIN}/admin/upload/image`, {
        headers: { Authorization: `Bearer ${token}` },
        multipart: { file: { name: 'big.png', mimeType: 'image/png', buffer: readFileSync(big) } }
      })
      expect(tooBig.status(), '超过 5MB 应返回 413').toBe(413)
      const bigBody = await tooBig.json().catch(() => ({}))
      expect(String(bigBody.message || ''), '413 文案应说明体积超限').toContain('5MB')

      // 反向确认：正常小图仍然是 200（避免上面三条因为别的故障而"恰好"通过）
      const okUrl = await uploadImageApi(request, token, png)
      expect(okUrl).toMatch(/^\/qiqi-mall\//)
    } finally {
      cleanupTempImages()
    }
  })

  test('SKU 图：后台设置后列表真实渲染，sku.pic 存相对路径', async ({ page, request }) => {
    const png = writeTempPng()
    const productName = `E2E图片链路SKU${stamp()}`
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)

      // 造一个商品（不带 SKU）
      await page.goto(`${ADMIN}/product`)
      await page.waitForTimeout(1000)
      await page.getByRole('button', { name: '新增' }).click()
      await page.waitForTimeout(600)
      await page.locator('.el-form-item').filter({ hasText: '商品名称' }).locator('input').fill(productName)
      await pickTreeSelect(page, '分类', '手机数码')
      await pickSelect(page, '类型', '手机')
      await page.getByRole('button', { name: '确定' }).click()
      await page.waitForTimeout(1500)

      let product = null
      for (let i = 0; i < 20 && !product; i++) {
        product = (await getAdminProductsApi(request, token, productName))[0] || null
        if (!product) await page.waitForTimeout(500)
      }
      expect(product, '商品应创建成功').toBeTruthy()

      // 进 SKU 管理页新增 SKU，并给它设图（真实上传）
      await page.goto(`${ADMIN}/product/${product.id}/skus`)
      await page.waitForTimeout(1200)
      await page.getByRole('button', { name: '新增 SKU' }).click()
      await page.waitForTimeout(600)

      const skuPicItem = page.locator('.el-form-item').filter({ hasText: 'SKU 图片' })
      await skuPicItem.locator('input[type="file"]').setInputFiles(png)
      await expect(page.locator('.el-message--success').first(), 'SKU 图上传应成功').toBeVisible({ timeout: 20_000 })
      await expectImageLoaded(skuPicItem.locator('.preview img'), 'SKU 图预览')

      // 规格值（颜色）+ 价格 + 库存
      await page.getByRole('button', { name: '添加规格值' }).click()
      await page.waitForTimeout(300)
      const specRow = page.locator('.spec-row').first()
      await specRow.locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '颜色' }).first().click()
      await dismissPoppers(page)
      await specRow.locator('input').nth(1).fill('曜石黑')
      await page.locator('.el-form-item').filter({ hasText: '价格' }).locator('input').first().fill('1999')
      await page.locator('.el-form-item').filter({ hasText: '库存' }).locator('input').first().fill('100')
      await page.getByRole('button', { name: '确定' }).click()
      await page.waitForTimeout(1500)

      // 列表里 SKU 缩略图必须真的加载出来
      const skuRow = page.locator('.el-table__body tr').filter({ hasText: '曜石黑' }).first()
      await expect(skuRow, 'SKU 列表应出现新 SKU').toBeVisible()
      await expectImageLoaded(skuRow.locator('img'), 'SKU 列表缩略图')

      // 库里存的也是相对路径
      const skus = await getAdminSkusApi(request, token, product.id)
      expect(skus.length, '应有 1 个 SKU').toBe(1)
      expect(skus[0].pic, 'sku.pic 应存相对路径').toMatch(/^\/qiqi-mall\//)
      await page.screenshot({ path: 'report/shots/image-chain-sku.png', fullPage: true })
    } finally {
      cleanupTempImages()
    }
  })
})
