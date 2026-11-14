import { test, expect } from '../../../support/test.js'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { API, stamp } from '../../../support/env.js'
import { adminLogin } from '../../../support/ui.js'
import {
  adminLoginHardened,
  gotoOrderList,
  createOrderViaUi,
  searchOrderRow,
  auditPassOrder,
  openOrderDrawer,
  deliveryRowBySn,
  clickDeliveryAction,
  pickWarehouseExcluding,
  wmsCallback,
  deliveryDialog,
  dangerDialog,
  confirmDanger,
  expectToastSuccess
} from '../../../support/admin-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'
import { receiverOf } from '../../../support/fixtures.js'

/**
 * 并发与竞态 / 双向竞态 / 发货单与仓库回传
 *
 * 真实世界的撞车：商家正在「编辑包裹（改发货仓）」的弹窗里犹豫，仓库那边已经把同一个包裹
 * 下发并回传了「出库」。商家这时点「确定」——必须被拒，而且**改仓一个字段都没写进去**。
 *
 * 并发怎么构造的（两个独立 browser context，两套令牌）：
 *   · 甲（商家）= 本用例 page fixture：打开订单详情抽屉 → 该包裹「编辑」弹窗 → 选好另一个仓，**停住不提交**
 *   · 乙（仓库/另一位操作员）= 另起 context：同一个包裹「下发」→「模拟WMS回传：出库」→ 包裹变已出库
 *   · 甲再提交 → 被拒；拒绝原因来自服务端，前端把它留在表单里（`delivery-form-preview-error`）
 *
 * 拒绝原因按**实际返回**断言（不写死）：编辑是「预演 → 确认 → 执行」两段式，
 *   PUT /admin/deliveries/{id}?dryRun=true 的预演阶段就会跑业务守卫
 *   `requirePending`（DeliveryEditService）—— 包裹已经出库，预演直接失败；
 *   所以这里拿到的是 **HTTP 200 + code=1 + 「只有待下发包裹可以编辑（当前状态 3；已下发的包裹不能编辑，取消入口只在待下发时可用，已下发后的状态只能由 WMS 回传推进）」**。
 *   （409 是「版本号对不上」那条路，它在执行阶段才会发生；本次状态守卫先一步拦下，
 *    因此断言按实际写 code=1，同时把 body 原样落盘，不假装是 409。）
 *
 * 关键不变量：**改仓没有生效** —— 库里该包裹的 warehouseId 还是原仓，状态是已出库，
 * 来源仍是自动拆单（source=0，人工改过的包会是 1）。
 */

/** 证据落盘目录（report/ 已被 e2e/.gitignore 忽略，属于产物不是源码） */
const EVIDENCE_DIR = fileURLToPath(new URL('../../../../report/shots/', import.meta.url))

const deliveryOf = async (request, token, orderId, deliveryId) =>
  (await listDeliveriesApi(request, token, orderId)).find((d) => d.id === deliveryId)

