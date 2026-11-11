/**
 * 商家侧「基础配置」场景（04-商家-基础配置 / 2-订单设置、3-退货原因字典）的真实 UI 操作 helper。
 *
 * 为什么单独一个文件：
 *   1. support/admin-ui.js 归另一位同学维护（并发作业中），本场景新增的 helper 一律放这里，避免互相踩；
 *   2. support/ui.js 只读，不做修改。
 *
 * 三条硬规则（与 support/ui.js、support/admin-ui.js 完全一致）：
 *   - 「业务操作」只能是真的点页面：本文件里所有 goto / click / fill 都作用在真实控件上；
 *   - 「断言读取」只允许 GET：见文件末尾的 readXxx 系列，命名一律以 read 开头，方便一眼分辨；
 *   - 字典数据的增删改**走接口准备**：管理端「退货原因字典」目前没有维护界面（详见
 *     docs/flows/02-业务场景/04-商家-基础配置/3-退货原因字典/1-业务流程.mmd 的现状说明），
 *     属于 support/data.js 里定义的「页面无入口的数据准备动作」，故放在本文件并单独分区标注。
 */
import { expect } from '@playwright/test'
import { ADMIN, API } from './env.js'
import { adminLoginHardened } from './admin-ui.js'

// 与 env.js 的约定保持一致：本文件的读写都指向同一个后端
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

// ---------------------------------------------------------------------------
// 通用：并发环境下的稳妥登录
// ---------------------------------------------------------------------------

/**
 * 后端「连续登录失败 5 次要求图形验证码」的失败计数是按**用户名**记的（5 分钟窗口，
 * 成功登录会清零）。并发跑用例时，只要有人拿 admin 去跑「密码错误」分支，
 * 就会把这个计数打满，导致同一时间所有 admin 用例卡在登录页。
 *
 * 这里在登录前先用只读接口问一下要不要验证码，要就等窗口过去（最多 5 分钟）再登，
 * 并对「等待期间又被别人打满」做有限次重试。只读接口 + 真实登录页，不绕过校验。
 */
export const waitAdminCaptchaWindow = async (page, timeoutMs = 55_000) => {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const res = await page.request.get(`${API}/admin/auth/captcha-required?username=admin`)
    const need = (await res.json()).data === true
    if (!need) return
    await page.waitForTimeout(5000)
  }
}

/** 登录（先等验证码窗口，再走真实登录页；被别人的并发用例二次打满时再等一次） */
export const adminLoginResilient = async (page, { attempts = 3, waitWindowMs = 55_000 } = {}) => {
  for (let i = 0; i < attempts; i++) {
    await waitAdminCaptchaWindow(page, waitWindowMs)
    try {
      await adminLoginHardened(page)
      return
    } catch (e) {
      const body = await page.locator('body').innerText().catch(() => '')
      const blockedByCaptcha = /验证码/.test(body)
      if (!blockedByCaptcha || i === attempts - 1) throw e
      await page.waitForTimeout(20_000)
    }
  }
}

// ---------------------------------------------------------------------------
// 04-商家-基础配置 / 2-订单设置 —— 真实 UI
// ---------------------------------------------------------------------------

/** 打开「订单设置」页（左侧菜单「订单 › 订单设置」的目标路由） */
export const gotoOrderSetting = async (page) => {
  await page.goto(`${ADMIN}/order-setting`)
  // 页面 onMounted 才拉配置，等两个数字框渲染出值
  await expect(page.locator('[data-testid="order-setting-form-auto-confirm-days"] input')).toBeVisible({
    timeout: 15_000
  })
  await page.waitForTimeout(800)
}

/** 两个数字输入框（天数 / 分钟数）—— 走 data-testid，不依赖中文标签 */
export const settingDaysInput = (page) => page.locator('[data-testid="order-setting-form-auto-confirm-days"] input')
export const settingMinutesInput = (page) => page.locator('[data-testid="order-setting-form-auto-close-minutes"] input')

