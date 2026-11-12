import { test, expect } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ADMIN, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import { openProducts, addProduct } from '../../support/catalog-ui.js'
import { conflictDialog, conflictRefresh, confirmDanger } from '../../support/admin-ui.js'
import { adminTokenOf, getAdminProductsApi } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { newestByName, productDescriptionOf, productNameOf } from '../../support/fixtures.js'

/**
 * 商家侧 02-商品管理 / 6-商品编辑并发冲突
 *
 * 对应时序图：docs/flows/02-业务场景/02-商家-商品管理/6-商品编辑并发冲突/1-编辑并发冲突时序图.svg
 *
 * 一个场景，五步，全程真实 UI 点击 + 真实后端（不 mock）：
 *   ① 两个**独立 browser context**（两套 localStorage / 两个 token）先后打开同一件商品的编辑弹窗，
 *      两边拿到的都是同一个版本号 V
 *   ② 会话甲保存 → 成功，库里 version 由 V 变 V+1
 *   ③ 会话乙保存（手里还是 V）→ 后端 409 拒绝 → 弹「数据已被他人修改（可能其他人在编辑）」+「刷新」
 *   ④ 库里商品名仍是甲改的值（乙那次一个字段都没写进去，没有覆盖甲）
 *   ⑤ 乙点「刷新」→ 表单被重填成甲改后的值 → 乙再改一次 → 保存成功，version 再 +1
 *
 * 三条硬约束（踩过的坑）：
 *   · 定位一律用 id（`product-row-<id>` + editProduct 走 catalog-ui 的行锚点），不用「列表第一项」——
 *     种子商品会因为用例不停新建而被挤到后面，按序号/文本取必翻车。
 *   · 用例自给自足：自己新建一件一次性商品（名字带唯一后缀），跑完删掉；同一份库连跑两轮都不留残留。
 *   · 断言不放宽：版本号断言具体数字（0→1→2），商品名断言具体字符串，409 断言 HTTP 状态码 + body.code
 *     + 后端中文原文，并把响应体落到 e2e/report/shots/ 里当证据。
 */

/** 与 3-商品维护.spec.js 一致的演示口径（家居类商品挂家居类目，画面才合理） */
const CATEGORY = '家具'
const TYPE = '食品家居'

/**
 * 管理员口令：e2e 独立库仍是种子里的演示弱口令（ui.js 的默认值），
 * 而容器栈（测试环境，录片用的那套）打完 scripts/set-admin-password.sh 的补丁后是强口令。
 * 所以这里只覆盖口令来源，不改 ui.js 的默认行为：
 *   验收：bash scripts/e2e-run.sh <spec>                      （不传，用默认值）
 *   录片：ADMIN_PASSWORD=… bash scripts/demo-run.sh <spec>    （由调用方从密钥文件读入，不落盘不回显）
 */
const ADMIN_USER = process.env.ADMIN_USER || 'admin'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '123456'

/** 409 证据落盘目录（report/ 已被 e2e/.gitignore 忽略，属于产物不是源码） */
const EVIDENCE_DIR = fileURLToPath(new URL('../../../report/shots/', import.meta.url))

/** 造一个「好看且一定不等于 taken 里任何一个」的商品名（三个名字必须两两不同，断言才咬得住） */
function distinctName(seedStr, taken = []) {
  let name = productNameOf(seedStr)
  for (let i = 1; taken.includes(name); i++) name = productNameOf(`${seedStr}-${i}`)
  return name
}

/**
 * 清掉本次建的一次性商品（在真实界面里走「删除 → 预演确认弹窗 → 确认」）。
 * 先 goto 列表页：即使中途失败时页面上还挂着弹窗/浮层，导航也能把它们收掉。
 */
async function removeProductIfExists(page, request, token, productId, name) {
  if (!productId) return
  await page.goto(`${ADMIN}/product`)
  await page.waitForTimeout(1200)
  const row = tid(page, `product-row-${productId}`)
  if (!(await row.count())) return
  await row.getByTestId('product-row-delete').click()
  await confirmDanger(page, { willDo: '将删除商品' })
  await expect(page.locator('.el-message--success').first(), '清理一次性商品应成功').toBeVisible({ timeout: 15_000 })
  await expect(tid(page, `product-row-${productId}`), `清理后列表里不应再有「${name}」`).toHaveCount(0)
}

