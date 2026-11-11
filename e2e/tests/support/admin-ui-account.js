/**
 * 05-账号与会员 / 2-管理员账号 专属 helper（自建，不改 support/*.js 现有文件）。
 *
 * 页面 facts（来自 admin-web/src/views/admin/index.vue 与 AdminController 实际代码）：
 *   - 表单收集：登录名 / 密码（新增必填、编辑留空=不改）/ 昵称 / 邮箱 / 状态开关
 *   - 行操作有「编辑」+「删除」；删除走 ElMessageBox 确认框，
 *     内置 admin 被前端先拦一次（黄条「系统内置账号不可删除」），后端也拒绝删它
 *   - 后端 admin.username 有唯一键：重复时接口报「已存在相同记录，请检查唯一字段」，
 *     页面表现为红色 toast「保存失败：…」，弹窗不关
 *
 * 安全纪律：只操作 e2e_ 前缀账号；**绝不修改/删除 admin**。
 */
import { expect } from '@playwright/test'
import { ADMIN, API } from './env.js'
import { adminTokenOf } from './assert.js'

/**
 * 打开管理员页。
 * 注意现状：/admin 页面路由被 /admin 接口前缀遮蔽（vite proxy 与 nginx 都是），
 * 直接 goto ${ADMIN}/admin 会命中后端接口而不是 SPA；所以走真实菜单导航。
 * 注意：el-sub-menu 父级要「点击」展开（hover 在无鼠标轨迹的自动化里不生效），
 * 展开后子项 data-testid 才可见可点。
 * 这个 helper 适合复用：任何被接口前缀遮蔽的页面路由都用「点父级 → 点子项」。
 */
export const gotoAdminList = async (page) => {
  await page.getByRole('menuitem', { name: '用户' }).click()
  await page.waitForTimeout(500)
  await page.getByTestId('nav-admin').click()
  await page.waitForTimeout(1200)
  await expect(page.getByTestId('admin-list-table'), '管理员列表应加载').toBeVisible()
}

/** 按登录名定位管理员行 */
export const adminRow = (page, username) =>
  page.locator('.el-table__body tr').filter({ hasText: username }).first()

/** 打开新增（不传 row）或编辑（传 row）弹窗 */
export const openAdminForm = async (page, row) => {
  if (row) await row.getByRole('button', { name: '编辑' }).click()
  else await page.getByTestId('admin-list-create').click()
  await expect(page.getByTestId('admin-form-dialog'), '管理员弹窗应打开').toBeVisible()
  await page.waitForTimeout(400)
}

/** 填登录名（空串 = 清空，用来测校验） */
export const fillAdminUsername = async (page, username) => {
  await page.getByTestId('admin-form-username').fill(username)
}

export const fillAdminNickname = async (page, nickname) => {
  await page.getByTestId('admin-form-nickname').fill(nickname)
}

export const fillAdminEmail = async (page, email) => {
  await page.getByTestId('admin-form-email').fill(email)
}

/** 填密码（表单新增了「密码」字段；非必填则可跳过） */
export const fillAdminPassword = async (page, password) => {
  const input = page.locator('.el-dialog .el-form-item').filter({ hasText: '密码' }).locator('input').first()
  await input.fill(password)
}

/** 切换启用/禁用开关 */
export const setAdminStatus = async (page, enabled) => {
  const sw = page.getByTestId('admin-form-status')
  const isOn = ((await sw.getAttribute('class')) || '').includes('is-checked')
  if (isOn !== enabled) await sw.click()
  await page.waitForTimeout(200)
}

/** 点弹窗「确定」 */
export const submitAdminForm = async (page) => {
  await page.getByTestId('admin-form-submit').click()
}

/** 行内点「删除」（还没确认）。内置 admin 会在这一步被前端拦下，直接弹黄条 */
export const clickAdminRowDelete = async (page, row) => {
  await row.getByTestId('admin-row-delete').click()
  await page.waitForTimeout(600)
}

/** 确认框里点「删除」按钮（不是「确定」） */
export const confirmAdminDeleteBox = async (page) => {
  const box = page.locator('.el-message-box')
  await expect(box, '应弹出删除确认框').toBeVisible()
  await box.getByRole('button', { name: '删除', exact: true }).click()
  await expect(box).toBeHidden({ timeout: 10_000 })
  await page.waitForTimeout(1200)
}

/** 一条提示（可以是 warning/error/success），返回其文本，用于「内置账号不可删除」这类黄条 */
export const toastText = async (page) => {
  const toast = page.locator('.el-message').first()
  await expect(toast, '应出现一条提示').toBeVisible({ timeout: 15_000 })
  return (await toast.innerText()).trim()
}

/** 表单校验错误文案（例如「请输入登录名」） */
export const adminFormItemError = (page) => page.locator('.el-form-item__error').first()

/**
 * 在一个全新的浏览器上下文里走真实登录页尝试登录，返回是否离开了登录页。
 * 用独立 context 是为了不污染主会话的登录态（localStorage 隔离）。
 * 用完记得 context.close()。
 */
export const tryAdminLogin = async (browser, username, password) => {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto(`${ADMIN}/login`)
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').waitFor({ state: 'visible', timeout: 10_000 })
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').fill(username)
  await page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForTimeout(2500)
  const leftLoginPage = !(page.url().includes('/login'))
  const bodyText = await page.locator('body').innerText().catch(() => '')
  await context.close()
  return { leftLoginPage, bodyText }
}

// ---------------------------------------------------------------------------
// 只读断言 + 自建账号清理（删除是清理动作，页面无删除按钮，只能走接口）
// ---------------------------------------------------------------------------

/** 只读：后台管理员列表（按用户名找） */
export const getAdminByUsernameApi = async (request, token, username) => {
  const res = await request.get(`${API}/admin/admins`, { headers: { Authorization: `Bearer ${token}` } })
  const list = (await res.json()).data || []
  return list.find((a) => a.username === username)
}

/**
 * 清理自建账号（e2e_ 前缀，按用户名删除）。安全护栏：
 *   - 用户名不以 e2e_ 开头时直接抛错，防止误删 admin 等真实账号
 */
export const deleteAdminAccountApi = async (request, page, username) => {
  if (!/^e2e_/.test(username)) throw new Error(`拒绝删除非 e2e_ 账号：${username}`)
  const token = await adminTokenOf(page)
  const found = await getAdminByUsernameApi(request, token, username)
  if (!found) return false
  const res = await request.delete(`${API}/admin/admins/${found.id}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  expect((await res.json()).code, `清理账号 ${username} 应成功`).toBe(0)
  return true
}
