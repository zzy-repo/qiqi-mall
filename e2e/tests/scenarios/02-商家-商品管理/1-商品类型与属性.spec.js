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
import { tid } from '../../support/tid.js'
import { attributeNames, attributeOf, newestByName, typeNames, typeOf, uniqueDisplayName } from '../../support/fixtures.js'

/**
 * 商家侧 02-商品管理 / 1-商品类型与属性
 *
 * 覆盖：类型新增 / 改名 / 必填校验；属性挂在类型下（聚合）新增 / 改名 / is_filter 开关；
 *       属性页只显示本类型的属性（聚合关系）；类型与属性的删除 —— 被引用时后端拒删并回显中文原因，
 *       无引用可删
 * 断言依据：admin/product-type/index.vue、attributes.vue（操作列有「编辑」「属性」「删除」）
 */

test.describe('商家侧 1 商品类型与属性', () => {
  test('新增类型 → 改名 → 服务端复核', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openProductTypes(page)

    const typesBefore = await getAdminProductTypesApi(request, token)
    const name = uniqueDisplayName(typeNames[0], typesBefore.map((t) => t.name))
    await expect(typeRowOf(page, name)).toHaveCount(0)

    await addProductType(page, name)
    let types = await getAdminProductTypesApi(request, token)
    expect(newestByName(types, name), '新类型应落库').toBeTruthy()

    // 改名
    const renamed = uniqueDisplayName(typeNames[1], types.map((t) => t.name))
    await renameProductType(page, name, renamed)
    types = await getAdminProductTypesApi(request, token)
    expect(newestByName(types, renamed), '改名应落库').toBeTruthy()
    expect(types.find((t) => t.name === name), '旧名称应不存在').toBeFalsy()
  })

  test('必填校验：类型名称不填不让保存', async ({ page }) => {
    await adminLogin(page)
    await openProductTypes(page)
    await tid(page, 'product-type-create').click()
    const dialog = tid(page, 'product-type-form-dialog')
    await expect(dialog).toBeVisible()
    await tid(page, 'product-type-form-submit').click()
    await expect(dialog.locator('.el-form-item__error').filter({ hasText: '请输入类型名称' })).toBeVisible()
    await expect(dialog, '校验不通过弹窗不应关闭').toBeVisible()
    await tid(page, 'product-type-form-cancel').click()
  })

  test('属性聚合于类型：新增 / 改名 / is_filter 开关，且只显示本类型属性', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openProductTypes(page)

    const typesNow = await getAdminProductTypesApi(request, token)
    const existingTypeNames = typesNow.map((t) => t.name)
    const t1 = uniqueDisplayName(typeNames[2], existingTypeNames)
    const t2 = uniqueDisplayName(typeNames[3], [...existingTypeNames, t1])
    await addProductType(page, t1)
    await addProductType(page, t2)
    // 属性断言按「本次新建的这个类型」过滤（其它类型下可能有同名属性，不能全局取最新）
    const t1Id = newestByName(await getAdminProductTypesApi(request, token), t1).id

    // A 类型下加两个属性：一个可筛选、一个不可
    await openAttributesOf(page, t1)
    const filterAttr = attributeNames[0]      // 颜色（可筛选）
    const plainAttr = attributeNames[3]       // 容量（不可筛选）
    await addAttribute(page, filterAttr, true)
    await addAttribute(page, plainAttr, false)

    const attrs = await getAdminAttributesApi(request, token, t1Id)
    const mine = attrs.filter((a) => a.name === filterAttr || a.name === plainAttr)
    expect(mine.length, '两个属性都应落库').toBeGreaterThanOrEqual(2)
    expect(newestByName(mine, filterAttr).isFilter, 'is_filter 应为 1').toBe(1)
    expect(newestByName(mine, plainAttr).isFilter, 'is_filter 应为 0').toBe(0)

    // 列表展示：「是否筛选」列显示 是 / 否
    await expect(attrRowOf(page, filterAttr), '可筛选属性应显示「是」').toContainText('是')
    await expect(attrRowOf(page, plainAttr), '不可筛选属性应显示「否」').toContainText('否')

    // 改名 + 关掉 is_filter
    const renamedAttr = `${attributeNames[1]}`     // 尺码
    await editAttribute(page, filterAttr, { name: renamedAttr, isFilter: false })
    await expect(attrRowOf(page, renamedAttr), '改名后应显示「否」').toContainText('否')
    const after = newestByName(await getAdminAttributesApi(request, token, t1Id), renamedAttr)
    expect(after.isFilter, 'is_filter 应被关成 0').toBe(0)

    // 聚合关系：B 类型的属性页看不到 A 的属性
    await openProductTypes(page)
    await openAttributesOf(page, t2)
    await expect(tid(page, 'attribute-list-table'), 'B 类型属性页不应出现 A 的属性').not.toContainText(renamedAttr)
  })

  test('删除类型/属性：被引用时被拒并给出中文原因，空的可正常删除', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openProductTypes(page)

    // ① 被引用的类型（种子「手机」有属性也有商品）→ 删除被拒
    await typeRowOf(page, '手机').getByTestId('product-type-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(page.locator('.el-message--error').first(), '被引用的类型应被拒绝').toContainText('不能删除', { timeout: 15_000 })
    expect((await getAdminProductTypesApi(request, token)).find((t) => t.name === '手机'), '类型应仍然存在').toBeTruthy()

    // ② 新建的空类型 → 可以删掉
    const empty = uniqueDisplayName(
      typeOf(stamp()),
      (await getAdminProductTypesApi(request, token)).map((t) => t.name)
    )
    await addProductType(page, empty)
    await typeRowOf(page, empty).getByTestId('product-type-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)
    expect((await getAdminProductTypesApi(request, token)).find((t) => t.name === empty), '空类型应被删除').toBeFalsy()

    // ③ 被 SKU 规格值引用的属性 → 删除被拒
    await openProductTypes(page)
    await openAttributesOf(page, '手机')
    const usedAttrRow = page.locator('.el-table__body tr').first()
    const usedAttrName = (await usedAttrRow.locator('td').nth(2).innerText()).trim()
    await usedAttrRow.getByTestId('attribute-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(page.locator('.el-message--error').first(), '被 SKU 使用的属性应被拒绝').toContainText('不能删除', { timeout: 15_000 })
    expect((await getAdminAttributesApi(request, token)).find((a) => a.name === usedAttrName)).toBeTruthy()

    // ④ 新建的空属性 → 可以删掉
    const emptyAttr = uniqueDisplayName(
      attributeOf(stamp()),
      (await getAdminAttributesApi(request, token)).map((a) => a.name)
    )
    await addAttribute(page, emptyAttr, false)
    await attrRowOf(page, emptyAttr).getByTestId('attribute-row-delete').click()
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)
    expect((await getAdminAttributesApi(request, token)).find((a) => a.name === emptyAttr), '空属性应被删除').toBeFalsy()
  })
})
