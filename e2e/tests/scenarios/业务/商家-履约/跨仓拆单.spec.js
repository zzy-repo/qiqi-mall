import { test, expect } from '../../../support/test.js'
import { stamp } from '../../../support/env.js'
import { memberRegisterAndLogin, addSpecToCart, submitCartOrder } from '../../../support/ui.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  openSplitPreview,
  auditPassOrder,
  openOrderDrawer,
  deliveryRowBySn,
  orderStatusOf,
  expectToastSuccess
} from '../../../support/admin-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'
import { memberNickOf, receiverOf } from '../../../support/fixtures.js'
import { memberLoginOf } from '../../../support/member-login.js'

/**
 * 业务 / 商家-履约 / 跨仓拆单（docs/flows/02-业务场景/业务/商家-履约/跨仓拆单/1-业务时序图.mmd）
 *
 * 两个用例（各自一段视频，单段都在 90s 内）：
 *   用例一：跨仓订单 —— 审核前在确认弹窗里预演拆单方案（2 个包裹）且**不落库**（点「取消」不执行）
 *          → 再走审核通过 → 真拆成 2 个发货单（各归其仓、source=0）→ 列表与抽屉的包裹数一致
 *   用例二：单仓订单 —— 预演只有 1 个包裹 → 通过后只生成 1 个发货单（同仓不拆）
 *
 * 新 UI 变更点：原来的「审核」+「查看拆单方案」独立预览弹窗都没了。
 * 现在点行内「审核通过」就进统一确认弹窗，拆单方案渲染在它的 details 区；
 * 「只看方案不执行」= 打开弹窗 → 读 details → 点「取消」（取消不发任何请求）。
 */
test.describe('业务 › 商家-履约 › 跨仓拆单', () => {
  test('用例一：跨仓订单先预览再拆成两个包裹（预览不落库）', async ({ page, request }) => {
    let skus

    await test.step('① 管理员登录并取 SKU 信息', async () => {
      await adminLoginHardened(page)
      const product = await getProductApi(request, 1)
      skus = product.skus
      expect(skus.length, '商品1 至少要有 2 个 SKU 才能造跨仓订单').toBeGreaterThanOrEqual(2)
    })

    let crossSn
    await test.step('② 买家下单两个不同 SKU（跨仓）', async () => {
      // 登录名会显示在后台订单列表的「买家」列，用自然会员号（唯一性靠数字后缀）
      const buyer = memberLoginOf(stamp())
      await memberRegisterAndLogin(page, buyer, '123456', memberNickOf(buyer))
      await addSpecToCart(page, 1, skus[0].specText, skus[0].id)
      await addSpecToCart(page, 1, skus[1].specText, skus[1].id)
      crossSn = await submitCartOrder(page, receiverOf(buyer))
    })

    await test.step('③ 预演拆单方案（不执行）：2 个包裹，且不落库', async () => {
      await gotoOrderList(page)
      const row = await searchOrderRow(page, crossSn)
      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, crossSn)
      const before = await listDeliveriesApi(request, token, order.id)
      expect(before.length, '预演前不应有任何发货单').toBe(0)

      // 点「审核通过」→ 确认弹窗里预演方案（details 区）→ 点「取消」不执行
      const plan = await openSplitPreview(page, row)
      expect(plan.length, '跨仓订单应预演出 2 个包裹').toBe(2)
      const names = plan.map((t) => t.split('\n')[0].trim())
      expect(new Set(names).size, '两个包裹应落在不同的仓').toBe(2)

      const during = await listDeliveriesApi(request, token, order.id)
      expect(during.length, '预演是 dry-run，发货单数必须还是 0').toBe(0)
      expect(await orderStatusOf(row), '取消了预演，订单应仍是待审核').toContain('待审核')

      // 正式审核通过（第二次点同一个按钮，这次确认执行）
      await auditPassOrder(page, row)
      await expectToastSuccess(page)
      expect(await orderStatusOf(row), '通过后订单应为待发货').toContain('待发货')
    })

    await test.step('④ 拆成 2 个发货单：各归其仓、自动拆单', async () => {
      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, crossSn)
      const deliveries = await listDeliveriesApi(request, token, order.id)
      expect(deliveries.length, '跨仓订单应拆成 2 个发货单').toBe(2)
      expect(new Set(deliveries.map((d) => d.warehouseId)).size, '两个包裹来自不同仓').toBe(2)
      deliveries.forEach((d) => {
        expect(d.status, '新发货单应待下发(0)').toBe(0)
        expect(d.source, '自动拆单 source=0').toBe(0)
        expect(d.items.length, '每个包裹都要有明细').toBeGreaterThan(0)
      })
    })

    await test.step('⑤ 抽屉里能看到 2 个包裹，列表标签也是「2 个包裹」', async () => {
      const row = await searchOrderRow(page, crossSn)
      await expect(row, '列表应显示 2 个包裹').toContainText('2 个包裹')
      const table = await openOrderDrawer(page, row)
      await expect(table.locator('.el-table__body tr'), '抽屉里应有 2 个包裹').toHaveCount(2)
      await expect(table, '默认都是自动拆单').toContainText('自动拆单')
      const firstSn = (await table.locator('.el-table__body tr').first().locator('td').first().innerText()).trim()
      await expect(deliveryRowBySn(table, firstSn)).toBeVisible()
    })
  })

  test('用例二：单仓订单只生成一个发货单（同仓不拆）', async ({ page, request }) => {
    let skus

    await test.step('① 管理员登录并取 SKU 信息', async () => {
      await adminLoginHardened(page)
      const product = await getProductApi(request, 1)
      skus = product.skus
    })

    let singleSn
    await test.step('② 买家下单同一个 SKU（单仓）', async () => {
      const buyer = memberLoginOf(stamp())
      await memberRegisterAndLogin(page, buyer, '123456', memberNickOf(buyer))
      await addSpecToCart(page, 1, skus[0].specText, skus[0].id)
      singleSn = await submitCartOrder(page, receiverOf(buyer))
    })

    await test.step('③ 预览只有 1 个包裹 → 通过后只生成 1 个发货单', async () => {
      await gotoOrderList(page)
      const row = await searchOrderRow(page, singleSn)
      const plan = await openSplitPreview(page, row)   // 预演后取消，不执行
      expect(plan.length, '单仓订单预演就是 1 个包裹').toBe(1)
      await auditPassOrder(page, row)
      await expectToastSuccess(page)

      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, singleSn)
      const deliveries = await listDeliveriesApi(request, token, order.id)
      expect(deliveries.length, '单仓订单只生成 1 个发货单').toBe(1)
      expect(deliveries[0].source, '自动拆单 source=0').toBe(0)
    })

    await test.step('④ 列表包裹数标签显示 1 个包裹', async () => {
      const row = await searchOrderRow(page, singleSn)
      await expect(row, '单仓订单应显示 1 个包裹').toContainText('1 个包裹')
      expect(await orderStatusOf(row), '通过后应为待发货').toContain('待发货')
    })
  })
})
