import { test, expect } from '../../support/test.js'
import { PORTAL, stamp } from '../../support/env.js'
import { memberRegister, memberRegisterAndLogin, memberLogin } from '../../support/ui.js'
import { loginError, loginFieldError } from '../../support/portal-ui.js'
import { getMemberProfileApi, getMemberOrdersApi, memberTokenOf } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { memberNickOf } from '../../support/fixtures.js'
import { demoName, demoUsername, expectAllImagesLoaded } from '../../support/images.js'


/**
 * 买家侧 01 注册与登录（docs/flows/02-业务场景/01-买家侧/1-注册与登录）
 *
 * 覆盖：注册成功即登录 / 用户名重复 / 空用户名空密码（前端必填）/ 密码错误 /
 *       未登录访问受保护页被拦截并带 redirect 回跳 / 登录成功 / 密码强度现状
 * 断言依据：PortalAuthController（空值 400、重名 400、密码错 401）+ router 守卫 + 登录页 el-alert
 */

/** 打开登录页并切到「注册」tab */
const openRegisterTab = async (page) => {
  await page.goto(`${PORTAL}/login`)
  await tid(page, 'auth-tab-register').click()
}

test.describe('买家侧 01 注册与登录', () => {
  test('注册成功即登录：自动写入登录态并进入首页', async ({ page, request }) => {
    const username = demoUsername('七七买家') || `e2e_auth_ok_${stamp()}`
    const nickname = memberNickOf(username)      // 顶栏会显示，用自然中文昵称
    await openRegisterTab(page)
    await tid(page, 'auth-input-username').fill(username)
    await tid(page, 'auth-input-password').fill('123456')
    await tid(page, 'auth-input-nickname').fill(nickname)
    await tid(page, 'auth-submit').click()

    await expect(page, '注册成功后应离开登录页').not.toHaveURL(/\/login/, { timeout: 20_000 })
    // 顶栏出现「你好，昵称」+ 退出入口，说明已登录
    await expect(tid(page, 'header-logout'), '顶栏应出现退出入口').toBeVisible()
    await expect(page.locator('body')).toContainText(nickname)
    // 首页商品图要真的出图（演示片里不能停在灰底占位框上）；顺带钉住「登录后首页能正常出图」
    await expectAllImagesLoaded(tid(page, 'home-product-card').locator('img'), '首页商品卡片图')

    // 服务端复核：token 可用，profile 是刚注册的账号
    const token = await memberTokenOf(page)
    expect(token, '注册后应写入登录令牌').toBeTruthy()
    const profile = await getMemberProfileApi(request, token)
    expect(profile.username).toBe(username)
    expect(profile.nickname).toBe(nickname)
    expect(profile.status, '新账号应为启用状态').toBe(1)
  })

  test('用户名已占用：注册被拒，停留在注册页并提示', async ({ page }) => {
    const username = demoUsername('林小姐') || `e2e_auth_dup_${stamp()}`
    // 先建一个占位账号（真实注册页）
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    await openRegisterTab(page)
    await tid(page, 'auth-input-username').fill(username)
    await tid(page, 'auth-input-password').fill('654321')
    await tid(page, 'auth-submit').click()

    await expect(loginError(page), '重名注册应给出错误提示').toContainText('用户名已被占用', { timeout: 15_000 })
    await expect(page, '注册失败应仍停留在登录页').toHaveURL(/\/login/)
  })

  test('必填校验：用户名/密码为空时不发请求、就地提示', async ({ page }) => {
    await page.goto(`${PORTAL}/login`)
    await tid(page, 'auth-submit').click()
    await expect(loginFieldError(page, '请输入用户名')).toBeVisible()
    await expect(loginFieldError(page, '请输入密码')).toBeVisible()

    // 注册 tab 同样有必填校验
    await tid(page, 'auth-tab-register').click()
    await tid(page, 'auth-submit').click()
    await expect(loginFieldError(page, '请输入用户名')).toBeVisible()
    await expect(loginFieldError(page, '请输入密码')).toBeVisible()

    // 只填用户名仍不通过
    await tid(page, 'auth-input-username').fill('only_name')
    await tid(page, 'auth-submit').click()
    await expect(loginFieldError(page, '请输入密码')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })

  test('密码错误：登录被拒并提示「用户名或密码错误」', async ({ page }) => {
    const username = demoUsername('陈先生') || `e2e_auth_pwd_${stamp()}`
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    // 退出登录（顶栏「退出」）
    await tid(page, 'header-logout').click()
    await page.waitForTimeout(800)
    await expect(page).toHaveURL(/\/login/)

    await tid(page, 'auth-input-username').fill(username)
    await tid(page, 'auth-input-password').fill('wrong-password')
    await tid(page, 'auth-submit').click()
    await expect(loginError(page)).toContainText('用户名或密码错误', { timeout: 15_000 })

    // 正确密码可以登录
    await tid(page, 'auth-input-password').fill('123456')
    await tid(page, 'auth-submit').click()
    await expect(page, '正确密码应登录成功').not.toHaveURL(/\/login/, { timeout: 20_000 })
  })

  test('未登录访问受保护页：拦截到登录页，登录后跳回原页面', async ({ page, request }) => {
    const username = demoUsername('王女士') || `e2e_auth_redirect_${stamp()}`
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    await tid(page, 'header-logout').click()
    await page.waitForTimeout(800)

    // 未登录直接开「收货地址」→ 被守卫拦到 /login?redirect=%2Faddresses
    await page.goto(`${PORTAL}/addresses`)
    await page.waitForTimeout(800)
    // vue-router 的 query 不强制百分号编码，两种写法都接受
    await expect(page, '未登录访问受保护页应跳登录页').toHaveURL(/\/login\?redirect=(%2F|\/)addresses/)
    await expect(tid(page, 'auth-input-username')).toBeVisible()

    // 登录后应回跳 /addresses
    await tid(page, 'auth-input-username').fill(username)
    await tid(page, 'auth-input-password').fill('123456')
    await tid(page, 'auth-submit').click()
    await expect(page, '登录后应回跳原页面').toHaveURL(/\/addresses/, { timeout: 20_000 })
    await expect(page.getByRole('heading', { name: '收货地址' })).toBeVisible()
  })

  /**
   * 密码强度：当前后端/前端都**没有**任何强度校验（PortalAuthController.register 只判空和重名），
   * 所以 1 位密码也能注册成功。这里把现状钉住：等哪天加了强度校验，这条会红，提醒同步改文档与用例。
   */
  test('密码强度现状：无强度校验，1 位密码也能注册（记录现状）', async ({ page }) => {
    const username = demoUsername('赵小满') || `e2e_auth_weak_${stamp()}`
    await openRegisterTab(page)
    await tid(page, 'auth-input-username').fill(username)
    await tid(page, 'auth-input-nickname').fill(memberNickOf(`${username}-weak`))  // 顶栏会显示，用自然中文昵称
    await tid(page, 'auth-input-password').fill('1')
    await tid(page, 'auth-submit').click()
    await expect(page, '当前无密码强度校验：1 位密码也能注册成功').not.toHaveURL(/\/login/, { timeout: 20_000 })
  })
})
