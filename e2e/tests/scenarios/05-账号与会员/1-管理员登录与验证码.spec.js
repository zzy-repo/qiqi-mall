import { test, expect } from '../../support/test.js'
import { stamp } from '../../support/env.js'
import { memberLoginOf } from '../../support/member-login.js'
import {
  readCaptchaFromLog,
  logSize,
  apiLogin,
  apiFetchCaptcha,
  apiCaptchaRequired,
  resetLoginState,
  uiLogin,
  openLoginPage,
  captchaInput,
  loginErrorAlert,
  expectLandedDashboard
} from '../../support/admin-ui-auth.js'

/**
 * 05-账号与会员 / 1-管理员登录与验证码
 * 图：docs/flows/02-业务场景/05-账号与会员/1-管理员登录与验证码/1-登录与验证码流程图.mmd
 *
 * 拆两个用例（demo 档按关键词分段出片）：
 *   「全流程」   = 正常登录 / 密码错误 / 验证码触发 / 校验四连 / 成功清零 / 未登录拦截
 *   「一次性消费」 = 同一 captchaId 第二次使用必须被拒（取出即删的接口语义）
 *
 * ⚠️ 验证码失败计数是「按用户名」的全局状态（KvStore，5 分钟窗口），
 *    触发失败路径一律用专用探针用户名（admin 全程只成功登录，永不失败）。
 *    探针名也会明文出现在登录页「用户名」输入框里，所以用 fixtures 的自然会员号（拼音+数字），
 *    不用 e2e_ 前缀 —— 刚好不存在于库里，唯一性靠数字后缀。
 *
 * 验证码答案来源：后端 CaptchaService 把明文以 DEBUG 打到服务端日志
 * （CAPTCHA_LOG，默认 /tmp/dev-server.log），helper 从日志读取；不为测试改任何生产代码（无后门）。
 */

const WRONG = 'wrong-pass'
// 每次运行用独立 probe 用户名：失败计数是后端全局状态（captcha:fail:<username>，5 分钟窗口），
// 多 shard / 多 agent 共用一个后端时，固定名字会被别人打满，导致本条「应 >=5 次」或「应清零」的断言假死。
// 名字要同时满足两条：界面上好看（登录页会显示）+ 库里不存在（探针不可能是真实会员）。
const PROBE = memberLoginOf(stamp())
const ADMIN_BASE = process.env.ADMIN_BASE || 'http://127.0.0.1:5175'

test.afterEach(async ({ request }) => {
  // 全局状态收尾：admin 成功登录一次即清零（probe 用户不存在，无法成功登录；
  // 其失败计数 5 分钟窗口自动过期，且用例内不再额外累计）。
  await resetLoginState(request).catch((e) => console.warn(`afterEach 清零失败：${e.message}`))
})

