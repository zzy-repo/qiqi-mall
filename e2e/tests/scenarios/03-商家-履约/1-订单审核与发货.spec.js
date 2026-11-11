import { test, expect } from '@playwright/test'
import { stamp } from '../../support/env.js'
import { memberRegisterAndLogin, addSpecToCart, submitCartOrder } from '../../support/ui.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  auditPassOrder,
  auditRejectOrder,
  openOrderDrawer,
  deliveryRowBySn,
  clickDeliveryAction,
  wmsCallback,
  orderStatusOf,
  expectToastSuccess
} from '../../support/admin-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../support/assert.js'

/**
 * 03-商家-履约 / 1-订单审核与发货（docs/flows/02-业务场景/03-商家-履约/1-订单审核与发货/1-业务时序图.mmd）
 *
 * 两个用例：
 *   用例一：买家下单支付 → 管理员审核通过（自动生成发货单）→ 下发 → 模拟 WMS 回传（拣货/出库/签收）
 *          → 订单状态按包裹聚合：已发货 → 已完成
 *   用例二：买家下单支付 → 管理员审核不通过 → 订单关闭 + 库存回补，且不生成发货单
 *
 * 为什么用买家真实下单而不是 admin「新增订单」：admin 新建订单**不扣库存**（只有 /
 * portal/orders 走 OrderTransactionalCreator 才扣），用 admin 建单会让「回补库存」断言失去意义。
 */
test.describe('03-商家-履约 › 1-订单审核与发货', () => {
  test('用例一：审核通过→下发→WMS回传→订单状态聚合', async ({ page, request }) => {
    let skuId
    let specText
    let stockBefore

    await test.step('① 管理员登录并取基准数据', async () => {
      await adminLoginHardened(page)
      const { skus } = await getProductApi(request, 1)
      skuId = skus[0].id
      specText = skus[0].specText
      stockBefore = skus[0].stock
    })

    let orderSn
    await test.step('② 买家下单支付（下单即扣 1 件库存，订单进入待审核）', async () => {
      await memberRegisterAndLogin(page, `e2e_audit_${stamp()}`)
      await addSpecToCart(page, 1, specText)
      orderSn = await submitCartOrder(page, { receiverName: '审核发货E2E' })
    })

    await test.step('③ 管理员看到待审核订单，审核通过 → 待发货 + 生成发货单', async () => {
      await gotoOrderList(page)
      const row = await searchOrderRow(page, orderSn)
      expect(await orderStatusOf(row), '支付后应为待审核').toContain('待审核')

      await auditPassOrder(page, row)
      await expectToastSuccess(page)
      expect(await orderStatusOf(row), '审核通过后应为待发货').toContain('待发货')

      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, orderSn)
      const deliveries = await listDeliveriesApi(request, token, order.id)
      expect(deliveries.length, '应生成 1 个发货单').toBe(1)
      expect(deliveries[0].status, '新发货单应为待下发(0)').toBe(0)
      expect(deliveries[0].source, '自动拆单 source=0').toBe(0)
    })

    let table, deliverySn
    await test.step('④ 下发发货单 → 状态变已下发', async () => {
      const row = await searchOrderRow(page, orderSn)
      table = await openOrderDrawer(page, row)
      deliverySn = (await table.locator('.el-table__body tr').first().locator('td').first().innerText()).trim()
      await clickDeliveryAction(page, deliveryRowBySn(table, deliverySn), '下发')
      await expectToastSuccess(page)
      await expect(deliveryRowBySn(table, deliverySn), '下发后应为已下发').toContainText('已下发')
    })

    await test.step('⑤ 模拟 WMS 回传：拣货 → 出库（出运单号）', async () => {
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '拣货')
      await expect(deliveryRowBySn(table, deliverySn), '拣货后应为已拣货').toContainText('已拣货')
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '出库')
      await expect(deliveryRowBySn(table, deliverySn), '出库后应为已出库').toContainText('已出库')
      await expect(deliveryRowBySn(table, deliverySn), '出库应生成运单号').toContainText(/SF\d{6,}/)
    })

    await test.step('⑥ 全部出库 → 订单自动变已发货', async () => {
      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, orderSn)
      expect(order.status, '子单出库后订单应为已发货(2)').toBe(2)
      await gotoOrderList(page)
      const row = await searchOrderRow(page, orderSn)
      expect(await orderStatusOf(row), '列表也应显示已发货').toContain('已发货')
    })

    await test.step('⑦ 签收 → 订单自动变已完成', async () => {
      const row = await searchOrderRow(page, orderSn)
      table = await openOrderDrawer(page, row)
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '签收')
      await expect(deliveryRowBySn(table, deliverySn), '签收后应为已签收').toContainText('已签收')

      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, orderSn)
      expect(order.status, '全部签收后订单应为已完成(3)').toBe(3)
    })

    await test.step('⑧ 审核与发货不再动库存（只有下单扣了 1 件）', async () => {
      const { skus } = await getProductApi(request, 1)
      const after = skus.find((s) => s.id === skuId).stock
      expect(after, '审核+发货不应再次扣库存').toBe(stockBefore - 1)
    })
  })

  test('用例二：审核不通过→订单关闭+库存回补', async ({ page, request }) => {
    let skuId
    let specText
    let stockBefore

    await test.step('① 管理员登录并取基准库存', async () => {
      await adminLoginHardened(page)
      const { skus } = await getProductApi(request, 1)
      skuId = skus[0].id
      specText = skus[0].specText
      stockBefore = skus[0].stock
    })

    let orderSn
    await test.step('② 买家下单支付（下单即扣 1 件）', async () => {
      await memberRegisterAndLogin(page, `e2e_reject_${stamp()}`)
      await addSpecToCart(page, 1, specText)
      orderSn = await submitCartOrder(page, { receiverName: '审核拒绝E2E' })
    })

    await test.step('③ 审核不通过 → 订单变已关闭，且不生成发货单', async () => {
      await gotoOrderList(page)
      const row = await searchOrderRow(page, orderSn)
      await auditRejectOrder(page, row)
      await expectToastSuccess(page)
      expect(await orderStatusOf(row), '审核不通过后应为已关闭').toContain('已关闭')

      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, orderSn)
      expect(order.status, '服务端状态应为已关闭(4)').toBe(4)
      const deliveries = await listDeliveriesApi(request, token, order.id)
      expect(deliveries.length, '审核不通过不应生成发货单').toBe(0)
    })

    await test.step('④ 库存回补到下单前', async () => {
      const { skus } = await getProductApi(request, 1)
      const after = skus.find((s) => s.id === skuId).stock
      expect(after, '审核不通过应把下单扣掉的 1 件补回来').toBe(stockBefore)
    })
  })
})
