import { test, expect } from '../../support/test.js'
import { API, stamp } from '../../support/env.js'
import {
  adminLoginHardened,
  gotoWarehouseList,
  warehouseRow,
  openWarehouseForm,
  fillWarehouseName,
  setWarehouseStatus,
  submitWarehouseForm,
  cancelWarehouseForm,
  formItemError,
  deleteWarehouse,
  expectToastSuccess,
  expectToastError,
  tableRowCount,
  gotoOrderList,
  createOrderViaUi,
  searchOrderRow,
  auditPassOrder
} from '../../support/admin-ui.js'
import { adminTokenOf, getAdminProductsApi, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { receiverOf, warehouseNameOf } from '../../support/fixtures.js'

/**
 * 04-商家-基础配置 / 1-仓库管理（docs/flows/02-业务场景/04-商家-基础配置/1-仓库管理.mmd）
 *
 * 完整走一遍管理员维护仓库的所有操作，不只 happy path：
 *   列表 → 新增（启用）→ 重名也允许（现状无唯一校验）→ 编辑改名 + 停用
 *   → 空名校验（拦在前端）→ 删除自建仓库（成功）
 *   → 删除被发货单引用的仓库（后端兜底报错，数据不动）
 *
 * 断言口径来自代码实际行为：
 *   WarehouseController.create/update/delete 无唯一校验、删除不校验存在性；
 *   delivery_order.warehouse_id 有外键，被引用时删除会失败。
 *   为了让「被引用」这条在任何库上都成立，第 ⑦ 步会先造一笔真实订单并审核通过。
 */
test.describe('04-商家-基础配置 › 1-仓库管理', () => {
  test('新增/编辑/删除仓库，覆盖重名、空名与被引用仓库删除失败', async ({ page, request }) => {
    // 仓库名要「像真的」且不重名：自然仓名 + 库位编号后缀（B482 这种）
    const seed = stamp()
    const base = warehouseNameOf(seed, 'base')
    const renamed = warehouseNameOf(seed, 'renamed')
    const dupName = warehouseNameOf(seed, 'dup')
    expect(new Set([base, renamed, dupName]).size, '三个仓库名必须两两不同（否则按文本定位会取错行）').toBe(3)

    let productName
    await test.step('① 管理员登录并进入仓库管理', async () => {
      // 后端刚重启时首个请求可能 500（重试一次即可）；401/403 等真实故障不会被重试掩盖
      await adminLoginHardened(page)
      await gotoWarehouseList(page)
      // 种子仓库应在列表里（拆单链路依赖这些仓）
      await expect(warehouseRow(page, '深圳仓')).toBeVisible()
      await expect(warehouseRow(page, '杭州仓')).toBeVisible()
      // 商品名从**后台**接口按 id=1 取：C 端接口在商品被并发下架时会返回 null，
      // 后台接口不受上下架影响（这里只需要一个「有 SKU、能被下单引用」的商品名）
      const token = await adminTokenOf(page)
      const seedProduct = (await getAdminProductsApi(request, token)).find((p) => p.id === 1)
      expect(seedProduct, '种子商品1 应存在于后台商品列表').toBeTruthy()
      productName = seedProduct.name
    })

    await test.step('② 新增仓库（启用）→ 列表出现且状态为启用', async () => {
      const before = await tableRowCount(page, 'warehouse-list-table')
      await openWarehouseForm(page)
      await fillWarehouseName(page, base)
      await setWarehouseStatus(page, true)
      await submitWarehouseForm(page)
      await expectToastSuccess(page)
      await expect(warehouseRow(page, base), '新建的仓库应出现在列表').toBeVisible()
      expect(await tableRowCount(page, 'warehouse-list-table'), '列表应多一行').toBe(before + 1)
      await expect(warehouseRow(page, base), '新建默认启用').toContainText('启用')
    })

    await test.step('③ 同名仓库也允许创建（现状：没有唯一校验）', async () => {
      await openWarehouseForm(page)
      await fillWarehouseName(page, dupName)
      await setWarehouseStatus(page, false)          // 这次建一个停用的
      await submitWarehouseForm(page)
      await expectToastSuccess(page)
      const dupRow = warehouseRow(page, dupName)
      await expect(dupRow, '同名仓库应能创建成功').toBeVisible()
      await expect(dupRow, '停用状态应显示为停用').toContainText('停用')
    })

    await test.step('④ 编辑：改名并停用 → 列表这一行立即变样', async () => {
      await openWarehouseForm(page, warehouseRow(page, base))
      await fillWarehouseName(page, renamed)
      await setWarehouseStatus(page, false)
      await submitWarehouseForm(page)
      await expectToastSuccess(page)
      await expect(warehouseRow(page, renamed), '改名后应能按新名字找到').toBeVisible()
      await expect(warehouseRow(page, renamed), '应已停用').toContainText('停用')
    })

    await test.step('⑤ 空名校验：清空名称点确定 → 前端拦住，弹窗不关、列表不变', async () => {
      const before = await tableRowCount(page, 'warehouse-list-table')
      await openWarehouseForm(page, warehouseRow(page, renamed))
      await fillWarehouseName(page, '')
      await submitWarehouseForm(page)
      await expect(formItemError(page), '应提示请输入仓库名称').toContainText('请输入仓库名称')
      await expect(tid(page, 'warehouse-form-dialog'), '校验不过时弹窗不能关闭').toBeVisible()
      await cancelWarehouseForm(page)
      expect(await tableRowCount(page, 'warehouse-list-table'), '取消后列表行数不变').toBe(before)
    })

    await test.step('⑥ 删除自己新建的两个仓库 → 列表不再有', async () => {
      await deleteWarehouse(page, warehouseRow(page, renamed))
      await expectToastSuccess(page, '删除成功')
      await expect(warehouseRow(page, renamed)).toHaveCount(0)

      await deleteWarehouse(page, warehouseRow(page, dupName))
      await expectToastSuccess(page, '删除成功')
      await expect(warehouseRow(page, dupName)).toHaveCount(0)
    })

    await test.step('⑦ 删除被发货单引用的仓库 → 明确报错，数据不动', async () => {
      // 先用一笔真实订单把这个仓库「用起来」：新增订单 → 审核通过 → 生成发货单
      await gotoOrderList(page)
      const orderSn = await createOrderViaUi(page, {
        receiver: receiverOf(seed),
        status: '待审核',
        items: [{ productName, skuIndex: 0, quantity: 1 }]
      })
      await auditPassOrder(page, await searchOrderRow(page, orderSn))

      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, orderSn)
      const [delivery] = await listDeliveriesApi(request, token, order.id)
      const whRes = await request.get(`${API}/admin/warehouses`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      const usedWhName = (await whRes.json()).data.find((w) => w.id === delivery.warehouseId).name

      // 回仓库管理删它 → 必须失败且数据不动
      await gotoWarehouseList(page)
      const before = await tableRowCount(page, 'warehouse-list-table')
      await deleteWarehouse(page, warehouseRow(page, usedWhName))
      // 文案口径（2026-11-12 收口）：外键拦截不再落到兜底「数据处理失败，请稍后重试」，
      // 而是 GlobalExceptionHandler 翻译出的具体原因。断言这句**更具体**的文案，顺带证明
      // 走的是「被引用 → 拒绝删除」这条守卫路径，而不是随便一个 500。
      await expectToastError(page, '数据被其它记录引用，无法删除')
      await expect(warehouseRow(page, usedWhName), '被引用的仓库必须还在').toBeVisible()
      await page.reload()
      await page.waitForTimeout(1500)
      expect(await tableRowCount(page, 'warehouse-list-table'), '刷新后行数也不变').toBe(before)
      await expect(warehouseRow(page, usedWhName), '刷新后仓库仍在').toBeVisible()
    })
  })
})
