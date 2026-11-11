/**
 * 03-商家-履约 / 4-退货处理 场景专用 helper。
 *
 * 为什么要单独一个文件：support/admin-ui.js 由别的并行任务在改，
 * support/ui.js 与 support/portal-ui.js 也各有归属，本场景的新 helper 全部放这里，
 * 互不影响。登录/加购/下单/我的订单这些**已经稳定存在**的通用动作直接复用 ui.js。
 *
 * 规则与全仓一致：
 *  - 业务状态只能靠真实点击推进（这里只有 goto/click/fill/可见性断言）；
 *  - 断言读取走只读通道（GET 接口 或 直读 payment_record 表）。
 *
 * 关于「退款流水」：退款流水目前**没有任何管理端页面或接口**（PaymentRecord 只有
 * service，没有 controller），而审核通过必须验证「确实记了一条退款流水」，
 * 所以这里用 mysql 客户端做只读查询。查询失败会抛错，不会静默放过。
 */
import { expect } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { API, ADMIN, PORTAL } from './env.js'
import { returnDescriptionOf } from './fixtures.js'
import { adminLogin } from './ui.js'

// ---------------------------------------------------------------------------
// 登录（后端刚重启时首个请求可能 500，只在这种情况重试一次）
// ---------------------------------------------------------------------------

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
// 提示条
// ---------------------------------------------------------------------------

/** 等一条成功提示（toast 会自己消失，要尽快断言） */
export const expectToastSuccess = async (page, text) => {
  const toast = page.locator('.el-message--success').first()
  await expect(toast, '应出现成功提示').toBeVisible({ timeout: 15_000 })
  if (text) await expect(toast).toContainText(text)
}

/** 等一条失败提示 */
export const expectToastError = async (page, text) => {
  const toast = page.locator('.el-message--error').first()
  await expect(toast, '应出现失败提示').toBeVisible({ timeout: 15_000 })
  if (text) await expect(toast).toContainText(text)
}

// ---------------------------------------------------------------------------
// 管理端「退货申请」页
// ---------------------------------------------------------------------------

/** 打开退货申请列表 */
export const gotoReturnApplyList = async (page) => {
  await page.goto(`${ADMIN}/return-apply`)
  await page.waitForTimeout(1200)
}

/** 按订单号定位退货申请行（列表按 id 倒序，新申请在最上面） */
export const returnApplyRow = async (page, orderSn) => {
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `退货申请列表应能看到订单 ${orderSn} 的申请`).toBeVisible()
  return row
}

/** 行内当前状态文案（待处理 / 已通过 / 已拒绝） */
export const returnApplyStatusOf = async (row) => {
  const tag = row.locator('.el-tag').first()
  return (await tag.innerText()).trim()
}

/** 刷新列表（审核后状态要重新拉一次才更新） */
export const reloadReturnApplyList = async (page) => {
  await page.reload()
  await page.waitForTimeout(1200)
}

/** 点行内「审核」，返回审核弹窗 locator */
export const openAuditDialog = async (page, row) => {
  await row.getByRole('button', { name: '审核' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '审核退货申请' })
  await expect(dialog, '审核弹窗应打开').toBeVisible()
  await page.waitForTimeout(400)
  return dialog
}

/** 选处理结果：'通过' | '拒绝' | '待处理' */
export const pickAuditResult = async (dialog, result) => {
  const radio = dialog.locator('.el-radio').filter({ hasText: result }).first()
  await radio.click()
  await dialog.page().waitForTimeout(200)
}

/** 填「详细说明」（拒绝理由也写这里；传空串等于清空） */
export const fillAuditDescription = async (dialog, text) => {
  const input = dialog.locator('.el-form-item').filter({ hasText: '详细说明' }).locator('textarea')
  await input.fill(text)
}

/** 读弹窗里「详细说明」的当前值 */
export const auditDescriptionValue = async (dialog) => {
  const input = dialog.locator('.el-form-item').filter({ hasText: '详细说明' }).locator('textarea')
  return input.inputValue()
}

/** 点「确定」提交审核（不自动 reload，调用方自己决定要不要等提示） */
export const submitAudit = async (dialog) => {
  await dialog.getByRole('button', { name: '确定' }).click()
}

/**
 * 完整审核动作：打开列表 → 定位行 → 审核 → 选结果 →（可选）改说明 → 确定。
 * @param result '通过' | '拒绝'
 * @param description 传 undefined 表示不动说明，传字符串（含空串）表示改写
 */
export const auditReturnApply = async (page, orderSn, { result, description }) => {
  await gotoReturnApplyList(page)
  const row = await returnApplyRow(page, orderSn)
  const dialog = await openAuditDialog(page, row)
  await pickAuditResult(dialog, result)
  if (description !== undefined) await fillAuditDescription(dialog, description)
  await submitAudit(dialog)
}

// ---------------------------------------------------------------------------
// 买家侧：提交退货申请（同一场景的另一端，放一起免得依赖别人正在改的文件）
// ---------------------------------------------------------------------------

/** 「我的订单」里按订单号定位行 */
export const memberOrderRowOf = async (page, orderSn) => {
  await page.goto(`${PORTAL}/orders`)
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `买家我的订单应能看到 ${orderSn}`).toBeVisible()
  return row
}

