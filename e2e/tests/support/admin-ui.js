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
import { tid } from './tid.js'
import { receiverOf } from './fixtures.js'
import {
  dangerDialog,
  dangerWillDo,
  dangerDetails,
  dangerOk,
  dangerCancel,
  dangerError,
  conflictDialog,
  conflictRefresh,
  waitDanger,
  confirmDanger,
  cancelDanger,
  dismissDanger,
  confirmDangerExpectError
} from './danger-ui.js'

// 高危操作确认弹窗 / 冲突弹窗的定位器与流程统一从 danger-ui.js 出，这里再导出一次，
// 复用方（spec 与其它 support 文件）只认 admin-ui.js 这一个入口即可。
export {
  dangerDialog,
  dangerWillDo,
  dangerDetails,
  dangerOk,
  dangerCancel,
  dangerError,
  conflictDialog,
  conflictRefresh,
  waitDanger,
  confirmDanger,
  cancelDanger,
  dismissDanger,
  confirmDangerExpectError
}

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

/** 当前列表有多少行；可传表格 testid 限定是哪张表（不传则全页统计） */
export const tableRowCount = (page, tableTid) =>
  (tableTid ? tid(page, tableTid) : page).locator('.el-table__body tr').count()

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
  const input = tid(page, 'order-list-search-sn')
  await input.fill(orderSn)
  await input.press('Enter')
  await page.waitForTimeout(1500)
  // 订单行由 el-table 渲染，没有行锚点 → 按订单号文本过滤（tid.js 未覆盖第 4 条）
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `订单列表应能看到 ${orderSn}`).toBeVisible()
  return row
}

/**
 * 审核通过：行内「审核通过」→ 预演确认弹窗 →「确认执行」。
 *
 * 新 UI 把原来的「审核」+ ElMessageBox（通过/不通过二选一）拆成了两个一级行按钮，
 * 点下去直接进统一的「预演 → 确认 → 执行」弹窗（DangerConfirmDialog，全站单例）。
 * 这里多了一道**验收点**（旧版没有）：弹窗必须用中文说清「待审核→待发货」。
 */
export const auditPassOrder = async (page, row) => {
  await row.getByTestId('order-row-audit-pass').click()
  await confirmDanger(page, { willDo: '待审核→待发货' })
  await page.waitForTimeout(1200)   // 等列表按新状态重拉
}

/**
 * 「只看拆单方案、不执行」：点「审核通过」→ 等预演把方案列进弹窗明细区 → 点「取消」。
 *
 * 原来独立的「拆单方案预览」弹窗（order-split-preview-dialog）已并入 danger 弹窗的
 * details 区，所以「只看不做」= 打开 → 断言/读取明细 → 取消（取消不发任何请求）。
 * @returns 明细区每行的文本（第 1 行是「仓名（N 条明细）」）
 */
export const openSplitPreview = async (page, row) => {
  await row.getByTestId('order-row-audit-pass').click()
  await waitDanger(page, { willDo: '待审核→待发货' })
  const details = dangerDetails(page)
  await expect(details, '预演弹窗应把拆单方案列进明细区（哪个仓、几条明细、哪些商品）').toBeVisible()
  const plan = await details.locator('.detail-row').allInnerTexts()
  await cancelDanger(page)
  return plan
}

/**
 * 用「新增订单」表单在 UI 里造一笔订单（状态可选，一般造「待审核」再走审核通过）。
 *
 * 收货人/电话/地址都会显示在弹窗和订单列表里，所以默认值取自 fixtures 的真实地址簿，
 * 不再用 `调整E2E` / `E2E路` 这种测试味文案；调用方可用 receiver 覆盖（建议传 receiverOf(seed)，
 * 保证姓名-电话-地址是同一个人的）。
 * @param items [{ productName, skuIndex, quantity }]
 * @param receiver { receiverName, receiverPhone, receiverAddress }
 * @returns 订单号（保存后列表第一行）
 */
