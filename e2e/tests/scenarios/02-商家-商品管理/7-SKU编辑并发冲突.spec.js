import { test, expect } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ADMIN, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import { openProducts, addProduct, openSkusById, addSku } from '../../support/catalog-ui.js'
import { conflictDialog, conflictRefresh, confirmDanger } from '../../support/admin-ui.js'
import { adminTokenOf, getAdminProductsApi, getAdminSkusApi } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { attributeValues, newestByName, productDescriptionOf, productNameOf } from '../../support/fixtures.js'

/**
 * 商家侧 02-商品管理 / 7-SKU 编辑并发冲突
 *
 * 与 6-商品编辑并发冲突.spec.js 同一个母题（两个会话 + 409 + 刷新重来），
 * 但这次撞的是**另一张表**：`sku` 的 version 乐观锁（SkuController#update）。
 * 为什么值得单独一条：商品编辑走的是 product.version，SKU 编辑走的是 sku.version，
 * 两条链路的刷新回调也不同（SKU 弹窗刷新后是**重新拉 GET /admin/skus/{id} 重填表单**，
 * 弹窗不关）。同一个断言模板复用到 SKU 上，才能证明乐观锁不是「只在商品上能用」。
 *
 * 一个场景，五步，全程真实 UI 点击 + 真实后端（不 mock）：
 *   ① 两个**独立 browser context**（两套 localStorage / 两个令牌）先后打开同一 SKU 的编辑弹窗，
 *      两边拿到的都是同一个版本号 V
 *   ② 会话甲改价保存 → 成功，库里 version 由 V 变 V+1
 *   ③ 会话乙保存（手里还是 V）→ 后端 409 → 弹「数据已被他人修改（可能其他人在编辑）」+「刷新」
 *   ④ 库里价格仍是甲改的值（乙那次一个字段都没写进去，没有覆盖甲）
 *   ⑤ 乙点「刷新」→ 表单被重填成甲改后的价格 → 乙再改一次 → 保存成功，version 再 +1
 *
 * 三条硬约束（踩过的坑）：
 *   · 定位一律用 id/testid：商品行走 `product-row-<id>`，SKU 行走 `sku-row-<skuId>`（skus.vue 里有这个锚点），
 *     不用「列表第一项」——同名商品/SKU 在库里会累积，按序号取必翻车。
 *   · 用例自给自足：自己新建一件一次性商品 + 1 个 SKU（名字带唯一后缀），跑完连商品一起删掉；
 *     同一份库连跑两轮（第二轮不 reset）都不留残留。
 *   · 断言不放宽：版本号断言具体数字（0→1→2）、价格断言具体金额、409 断言 HTTP 状态码 + body.code
 *     + 后端中文原文，并把响应体落到 e2e/report/shots/ 里当证据。
 */

/** 与 4-SKU与规格.spec.js 一致的演示口径（手机类商品挂手机数码 + 手机类型，才有「颜色」属性可选） */
const CATEGORY = '手机数码'
const TYPE = '手机'
const PRICE_0 = 1200
const STOCK_0 = 30
/** 甲、乙分别改成这个价（三个数字必须两两不同，断言才咬得住「库里到底是谁的值」） */
const PRICE_A = 1999
const PRICE_B = 888

/** 409 证据落盘目录（report/ 已被 e2e/.gitignore 忽略，属于产物不是源码） */
const EVIDENCE_DIR = fileURLToPath(new URL('../../../report/shots/', import.meta.url))

/** el-input-number 的输入框里是格式化后的字符串（precision=2 → "1999.00"），断言前统一转数字 */
const numberOf = async (locator) => Number(await locator.inputValue())

/**
 * 清掉本次建的一次性商品（真实界面：删除 → 预演确认弹窗 → 确认）。
 * 先 goto 列表页：即使中途失败时页面上还挂着弹窗，导航也能把它们收掉。
 */
async function removeProductIfExists(page, productId) {
  if (!productId) return
  await page.goto(`${ADMIN}/product`)
  await page.waitForTimeout(1200)
  const row = tid(page, `product-row-${productId}`)
  if (!(await row.count())) return
  await row.getByTestId('product-row-delete').click()
  await confirmDanger(page, { willDo: '将删除商品' })
  await expect(page.locator('.el-message--success').first(), '清理一次性商品应成功').toBeVisible({ timeout: 15_000 })
  await expect(tid(page, `product-row-${productId}`), '清理后列表里不应再有这件一次性商品').toHaveCount(0)
}

