import { test, expect } from '@playwright/test'
import { ADMIN, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import {
  openProductTypes,
  typeRowOf,
  addProductType,
  renameProductType,
  openAttributesOf,
  attrRowOf,
  addAttribute,
  editAttribute
} from '../../support/catalog-ui.js'
import { adminTokenOf, getAdminProductTypesApi, getAdminAttributesApi } from '../../support/assert.js'

/**
 * 商家侧 02-商品管理 / 1-商品类型与属性
 *
 * 覆盖：类型新增 / 改名 / 必填校验；属性挂在类型下（聚合）新增 / 改名 / is_filter 开关；
 *       属性页只显示本类型的属性（聚合关系）；类型删除 —— **后台没有删除入口**（见报告）
 * 断言依据：admin/product-type/index.vue、attributes.vue（操作列只有「编辑」「属性」）
 */

test.describe('商家侧 1 商品类型与属性', () => {
  test('新增类型 → 改名 → 服务端复核', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openProductTypes(page)

    const name = `E2E类型${stamp()}`
    await expect(typeRowOf(page, name)).toHaveCount(0)

    await addProductType(page, name)
    let types = await getAdminProductTypesApi(request, token)
    expect(types.find((t) => t.name === name), '新类型应落库').toBeTruthy()

    // 改名
    const renamed = `${name}改`
    await renameProductType(page, name, renamed)
    types = await getAdminProductTypesApi(request, token)
    expect(types.find((t) => t.name === renamed), '改名应落库').toBeTruthy()
    expect(types.find((t) => t.name === name), '旧名称应不存在').toBeFalsy()
  })

  test('必填校验：类型名称不填不让保存', async ({ page }) => {
    await adminLogin(page)
    await openProductTypes(page)
    await page.getByRole('button', { name: '新增' }).click()
    const dialog = page.locator('.el-dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '确定' }).click()
    await expect(dialog.locator('.el-form-item__error').filter({ hasText: '请输入类型名称' })).toBeVisible()
    await expect(dialog, '校验不通过弹窗不应关闭').toBeVisible()
    await dialog.getByRole('button', { name: '取消' }).click()
  })

  test('属性聚合于类型：新增 / 改名 / is_filter 开关，且只显示本类型属性', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openProductTypes(page)

    const t1 = `E2E属性类型A${stamp()}`
    const t2 = `E2E属性类型B${stamp()}`
    await addProductType(page, t1)
    await addProductType(page, t2)

    // A 类型下加两个属性：一个可筛选、一个不可
    await openAttributesOf(page, t1)
    const filterAttr = '颜色'
    const plainAttr = '重量'
    await addAttribute(page, filterAttr, true)
    await addAttribute(page, plainAttr, false)

    const attrs = await getAdminAttributesApi(request, token)
    const mine = attrs.filter((a) => a.name === filterAttr || a.name === plainAttr)
    expect(mine.length, '两个属性都应落库').toBeGreaterThanOrEqual(2)
    expect(mine.find((a) => a.name === filterAttr).isFilter, 'is_filter 应为 1').toBe(1)
    expect(mine.find((a) => a.name === plainAttr).isFilter, 'is_filter 应为 0').toBe(0)

    // 列表展示：「是否筛选」列显示 是 / 否
    await expect(attrRowOf(page, filterAttr), '可筛选属性应显示「是」').toContainText('是')
    await expect(attrRowOf(page, plainAttr), '不可筛选属性应显示「否」').toContainText('否')

    // 改名 + 关掉 is_filter
    const renamedAttr = '颜色(新)'
    await editAttribute(page, filterAttr, { name: renamedAttr, isFilter: false })
    await expect(attrRowOf(page, renamedAttr), '改名后应显示「否」').toContainText('否')
    const after = (await getAdminAttributesApi(request, token)).find((a) => a.name === renamedAttr)
    expect(after.isFilter, 'is_filter 应被关成 0').toBe(0)

    // 聚合关系：B 类型的属性页看不到 A 的属性
    await openProductTypes(page)
    await openAttributesOf(page, t2)
    await expect(page.locator('.el-table__body'), 'B 类型属性页不应出现 A 的属性').not.toContainText(renamedAttr)
  })

  test('【已知缺口】类型/属性没有删除入口：后台只提供新增与编辑', async ({ page }) => {
    await adminLogin(page)
    await openProductTypes(page)
    const row = page.locator('.el-table__body tr').first()
    await expect(row).toBeVisible()
    // 记录现状：操作列只有「编辑」「属性」，没有「删除」（接口存在但 UI 未接）
    await expect(row.getByRole('button', { name: '编辑' })).toBeVisible()
    await expect(row.getByRole('button', { name: '删除' }), '当前后台没有删除类型的入口').toHaveCount(0)

    await openAttributesOf(page, (await row.innerText()).split('\t')[0].trim())
    const attrRow = page.locator('.el-table__body tr').first()
    if (await attrRow.count()) {
      await expect(attrRow.getByRole('button', { name: '删除' }), '当前后台没有删除属性的入口').toHaveCount(0)
    }
  })
})