test('1-管理员登录与验证码：登录与验证码触发', async ({ page, request }) => {
  // ── 前置清零：确保 admin 当前不需要验证码 ──
  await resetLoginState(request)

  // ── 1. 正常登录：admin + ADMIN_PASSWORD（e2e 独立库无该环境变量时回落 123456）→ 进入后台 dashboard ──
  // （成功的登录本身就会把 admin 的失败计数清零，所以「计数为 0」这条断言放在登录之后校验，
  //   否则会被别的用例/别的人为操作在登录前打满的计数误伤）
  await uiLogin(page, { username: 'admin', password: process.env.ADMIN_PASSWORD || '123456' })
  await expectLandedDashboard(page)
  expect(await apiCaptchaRequired(request, 'admin'), '成功登录后 admin 的失败计数应为 0').toBe(false)
  await page.evaluate(() => localStorage.clear())   // 退登态，后续从登录页重测

  // ── 2. 密码错误（用专用 probe 用户名，不污染 admin）──
  await uiLogin(page, { username: PROBE, password: WRONG })
  await expect(loginErrorAlert(page), '应出现错误提示').toBeVisible()
  await expect(loginErrorAlert(page)).toContainText('用户名或密码错误')
  await expect(page).toHaveURL(/\/login/)

  // ── 3. 验证码触发：连续 5 次密码错误 → 第 6 次出现验证码输入行 ──
  //    第 2 步已错 1 次，再错 4 次凑满 5（失败计数按用户名累计）
  for (let i = 0; i < 4; i++) {
    await uiLogin(page, { username: PROBE, password: WRONG })
    await expect(loginErrorAlert(page)).toContainText('用户名或密码错误')
  }
  await expect(page).toHaveURL(/\/login/)

  // 第 6 次：打开登录页、填用户名失焦后应出现验证码输入行（captcha-required 变 true）
  await openLoginPage(page)
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').fill(PROBE)
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').blur()
  await expect(captchaInput(page), '失败满 5 次后登录页应出现验证码输入行').toBeVisible({ timeout: 10_000 })
  await expect(page.locator('.captcha-img'), '应同时展示验证码图片').toBeVisible()
  expect(await apiCaptchaRequired(request, PROBE)).toBe(true)

  // ── 4. 验证码校验（UI 侧） ──
  // 4a. 不填验证码提交 → 前端必填校验拦截（请输入验证码），无请求发出
  await page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input').fill(WRONG)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.locator('.el-form-item__error').filter({ hasText: '请输入验证码' }).first())
    .toBeVisible({ timeout: 5_000 })
  await expect(page).toHaveURL(/\/login/)

  // 4b. 填错误验证码 → 被拒「验证码错误，请重新获取」
  await uiLogin(page, { username: PROBE, password: process.env.ADMIN_PASSWORD || '123456', captchaCode: 'zzzz' })
  await expect(loginErrorAlert(page)).toContainText('验证码错误')

  // 4c. 填正确验证码 → 仍因密码错被拒（此时计数继续累加）
  await uiLogin(page, { username: PROBE, password: WRONG })  // 自动从日志读当前图码填入
  await expect(loginErrorAlert(page)).toContainText('用户名或密码错误')

})

test('1-管理员登录与验证码：登录成功清零与未登录拦截', async ({ page, request }) => {
  // admin 全程只成功、不失败，计数永远是 0；若这里出现验证码行说明被别人污染（afterEach 兜底）。
  await uiLogin(page, { username: 'admin', password: process.env.ADMIN_PASSWORD || '123456' })  // 自动读最新图码（若有）
  await expectLandedDashboard(page)
  await page.evaluate(() => localStorage.clear())
  await uiLogin(page, { username: 'admin', password: process.env.ADMIN_PASSWORD || '123456' })  // 无验证码行即证明计数已清零
  await expect(captchaInput(page), '成功登录清零后再次登录不应要求验证码').toBeHidden({ timeout: 6_000 })
  await expectLandedDashboard(page)

  // ── 6. 未登录拦截：直接访问 /dashboard → 跳登录页并带 redirect ──
  await page.evaluate(() => localStorage.clear())   // 清会话（当前可能还在后台页）
  await page.goto(`${ADMIN_BASE}/dashboard`)
  await expect(page).toHaveURL(/\/login\?redirect=\/dashboard/, { timeout: 10_000 })
})

test('1-管理员登录与验证码：验证码一次性消费', async ({ request }) => {
  // probe 累计 5 次失败进入「需要验证码」状态
  for (let i = 0; i < 5; i++) {
    const { status } = await apiLogin(request, { username: PROBE, password: WRONG })
    expect(status).toBe(401)
  }
  expect(await apiCaptchaRequired(request, PROBE)).toBe(true)

  // 取一个验证码，第一次使用（密码错）→ 验证码被消费，返回「用户名或密码错误」
  const size = logSize()
  const captchaId = await apiFetchCaptcha(request)
  const { code } = await readCaptchaFromLog(size, captchaId)
  const first = await apiLogin(request, { username: PROBE, password: WRONG, captchaId, captchaCode: code })
  expect(first.status, '第一次使用：密码错被拒(401)且验证码已消费').toBe(401)
  expect(first.body.message).toContain('用户名或密码错误')

  // 第二次使用同一个 captchaId（这次密码是对的）→ 仍被拒「验证码错误」：取出即删，防重放
  const second = await apiLogin(request, { username: PROBE, password: process.env.ADMIN_PASSWORD || '123456', captchaId, captchaCode: code })
  expect(second.status, '同一 captchaId 第二次使用应被拒').toBe(401)
  expect(second.body.message).toContain('验证码错误')
})
