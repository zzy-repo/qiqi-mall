/**
 * 商家侧 05-账号与会员（admin-web /member 及两个子页）真实 UI 操作 + 该场景需要的只读查询。
 *
 * 规则与 support/admin-ui.js、ui.js 一致：业务动作只走真实点击；
 * 唯一例外是 deleteMemberApi()——会员列表**页面上没有删除入口**（操作列只有
 * 收货地址 / 购物车 / 编辑三项），所以「删除会员」与测试后的数据清理只能走接口，
 * 见该函数注释里的自查说明。
 *
 * 页面事实（admin-web/src/views/member/）：
 * - 列表：新增（member-list-create）、行内 收货地址/购物车/编辑（跳路由，不是弹窗/抽屉）
 * - 新增/编辑弹窗：登录名（必填）、昵称、手机号、状态开关 —— **没有密码字段**
 *   （member.password 在库里是 NOT NULL DEFAULT ''，PortalAuthController 对空密码直接判登录失败）
 * - 子页是独立路由 /member/:memberId/addresses、/member/:memberId/carts，按 memberId 各查各的
 */
import { expect } from '@playwright/test'
import { ADMIN, API } from './env.js'
import { adminLoginHardened } from './admin-ui.js'

const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

// ---------------------------------------------------------------------------
// 列表页
// ---------------------------------------------------------------------------

/**
 * 打开会员列表。
 * 开发服务器在被并行用例频繁改动源码时会触发 HMR 重载，偶发一次「表格还没挂上」，
 * 所以这里容错重载一次；两次都拿不到就照实报错（不掩盖真故障）。
 */
export const gotoMemberList = async (page) => {
  await page.goto(`${ADMIN}/member`)
  const table = page.locator('[data-testid="member-list-table"]')
  const ok = await table.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true).catch(() => false)
  if (!ok) {
    await page.reload()
    await expect(table, '会员列表应渲染').toBeVisible({ timeout: 20_000 })
  }
  await page.waitForTimeout(1200)
}

/** 会员列表表格 */
export const memberTable = (page) => page.locator('[data-testid="member-list-table"]')

/** 按登录名定位一行（登录名全局唯一，用精确文本匹配） */
export const memberRow = (page, username) =>
  memberTable(page).locator('.el-table__body tr').filter({ hasText: username }).first()

/** 当前列表行数 */
export const memberRowCount = (page) => memberTable(page).locator('.el-table__body tr').count()

/** 刷新列表（保存/删除后核对「列表是否真的变了」） */
export const reloadMemberList = async (page) => {
  await page.reload()
  await expect(memberTable(page), '刷新后会员列表应仍渲染').toBeVisible({ timeout: 20_000 })
  await page.waitForTimeout(1500)
}

// ---------------------------------------------------------------------------
// 新增 / 编辑弹窗
// ---------------------------------------------------------------------------

/**
 * 打开新增（不传 row）或编辑（传 row）弹窗。
 * 注意：弹窗里**没有密码项**，后台新建的会员没有初始密码。
 */
export const openMemberForm = async (page, row) => {
  if (row) await row.getByRole('button', { name: '编辑' }).click()
  else await page.locator('[data-testid="member-list-create"]').click()
  await expect(page.locator('[data-testid="member-form-dialog"]'), '会员弹窗应打开').toBeVisible()
  await page.waitForTimeout(400)
}

/** 弹窗里是否有「密码」输入项（现状：没有。用例用它把「无密码」这件事显式记录下来） */
export const memberFormHasPasswordField = async (page) =>
  (await page.locator('[data-testid="member-form-dialog"] .el-form-item')
    .filter({ hasText: '密码' }).count()) > 0

/** 填登录名（空串 = 清空，用来测必填校验） */
export const fillMemberUsername = async (page, username) => {
  await page.locator('[data-testid="member-form-username"]').fill(username)
}

/** 填昵称 */
export const fillMemberNickname = async (page, nickname) => {
  await page.locator('[data-testid="member-form-nickname"]').fill(nickname)
}

/** 填手机号 */
export const fillMemberPhone = async (page, phone) => {
  await page.locator('[data-testid="member-form-phone"]').fill(phone)
}

/** 切换启用/禁用开关（active-value=1 / inactive-value=0） */
export const setMemberStatus = async (page, enabled) => {
  const sw = page.locator('[data-testid="member-form-status"]')
  const isOn = ((await sw.getAttribute('class')) || '').includes('is-checked')
  if (isOn !== enabled) await sw.click()
  await page.waitForTimeout(200)
}

/** 点「确定」提交弹窗 */
export const submitMemberForm = async (page) => {
  await page.locator('[data-testid="member-form-submit"]').click()
}

/** 点「取消」关闭弹窗 */
export const cancelMemberForm = async (page) => {
  await page.locator('[data-testid="member-form-cancel"]').click()
  await expect(page.locator('[data-testid="member-form-dialog"]')).toBeHidden({ timeout: 10_000 })
}

/** 弹窗内表单校验错误文案 */
export const memberFormError = (page) =>
  page.locator('[data-testid="member-form-dialog"] .el-form-item__error').first()

// ---------------------------------------------------------------------------
// 子页：收货地址 / 购物车（聚合于会员，独立路由）
// ---------------------------------------------------------------------------

/** 列表行点「收货地址」→ 进入该会员的地址子页，返回 URL 里的 memberId */
export const openMemberAddresses = async (page, row) => {
  await row.getByRole('button', { name: '收货地址' }).click()
  await page.waitForURL(/\/member\/\d+\/addresses/, { timeout: 20_000 })
  await expect(page.locator('[data-testid="member-address-list-table"]'), '地址子页应渲染').toBeVisible()
  await page.waitForTimeout(1000)
  return Number(page.url().match(/\/member\/(\d+)\/addresses/)[1])
}

