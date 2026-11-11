/**
 * 05-账号与会员 / 1-管理员登录与验证码 专用 helper。
 *
 * 现有 support/ui.js 的 adminLogin 不感知验证码行，且本场景要精细控制
 * 「填错密码 / 触发验证码 / 读验证码答案」，所以单独建文件，不改公共库。
 *
 * 规则同 support/ui.js：页面操作走真实 UI；request 仅用于
 *   1) 只读断言（captcha-required）
 *   2) 状态准备/清零（成功登录会 clearFail）
 *   3) 验证码「一次性消费」这类 UI 无入口的后端语义测试
 *
 * 验证码答案来源：dev 档 CaptchaService 以 DEBUG 级别把明文打到服务端日志
 * （/tmp/dev-server.log，可用 CAPTCHA_LOG 覆盖）。不为测试改任何生产代码。
 */
import { expect } from '@playwright/test'
import { readFileSync, statSync } from 'node:fs'
import { ADMIN, API } from './env.js'

/**
 * 验证码明文所在的后端日志。
 *
 * 以前写死 /tmp/dev-server.log（只有 dev 栈 8081 有）；换到 scripts/e2e-run.sh 的分片栈后，
 * 每个 shard 有独立后端（8091 / 8092）和自己的日志（/tmp/e2e-server-a.log / -b.log），
 * 写死路径就读不到新验证码 → 「验证码日志里没有新记录」。这里按 API 端口自动选，
 * 仍可用 CAPTCHA_LOG 显式覆盖。
 */
const resolveCaptchaLog = () => {
  if (process.env.CAPTCHA_LOG) return process.env.CAPTCHA_LOG
  const port = (() => {
    try { return new URL(API).port } catch { return '' }
  })()
  const byPort = {
    8081: '/tmp/dev-server.log',        // dev 栈（npm test 默认）
    8091: '/tmp/e2e-server-a.log',      // e2e 分片 a
    8092: '/tmp/e2e-server-b.log'       // e2e 分片 b
  }
  return byPort[port] || '/tmp/dev-server.log'
}
const CAPTCHA_LOG = resolveCaptchaLog()

// ---------------------------------------------------------------------------
// 验证码答案：从服务端 debug 日志读明文（只读日志，不动后端）
// ---------------------------------------------------------------------------

