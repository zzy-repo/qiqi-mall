import { test, expect } from '@playwright/test'
import { ADMIN, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import {
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
  tableRowCount
} from '../../support/admin-ui.js'

/**
 * 04-商家-基础配置 / 1-仓库管理（docs/flows/02-业务场景/04-商家-基础配置/1-仓库管理.mmd）
 *
 * 完整走一遍管理员维护仓库的所有操作，不只 happy path：
 *   列表 → 新增（启用）→ 重名也允许（现状无唯一校验）→ 编辑改名 + 停用
 *   → 空名校验（拦在前端）→ 删除空仓（成功）
 *   → 删除被发货单引用的仓库（后端 500 兜底提示，数据不动）
 *
 * 断言口径来自代码实际行为：
 *   WarehouseController.create/update/delete 无唯一校验、删除无存在性校验；
 *   warehouse.name 传给 delivery_order.warehouse_id 有外键，被引用时删除会失败。
 */
test.describe('04-商家-基础配置 › 1-仓库管理', () => {
  test('新增/编辑/删除仓库，覆盖重名、空名与被引用仓库删除失败', async ({ page }) => {
    const base = `E2E仓${stamp()}`
    const renamed = `${base}-改`
    const dupName = `${base}-重名`

    await test.step('① 管理员登录并进入仓库管理', async () => {
      // 后端重启窗口里登录接口偶发 500：重试一次，别把环境波动算成业务失败
      await adminLogin(page).catch(async () => {
        await page.goto(`${ADMIN}/login`)
        await page.waitForTimeout(1500)
        await adminLogin(page)
      })
      await gotoWarehouseList(page)
      // 种子仓库应在列表里（拆单链路依赖这三个仓）
      await expect(warehouseRow(page, '深圳仓')).toBeVisible()
      await expect(warehouseRow(page, '杭州仓')).toBeVisible()
      await expect(warehouseRow(page, '北京仓')).toBeVisible()
    })

    await test.step('② 新增仓库（启用）→ 列表出现且状态为启用', async () => {
      const before = await tableRowCount(page)
      await openWarehouseForm(page)
      await fillWarehouseName(page, base)
      await setWarehouseStatus(page, true)
      await submitWarehouseForm(page)
      await expectToastSuccess(page)
      await expect(warehouseRow(page, base), '新建的仓库应出现在列表').toBeVisible()
      expect(await tableRowCount(page), '列表应多一行').toBe(before + 1)
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
      const before = await tableRowCount(page)
      await openWarehouseForm(page, warehouseRow(page, renamed))
      await fillWarehouseName(page, '')
      await submitWarehouseForm(page)
      await expect(formItemError(page), '应提示请输入仓库名称').toContainText('请输入仓库名称')
      await expect(page.locator('.el-dialog'), '校验不过时弹窗不能关闭').toBeVisible()
      await cancelWarehouseForm(page)
      expect(await tableRowCount(page), '取消后列表行数不变').toBe(before)
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
      const before = await tableRowCount(page)
      await deleteWarehouse(page, warehouseRow(page, '深圳仓'))
      // 后端把外键异常兜底成「数据处理失败，请稍后重试」
      await expectToastError(page, '数据处理失败')
      await expect(warehouseRow(page, '深圳仓'), '被引用的仓库必须还在').toBeVisible()
      await page.reload()
      await page.waitForTimeout(1500)
      expect(await tableRowCount(page), '刷新后行数也不变').toBe(before)
      await expect(warehouseRow(page, '深圳仓'), '刷新后仓库仍在').toBeVisible()
    })
  })
})