/** 读表单里两个框当前显示的值（数字；空框返回 null） */
export const readSettingFields = async (page) => {
  const raw = async (loc) => {
    const v = await loc.inputValue()
    return v === '' ? null : Number(v)
  }
  return {
    days: await raw(settingDaysInput(page)),
    minutes: await raw(settingMinutesInput(page))
  }
}

/**
 * 往数字框里填值并失焦。
 * 失焦很关键：组件的「下限 1 / 取整」是在失焦时纠正的，不按 Tab 就还是原始输入。
 */
export const fillSettingField = async (page, which, value) => {
  const input = which === 'days' ? settingDaysInput(page) : settingMinutesInput(page)
  await input.click()
  await input.press('Control+a')
  if (value === '') await input.fill('')
  else await input.fill(String(value))
  await input.press('Tab')
  await page.waitForTimeout(250)
}

/** 用键盘逐字符输入（用来验证「字母等非数字」在组件里到底能不能落进去） */
export const typeSettingField = async (page, which, text) => {
  const input = which === 'days' ? settingDaysInput(page) : settingMinutesInput(page)
  await input.click()
  await input.press('Control+a')
  await input.pressSequentially(text)
  await page.waitForTimeout(250)
}

/** 点「保存」 */
export const submitOrderSetting = async (page) => {
  await page.locator('[data-testid="order-setting-form-submit"]').click()
  await page.waitForTimeout(1200)
}

/** 表单校验错误文案（必填拦截时出现） */
export const settingFormError = (page) => page.locator('.el-form-item__error').first()

/** 表单校验错误文案列表 */
export const settingFormErrors = (page) => page.locator('.el-form-item__error')

// ---------------------------------------------------------------------------
// 04-商家-基础配置 / 3-退货原因字典 —— 真实 UI（消费侧）
// ---------------------------------------------------------------------------

/** 打开管理端「退货申请」页（字典在这里作为「退货原因」下拉被消费） */
export const gotoReturnApplyList = async (page) => {
  await page.goto(`${ADMIN}/return-apply`)
  await expect(page.locator('[data-testid="return-apply-list-table"]')).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(900)
}

/** 管理端退货申请列表的某一行（按文本，一般用订单号定位） */
export const returnApplyRow = (page, text) =>
  page.locator('.el-table__body tr').filter({ hasText: text }).first()

/** 打开「新增退货申请」弹窗 */
export const openReturnApplyCreate = async (page) => {
  await page.getByRole('button', { name: '新增' }).click()
  const dlg = page.locator('[data-testid="return-apply-form-dialog"]')
  await expect(dlg, '新增退货申请弹窗应打开').toBeVisible()
  await page.waitForTimeout(500)
  return dlg
}

/** 打开某一行的「审核」弹窗 */
export const openReturnApplyAudit = async (page, row) => {
  await row.locator('[data-testid="return-apply-row-audit"]').click()
  const dlg = page.locator('[data-testid="return-apply-form-dialog"]')
  await expect(dlg, '审核退货申请弹窗应打开').toBeVisible()
  await page.waitForTimeout(500)
  return dlg
}

/** 弹窗里「退货原因」下拉的选项文本（先点开再读，读完按 Esc 收起） */
export const readReturnApplyReasonOptions = async (page) => {
  await page.locator('[data-testid="return-apply-form-reason"]').click()
  await page.waitForTimeout(500)
  const items = page.locator('.el-select-dropdown__item:visible')
  const texts = (await items.allInnerTexts()).map((t) => t.trim())
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)
  return texts
}

/** 在弹窗里选一个退货原因（按可见文本，空名选项用 emptyName=true 定位） */
export const pickReturnApplyReason = async (page, name) => {
  await page.locator('[data-testid="return-apply-form-reason"]').click()
  await page.waitForTimeout(500)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: name }).first().click()
  await page.waitForTimeout(300)
}

