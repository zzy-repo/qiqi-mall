import { test, expect } from '../../../support/test.js'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { stamp } from '../../../support/env.js'
import { adminLogin } from '../../../support/ui.js'
import {
  adminLoginHardened,
  gotoOrderList,
  createOrderViaUi,
  searchOrderRow,
  orderStatusOf,
  dangerDialog,
  dangerError,
  dangerOk,
  waitDanger,
  dismissDanger,
  confirmDanger
} from '../../../support/admin-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../../support/assert.js'
import { receiverOf } from '../../../support/fixtures.js'

/**
 * 并发与竞态 / 状态竞态 / 订单审核并发
 *
 * 两个管理员**同时**审核同一笔待审核订单：甲点「确认通过」成功，乙随后点「确认通过」被服务端拦下。
 *
 * ⚠️ 这条走的是**业务状态守卫**，不是 409：
 *   OrderController#auditPass 的第一道闸是 `status != 7 → Result.fail("只有待审核订单可以审核通过")`
 *   —— 返回的是 **HTTP 200 + code=1 + 中文原因**（不是乐观锁的 409）。
 *   所以断言按实际返回写：HTTP 200、body.code=1、body.message 是那句守卫原文，
 *   前端把原因留在确认弹窗的 `danger-confirm-error` 里（弹窗不关）。
 *   409 只会在「读到 status=7 之后、写回之前」的极窄窗口出现，不是这条主路径的稳定结果，
 *   因此这里**不**断言 409（写死成 409 就是假验收）。
 *
 * 并发是怎么构造出来的（这是本用例的关键，不是「点两次按钮」）：
 *   两个 browser context 各自打开同一笔订单的审核确认弹窗 —— 点行内「审核通过」就会跑一次
 *   预演（POST /admin/orders/{id}/split-preview），**两边都拿到「待审核→待发货」的预演结果**，
 *   证明「两个人都是在订单还是待审核时点进来的」。然后才让甲执行、乙执行，
 *   这才是真实世界里两个人同时看着同一张单的场景。
 *
 * 关键不变量（钱和货的账都在这里）：
 *   · 订单只推进一次：状态 7 → 8，version 只 +1（乙被拒时没有第二个 update）
 *   · 发货单只生成 1 组：乙那次被拒之后，订单下的发货单仍然是甲那次生成的 1 组
 *   · 乙的 UI 上有明确失败提示（弹窗里的中文原因），不是静默失败
 */

/** 证据落盘目录（report/ 已被 e2e/.gitignore 忽略，属于产物不是源码） */
const EVIDENCE_DIR = fileURLToPath(new URL('../../../../report/shots/', import.meta.url))

/** 打开审核确认弹窗并等预演就绪（willDo 说明「待审核→待发货」+ 拆单方案已填进明细区） */
async function openAuditDialog(page, orderSn) {
  // 两个会话各自从订单列表进：先 goto，再按订单号搜（列表行没有行锚点，助手函数内部按订单号过滤）
  await gotoOrderList(page)
  const row = await searchOrderRow(page, orderSn)
  await expect(row.getByTestId('order-row-audit-pass'), '待审核订单应有「审核通过」入口').toBeVisible()
  const previewResp = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/admin/orders/') && r.url().includes('/split-preview'),
    { timeout: 20_000 }
  )
  await row.getByTestId('order-row-audit-pass').click()
  await waitDanger(page, { willDo: '待审核→待发货' })
  const preview = await previewResp
  return { row, previewStatus: preview.status(), previewBody: await preview.json() }
}

/** 点「确认执行」并拿到这一次真正发出去的执行请求的响应（不管成功还是被守卫拦下） */
async function confirmAndCapture(page, orderId) {
  const execResp = page.waitForResponse(
    (r) => r.request().method() === 'POST'
      && r.url().includes(`/admin/orders/${orderId}/audit-pass`)
      && !r.url().includes('dryRun'),
    { timeout: 20_000 }
  )
  await dangerOk(page).click()
  const resp = await execResp
  return { status: resp.status(), body: await resp.json() }
}