export const createOrderViaUi = async (page, { receiver, status = '待审核', items }) => {
  const who = receiver ?? receiverOf('新增订单默认收货人')
  // 订单列表页头的「新增」按钮没有 testid（本轮 testid 只覆盖了搜索/筛选/行操作），按文案点
  await page.getByRole('button', { name: '新增' }).click()
  const dlg = tid(page, 'order-form-dialog')
  await expect(dlg, '新增订单弹窗应打开').toBeVisible()

  // 买家：选第一个会员（浮层选项没有 testid，按文本/序号选 —— tid.js 未覆盖第 3 条）
  await tid(page, 'order-form-member').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').first().click()

  await tid(page, 'order-form-receiver-name').fill(who.receiverName)
  await tid(page, 'order-form-receiver-phone').fill(who.receiverPhone)
  await tid(page, 'order-form-receiver-address').fill(who.receiverAddress)

  // 状态
  await tid(page, 'order-form-status').click()
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

  await tid(page, 'order-form-submit').click()
  await page.waitForTimeout(1800)
  const firstRow = page.locator('.el-table__body tr').first()
  await expect(firstRow).toBeVisible()
  return (await firstRow.locator('td').first().innerText()).trim()
}

/** 审核不通过：行内「不通过」→ 预演确认弹窗（说明关闭订单 + 回补哪些库存）→「确认执行」 */
export const auditRejectOrder = async (page, row) => {
  await row.getByTestId('order-row-audit-reject').click()
  await confirmDanger(page, { willDo: '待审核→已关闭' })
  await page.waitForTimeout(1200)
}

/** 模拟 WMS 回传：拣货/出库/揽收/签收（选完事件后还要过一次统一确认弹窗） */
export const wmsCallback = async (page, row, label) => {
  await row.getByTestId('delivery-row-wms-callback').click()
  await page.waitForTimeout(500)
  await page.locator('.el-dropdown-menu__item:visible').filter({ hasText: label }).first().click()
  await confirmDanger(page)
  await page.waitForTimeout(800)
}

/** 订单列表某行的状态文字（行内状态标签有 testid，不再依赖列序） */
export const orderStatusOf = async (row) => (await row.getByTestId('order-row-status').innerText()).trim()

/** 点「明细」打开订单详情抽屉，返回抽屉内的发货单表格 */
export const openOrderDrawer = async (page, row) => {
  await row.getByTestId('order-row-detail').click()
  const drawer = tid(page, 'order-detail-drawer')
  await expect(drawer, '订单详情抽屉应打开').toBeVisible()
  await expect(drawer.locator('h4').filter({ hasText: '发货单' })).toBeVisible()
  return tid(page, 'order-detail-delivery-table')
}

/** 抽屉里按仓库名定位发货单行 */
export const deliveryRowOf = (deliveryTable, warehouseName) =>
  deliveryTable.locator('.el-table__body tr').filter({ hasText: warehouseName }).first()

/** 抽屉里按发货单号定位（仓库名可能重名，用单号更稳） */
export const deliveryRowBySn = (deliveryTable, deliverySn) =>
  deliveryTable.locator('.el-table__body tr').filter({ hasText: deliverySn }).first()

/** 抽屉里当前有几个包裹 */
export const deliveryRowCount = (deliveryTable) => deliveryTable.locator('.el-table__body tr').count()

/** 点发货单行内某个操作（编辑/拆分/合并/删除/下发/取消）—— 动作名 → testid 映射 */
const DELIVERY_ACTION_TID = {
  编辑: 'delivery-row-edit',
  拆分: 'delivery-row-split',
  合并: 'delivery-row-merge',
  删除: 'delivery-row-remove',
  下发: 'delivery-row-dispatch',
  取消: 'delivery-row-cancel'
}
export const deliveryActionTid = (name) => DELIVERY_ACTION_TID[name]

/**
 * 「点一下就进确认弹窗」的动作（没有表单要填）。
 * 编辑/拆分/合并/删除 也走同一个弹窗，但它们先弹表单弹窗，确认动作在
 * submitDeliveryDialog / submitMergeDialog 里做。
 */
const DANGER_ONLY_ACTIONS = new Set(['下发', '取消'])
/** 这些动作的预演文案里应含的状态流转（验收点：用户得先看清将要发生什么） */
const DANGER_ACTIONS_WILLDO = { 下发: '待下发→已下发', 取消: '待下发→已取消' }