/** 退货申请列表里「原因」列的文字（第 3 列：订单号 / 申请人 / 原因 / 说明 / 状态 / 操作） */
export const reasonCellOf = async (row) => (await row.locator('td').nth(2).innerText()).trim()

// ---------------------------------------------------------------------------
// 04-商家-基础配置 / 3-退货原因字典 —— 真实 UI（维护页面）
// 页面：admin-web/src/views/return-reason/index.vue（路由 /return-reason，菜单「退货原因」）
// ---------------------------------------------------------------------------

/** 打开管理端「退货原因」维护页 */
export const gotoReturnReasonList = async (page) => {
  await page.goto(`${ADMIN}/return-reason`)
  await expect(page.locator('[data-testid="return-reason-list-table"]')).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(900)
}

/** 列表里「原因名称」所在的行（表格列：编号 / 原因名称 / 操作） */
export const returnReasonRow = (page, name) =>
  page.locator('.el-table__body tr').filter({ hasText: name }).first()

/** 列表行数 */
export const returnReasonRowCount = (page) => page.locator('.el-table__body tr').count()

/** 打开「新增退货原因」（不传 row）或「编辑退货原因」（传 row）弹窗 */
export const openReturnReasonForm = async (page, row) => {
  if (row) await row.locator('[data-testid="return-reason-row-edit"]').click()
  else await page.locator('[data-testid="return-reason-create"]').click()
  const dlg = page.locator('[data-testid="return-reason-form-dialog"]')
  await expect(dlg, '退货原因弹窗应打开').toBeVisible()
  await page.waitForTimeout(400)
  return dlg
}

/** 填原因名称（空串 = 清空，用来测校验） */
export const fillReturnReasonName = async (page, name) => {
  await page.locator('[data-testid="return-reason-form-name"]').fill(name)
}

/** 点弹窗「确定」 */
export const submitReturnReasonForm = async (page) => {
  await page.locator('[data-testid="return-reason-form-submit"]').click()
  await page.waitForTimeout(1200)
}

/** 点弹窗「取消」 */
export const cancelReturnReasonForm = async (page) => {
  await page.locator('[data-testid="return-reason-form-cancel"]').click()
  await expect(page.locator('[data-testid="return-reason-form-dialog"]')).toBeHidden({ timeout: 10_000 })
}

/** 弹窗里的表单校验错误文案 */
export const returnReasonFormError = (page) =>
  page.locator('[data-testid="return-reason-form-dialog"] .el-form-item__error').first()

/** 删除某一行（含确认框，确认按钮文案是「删除」） */
export const deleteReturnReasonRow = async (page, row) => {
  await row.locator('[data-testid="return-reason-row-delete"]').click()
  const box = page.locator('.el-message-box')
  await expect(box, '删除应有确认框').toBeVisible()
  await box.locator('.el-message-box__btns').getByRole('button', { name: '删除', exact: true }).click()
  await page.waitForTimeout(1200)
}

/** 当前页面上某类 toast 的文案（success / error / warning），用于区分校验级别 */
export const toastTextOf = async (page, kind = 'success') => {
  const loc = page.locator(`.el-message--${kind}`)
  return ((await loc.count()) ? (await loc.first().innerText()) : '').trim()
}

// ---------------------------------------------------------------------------
// C 端「申请退货」弹窗 —— 真实 UI（字典在买家的下拉里被消费，这是字典存在的意义）
// ---------------------------------------------------------------------------

/** C 端「我的订单」页的某一行（按订单号） */
export const portalOrderRow = (page, orderSn) =>
  page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()

/** 打开该行的「申请退货」弹窗 */
export const openPortalReturnDialog = async (page, row) => {
  await row.locator('[data-testid="orders-row-return"]').click()
  const dlg = page.locator('[data-testid="order-return-dialog"]')
  await expect(dlg, '申请退货弹窗应打开').toBeVisible()
  await page.waitForTimeout(600)
  return dlg
}

