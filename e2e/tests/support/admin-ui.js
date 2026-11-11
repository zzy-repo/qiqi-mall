/**
 * 商家侧（admin-web）真实 UI 操作 helper —— 覆盖 docs/flows/02-业务场景/ 下
 * 02-商家-商品管理 / 03-商家-履约 / 04-商家-基础配置 / 05-账号与会员 / 06-数据概览 的场景。
 *
 * 规则与 support/ui.js 一致：这里只做真实点击/输入/可见性断言；
 * 接口只用于只读断言（support/assert.js），不允许用接口推进业务状态。
 * 买家侧的操作放 support/portal-ui.js，两边不要互相依赖。
 */
import { expect } from '@playwright/test'
import { ADMIN } from './env.js'

// ---------------------------------------------------------------------------
// 通用：Element Plus 弹窗 / 提示
// ---------------------------------------------------------------------------

/** 等一条成功提示出现（toast 会自己消失，所以要尽快断言） */
export const expectToastSuccess = async (page, text) => {
  const toast = page.locator('.el-message--success').first()
  await expect(toast).toBeVisible({ timeout: 15_000 })
  if (text) await expect(toast).toContainText(text)
}

/** 等一条失败提示出现 */
export const expectToastError = async (page, text) => {
  const toast = page.locator('.el-message--error').first()
  await expect(toast).toBeVisible({ timeout: 15_000 })
  if (text) await expect(toast).toContainText(text)
}

/** 点掉 ElMessageBox 的「确定」 */
export const confirmMessageBox = async (page) => {
  const box = page.locator('.el-message-box')
  await expect(box, '应弹出确认框').toBeVisible()
  await box.locator('.el-message-box__btns').getByRole('button', { name: '确定' }).click()
  await expect(box).toBeHidden({ timeout: 10_000 })
}

/** 当前列表有多少行 */
export const tableRowCount = (page) => page.locator('.el-table__body tr').count()

// ---------------------------------------------------------------------------
// 04-商家-基础配置 / 1-仓库管理
// ---------------------------------------------------------------------------

/** 打开「仓库管理」页（admin 左侧菜单 首页之外的「仓库管理」入口，直接走路由） */
export const gotoWarehouseList = async (page) => {
  await page.goto(`${ADMIN}/warehouse`)
  await page.waitForTimeout(1200)
}

/** 按名称定位仓库行 */
export const warehouseRow = (page, name) =>
  page.locator('.el-table__body tr').filter({ hasText: name }).first()

/** 打开新增（不传 row）或编辑（传 row）弹窗 */
export const openWarehouseForm = async (page, row) => {
  if (row) await row.getByRole('button', { name: '编辑' }).click()
  else await page.getByRole('button', { name: '新增仓库' }).click()
  await expect(page.locator('.el-dialog'), '仓库弹窗应打开').toBeVisible()
  await page.waitForTimeout(400)
}

/** 填仓库名称（空串 = 清空，用来测校验） */
export const fillWarehouseName = async (page, name) => {
  const input = page.locator('.el-dialog .el-form-item').filter({ hasText: '仓库名称' }).locator('input')
  await input.fill(name)
}

/** 切换启用/停用开关 */
export const setWarehouseStatus = async (page, enabled) => {
  const sw = page.locator('.el-dialog .el-switch')
  const isOn = ((await sw.getAttribute('class')) || '').includes('is-checked')
  if (isOn !== enabled) await sw.click()
  await page.waitForTimeout(200)
}

/** 点弹窗「确定」 */
export const submitWarehouseForm = async (page) => {
  await page.locator('.el-dialog').getByRole('button', { name: '确定' }).click()
}

/** 点弹窗「取消」 */
export const cancelWarehouseForm = async (page) => {
  await page.locator('.el-dialog').getByRole('button', { name: '取消' }).click()
  await expect(page.locator('.el-dialog')).toBeHidden({ timeout: 10_000 })
}

/** 表单校验错误文案（例如「请输入仓库名称」） */
export const formItemError = (page) => page.locator('.el-dialog .el-form-item__error').first()

/** 删除某个仓库（含确认框） */
export const deleteWarehouse = async (page, row) => {
  await row.getByRole('button', { name: '删除' }).click()
  await confirmMessageBox(page)
}