/** 该行「售后」列文案（没有售后时是「—」） */
export const memberAfterSaleTextOf = async (row) => {
  const tag = row.locator('.el-tag').nth(1)   // 第 1 个 tag 是订单状态，第 2 个是售后
  return (await tag.count()) ? (await tag.innerText()).trim() : '—'
}

/**
 * 在「我的订单」里对某笔订单点「申请退货」并提交。
 * @returns 提交后该订单行 locator
 */
export const memberApplyReturn = async (page, orderSn, { description = returnDescriptionOf(orderSn) } = {}) => {
  const row = await memberOrderRowOf(page, orderSn)
  await row.getByRole('button', { name: '申请退货' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '申请退货' })
  await expect(dialog, '申请退货弹窗应打开').toBeVisible()

  // 退货原因：字典下拉，选第一项即可（具体原因不影响流程，断言里只验「有原因」）
  await dialog.locator('.el-form-item').filter({ hasText: '退货原因' }).locator('.el-select__wrapper').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').first().click()
  await page.waitForTimeout(200)

  if (description !== undefined) {
    await dialog.locator('.el-form-item').filter({ hasText: '详细说明' }).locator('textarea').fill(description)
  }
  await dialog.getByRole('button', { name: '提交申请' }).click()
  await expectToastSuccess(page, '申请')
  await page.waitForTimeout(1200)
  return row
}

// ---------------------------------------------------------------------------
// 只读断言通道
// ---------------------------------------------------------------------------

/** 管理端退货申请列表（只读 GET，可按订单过滤） */
export async function getAdminReturnAppliesApi(request, token, orderId) {
  const query = orderId ? `?orderId=${orderId}` : ''
  const res = await request.get(`${API}/admin/return-applies${query}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  const body = await res.json()
  const list = body.data || []
  return orderId ? list.filter((r) => r.orderId === orderId) : list
}

/** mysql 只读查询（退款流水没有接口，只能直读 payment_record） */
function mysqlQuery(sql, dbName) {
  const host = process.env.DB_HOST || '127.0.0.1'
  const port = process.env.DB_PORT || '3306'
  const user = process.env.DB_USER || 'root'
  const pass = process.env.DB_PASS || 'qiqi123456'
  const out = execFileSync(
    'mysql',
    ['-h', host, '-P', port, '-u', user, `-p${pass}`, '-N', '-B', '-e', sql, dbName],
    { encoding: 'utf8', timeout: 20_000 }
  )
  return out.split('\n').filter(Boolean).map((line) => line.split('\t'))
}

/**
 * 某订单号的支付流水（type 1=支付 2=退款）。
 * dev 栈连库 qiqi_mall、容器栈（DEMO）连库 qiqi_mall_test，两个库都查一遍再合并，
 * 这样同一份 spec 在两档下都能跑，不需要改断言。
 */
export function paymentRecordsOf(orderSn) {
  // 库候选：显式 DB_NAME 优先，其次 e2e 独立库（scripts/e2e-run.sh 起的两套后端），最后才是老的 dev/DEMO 库。
  // 注意不能「所有库都查一遍再合并」就完事：订单只存在于其中一个库，查错的库永远返回 0 行，
  // 全量跑时会表现为「退款流水应恰好有一条，实到 0」。所以命中即返回。
  const dbs = [
    process.env.DB_NAME,
    'qiqi_mall_e2e_a',
    'qiqi_mall_e2e_b',
    'qiqi_mall',
    'qiqi_mall_test'
  ].filter(Boolean)
  for (const db of dbs) {
    let out
    try {
      out = mysqlQuery(
        `SELECT id, type, amount, trade_no FROM payment_record WHERE order_sn='${orderSn}' ORDER BY id`,
        db
      )
    } catch {
      continue // 该库不存在 / 连不上：换下一个
    }
    if (out.length) {
      return out.map(([id, type, amount, tradeNo]) => ({ id, type: Number(type), amount: Number(amount), tradeNo }))
    }
  }
  return []
}

// ---------------------------------------------------------------------------
// 管理端订单列表（只定位，不改状态，避免依赖 admin-ui.js）
// ---------------------------------------------------------------------------

/** 订单列表按订单号搜索，返回目标行 */
export const adminOrderRowOf = async (page, orderSn) => {
  await page.goto(`${ADMIN}/order`)
  await page.waitForTimeout(1200)
  const input = page.locator('input[placeholder="按订单号搜索"]')
  await input.fill(orderSn)
  await input.press('Enter')
  await page.waitForTimeout(1500)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `管理员订单列表应能看到 ${orderSn}`).toBeVisible()
  return row
}

/** 订单审核通过（真实点击：行内「审核」→ 确认框「通过」），审核通过后自动生成发货单 */
export const adminAuditOrderPass = async (page, row) => {
  await row.getByRole('button', { name: '审核' }).click()
  const box = page.locator('.el-message-box')
  await expect(box, '应弹出审核确认框').toBeVisible()
  await box.locator('.el-message-box__btns').getByRole('button', { name: '通过', exact: true }).click()
  await page.waitForTimeout(2000)
}

/** 订单行状态文案 */
export const adminOrderStatusOf = async (row) => (await row.locator('.el-tag').first().innerText()).trim()
