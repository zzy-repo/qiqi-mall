import { test, expect } from '../../../support/test.js'
import { stamp } from '../../../support/env.js'
import { memberRegisterAndLogin, addSpecToCart, submitCartOrder } from '../../../support/ui.js'
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
} from '../../../support/admin-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'
import { memberNickOf, receiverOf } from '../../../support/fixtures.js'
import { memberLoginOf } from '../../../support/member-login.js'

/**
 * 业务 / 商家-履约 / 订单审核与发货（docs/flows/02-业务场景/业务/商家-履约/订单审核与发货/1-业务时序图.mmd）
 *
 * 两个用例：
 *   用例一：买家下单支付 → 管理员审核通过（自动生成发货单）→ 下发 → 模拟 WMS 回传（拣货/出库/签收）
 *          → 订单状态按包裹聚合：已发货 → 已完成
 *   用例二：买家下单支付 → 管理员审核不通过 → 订单关闭 + 库存回补，且不生成发货单
 *
 * 为什么用买家真实下单而不是 admin「新增订单」：admin 新建订单**不扣库存**（只有 /
 * portal/orders 走 OrderTransactionalCreator 才扣），用 admin 建单会让「回补库存」断言失去意义。
 */
test.describe('业务 › 商家-履约 › 订单审核与发货', () => {
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
      // 登录名会显示在后台订单列表的「买家」列，用自然会员号（唯一性靠数字后缀）
      const buyer = memberLoginOf(stamp())
      await memberRegisterAndLogin(page, buyer, '123456', memberNickOf(buyer))
      await addSpecToCart(page, 1, specText, skuId)
      orderSn = await submitCartOrder(page, receiverOf(buyer))
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
      // 这里断言的是「审核/发货没有再扣库存」：只要求缓存收敛到下单后的那个值（不跟 250ms 回写赛跑）
      await expect.poll(async () => (await getProductApi(request, 1)).skus.find((s) => s.id === skuId).stock,
        { timeout: 2_000, intervals: [50, 100, 200, 400], message: '审核+发货不应再次扣库存' }
      ).toBe(stockBefore - 1)
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
      const buyer = memberLoginOf(stamp())
      await memberRegisterAndLogin(page, buyer, '123456', memberNickOf(buyer))
      await addSpecToCart(page, 1, specText, skuId)
      orderSn = await submitCartOrder(page, receiverOf(buyer))
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
      // sku.stock 在分桶模式下只是展示缓存（由后台任务按 SUM(桶) 每 250ms 回写），
      // 所以不能「读一次就断言」——必须轮询等它收敛，写法与同文件用例一 ⑧ 一致。
      // 断言口径不变：仍然要求缓存值**恰好**等于下单前的值（不是放宽成 ≥ / ≈）。
      await expect.poll(async () => (await getProductApi(request, 1)).skus.find((s) => s.id === skuId).stock,
        { timeout: 2_000, intervals: [50, 100, 200, 400], message: '审核不通过应把下单扣掉的 1 件补回来' }
      ).toBe(stockBefore)
    })
  })
})