/** 读日志里水位 sinceSize 之后「生成验证码」的明文；传 captchaId 时精确匹配该条，最多等 8s */
export async function readCaptchaFromLog(sinceSize = 0, captchaId = null) {
  for (let i = 0; i < 16; i++) {
    // 全程用 Buffer（字节）操作：日志含中文，utf8 字符串索引与字节偏移不一致，
    // 用字符串 slice 会把水位切错位置导致漏读
    const buf = readFileSync(CAPTCHA_LOG)
    let fresh = sinceSize > 0 ? buf.subarray(sinceSize) : buf
    // 水位可能切在行中间（前一个字节不是 \n）：丢弃首行（不完整的那行）
    if (sinceSize > 0 && buf[sinceSize - 1] !== 0x0a) {
      const nl = fresh.indexOf(0x0a)
      fresh = nl >= 0 ? fresh.subarray(nl + 1) : Buffer.alloc(0)
    }
    const freshText = fresh.toString('utf8')
    if (captchaId) {
      // 精确模式：必须等到这一条 captchaId 的记录落盘（并发下别的请求会插入别的记录）
      const m = freshText.match(new RegExp(`生成验证码 captchaId=${captchaId} code=(\\w+)`))
      if (m) return { captchaId, code: m[1] }
    } else {
      const lines = freshText.match(/生成验证码 captchaId=(\w+) code=(\w+)/g) || []
      if (lines.length) {
        const m = lines[lines.length - 1].match(/captchaId=(\w+) code=(\w+)/)
        return { captchaId: m[1], code: m[2] }
      }
    }
    if (process.env.CAPTCHA_DEBUG) {
      console.warn(`[captcha-debug] try=${i} fileSize=${buf.length} wm=${sinceSize} freshLen=${fresh.length} match=${lines.length || (captchaId ? freshText.includes(captchaId) : 0)}`)
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  if (process.env.CAPTCHA_DEBUG) {
    const buf = readFileSync(CAPTCHA_LOG)
    console.warn(`[captcha-debug] MISS final: fileSize=${buf.length} wm=${sinceSize} wmGone=${buf.length < sinceSize}`)
  }
  throw new Error(`验证码日志里没有新记录（${CAPTCHA_LOG} 水位 ${sinceSize}）`)
}

export const logSize = () => { try { return statSync(CAPTCHA_LOG).size } catch { return 0 } }

// ---------------------------------------------------------------------------
// API：登录接口本身是被测对象（一次性消费等语义只能在接口层测）
// ---------------------------------------------------------------------------

const postLogin = (request, payload) =>
  request.post(`${API}/admin/auth/login`, { data: payload })

export const apiLogin = async (request, payload) => {
  const res = await postLogin(request, payload)
  return { status: res.status(), body: await res.json() }
}

/** GET /admin/auth/captcha —— 返回 captchaId（明文随后去日志里读） */
export const apiFetchCaptcha = async (request) => {
  const res = await request.get(`${API}/admin/auth/captcha`)
  const body = await res.json()
  return body.data.captchaId
}

/** GET /admin/auth/captcha-required?username=xxx（只读） */
export const apiCaptchaRequired = async (request, username = 'admin') => {
  const res = await request.get(`${API}/admin/auth/captcha-required?username=${username}`)
  return (await res.json()).data
}

/**
 * 状态清零：验证码失败计数是按用户名的全局状态（其他用例共享），
 * 每个用例前后都要用「正确密码 +（必要时）正确验证码」成功登录一次 —— 成功即 clearFail。
 * 返回 accessToken 备用。
 */
export const resetLoginState = async (request, username = 'admin', password = '123456') => {
  for (let i = 0; i < 3; i++) {
    if (!(await apiCaptchaRequired(request, username))) {
      const { status, body } = await apiLogin(request, { username, password })
      if (status === 200) return body.data.accessToken
    }
    const size = logSize()
    await apiFetchCaptcha(request)
    await page_nop()
    const { captchaId, code } = await readCaptchaFromLog(size)
    const { status, body } = await apiLogin(request, { username, password, captchaId, captchaCode: code })
    if (status === 200) return body.data.accessToken
  }
  throw new Error('resetLoginState: 3 次尝试后仍未成功登录，计数未清零')
}

const page_nop = () => new Promise((r) => setTimeout(r, 400)) // 等日志落盘

// ---------------------------------------------------------------------------
// 真实 UI：登录页（感知验证码行）
// ---------------------------------------------------------------------------

const usernameInput = (page) =>
  page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input')
const passwordInput = (page) =>
  page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input')
export const captchaInput = (page) => page.getByTestId('login-form-captcha')
export const loginErrorAlert = (page) => page.locator('.el-alert--error')

/** 打开登录页并等待表单 */
export const openLoginPage = async (page) => {
  await page.goto(`${ADMIN}/login`)
  await expect(usernameInput(page), '登录表单应出现').toBeVisible()
}

/**
 * 真实 UI 登录：填用户名（blur 触发 captcha-required 探测）→ 密码 →（可选）验证码 → 点登录。
 * captchaCode 传 undefined 且页面上出现验证码行时，自动从服务端日志读明文填入。
 * 不断言结果（由用例自己断言成功/失败）。
 */
export const uiLogin = async (page, { username = 'admin', password = '123456', captchaCode } = {}) => {
  // 日志水位要在打开页面之前记：页面自己会先取一次验证码（loadCaptcha），那份才是表单里展示的
  const size = logSize()
  await openLoginPage(page)
  await usernameInput(page).fill(username)
  await usernameInput(page).blur()          // 触发 checkCaptcha（captcha-required 探测）
  await passwordInput(page).fill(password)
  // 等 checkCaptcha 的响应回来，避免验证码行「还没渲染就判空」的竞态
  await page.waitForResponse(
    (r) => r.url().includes('/admin/auth/captcha-required'),
    { timeout: 8_000 }
  ).catch(() => {})
  const box = captchaInput(page)
  if (await box.isVisible().catch(() => false)) {
    await page.waitForTimeout(500)          // 等页面请求的验证码日志落盘
    let code = captchaCode
    if (code === undefined) code = (await readCaptchaFromLog(size)).code
    await box.fill(code)
  }
  await page.getByRole('button', { name: '登录' }).click()
}

/** 成功登录的断言：离开登录页并落在 dashboard */
export const expectLandedDashboard = async (page) => {
  await expect(page, '登录成功应跳转后台').toHaveURL(/\/dashboard/, { timeout: 20_000 })
}