test.describe('商家侧 7 SKU 编辑并发冲突', () => {
  test('两个会话同时编辑同一 SKU：甲改价生效，乙被 409 拦住，刷新后重来才写入', async ({ browser, page, request }) => {
    const seed = stamp()
    const name0 = productNameOf(seed)
    /** 规格值只用来「看起来像真的」，定位一律走 sku-row-<id>，不靠这段文本 */
    const specValue = attributeValues[2]

    // 会话乙 = 本用例的 page fixture；会话甲 = 另起一个 browser context（独立 storageState）
    const pageB = page
    const ctxA = await browser.newContext()
    const pageA = await ctxA.newPage()

    let productId = null
    let skuId = null
    try {
      // ---------- ① 两个独立的登录会话 ----------
      await adminLogin(pageB)
      await adminLogin(pageA)
      const tokenA = await adminTokenOf(pageA)
      const tokenB = await adminTokenOf(pageB)
      expect(tokenA, '甲的会话应拿到自己的令牌').toBeTruthy()
      expect(tokenB, '乙的会话应拿到自己的令牌').toBeTruthy()
      // 「storageState 不共享」的实证：在甲里写一个标记，乙里必须读不到
      await pageA.evaluate(() => localStorage.setItem('concurrency-probe', 'only-in-context-A'))
      expect(
        await pageB.evaluate(() => localStorage.getItem('concurrency-probe')),
        '两个 browser context 的 localStorage 必须互不可见（否则就不是两个会话）'
      ).toBeNull()

      // ---------- 准备：界面上新建一件一次性商品 + 1 个 SKU ----------
      await openProducts(pageB)
      await addProduct(pageB, {
        name: name0,
        categoryName: CATEGORY,
        typeName: TYPE,
        description: productDescriptionOf(seed)
      })
      // 之后一律按 id 定位（同名商品在库里会累积，按文本取会取到旧那条）
      const created = newestByName(await getAdminProductsApi(request, tokenB, name0), name0)
      expect(created, `新商品「${name0}」应落库`).toBeTruthy()
      productId = created.id

      await openSkusById(pageB, productId)
      await addSku(pageB, { specs: [{ attrName: '颜色', value: specValue }], price: PRICE_0, stock: STOCK_0 })
      const skus0 = await getAdminSkusApi(request, tokenB, productId)
      expect(skus0.length, '新商品下应有 1 个 SKU').toBe(1)
      skuId = skus0[0].id
      expect(Number(skus0[0].price), '新建 SKU 的初始价格').toBe(PRICE_0)
      expect(skus0[0].version, '新建 SKU 的初始版本号应为 0').toBe(0)

      // ---------- 两人各自打开同一 SKU 的编辑弹窗，手上都是同一个 V=0 ----------
      const openSkuEdit = async (p) => {
        await openProducts(p)
        await openSkusById(p, productId)
        await tid(p, `sku-row-${skuId}`).getByTestId('sku-row-edit').click()
        const dlg = tid(p, 'sku-form-dialog')
        await expect(dlg, 'SKU 编辑弹窗应打开').toBeVisible()
        await expect(tid(p, 'sku-form-price'), '弹窗里应先显示库里的原价').toBeVisible()
        return dlg
      }
      const dialogA = await openSkuEdit(pageA)
      const priceInputA = tid(pageA, 'sku-form-price').locator('input').first()
      expect(await numberOf(priceInputA), '甲打开时看到的是原价').toBe(PRICE_0)

      const dialogB = await openSkuEdit(pageB)
      const priceInputB = tid(pageB, 'sku-form-price').locator('input').first()
      expect(await numberOf(priceInputB), '乙打开时看到的也是原价（两边都是同一份 V=0）').toBe(PRICE_0)

      // ---------- ② 甲先改价并保存 → 成功，version 0 → 1 ----------
      await priceInputA.fill(String(PRICE_A))
      const putA = pageA.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/skus/${skuId}`)
      )
      await tid(pageA, 'sku-form-submit').click()
      const resA = await putA
      expect(resA.status(), '甲的保存应成功（HTTP 200）').toBe(200)
      expect((await resA.json()).code, '甲保存的业务码应为 0').toBe(0)
      await expect(pageA.locator('.el-message--success').first(), '甲应看到「保存成功」').toBeVisible({ timeout: 15_000 })
      await expect(dialogA, '甲保存后弹窗应收起').toBeHidden({ timeout: 15_000 })

      const afterA = (await getAdminSkusApi(request, tokenB, productId)).find((s) => s.id === skuId)
      expect(Number(afterA.price), '库里价格应变成甲改的价').toBe(PRICE_A)
      expect(afterA.version, '甲的这次保存应把版本号推进到 1').toBe(1)

      // ---------- ③ 乙后保存（手里还是 version=0）→ 后端 409 拒绝 ----------
      await priceInputB.fill(String(PRICE_B))
      const putB = pageB.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/skus/${skuId}`)
      )
      await tid(pageB, 'sku-form-submit').click()
      const resB = await putB
      const bodyB = await resB.json()
      expect(resB.status(), '乙的保存必须被后端拒绝（真 HTTP 409，不是 200 + body 里说失败）').toBe(409)
      expect(bodyB.code, 'body 里的业务码也应是 409').toBe(409)
      expect(bodyB.message, '后端拒因应是那句中文原文').toBe('数据已被他人修改（可能其他人在编辑），请刷新后重试')

      // 证据：把这次 409 的状态码与响应体落盘（report/ 是产物目录，不进 git）
      mkdirSync(EVIDENCE_DIR, { recursive: true })
      writeFileSync(
        `${EVIDENCE_DIR}7-SKU编辑并发冲突-乙409响应.json`,
        JSON.stringify(
          {
            capturedAt: new Date().toISOString(),
            request: { method: 'PUT', path: `/admin/skus/${skuId}`, sentVersion: 0, sentPrice: PRICE_B },
            response: { status: resB.status(), body: bodyB }
          },
          null,
          2
        ) + '\n'
      )

      // 冲突弹窗：说清「数据已被他人修改」+ 给「刷新」按钮（不能只用 2 秒就消失的 toast 打发）
      const conflict = conflictDialog(pageB)
      await expect(conflict, '应弹出「数据已被他人修改」的冲突弹窗').toBeVisible({ timeout: 15_000 })
      await expect(pageB.getByTestId('conflict-message'), '弹窗主文案应说明被别人改过')
        .toContainText('数据已被他人修改（可能其他人在编辑）')
      await expect(pageB.getByTestId('conflict-hint'), '弹窗应说明刚才填的内容没被保存')
        .toContainText('你刚才填写的内容没有被保存')
      const refreshBtn = conflictRefresh(pageB)
      await expect(refreshBtn, '冲突弹窗里必须有「刷新」按钮').toBeVisible()
      await expect(refreshBtn, '冲突弹窗的按钮文案应是「刷新」').toHaveText('刷新')
      await pageB.screenshot({ path: `${EVIDENCE_DIR}7-SKU编辑并发冲突-乙409弹窗.png`, fullPage: false })

      // ---------- ④ 乙的这次提交一个字段都没写进去：库里仍是甲的价 ----------
      const afterB = (await getAdminSkusApi(request, tokenB, productId)).find((s) => s.id === skuId)
      expect(Number(afterB.price), '乙没有覆盖甲：库里价格应仍是甲改的价').toBe(PRICE_A)
      expect(afterB.version, '被拒的那次保存不应推进版本号（仍是 1）').toBe(1)

      // ---------- ⑤ 乙点「刷新」→ 表单重填成甲的价 → 再改一次 → 保存成功，version 1 → 2 ----------
      await refreshBtn.click()
      await expect(conflict, '点「刷新」后冲突弹窗应收起').toBeHidden({ timeout: 15_000 })
      await expect(pageB.locator('.el-message--success').first(), '刷新后应提示已拉到最新数据')
        .toContainText('已刷新为最新数据')
      // SKU 弹窗的刷新是「重拉 GET /admin/skus/{id} 重填表单」，弹窗本身不关
      await expect(dialogB, '刷新后 SKU 编辑弹窗应仍然开着').toBeVisible({ timeout: 15_000 })
      expect(await numberOf(priceInputB), '表单应被重填成甲改后的价格').toBe(PRICE_A)

      await priceInputB.fill(String(PRICE_B))
      const putB2 = pageB.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/skus/${skuId}`)
      )
      await tid(pageB, 'sku-form-submit').click()
      const resB2 = await putB2
      expect(resB2.status(), '乙刷新后重新保存应成功（HTTP 200）').toBe(200)
      expect((await resB2.json()).code, '这次保存的业务码应为 0').toBe(0)
      await expect(pageB.locator('.el-message--success').first(), '乙应看到「保存成功」').toBeVisible({ timeout: 15_000 })
      await expect(dialogB, '乙保存后弹窗应收起').toBeHidden({ timeout: 15_000 })

      const final = (await getAdminSkusApi(request, tokenB, productId)).find((s) => s.id === skuId)
      expect(Number(final.price), '库里价格应变成乙改的价').toBe(PRICE_B)
      expect(final.version, '乙的这次保存应把版本号再推到 2').toBe(2)
      expect(final.stock, '价格并发全程不该动库存').toBe(STOCK_0)
    } finally {
      // 无论成败都清掉这件一次性商品（连 SKU 一起级联删除）：同一份库连跑两轮也不留残留
      await removeProductIfExists(pageB, productId).catch(() => {})
      await ctxA.close()
    }
  })
})