/** 读 C 端申请退货弹窗里「退货原因」下拉的选项文本 */
export const readPortalReasonOptions = async (page) => {
  await page.locator('[data-testid="order-return-form-reason"]').click()
  await page.waitForTimeout(500)
  const items = page.locator('.el-select-dropdown__item:visible')
  const texts = (await items.allInnerTexts()).map((t) => t.trim())
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)
  return texts
}

/** 在 C 端申请退货弹窗里选原因 + 填说明并提交 */
export const submitPortalReturnWithReason = async (page, reasonName, description) => {
  await page.locator('[data-testid="order-return-form-reason"]').click()
  await page.waitForTimeout(500)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: reasonName }).first().click()
  await page.waitForTimeout(300)
  await page.locator('[data-testid="order-return-form-description"]').fill(description)
  await page.locator('[data-testid="order-return-form-submit"]').click()
  await page.waitForTimeout(1800)
}

// ---------------------------------------------------------------------------
// 字典数据的准备动作 —— 页面无入口，只能走接口（口径同 support/data.js 第 2 条）
//
// 管理端目前**没有**退货原因字典的维护界面：路由/菜单里都没有入口，
// 后端 ReturnReasonController 却提供完整增删改。为了让「字典 → 买家下拉」这条链路可测，
// 字典本身的行只能用接口造；所有「字典是否生效」的判断仍走真实 UI + 只读 GET。
// 这里只造/清自己建的行，种子原因（质量问题/七天无理由/发错货/不想要了）一个不碰。
// ---------------------------------------------------------------------------

/** 只读：字典全量 */
export async function readReasonsApi(request, token) {
  const res = await request.get(`${API}/admin/return-reasons`, auth(token))
  return (await res.json()).data || []
}

/** 准备动作：新增一条字典项，返回后端分配的行 */
export async function seedReasonName(request, token, name) {
  const res = await request.post(`${API}/admin/return-reasons`, { ...auth(token), data: { name } })
  const body = await res.json()
  if (body.code !== 0) throw new Error(`造字典项失败：${body.message}`)
  return body.data
}

/** 准备动作：改名 */
export async function renameReason(request, token, id, name) {
  const res = await request.put(`${API}/admin/return-reasons/${id}`, { ...auth(token), data: { id, name } })
  const body = await res.json()
  if (body.code !== 0) throw new Error(`改字典项失败：${body.message}`)
  return body
}

/** 准备动作：删除（删除被引用项当前不会被拦，见 spec 里的「已知缺陷」注释） */
export async function dropReason(request, token, id) {
  const res = await request.delete(`${API}/admin/return-reasons/${id}`, auth(token))
  return await res.json()
}

/** 只读：C 端买家退货原因字典（和买家看到的是同一份） */
export async function readPortalReasonsApi(request) {
  const res = await request.get(`${API}/portal/returns/reasons`)
  return (await res.json()).data || []
}

/** 只读：订单设置当前配置 */
export async function readOrderSettingApi(request, token) {
  const res = await request.get(`${API}/admin/order-setting`, auth(token))
  return (await res.json()).data || {}
}

/** 清理动作：删掉自己造的退货申请（用于解除字典项上的引用，跑完不留脏数据） */
export async function dropReturnApply(request, token, id) {
  const res = await request.delete(`${API}/admin/return-applies/${id}`, auth(token))
  return await res.json()
}

/** 只读：管理端退货申请列表（可按订单号过滤，返回带 orderSn 的行） */
export async function readReturnAppliesApi(request, token, orderId) {
  const url = orderId ? `${API}/admin/return-applies?orderId=${orderId}` : `${API}/admin/return-applies`
  const res = await request.get(url, auth(token))
  return (await res.json()).data || []
}

/** 只读：某买家的退货申请列表 */
export async function readMemberReturnsApi(request, token) {
  const res = await request.get(`${API}/portal/returns`, auth(token))
  return (await res.json()).data || []
}
