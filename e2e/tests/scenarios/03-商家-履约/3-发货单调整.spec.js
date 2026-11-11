import { test, expect } from '@playwright/test'
import {
  adminLoginHardened,
  gotoOrderList,
  createOrderViaUi,
  searchOrderRow,
  auditPassOrder,
  openOrderDrawer,
  deliveryRowBySn,
  clickDeliveryAction,
  setDialogQty,
  submitDeliveryDialog,
  pickMergeTarget,
  submitMergeDialog,
  expectToastSuccess,
  warehouseCellOf,
  pickWarehouseExcluding
} from '../../support/admin-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { addressOf } from '../../support/fixtures.js'

/**
 * 03-商家-履约 / 3-发货单调整（docs/flows/02-业务场景/03-商家-履约/3-发货单调整.mmd）
 *
 * 覆盖「待下发」阶段的人工干预全链路：改仓 → 改量（减/加）→ 拆分 → 合并 → 删除（必须并入别处）
 * 以及守卫：下发之后干预入口全部消失（只能回传/取消）。
 *
 * 数据准备全部走 admin 真实 UI：新增订单（状态=待审核）→ 审核通过 → 自动拆出 2 个待下发包裹。
 * 商品名与仓库名都从接口实时读，不写死（dev 库与测试库种子不同，写死必挂）。
 */