test.describe('商家侧 6 商品编辑并发冲突', () => {
  test('两个会话同时编辑同一商品：甲保存生效，乙被 409 拦住，刷新后重来才写入', async ({ browser, page, request }) => {
    const seed = stamp()
    // 三个名字两两不同：原始名 / 甲改成的名字 / 乙改成的名字 —— 「库里到底是谁的值」才能断言
    const name0 = productNameOf(seed)
    const nameA = distinctName(`${seed}-jia`, [name0])
    const nameB = distinctName(`${seed}-yi`, [name0, nameA])

    // 会话乙 = 本用例的 page fixture（config 里的录制只作用于它的 context → 视频里就是乙的视角）
    const pageB = page
    // 会话甲 = 另起一个 browser context：独立的 storageState，等于另一个人用另一台机器登录
    const ctxA = await browser.newContext()
    const pageA = await ctxA.newPage()

    let productId = null
    let created = null
    try {
      // ---------- ① 两个独立的登录会话 ----------
      // 先登乙（录制窗口）再登甲：视频一开场就是真实登录页，而不是浏览器白屏
      await adminLogin(pageB, ADMIN_USER, ADMIN_PASSWORD)
      await adminLogin(pageA, ADMIN_USER, ADMIN_PASSWORD)
      const tokenA = await adminTokenOf(pageA)
      const tokenB = await adminTokenOf(pageB)
      // JWT 无状态：同一个 admin 账号登两次各自拿到自己的令牌，两个 context 各存一份
      expect(tokenA, '甲的会话应拿到自己的令牌').toBeTruthy()
      expect(tokenB, '乙的会话应拿到自己的令牌').toBeTruthy()
      // 「storageState 不共享」的实证：在甲里写一个标记，乙里必须读不到
      await pageA.evaluate(() => localStorage.setItem('concurrency-probe', 'only-in-context-A'))
      expect(
        await pageB.evaluate(() => localStorage.getItem('concurrency-probe')),
        '两个 browser context 的 localStorage 必须互不可见（否则就不是两个会话）'
      ).toBeNull()
      expect(JSON.stringify(await ctxA.storageState()), '甲的 storageState 里应有自己的令牌').toContain(tokenA)
      expect(JSON.stringify(await pageB.context().storageState()), '乙的 storageState 里应有自己的令牌').toContain(tokenB)

      // ---------- 准备：在界面里新建一件一次性商品（乙操作，画面里看得见） ----------
      await openProducts(pageB)
      await addProduct(pageB, {
        name: name0,
        categoryName: CATEGORY,
        typeName: TYPE,
        description: productDescriptionOf(seed)
      })
      // 之后一律按 id 定位（同名商品在库里会累积，按文本取会取到旧那条）
      created = newestByName(await getAdminProductsApi(request, tokenB, name0), name0)
      expect(created, `新商品「${name0}」应落库`).toBeTruthy()
      productId = created.id
      expect(created.version, '新建商品的初始版本号应为 0').toBe(0)
      expect(created.name, '新建商品的名字应是 name0').toBe(name0)

      // ---------- 两人各自打开同一件商品的编辑弹窗，手上都是同一个 V=0 ----------
      await openProducts(pageA)
      await tid(pageA, `product-row-${productId}`).getByTestId('product-row-edit').click()
      const dialogA = tid(pageA, 'product-form-dialog')
      await expect(dialogA, '甲的编辑弹窗应打开').toBeVisible()
      await expect(tid(pageA, 'product-form-name'), '甲打开时看到的应是原始名字').toHaveValue(name0)

      await tid(pageB, `product-row-${productId}`).getByTestId('product-row-edit').click()
      const dialogB = tid(pageB, 'product-form-dialog')
      await expect(dialogB, '乙的编辑弹窗应打开').toBeVisible()
      await expect(tid(pageB, 'product-form-name'), '乙打开时看到的也应是原始名字（两边都是同一份 V）').toHaveValue(name0)

      // ---------- ② 甲先改并保存 → 成功，version 0 → 1 ----------
      await tid(pageA, 'product-form-name').fill(nameA)
      const putA = pageA.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/products/${productId}`)
      )
      await tid(pageA, 'product-form-submit').click()
      const resA = await putA
      expect(resA.status(), '甲的保存应成功（HTTP 200）').toBe(200)
      expect((await resA.json()).code, '甲保存的业务码应为 0').toBe(0)
      await expect(pageA.locator('.el-message--success').first(), '甲应看到「保存成功」').toBeVisible({ timeout: 15_000 })
      await expect(dialogA, '甲保存后弹窗应收起').toBeHidden({ timeout: 15_000 })

      const afterA = (await getAdminProductsApi(request, tokenB)).find((p) => p.id === productId)
      expect(afterA.name, '库里商品名应变成甲改的名字').toBe(nameA)
      expect(afterA.version, '甲的这次保存应把版本号推进到 1').toBe(1)

      // ---------- ③ 乙后保存（手里还是 version=0）→ 后端 409 拒绝 ----------
      await tid(pageB, 'product-form-name').fill(nameB)
      const putB = pageB.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/products/${productId}`)
      )
      await tid(pageB, 'product-form-submit').click()
      const resB = await putB
      const bodyB = await resB.json()
      expect(resB.status(), '乙的保存必须被后端拒绝（真 HTTP 409，不是 200 + body 里说失败）').toBe(409)
      expect(bodyB.code, 'body 里的业务码也应是 409').toBe(409)
      expect(bodyB.message, '后端拒因应是那句中文原文').toBe('数据已被他人修改（可能其他人在编辑），请刷新后重试')

      // 证据：把这次 409 的状态码与响应体落盘（report/ 是产物目录，不进 git）
      mkdirSync(EVIDENCE_DIR, { recursive: true })
      writeFileSync(
        `${EVIDENCE_DIR}6-商品编辑并发冲突-乙409响应.json`,
        JSON.stringify(
          {
            capturedAt: new Date().toISOString(),
            request: { method: 'PUT', path: `/admin/products/${productId}`, sentVersion: 0, sentName: nameB },
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
      // 让这一帧在视频里留得住（409 弹窗 + 刷新按钮是本场景的关键画面）
      await pageB.waitForTimeout(1500)
      await pageB.screenshot({ path: `${EVIDENCE_DIR}6-商品编辑并发冲突-乙409弹窗.png`, fullPage: false })

      // ---------- ④ 乙的这次提交一个字段都没写进去：库里仍是甲的值 ----------
      const afterB = (await getAdminProductsApi(request, tokenB)).find((p) => p.id === productId)
      expect(afterB.name, '乙没有覆盖甲：库里商品名应仍是甲改的值').toBe(nameA)
      expect(afterB.version, '被拒的那次保存不应推进版本号（仍是 1）').toBe(1)

      // ---------- ⑤ 乙点「刷新」→ 表单重填成甲的值 → 再改一次 → 保存成功，version 1 → 2 ----------
      await refreshBtn.click()
      await expect(conflict, '点「刷新」后冲突弹窗应收起').toBeHidden({ timeout: 15_000 })
      await expect(pageB.locator('.el-message--success').first(), '刷新后应提示已拉到最新数据')
        .toContainText('已刷新为最新数据')
      await expect(tid(pageB, 'product-form-name'), '表单应被重填成甲改后的值').toHaveValue(nameA)

      await tid(pageB, 'product-form-name').fill(nameB)
      const putB2 = pageB.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/products/${productId}`)
      )
      await tid(pageB, 'product-form-submit').click()
      const resB2 = await putB2
      expect(resB2.status(), '乙刷新后重新保存应成功（HTTP 200）').toBe(200)
      expect((await resB2.json()).code, '这次保存的业务码应为 0').toBe(0)
      await expect(pageB.locator('.el-message--success').first(), '乙应看到「保存成功」').toBeVisible({ timeout: 15_000 })
      await expect(dialogB, '乙保存后弹窗应收起').toBeHidden({ timeout: 15_000 })

      const final = (await getAdminProductsApi(request, tokenB)).find((p) => p.id === productId)
      expect(final.name, '库里商品名应变成乙改的名字').toBe(nameB)
      expect(final.version, '乙的这次保存应把版本号再推到 2').toBe(2)
    } finally {
      // 无论成败都清掉这件一次性商品：同一份库连跑两轮（第二轮不 reset）也不留残留
      await removeProductIfExists(pageB, request, await adminTokenOf(pageB), productId, nameB).catch(() => {})
      await ctxA.close()
    }
  })
})
