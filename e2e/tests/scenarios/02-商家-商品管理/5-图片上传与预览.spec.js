import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { PORTAL, ADMIN, API, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import { openProducts, addProduct } from '../../support/catalog-ui.js'
import {
  writeTempPng,
  writeOversizePng,
  cleanupTempImages,
  expectImageLoaded,
  expectAllImagesLoaded,
  demoImageFile,
  naturalName,
  IS_DEMO
} from '../../support/images.js'
import { adminTokenOf, getAdminProductsApi } from '../../support/assert.js'

/**
 * 商家侧 02-商品管理 / 5-图片上传与预览
 *
 * 覆盖（完整走一遍上传链路，而不是只看 DOM）：
 *  1) 点「上传」→ 选文件 → 预览图 naturalWidth>0 → 表单带回相对路径 → 保存 → C 端详情页显示
 *  2) 边界：未登录 401 / 非图片 415 / 超 5MB 413（场景视角的断言，复用 images.js 的临时文件工具）
 *  3) 相册多图：加 3 张 → 排序字段生效 → 保存后 C 端顺序与库里一致
 *  4) SKU 图：后台 SKU 列表缩略图真实渲染；顺带钉住「详情页切规格主图不跟着变」的现状（见报告）
 *
 * 上传相关选择器：ImageUploader 是「URL 输入 + el-upload」，点「上传」会弹系统文件选择框，
 * 这里用 filechooser 事件接管，跟真人操作路径一致。
 */

/** 在某个表单项里点「上传」并选文件（el-upload 容器本身也有 role=button，这里点内部真正的 .el-button） */
async function uploadIn(page, formItem, filePath) {
  const chooserPromise = page.waitForEvent('filechooser')
  await formItem.locator('.el-upload .el-button').first().click()
  const chooser = await chooserPromise
  await chooser.setFiles(filePath)
  await expect(page.locator('.el-message--success').first(), '上传应提示成功').toBeVisible({ timeout: 20_000 })
  await page.waitForTimeout(600)
}

const formItemOf = (page, label) => page.locator('.el-form-item').filter({ hasText: label })

test.describe('商家侧 5 图片上传与预览', () => {
  test('上传主图：点上传 → 预览 → 回填相对路径 → 保存 → C 端显示', async ({ page, request }) => {
    // demo 档用真实种子图（视频里是真实照片），默认档用纯色小图（快且可重复）
    const png = await demoImageFile()
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      const name = naturalName(2, `E2E上传预览${stamp()}`)

      await openProducts(page)
      await page.getByRole('button', { name: '新增' }).click()
      const dialog = page.locator('.el-dialog')
      await expect(dialog).toBeVisible()
      await formItemOf(page, '商品名称').locator('input').fill(name)
      await formItemOf(page, '分类').locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item').first().click()
      await formItemOf(page, '类型').locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-select-dropdown__item:visible').first().click()

      // ---- 主图：点「上传」→ 选文件 ----
      const coverItem = formItemOf(page, '主图')
      await uploadIn(page, coverItem, png)
      const coverUrl = await coverItem.locator('input[type="text"]').inputValue()
      expect(coverUrl, '上传后表单字段应带回相对路径').toMatch(/^\/qiqi-mall\//)
      await expectImageLoaded(coverItem.locator('.preview img'), '主图预览')

      // 保存
      await dialog.getByRole('button', { name: '确定' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1000)

      const saved = (await getAdminProductsApi(request, token, name)).sort((a, b) => b.id - a.id)[0]
      expect(saved, '商品应保存成功').toBeTruthy()
      expect(saved.pic, '库里的主图应是刚上传的相对路径').toBe(coverUrl)

      // 上传返回的地址真能取到图（前端 nginx 反代 MinIO）
      const imgRes = await request.get(`${ADMIN}${coverUrl}`)
      expect(imgRes.status(), '上传后的图片应可 GET 200').toBe(200)
      expect(imgRes.headers()['content-type'] || '').toContain('image/')

      // C 端详情页显示
      await page.goto(`${PORTAL}/product/${saved.id}`)
      await page.waitForTimeout(1200)
      await expect(page.locator('h2')).toContainText(name)
      await expectImageLoaded(page.locator('.thumb img'), 'C 端详情页主图')
      expect(await page.locator('.thumb img').getAttribute('src'), 'C 端用的就是这张相对路径').toContain(coverUrl)
    } finally {
      cleanupTempImages()
    }
  })

  test('上传边界：未登录 401 / 非图片 415 / 超 5MB 413', async ({ page, request }) => {
    const png = writeTempPng()
    const big = writeOversizePng()
    try {
      // ① 未登录
      const noAuth = await request.post(`${ADMIN}/admin/upload/image`, {
        multipart: { file: { name: 'a.png', mimeType: 'image/png', buffer: readFileSync(png) } }
      })
      expect(noAuth.status(), '未登录上传应 401').toBe(401)

      await adminLogin(page)
      const token = await adminTokenOf(page)

      // ② 非图片
      const wrongType = await request.post(`${ADMIN}/admin/upload/image`, {
        headers: { Authorization: `Bearer ${token}` },
        multipart: { file: { name: 'a.txt', mimeType: 'text/plain', buffer: Buffer.from('not an image') } }
      })
      expect(wrongType.status(), '非图片类型应 415').toBe(415)
      expect((await wrongType.json()).message).toContain('jpg')

      // ③ 超 5MB（multipart 上限 5MB / nginx 6m，5.5MB 能打到后端）
      const tooBig = await request.post(`${ADMIN}/admin/upload/image`, {
        headers: { Authorization: `Bearer ${token}` },
        multipart: { file: { name: 'big.png', mimeType: 'image/png', buffer: readFileSync(big) } }
      })
      expect(tooBig.status(), '超过 5MB 应返回真 413').toBe(413)
      expect(String((await tooBig.json().catch(() => ({}))).message || '')).toContain('5MB')

      // 反向确认：正常小图仍 200（避免上面因为别的故障"恰好"通过）
      const okRes = await request.post(`${ADMIN}/admin/upload/image`, {
        headers: { Authorization: `Bearer ${token}` },
        multipart: { file: { name: 'ok.png', mimeType: 'image/png', buffer: readFileSync(png) } }
      })
      expect(okRes.status(), '正常小图应 200').toBe(200)
      expect((await okRes.json()).data.url).toMatch(/^\/qiqi-mall\//)
    } finally {
      cleanupTempImages()
    }
  })

  test('相册多图：加 3 张 → 排序生效 → 保存后 C 端顺序一致', async ({ page, request }) => {
    const png = await demoImageFile()
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      const name = naturalName(0, `E2E相册排序${stamp()}`)

      await openProducts(page)
      await page.getByRole('button', { name: '新增' }).click()
      const dialog = page.locator('.el-dialog')
      await expect(dialog).toBeVisible()
      await formItemOf(page, '商品名称').locator('input').fill(name)
      await formItemOf(page, '分类').locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item').first().click()
      await formItemOf(page, '类型').locator('.el-select__wrapper').click()
      await page.waitForTimeout(400)
      await page.locator('.el-select-dropdown__item:visible').first().click()

      // 主图 + 3 张相册（各自上传，object 名不同便于校验顺序）
      await uploadIn(page, formItemOf(page, '主图'), png)
      const galleryUrls = []
      for (let i = 0; i < 3; i++) {
        await page.getByRole('button', { name: '添加图片' }).click()
        await page.waitForTimeout(300)
        const row = page.locator('.gallery-row').nth(i)
        await uploadIn(page, row, png)
        galleryUrls.push(await row.locator('input[type="text"]').inputValue())
      }
      expect(new Set(galleryUrls).size, '三张相册应是三份不同对象').toBe(3)

      // 故意把排序改成 3 / 2 / 1（倒序），验证「排序字段生效」而不是按添加顺序
      for (let i = 0; i < 3; i++) {
        const sortInput = page.locator('.gallery-row').nth(i).locator('.el-input-number input')
        await sortInput.fill(String(3 - i))
        await sortInput.press('Tab')
        await page.waitForTimeout(200)
      }
      await dialog.getByRole('button', { name: '确定' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(1000)

      const saved = (await getAdminProductsApi(request, token, name)).sort((a, b) => b.id - a.id)[0]
      const detail = (await (await request.get(`${API}/admin/products/${saved.id}`, {
        headers: { Authorization: `Bearer ${token}` }
      })).json()).data
      const savedUrls = detail.gallery.map((g) => g.url)
      expect(detail.gallery.map((g) => g.sort), '库里应按 sort 升序返回').toEqual([1, 2, 3])
      expect(savedUrls, '排序 1 对应的应是最后添加的那张').toEqual([...galleryUrls].reverse())

      // C 端相册顺序与库里一致
      await page.goto(`${PORTAL}/product/${saved.id}`)
      await page.waitForTimeout(1500)
      const shown = await page.locator('.gallery-item img').evaluateAll((els) => els.map((e) => e.getAttribute('src')))
      expect(shown, 'C 端相册顺序应与库里一致').toEqual(savedUrls)
      await expectAllImagesLoaded(page.locator('.gallery-item img'), 'C 端相册图')
    } finally {
      cleanupTempImages()
    }
  })

  test('SKU 图：列表缩略图渲染，且 C 端切规格时主图跟着换、没配图的规格回退商品主图', async ({ page, request }) => {
    const png = await demoImageFile()
    try {
      await adminLogin(page)
      const token = await adminTokenOf(page)
      const name = naturalName(4, `E2E-SKU图预览${stamp()}`)
      await openProducts(page)
      await addProduct(page, { name, categoryName: '手机数码', typeName: '手机' })
      const product = (await getAdminProductsApi(request, token, name)).sort((a, b) => b.id - a.id)[0]

      // 商品主图（回退用的底图）
      await page.getByRole('button', { name: '新增' }).waitFor()
      await page.locator('.el-table__body tr').filter({ hasText: name }).last()  // demo 档商品名可重复，取最新一行
        .getByRole('button', { name: '编辑' }).click()
      let dialog = page.locator('.el-dialog')
      await uploadIn(page, formItemOf(page, '主图'), png)
      const coverUrl = await formItemOf(page, '主图').locator('input[type="text"]').inputValue()
      await dialog.getByRole('button', { name: '确定' }).click()
      await page.waitForTimeout(1000)

      // 两个 SKU：第一个配图，第二个**不配图**（用来验回退）
      await openProducts(page)
      await page.locator('.el-table__body tr').filter({ hasText: name }).last()  // demo 档商品名可重复，取最新一行
        .getByRole('button', { name: 'SKU' }).click()
      await page.waitForURL(/\/product\/\d+\/skus/, { timeout: 15_000 })
      await page.waitForTimeout(1000)
      let skuPicUrl = ''
      for (const [idx, value] of ['黑色款', '白色款'].entries()) {
        await page.getByRole('button', { name: '新增 SKU' }).click()
        dialog = page.locator('.el-dialog')
        await expect(dialog).toBeVisible()
        if (idx === 0) {
          await uploadIn(page, formItemOf(page, 'SKU 图片'), png)
          skuPicUrl = await formItemOf(page, 'SKU 图片').locator('input[type="text"]').inputValue()
        }
        await page.getByRole('button', { name: '添加规格值' }).click()
        await page.waitForTimeout(300)
        const specRow = page.locator('.spec-row').first()
        await specRow.locator('.el-select__wrapper').click()
        await page.waitForTimeout(400)
        await page.locator('.el-select-dropdown__item:visible').first().click()
        await specRow.locator('input').nth(1).fill(value)
        await formItemOf(page, '价格').locator('input').first().fill(String(1000 + idx * 100))
        await formItemOf(page, '库存').locator('input').first().fill('20')
        await dialog.getByRole('button', { name: '确定' }).click()
        await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
        await page.waitForTimeout(1000)
      }
      expect(skuPicUrl, '第一个 SKU 应有图').toMatch(/^\/qiqi-mall\//)

      // 后台 SKU 列表缩略图真实渲染
      await expect(page.locator('.el-table__body tr'), '应有 2 个 SKU').toHaveCount(2)
      await expectAllImagesLoaded(page.locator('.el-table__body tr img'), 'SKU 列表缩略图')

      // C 端：切到配了图的规格 → 主图变成 SKU 图；切到没配图的规格 → 回退商品主图
      await page.goto(`${PORTAL}/product/${product.id}`)
      await page.waitForTimeout(1500)
      const specs = page.locator('.el-radio-group .el-radio-button')
      await expect(specs, '应有两个规格按钮').toHaveCount(2)

      await specs.nth(0).click()
      await page.waitForTimeout(500)
      const coverOfSku1 = await page.locator('.thumb img').getAttribute('src')
      expect(coverOfSku1, '选中有图的规格时主图应是该 SKU 的图').toContain(skuPicUrl)

      await specs.nth(1).click()
      await page.waitForTimeout(500)
      const coverOfSku2 = await page.locator('.thumb img').getAttribute('src')
      expect(coverOfSku2, '选中没配图的规格时应回退到商品主图').toContain(coverUrl)

      await expectImageLoaded(page.locator('.thumb img'), '切换后的主图真实加载')
    } finally {
      cleanupTempImages()
    }
  })
})