test.describe('03-商家-履约 › 3-发货单调整', () => {
  test('待下发包裹可改仓/改量/拆分/合并/删除，下发后入口消失', async ({ page, request }) => {
    let productName
    let skuCount
    await test.step('① 管理员登录并取商品信息', async () => {
      await adminLoginHardened(page)
      const { product, skus } = await getProductApi(request, 1)
      productName = product.name
      skuCount = skus.length
      expect(skuCount, '商品1 至少要有 2 个 SKU 才能拆成两个仓').toBeGreaterThanOrEqual(2)
    })

    let orderSn
    let table
    const orderSnStamp = `adj-${Date.now()}`
    await test.step('② 造一笔待审核订单（同一商品两个 SKU，跨两个仓）', async () => {
      await gotoOrderList(page)
      orderSn = await createOrderViaUi(page, {
        receiverName: addressOf(orderSnStamp).name,
        status: '待审核',
        items: [
          { productName, skuIndex: 0, quantity: 2 },
          { productName, skuIndex: 1, quantity: 1 }
        ]
      })
      await expect(page.locator('.el-table__body tr').first(), '新订单应为待审核').toContainText('待审核')
    })

    let snA, snB, whA, whB
    await test.step('③ 审核通过 → 自动拆出 2 个待下发包裹', async () => {
      const row = await searchOrderRow(page, orderSn)
      await auditPassOrder(page, row)
      await expect(row.getByTestId('order-row-status'), '审核后订单应为待发货').toContainText('待发货')
      const row2 = await searchOrderRow(page, orderSn)
      table = await openOrderDrawer(page, row2)
      const rows = table.locator('.el-table__body tr')
      await expect(rows, '应拆出 2 个包裹').toHaveCount(2)
      await expect(table).toContainText('待下发')
      await expect(table, '两个包裹默认都是自动拆单').toContainText('自动拆单')
      snA = (await rows.first().locator('td').first().innerText()).trim()
      snB = (await rows.last().locator('td').first().innerText()).trim()
      whA = await warehouseCellOf(rows.first())
      whB = await warehouseCellOf(rows.last())
      expect(whA, '两个包裹应从不同的仓发出').not.toEqual(whB)
    })

    let newWh
    await test.step('④ 编辑：把第一个包裹改到一个新的仓 → 来源变「人工调整」', async () => {
      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      newWh = await pickWarehouseExcluding(page, [whA, whB])
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      await expect(deliveryRowBySn(table, snA), `改仓后应显示 ${newWh}`).toContainText(newWh)
      await expect(deliveryRowBySn(table, snA), '人工改过的包裹应标记人工调整').toContainText('人工调整')
    })

    await test.step('⑤ 编辑：改量 2 → 1 再改回 2，后端数量跟着变', async () => {
      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, orderSn)

      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      await setDialogQty(page, 0, 1)
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      let list = await listDeliveriesApi(request, token, order.id)
      let pkg = list.find((d) => d.deliverySn === snA)
      expect(pkg.items[0].quantity, '改量后后端数量应为 1').toBe(1)
      expect(pkg.source, '人工改过的包裹 source=1').toBe(1)

      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      await setDialogQty(page, 0, 2)
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      list = await listDeliveriesApi(request, token, order.id)
      pkg = list.find((d) => d.deliverySn === snA)
      expect(pkg.items[0].quantity, '再改回 2 后后端数量应为 2').toBe(2)
    })

    await test.step('⑥ 拆分：从该包裹拆 1 件到新包裹（沿用原仓）', async () => {
      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '拆分')
      await setDialogQty(page, 0, 1)
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      await expect(table.locator('.el-table__body tr'), '拆分后应变成 3 个包裹').toHaveCount(3)
      await expect(table, '拆出来的新包裹也是人工调整').toContainText('人工调整')
    })

    await test.step('⑦ 合并：把新拆出来的包裹并入同仓包裹，被合并的消失', async () => {
      const rows = table.locator('.el-table__body tr')
      const newRow = rows.last()                            // 新拆出来的包裹排在最后
      const newSn = (await newRow.locator('td').first().innerText()).trim()
      const targetRow = rows.first()
      const targetSn = (await targetRow.locator('td').first().innerText()).trim()
      await clickDeliveryAction(page, newRow, '合并')
      await pickMergeTarget(page, targetSn)
      await submitMergeDialog(page)
      await expectToastSuccess(page)
      await expect(table.locator('.el-table__body tr'), '合并后应剩 2 个包裹').toHaveCount(2)
      await expect(table, `被合并掉的包裹 ${newSn} 不应再出现`).not.toContainText(newSn)
    })

    await test.step('⑧ 删除：有明细的包裹必须选并入目标（等同合并到别处）', async () => {
      const rows = table.locator('.el-table__body tr')
      const victimRow = rows.first()
      const victimSn = (await victimRow.locator('td').first().innerText()).trim()
      const keepRow = rows.last()
      const keepSn = (await keepRow.locator('td').first().innerText()).trim()

      await clickDeliveryAction(page, victimRow, '删除')
      await expect(tid(page, 'delivery-merge-dialog'), '有明细时删除要指定并入目标').toBeVisible()
      await pickMergeTarget(page, keepSn)
      await submitMergeDialog(page)
      await expectToastSuccess(page, '已删除')
      await expect(table.locator('.el-table__body tr'), '删除后只剩 1 个包裹').toHaveCount(1)
      await expect(table, `被删掉的包裹 ${victimSn} 不应再出现`).not.toContainText(victimSn)
    })

    await test.step('⑨ 下发后：干预入口全部消失（只能回传/取消）', async () => {
      const row = table.locator('.el-table__body tr').first()
      await clickDeliveryAction(page, row, '下发')
      await expect(row.getByTestId('delivery-row-status'), '下发后应为已下发').toContainText('已下发')
      for (const [name, testId] of [
        ['编辑', 'delivery-row-edit'],
        ['拆分', 'delivery-row-split'],
        ['合并', 'delivery-row-merge'],
        ['删除', 'delivery-row-remove']
      ]) {
        await expect(row.getByTestId(testId), `已下发不能${name}`).toHaveCount(0)
      }
      await expect(row.getByTestId('delivery-row-cancel'), '已下发也不能取消').toHaveCount(0)
      await expect(row.getByTestId('delivery-row-wms-callback'), '只剩回传入口').toBeVisible()
    })
  })
})
