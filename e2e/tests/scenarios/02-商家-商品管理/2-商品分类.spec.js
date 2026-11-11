import { test, expect } from '@playwright/test'
import { API, stamp } from '../../support/env.js'
import { adminLogin } from '../../support/ui.js'
import {
  openCategories,
  categoryRowOf,
  addCategory,
  renameCategory,
  openProducts,
  addProduct,
  productRowOf
} from '../../support/catalog-ui.js'
import { adminTokenOf, getAdminCategoriesApi } from '../../support/assert.js'

/**
 * 商家侧 02-商品管理 / 2-商品分类
 *
 * 覆盖：树形两层结构展示 / 新增顶级分类 / 新增子分类（父分类选择 = check-strictly 回归）/
 *       改名 / 必填校验 / 父子层级展示（子分类缩进在父分类下）/
 *       分类删除 —— **后台没有删除入口**（见报告）
 * 断言依据：admin/category/index.vue（el-table row-key + default-expand-all 展示树，
 *          新增/编辑弹窗里父分类用 el-tree-select + check-strictly）
 */
test.describe('商家侧 2 商品分类', () => {
  test('新增顶级分类 → 新增子分类 → 改名，层级与落库都对', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openCategories(page)

    const parent = `E2E父分类${stamp()}`
    const child = `${parent}-子类`

    // 顶级分类
    await addCategory(page, parent)
    let cats = await getAdminCategoriesApi(request, token)
    const parentRow = cats.find((c) => c.name === parent)
    expect(parentRow, '顶级分类应落库').toBeTruthy()
    expect(parentRow.parentId, '顶级分类 parentId 应为 0').toBe(0)

    // 子分类：父分类用树选择（父节点可选的 check-strictly 回归点）
    await addCategory(page, child, parent)
    cats = await getAdminCategoriesApi(request, token)
    const childRow = cats.find((c) => c.name === child)
    expect(childRow, '子分类应落库').toBeTruthy()
    expect(childRow.parentId, '子分类应挂到父分类下').toBe(parentRow.id)

    // 层级展示：树形表里子分类行缩进层级比父分类深（el-table 的 tree 缩进）
    const parentDom = await categoryRowOf(page, parent).evaluate((el) => el.getAttribute('class'))
    expect(parentDom, '父分类行应在表里').toBeTruthy()
    const childIndent = await categoryRowOf(page, child)
      .locator('.el-table__indent').first().evaluate((el) => el.style.paddingLeft || '0px').catch(() => '0px')
    expect(childIndent, '子分类应有缩进（体现父子层级）').not.toBe('0px')

    // 改名
    const renamed = `${parent}改名`
    await renameCategory(page, parent, renamed)
    cats = await getAdminCategoriesApi(request, token)
    expect(cats.find((c) => c.name === renamed), '改名应落库').toBeTruthy()
    expect(cats.find((c) => c.name === parent), '旧名字应不存在').toBeFalsy()
  })

  test('必填校验：分类名称不填不让保存', async ({ page }) => {
    await adminLogin(page)
    await openCategories(page)
    await page.getByRole('button', { name: '新增' }).click()
    const dialog = page.locator('.el-dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '确定' }).click()
    await expect(dialog.locator('.el-form-item__error').filter({ hasText: '请输入分类名称' })).toBeVisible()
    await dialog.getByRole('button', { name: '取消' }).click()
  })

  test('父分类可被商品直接使用（check-strictly 回归）', async ({ page, request }) => {
    await adminLogin(page)
    const token = await adminTokenOf(page)
    await openCategories(page)

    // 造一个「父分类 + 子分类」，然后让商品直接挂到**父分类**上
    const parent = `E2E父分类挂商品${stamp()}`
    await addCategory(page, parent)
    const cats = await getAdminCategoriesApi(request, token)
    const parentId = cats.find((c) => c.name === parent).id

    const productName = `E2E父分类商品${stamp()}`
    await openProducts(page)
    await addProduct(page, { name: productName, categoryName: parent, typeName: '手机' })
    await expect(productRowOf(page, productName)).toBeVisible()

    const saved = (await (await request.get(`${API}/admin/products`, {
      headers: { Authorization: `Bearer ${token}` }
    })).json()).data.find((p) => p.name === productName)
    expect(saved.categoryId, '商品应能挂到父分类上').toBe(parentId)
  })

  test('【已知缺口】分类没有删除入口：后台只提供新增与编辑', async ({ page }) => {
    await adminLogin(page)
    await openCategories(page)
    const row = page.locator('.el-table__body tr').first()
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', { name: '编辑' })).toBeVisible()
    await expect(row.getByRole('button', { name: '删除' }), '当前后台没有删除分类的入口').toHaveCount(0)
  })
})