test.describe('并发与竞态 › 双向竞态 › 发货单与仓库回传', () => {
  test('编辑弹窗开着时包裹被仓库存回传出库：提交被状态守卫拒绝，改仓没生效', async ({ browser, page, request }) => {
    test.setTimeout(180_000)
    // 会话甲（商家）= page fixture；会话乙（仓库/另一位操作员）= 另起 context
    const pageA = page
    const ctxB = await browser.newContext()
    const pageB = await ctxB.newPage()

    let orderSn = null
    let orderId = null
    try {
      await adminLoginHardened(pageA)
      await adminLogin(pageB)
      const tokenA = await adminTokenOf(pageA)
      const tokenB = await adminTokenOf(pageB)
      expect(tokenA, '甲的会话应拿到自己的令牌').toBeTruthy()
      expect(tokenB, '乙的会话应拿到自己的令牌').toBeTruthy()

      // ---------- 准备：造一笔待审核订单 → 审核通过 → 1 个待下发包裹 ----------
      const { product } = await getProductApi(request, 1)
      await gotoOrderList(pageA)
      orderSn = await createOrderViaUi(pageA, {
        receiver: receiverOf(`wms-race-${stamp()}`),
        status: '待审核',
        items: [{ productName: product.name, skuIndex: 0, quantity: 1 }]
      })
      await auditPassOrder(pageA, await searchOrderRow(pageA, orderSn))
      const order = await getAdminOrderBySnApi(request, tokenA, orderSn)
      orderId = order.id
      expect(order.status, '审核通过后订单应为待发货(8)').toBe(8)
      const deliveries0 = await listDeliveriesApi(request, tokenA, orderId)
      expect(deliveries0.length, '这笔订单应只有 1 个待下发包裹（并发就撞这一个）').toBe(1)
      const pkg0 = deliveries0[0]
      expect(pkg0.status, '包裹应为待下发(0)').toBe(0)
      const version0 = pkg0.version
      const originalWhName = pkg0.warehouseName
      const originalWhId = pkg0.warehouseId

      // ---------- ① 甲打开该包裹的「编辑」弹窗，选好另一个仓，停在这里不提交 ----------
      // 「改到另一个仓」得先确认库里有第二个仓（只读 GET，不是用接口推进业务状态）
      const warehouses = (await (await request.get(`${API}/admin/warehouses`, {
        headers: { Authorization: `Bearer ${tokenA}` }
      })).json()).data || []
      expect(warehouses.length, '至少要 2 个仓库，才能演示「改到另一个仓」').toBeGreaterThanOrEqual(2)

      const table = await openOrderDrawer(pageA, await searchOrderRow(pageA, orderSn))
      await clickDeliveryAction(pageA, deliveryRowBySn(table, pkg0.deliverySn), '编辑')
      const dlg = deliveryDialog(pageA)
      await expect(dlg, '甲的编辑弹窗应打开').toBeVisible()
      await expect(tid(pageA, 'delivery-form-warehouse'), '弹窗里应带回当前发货仓')
        .toContainText(originalWhName)
      const pickedWh = await pickWarehouseExcluding(pageA, [originalWhName])
      expect(pickedWh, '新选的仓不能还是原仓').not.toBe(originalWhName)
      await expect(tid(pageA, 'delivery-form-warehouse'), '发货仓应已改成新选的仓').toContainText(pickedWh)

      // ---------- ② 乙（另一个会话）把同一包裹下发 → 回传「出库」，推成已出库 ----------
      await gotoOrderList(pageB)                       // 乙是另一个会话，自己从订单列表进来搜这笔单
      const tableB = await openOrderDrawer(pageB, await searchOrderRow(pageB, orderSn))
      const rowB = deliveryRowBySn(tableB, pkg0.deliverySn)
      await clickDeliveryAction(pageB, rowB, '下发')
      await expect(rowB, '乙下发后包裹应为已下发').toContainText('已下发')
      await wmsCallback(pageB, rowB, '出库')
      await expect(rowB, '乙回传出库后包裹应为已出库').toContainText('已出库')

      const moved = await deliveryOf(request, tokenA, orderId, pkg0.id)
      expect(moved.status, '并发方推送后包裹应为已出库(3)').toBe(3)
      expect(moved.version, '下发 + 出库各写一次 → 版本号应比甲手里的 V 大 2').toBe(version0 + 2)
      expect(moved.warehouseId, '并发方只推进状态，没动仓库').toBe(originalWhId)
      expect(moved.trackNo, '出库应写入运单号').toMatch(/SF\d{6,}/)

      // ---------- ③ 甲这时点「确定」→ 预演被业务状态守卫拒绝 ----------
      const previewResp = pageA.waitForResponse(
        (r) => r.request().method() === 'PUT'
          && r.url().includes(`/admin/deliveries/${pkg0.id}`)
          && r.url().includes('dryRun'),
        { timeout: 20_000 }
      )
      await tid(pageA, 'delivery-form-submit').click()
      const resp = await previewResp
      const body = await resp.json()
      expect(resp.status(), '预演请求本身是 HTTP 200（业务失败用 code 表达）').toBe(200)
      expect(body.code, '业务守卫拒绝的业务码是 1').toBe(1)
      expect(body.message, '守卫拒因应是服务端原话（当前状态=已出库=3）')
        .toBe('只有待下发包裹可以编辑（当前状态 3；已下发的包裹不能编辑，取消入口只在待下发时可用，已下发后的状态只能由 WMS 回传推进）')

      // 证据：把甲这次的真实响应体落盘
      mkdirSync(EVIDENCE_DIR, { recursive: true })
      writeFileSync(
        `${EVIDENCE_DIR}6-发货单与仓库回传并发-甲被拒响应.json`,
        JSON.stringify(
          {
            capturedAt: new Date().toISOString(),
            request: {
              method: 'PUT',
              path: `/admin/deliveries/${pkg0.id}?dryRun=true`,
              heldVersion: version0,
              pickedWarehouse: pickedWh,
              originalWarehouse: originalWhName
            },
            response: { status: resp.status(), body }
          },
          null,
          2
        ) + '\n'
      )

      // ---------- ④ 拒绝原因可读：留在表单里（不是一闪而过的 toast），已填的仓不丢 ----------
      const inlineError = tid(pageA, 'delivery-form-preview-error')
      await expect(inlineError, '预演失败的原因应显示在编辑表单里').toBeVisible({ timeout: 20_000 })
      await expect(inlineError, '表单里的原因应是服务端原话')
        .toHaveText('预演失败：只有待下发包裹可以编辑（当前状态 3；已下发的包裹不能编辑，取消入口只在待下发时可用，已下发后的状态只能由 WMS 回传推进）')
      await expect(dlg, '预演失败时编辑表单不应收起').toBeVisible()
      await expect(tid(pageA, 'delivery-form-warehouse'), '刚选的仓不能被重置')
        .toContainText(pickedWh)
      await expect(dangerDialog(pageA), '预演失败后确认弹窗应收起').toBeHidden({ timeout: 10_000 })
      await pageA.screenshot({ path: `${EVIDENCE_DIR}6-发货单与仓库回传并发-甲提交被拒.png`, fullPage: false })

      // ---------- ⑤ 不变量：改仓没有生效（仓库、状态、来源全都还是原来的） ----------
      const final = await deliveryOf(request, tokenA, orderId, pkg0.id)
      expect(final.warehouseId, '改仓没有生效：库里仓库仍是原仓').toBe(originalWhId)
      expect(final.warehouseName, '库里仓库名也仍是原仓名').toBe(originalWhName)
      expect(final.status, '包裹仍是已出库(3)').toBe(3)
      expect(final.source, '没被写成人工调整：来源仍是自动拆单(0)').toBe(0)
      expect(
        (await listDeliveriesApi(request, tokenA, orderId)).length,
        '全程没有多出/少掉包裹'
      ).toBe(1)

      // 收尾：关掉停在原地的编辑表单（数据已是已出库，只能取消）
      await tid(pageA, 'delivery-form-cancel').click()
      await expect(dlg).toBeHidden({ timeout: 10_000 })
    } finally {
      // 清掉这笔一次性订单（级联删明细/发货单）：同一份库连跑两轮也不留残留
      await (async () => {
        if (!orderSn) return
        await gotoOrderList(pageA)
        const row = await searchOrderRow(pageA, orderSn)
        await row.getByTestId('order-row-delete').click()
        await confirmDanger(pageA, { willDo: '将删除订单' })
        await expectToastSuccess(pageA, '订单已删除')
        expect(await getAdminOrderBySnApi(request, await adminTokenOf(pageA), orderSn), '订单应已被删除')
          .toBeUndefined()
      })().catch(() => {})
      await ctxB.close()
    }
  })
})