/** 列表行点「购物车」→ 进入该会员的购物车子页，返回 URL 里的 memberId */
export const openMemberCarts = async (page, row) => {
  await row.getByRole('button', { name: '购物车' }).click()
  await page.waitForURL(/\/member\/\d+\/carts/, { timeout: 20_000 })
  await expect(page.locator('[data-testid="member-cart-list-table"]'), '购物车子页应渲染').toBeVisible()
  await page.waitForTimeout(1200)
  return Number(page.url().match(/\/member\/(\d+)\/carts/)[1])
}

/** 子页表格行 */
export const memberSubTableRows = (page) => page.locator('.el-table__body tr')

/** 子页标题旁的会员名标签（证明看的是谁的子页） */
export const memberSubPageName = (page) => page.locator('.el-card__header .el-tag').first()

/** 子页「返回」→ 回到会员列表 */
export const backToMemberList = async (page) => {
  await page.getByRole('button', { name: '返回' }).click()
  await page.waitForURL(/\/member$/, { timeout: 20_000 })
  await expect(page.locator('[data-testid="member-list-table"]')).toBeVisible()
  await page.waitForTimeout(800)
}

// ---------------------------------------------------------------------------
// 只读查询（GET）
// ---------------------------------------------------------------------------

/**
 * 等后台登录失败计数的 5 分钟窗口过期。
 *
 * 背景：AdminAuthController 在「同一登录名连续输错 5 次」后要求图形验证码
 * （CaptchaStore：key=captcha:fail:{username}，TTL 5 分钟）。其它场景用例会故意
 * 输错密码把 admin 推到要验证码的状态，那会让所有并行用例的后台登录一起被拦。
 * 这里只读 captcha-required 做前置等待，不改服务端状态（成功登录自然清零）。
 *
 * 轮询节奏：300ms 起、指数退避到上限 1000ms（300 → 600 → 1000 → 1000 …）。
 * 原来固定 5s 轮一次，查的只是一个布尔值，门禁一旦解除（成功登录会清零）本该立刻返回，
 * 却要平白多等最多 5s——而这 5s 落在**每个需要等窗口的用例**的关键路径上。
 * 退避上限 1000ms 是为了不让这个只读接口被打爆（解除前最多 ~1 次/秒）。
 * 总时限 360s 不变：语义与超时判定完全一致，只是问得更勤、返回得更早。
 */
export async function waitAdminCaptchaClear(request, username = 'admin', timeoutMs = 360_000) {
  const POLL_MIN_MS = 300
  const POLL_MAX_MS = 1000
  const deadline = Date.now() + timeoutMs
  let interval = POLL_MIN_MS
  while (Date.now() < deadline) {
    const res = await request.get(`${API}/admin/auth/captcha-required?username=${username}`)
    const body = await res.json().catch(() => ({}))
    if (body.data === false) return
    // 不睡过 deadline：最后一段按剩余时间收敛，保证总时限仍是 timeoutMs
    const remaining = deadline - Date.now()
    if (remaining <= 0) break
    await new Promise((resolve) => setTimeout(resolve, Math.min(interval, remaining)))
    interval = Math.min(interval * 2, POLL_MAX_MS)
  }
  throw new Error('等待后台验证码门禁解除超时（其它用例连续输错密码触发，需再等 5 分钟窗口）')
}

/** 管理端会员列表（可按登录名过滤） */
export async function listMembersApi(request, token, username) {
  const url = username
    ? `${API}/admin/members?username=${encodeURIComponent(username)}`
    : `${API}/admin/members`
  const res = await request.get(url, auth(token))
  const list = (await res.json()).data || []
  return username ? list.filter((m) => m.username === username) : list
}

/** 管理端会员聚合详情（地址 + 购物车） */
export async function getMemberDetailApi(request, token, id) {
  const res = await request.get(`${API}/admin/members/${id}/detail`, auth(token))
  return (await res.json()).data
}

/**
 * 删除会员 —— **页面无入口，仅补操作**。
 *
 * 自查（按 support/data.js 的三条规则）：
 * 1. 页面上确实没有入口：admin-web/src/views/member/index.vue 操作列只有
 *    收货地址 / 购物车 / 编辑，全站也没有会员删除按钮；接口 MemberController.delete 存在；
 * 2. 因此「删除会员」这个能力只能从接口触发，用例据此断言前端列表的真实变化
 *    （删完 reload 列表 → 行消失）与登录失败，不伪造 UI；
 * 3. 只用于删除本用例自己建（e2e_member_ 前缀）的账号，绝不删种子会员。
 */
export async function deleteMemberApi(request, token, id) {
  const res = await request.delete(`${API}/admin/members/${id}`, auth(token))
  const body = await res.json()
  return { status: res.status(), body }
}

/**
 * 后台登录（抗并发）：先等验证码门禁空窗，登录后再核对；
 * 并行跑用例时「同一登录名连续输错 5 次就要验证码」的 5 分钟窗口会被别的用例反复点亮，
 * 这里最多重试 3 次（每次重试前都等窗口过去），失败原因照实抛出，不掩盖真实密码错误。
 */
export async function adminLoginResilient(page, request, attempts = 3) {
  let lastError
  for (let i = 0; i < attempts; i++) {
    await waitAdminCaptchaClear(request)
    try {
      await adminLoginHardened(page)
      return
    } catch (e) {
      lastError = e
      if (i === attempts - 1) break
    }
  }
  throw lastError
}