test.describe('并发与竞态 › 状态竞态 › 订单审核并发', () => {
  test('两个会话同时审核同一笔待审核订单：甲通过，乙被业务守卫拦下，订单只推进一次、发货单只 1 组', async ({ browser, page, request }) => {
    // 两个浏览器会话 + 多次页面往返，给足时间（不是靠重试掩盖问题：失败仍然会红）
    test.setTimeout(180_000)
    // 会话甲 = 本用例的 page fixture；会话乙 = 另起一个 browser context（独立 storageState）
    const pageA = page
    const ctxB = await browser.newContext()
    const pageB = await ctxB.newPage()

    let orderId = null
    let orderSn = null
    try {
      await adminLoginHardened(pageA)
      await adminLogin(pageB)
      const tokenA = await adminTokenOf(pageA)
      const tokenB = await adminTokenOf(pageB)
      expect(tokenA, '甲的会话应拿到自己的令牌').toBeTruthy()
      expect(tokenB, '乙的会话应拿到自己的令牌').toBeTruthy()

      // ---------- 准备：界面上造一笔待审核订单（数据自给自足，不依赖种子订单） ----------
      const { product } = await getProductApi(request, 1)
      await gotoOrderList(pageB)
      orderSn = await createOrderViaUi(pageB, {
        receiver: receiverOf(`audit-race-${stamp()}`),
        status: '待审核',
        items: [{ productName: product.name, skuIndex: 0, quantity: 1 }]
      })
      const order0 = await getAdminOrderBySnApi(request, tokenB, orderSn)
      expect(order0, `新订单 ${orderSn} 应落库`).toBeTruthy()
      orderId = order0.id
      expect(order0.status, '新订单应为待审核(7)').toBe(7)
      const version0 = order0.version
      expect((await listDeliveriesApi(request, tokenB, orderId)).length, '待审核订单不该有发货单').toBe(0)

      // ---------- ① 两个会话各自打开同一笔订单的审核弹窗（预演都成功 = 都是在「待审核」时进来的） ----------
      const a = await openAuditDialog(pageA, orderSn)
      expect(a.previewStatus, '甲的预演请求应 HTTP 200').toBe(200)
      expect(a.previewBody.code, '甲的预演业务码应为 0').toBe(0)
      expect(a.previewBody.data.willDo, '甲的预演应说明「待审核→待发货」').toContain('待审核→待发货')

      const b = await openAuditDialog(pageB, orderSn)
      expect(b.previewStatus, '乙的预演请求也应 HTTP 200').toBe(200)
      expect(b.previewBody.code, '乙的预演业务码也应为 0（订单此刻还是待审核）').toBe(0)
      expect(b.previewBody.data.willDo, '乙的预演同样应说明「待审核→待发货」').toContain('待审核→待发货')
      // 两个 context 的 localStorage 互不可见 → 确实是两个独立会话
      await pageA.evaluate(() => localStorage.setItem('concurrency-probe', 'only-in-context-A'))
      expect(
        await pageB.evaluate(() => localStorage.getItem('concurrency-probe')),
        '两个 browser context 的 localStorage 必须互不可见（否则就不是两个会话）'
      ).toBeNull()

      // ---------- ② 甲先执行 → 成功：订单 7→8，生成 1 组发货单 ----------
      const resA = await confirmAndCapture(pageA, orderId)
      expect(resA.status, '甲的审核应成功（HTTP 200）').toBe(200)
      expect(resA.body.code, '甲审核的业务码应为 0').toBe(0)
      await expect(dangerDialog(pageA), '甲确认后弹窗应收起').toBeHidden({ timeout: 20_000 })
      await expect(pageA.locator('.el-message--success').first(), '甲应看到「审核通过，已生成发货单」')
        .toContainText('审核通过，已生成发货单')

      const afterA = await getAdminOrderBySnApi(request, tokenB, orderSn)
      expect(afterA.status, '甲审核后订单应为待发货(8)').toBe(8)
      // 审核通过这一笔事务里订单被写了两次：① 状态 7→8（OrderController#auditPass）
      // ② 拆单标记 split_status（SplitOrderService#split），所以版本号一次审核 +2（不是 +1）。
      // 这里断言具体数字，是为了让「乙被拒时**没有再写一次**」这个不变量有参照点（见下面 afterB）。
      expect(afterA.version, '甲的这次审核应把订单版本号推进 2（状态 + 拆单标记各一次写入）').toBe(version0 + 2)
      const versionAfterA = afterA.version
      const deliveriesAfterA = await listDeliveriesApi(request, tokenB, orderId)
      expect(deliveriesAfterA.length, '甲审核后应生成 1 组发货单').toBe(1)
      expect(deliveriesAfterA[0].status, '新发货单应为待下发(0)').toBe(0)
      const qtyAfterA = deliveriesAfterA[0].items.reduce((n, it) => n + it.quantity, 0)
      expect(qtyAfterA, '发货单里的件数应等于订单明细件数（1 件），没有多出一组').toBe(1)

      // ---------- ③ 乙随后执行（手里还是「待审核」那一帧）→ 被业务状态守卫拒绝 ----------
      await expect(dangerOk(pageB), '乙的确认按钮此刻仍可点（他的弹窗停在待审核那一帧）').toBeEnabled()
      const resB = await confirmAndCapture(pageB, orderId)
      // 业务状态守卫：HTTP 200 + code=1 + 中文原因（**不是** 409）
      expect(resB.status, '业务守卫拦截返回的是 HTTP 200（不是 409）').toBe(200)
      expect(resB.body.code, '守卫拒绝的业务码是 1').toBe(1)
      expect(resB.body.message, '守卫拒因应是后端那句原文').toBe('只有待审核订单可以审核通过')

      // 证据：把乙这次的真实响应体落盘
      mkdirSync(EVIDENCE_DIR, { recursive: true })
      writeFileSync(
        `${EVIDENCE_DIR}5-订单审核并发-乙响应.json`,
        JSON.stringify(
          {
            capturedAt: new Date().toISOString(),
            request: { method: 'POST', path: `/admin/orders/${orderId}/audit-pass` },
            response: { status: resB.status, body: resB.body }
          },
          null,
          2
        ) + '\n'
      )

      // 乙的 UI 必须有明确失败提示：原因留在确认弹窗里、弹窗不关（不是一闪而过的 toast）
      await expect(dangerError(pageB), '执行失败时弹窗里应显示中文原因（弹窗不关）')
        .toBeVisible({ timeout: 20_000 })
      await expect(dangerError(pageB), '弹窗里的原因应是服务端那句守卫原文')
        .toHaveText('只有待审核订单可以审核通过')
      await expect(dangerDialog(pageB), '执行失败时确认弹窗不应自动关闭').toBeVisible()
      await pageB.screenshot({ path: `${EVIDENCE_DIR}5-订单审核并发-乙被拒弹窗.png`, fullPage: false })

      // ---------- ④ 不变量：订单只推进一次，发货单只有 1 组，库里状态没被乙改动 ----------
      const afterB = await getAdminOrderBySnApi(request, tokenB, orderSn)
      expect(afterB.status, '乙被拒后订单仍应为待发货(8)').toBe(8)
      expect(afterB.version, '乙那次被拒不应再写一次订单（版本号与甲审核刚结束时完全一致）')
        .toBe(versionAfterA)
      const deliveriesAfterB = await listDeliveriesApi(request, tokenB, orderId)
      expect(deliveriesAfterB.length, '乙被拒后发货单仍只有 1 组（不能出现 2 组）').toBe(1)
      expect(deliveriesAfterB[0].deliverySn, '发货单号仍是甲那次生成的那一个')
        .toBe(deliveriesAfterA[0].deliverySn)

      // 乙刷新列表 → 看到的是甲推进后的真实状态（UI 不会停在假的「待审核」）
      await dismissDanger(pageB)
      const rowFresh = await searchOrderRow(pageB, orderSn)
      expect(await orderStatusOf(rowFresh), '乙刷新后列表应显示待发货').toContain('待发货')
    } finally {
      // 清掉这笔一次性订单（级联删明细/发货单）：同一份库连跑两轮也不留残留
      await (async () => {
        if (!orderSn) return
        await gotoOrderList(pageB)
        const row = await searchOrderRow(pageB, orderSn)
        await row.getByTestId('order-row-delete').click()
        await confirmDanger(pageB, { willDo: '将删除订单' })
        await expect(pageB.locator('.el-message--success').first(), '清理一次性订单应成功')
          .toBeVisible({ timeout: 15_000 })
        expect(await getAdminOrderBySnApi(request, await adminTokenOf(pageB), orderSn), '订单应已被删除')
          .toBeUndefined()
      })().catch(() => {})
      await ctxB.close()
    }
  })
})
