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
import { adminLogin } from './ui.js'

// ---------------------------------------------------------------------------
// 通用：登录（带一次 500 重试）
// ---------------------------------------------------------------------------

/**
 * 管理员登录。
 * 后端刚重启时**首个请求可能 500**，这不是业务缺陷，所以仅在这种情况重试一次；
 * 401/403 或其它失败一律照实抛出，不能被重试掩盖。
 */
export const adminLoginHardened = async (page) => {
  try {
    await adminLogin(page)
  } catch (e) {
    const body = await page.locator('body').innerText().catch(() => '')
    if (!/status code 500/i.test(body)) throw e
    await page.goto(`${ADMIN}/login`)
    await page.waitForTimeout(1500)
    await adminLogin(page)
  }
}

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
// 03-商家-履约：订单列表 / 审核 / 订单详情抽屉 / 发货单人工调整
// ---------------------------------------------------------------------------

/** 打开订单列表 */
export const gotoOrderList = async (page) => {
  await page.goto(`${ADMIN}/order`)
  await page.waitForTimeout(1200)
}

/** 按订单号搜索，返回目标行 */
export const searchOrderRow = async (page, orderSn) => {
  const input = page.locator('input[placeholder="按订单号搜索"]')
  await input.fill(orderSn)
  await input.press('Enter')
  await page.waitForTimeout(1500)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `订单列表应能看到 ${orderSn}`).toBeVisible()
  return row
}

/** 点「审核」并选「通过」（走审核弹窗，不点「查看拆单方案」） */
export const auditPassOrder = async (page, row) => {
  await row.getByRole('button', { name: '审核' }).click()
  const box = page.locator('.el-message-box')
  await expect(box, '应弹出审核确认框').toBeVisible()
  await box.locator('.el-message-box__btns').getByRole('button', { name: '通过', exact: true }).click()
  await page.waitForTimeout(2000)
}

/**
 * 用「新增订单」表单在 UI 里造一笔订单（状态可选，一般造「待审核」再走审核通过）。
 * @param items [{ productName, skuIndex, quantity }]
 * @returns 订单号（保存后列表第一行）
 */
export const createOrderViaUi = async (page, { receiverName = '调整E2E', status = '待审核', items }) => {
  await page.getByRole('button', { name: '新增' }).click()
  const dlg = page.locator('.el-dialog').filter({ hasText: '新增订单' })
  await expect(dlg, '新增订单弹窗应打开').toBeVisible()

  // 买家：选第一个会员
  await dlg.locator('.el-form-item').filter({ hasText: '买家' }).locator('.el-select').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').first().click()

  await dlg.locator('.el-form-item').filter({ hasText: '收货人' }).locator('input').fill(receiverName)
  await dlg.locator('.el-form-item').filter({ hasText: '收货电话' }).locator('input').fill('13600003333')
  await dlg.locator('.el-form-item').filter({ hasText: '收货地址' }).locator('textarea').fill('广东省深圳市南山区E2E路 9 号')

  // 状态
  await dlg.locator('.el-form-item').filter({ hasText: '状态' }).locator('.el-select').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: status }).first().click()
  await page.waitForTimeout(300)

  for (let i = 0; i < items.length; i++) {
    if (i > 0) {
      await dlg.getByRole('button', { name: '加一条明细' }).click()
      await page.waitForTimeout(300)
    }
    const row = dlg.locator('.el-table__body tr').nth(i)
    await row.locator('.el-select').first().click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: items[i].productName }).first().click()
    await page.waitForTimeout(400)
    await row.locator('.el-select').nth(1).click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible').nth(items[i].skuIndex ?? 0).click()
    await page.waitForTimeout(400)
    const qty = row.locator('.el-input-number input').last()
    await qty.fill(String(items[i].quantity))
    await qty.press('Tab')
    await page.waitForTimeout(200)
  }

  await dlg.getByRole('button', { name: '确定' }).click()
  await page.waitForTimeout(1800)
  const firstRow = page.locator('.el-table__body tr').first()
  await expect(firstRow).toBeVisible()
  return (await firstRow.locator('td').first().innerText()).trim()
}