export const clickDeliveryAction = async (page, row, name) => {
  const testId = DELIVERY_ACTION_TID[name]
  if (testId) await row.getByTestId(testId).click()
  else await row.getByRole('button', { name, exact: true }).click()   // 兜底：未知动作
  await page.waitForTimeout(900)
  // 下发 / 取消 是「一步到位」的高危操作：点下去直接进统一确认弹窗，没有表单要填
  if (DANGER_ONLY_ACTIONS.has(name)) await confirmDanger(page, { willDo: DANGER_ACTIONS_WILLDO[name] })
}

/** 编辑/拆分弹窗（两种模式共用一个弹窗组件） */
export const deliveryDialog = (page) => tid(page, 'delivery-form-dialog')

/** 抽屉里某一行「发货仓」列的文字（第 2 列） */
export const warehouseCellOf = async (row) => (await row.locator('td').nth(1).innerText()).trim()

/**
 * 在编辑弹窗里挑一个「不在 excluded 里」的仓，返回选中的仓名。
 * 用途：改仓时避开两个包裹已有的仓，免得改完两个包同名、后面定位不清。
 */
export const pickWarehouseExcluding = async (page, excluded = []) => {
  await tid(page, 'delivery-form-warehouse').click()
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
  await tid(page, 'delivery-form-warehouse').click()
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

/**
 * 提交编辑/拆分弹窗。
 * 新 UI：表单收起后走统一确认弹窗（编辑的 body 里带行级 version，冲突时改弹 ConflictDialog），
 * 所以这里必须再点一次「确认执行」——这正是这次要覆盖的交互。
 */
export const submitDeliveryDialog = async (page) => {
  await tid(page, 'delivery-form-submit').click()
  await confirmDanger(page)
  await page.waitForTimeout(800)
}

/** 合并/删除并入弹窗：选目标包裹（按发货单号） */
export const pickMergeTarget = async (page, deliverySn) => {
  await expect(tid(page, 'delivery-merge-dialog'), '合并弹窗应打开').toBeVisible()
  await tid(page, 'delivery-merge-target').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: deliverySn }).first().click()
  await page.waitForTimeout(300)
}

/** 提交合并/删除并入弹窗（同样是「预演 → 确认 → 执行」） */
export const submitMergeDialog = async (page) => {
  await tid(page, 'delivery-merge-submit').click()
  await confirmDanger(page)
  await page.waitForTimeout(800)
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
  if (row) await row.getByTestId('warehouse-row-edit').click()
  else await tid(page, 'warehouse-list-create').click()
  await expect(tid(page, 'warehouse-form-dialog'), '仓库弹窗应打开').toBeVisible()
  await page.waitForTimeout(400)
}

/** 填仓库名称（空串 = 清空，用来测校验） */
export const fillWarehouseName = async (page, name) => {
  await tid(page, 'warehouse-form-name').fill(name)
}

/** 切换启用/停用开关 */
export const setWarehouseStatus = async (page, enabled) => {
  const sw = tid(page, 'warehouse-form-status')
  const isOn = ((await sw.getAttribute('class')) || '').includes('is-checked')
  if (isOn !== enabled) await sw.click()
  await page.waitForTimeout(200)
}

/** 点弹窗「确定」 */
export const submitWarehouseForm = async (page) => {
  await tid(page, 'warehouse-form-submit').click()
}

/** 点弹窗「取消」 */
export const cancelWarehouseForm = async (page) => {
  await tid(page, 'warehouse-form-cancel').click()
  await expect(tid(page, 'warehouse-form-dialog')).toBeHidden({ timeout: 10_000 })
}

/** 表单校验错误文案（例如「请输入仓库名称」） */
// 校验错误文本由 el-form-item 内部渲染（tid.js 未覆盖第 7 条），用弹窗 testid 限定范围
export const formItemError = (page) => tid(page, 'warehouse-form-dialog').locator('.el-form-item__error').first()

/** 删除某个仓库（含确认框） */
export const deleteWarehouse = async (page, row) => {
  await row.getByTestId('warehouse-row-remove').click()
  await confirmMessageBox(page)
}