/** 点「明细」打开订单详情抽屉，返回抽屉内的发货单表格 */
export const openOrderDrawer = async (page, row) => {
  await row.getByRole('button', { name: '明细' }).click()
  const drawer = page.locator('.el-drawer')
  await expect(drawer, '订单详情抽屉应打开').toBeVisible()
  await expect(drawer.locator('h4').filter({ hasText: '发货单' })).toBeVisible()
  return drawer.locator('.el-table').first()
}

/** 抽屉里按仓库名定位发货单行 */
export const deliveryRowOf = (deliveryTable, warehouseName) =>
  deliveryTable.locator('.el-table__body tr').filter({ hasText: warehouseName }).first()

/** 抽屉里按发货单号定位（仓库名可能重名，用单号更稳） */
export const deliveryRowBySn = (deliveryTable, deliverySn) =>
  deliveryTable.locator('.el-table__body tr').filter({ hasText: deliverySn }).first()

/** 抽屉里当前有几个包裹 */
export const deliveryRowCount = (deliveryTable) => deliveryTable.locator('.el-table__body tr').count()

/** 点发货单行内某个操作（编辑/拆分/合并/删除/下发/取消） */
export const clickDeliveryAction = async (page, row, name) => {
  await row.getByRole('button', { name, exact: true }).click()
  await page.waitForTimeout(900)
}

/** 编辑/拆分弹窗（两种标题共用一个选择器） */
export const deliveryDialog = (page) => page.locator('.el-dialog').filter({ hasText: /编辑包裹|拆分包裹/ })

/** 抽屉里某一行「发货仓」列的文字（第 2 列） */
export const warehouseCellOf = async (row) => (await row.locator('td').nth(1).innerText()).trim()

/**
 * 在编辑弹窗里挑一个「不在 excluded 里」的仓，返回选中的仓名。
 * 用途：改仓时避开两个包裹已有的仓，免得改完两个包同名、后面定位不清。
 */
export const pickWarehouseExcluding = async (page, excluded = []) => {
  const dlg = deliveryDialog(page)
  await dlg.locator('.el-select').first().click()
  await page.waitForTimeout(400)
  const items = page.locator('.el-select-dropdown__item:visible')
  const count = await items.count()
  for (let i = 0; i < count; i++) {
    const text = (await items.nth(i).innerText()).trim()
    if (text && !excluded.includes(text)) {
      await items.nth(i).click()
      await page.waitForTimeout(300)
      return text
    }
  }
  throw new Error(`没有可选的仓库（已排除 ${excluded.join('/')}）`)
}

/** 在编辑/拆分弹窗里选发货仓 */
export const pickDialogWarehouse = async (page, warehouseName) => {
  const dlg = deliveryDialog(page)
  await dlg.locator('.el-select').first().click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: warehouseName }).first().click()
  await page.waitForTimeout(300)
}

/** 在编辑/拆分弹窗里改第 rowIndex 行明细的数量 */
export const setDialogQty = async (page, rowIndex, qty) => {
  const dlg = deliveryDialog(page)
  const input = dlg.locator('.el-table__body tr').nth(rowIndex).locator('input').first()
  await input.fill(String(qty))
  await input.press('Tab')
  await page.waitForTimeout(200)
}

/** 提交编辑/拆分弹窗 */
export const submitDeliveryDialog = async (page) => {
  await deliveryDialog(page).getByRole('button', { name: '确定' }).click()
  await page.waitForTimeout(1500)
}

/** 合并/删除并入弹窗：选目标包裹（按发货单号） */
export const pickMergeTarget = async (page, deliverySn) => {
  const dlg = page.locator('.el-dialog').filter({ hasText: '合并包裹' })
  await expect(dlg, '合并弹窗应打开').toBeVisible()
  await dlg.locator('.el-select').first().click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: deliverySn }).first().click()
  await page.waitForTimeout(300)
}

/** 提交合并/删除并入弹窗 */
export const submitMergeDialog = async (page) => {
  await page.locator('.el-dialog').filter({ hasText: '合并包裹' }).getByRole('button', { name: '确定' }).click()
  await page.waitForTimeout(1500)
}

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
