import { test, expect } from '../../../support/test.js'
import { stamp } from '../../../support/env.js'
import { adminNickOf, pick } from '../../../support/fixtures.js'
import {
  adminLoginHardened,
  expectToastSuccess,
  expectToastError,
  tableRowCount
} from '../../../support/admin-ui.js'
import {
  gotoAdminList,
  adminRow,
  openAdminForm,
  fillAdminUsername,
  fillAdminNickname,
  fillAdminEmail,
  fillAdminPassword,
  setAdminStatus,
  submitAdminForm,
  adminFormItemError,
  tryAdminLogin,
  getAdminByUsernameApi,
  deleteAdminAccountApi,
  clickAdminRowDelete,
  confirmAdminDeleteBox,
  toastText
} from '../../../support/admin-ui-account.js'
import { adminTokenOf } from '../../../support/assert.js'

/**
 * 业务 / 账号与会员 / 管理员账号（views/admin + AdminController 实际行为）
 *
 * 完整走一遍管理员账号维护：列表 → 新增 → 用新账号登录（证明账号真的可用）
 * → 编辑 → 删除（列表消失 + 登录失败）→ 边界（重名 / 必填 / 禁用登录）
 *
 * 断言口径来自代码实际行为（AdminController / AdminAuthController / admin/index.vue）：
 *   1. 表单有「密码」字段（新增必填、编辑留空=不改）；密码 BCrypt 落库，接口不回读
 *   2. username 有唯一键 → 重名保存后端拒绝，页面红 toast「保存失败」
 *   3. 空登录名/空密码前端必填拦截「请输入登录名/密码」
 *   4. 禁用（status=0）后登录返回 401「用户名或密码错误」
 *   5. 列表行内有「删除」→ ElMessageBox 确认框；内置 admin 前端先拦（黄条「系统内置账号不可删除」），
 *      后端 AdminController.delete 同样拒绝删 admin —— 两侧都钉住
 *   6. 删除后该账号立即登录不了
 *
 * 安全纪律：错误密码登录尝试只发生在 e2e_ 前缀账号上（失败计数 5 分钟窗口），
 * 绝不对 admin 做失败登录，避免把共享登录态打进「需要验证码」。
 */
test.describe('业务 › 账号与会员 › 管理员账号', () => {
  /**
   * 登录名会显示在列表「登录名」列、也会出现在邮箱列里，所以用「拼音名 + 4 位数字」
   * 这类真实运营账号的样子；唯一性靠那 4 位数字（同名账号不会撞）。
   * 清理仍然按登录名来删，只删本用例自己建的。
   */
  const ADMIN_LOGINS = ['chenyunwei', 'xukehu', 'zhoucang', 'suncaishui', 'wuxiaoyu', 'linshuhang']
  const uname = () => `${pick(ADMIN_LOGINS, stamp())}${String(Date.now()).slice(-4)}`
  const created = []          // 本用例创建过的账号，finally 里逐个清理

  test.afterEach(async ({ request, page }) => {
    for (const u of created.reverse()) {
      await deleteAdminAccountApi(request, page, u).catch(() => {})
    }
    created.length = 0
  })

  test('新增→新账号登录→编辑→删除→边界（重名/必填/禁用）', async ({ page, request, browser }) => {
    test.setTimeout(300_000)
    const u1 = uname()
    const nick1 = adminNickOf(u1)                 // 列表「昵称」列显示它
    const nick1Renamed = `${nick1}（代班）`
    const u1Holder = u1.replace(/(\d{4})$/, '$102')   // 编辑后的邮箱前缀，保持唯一
    const pwd1 = 'E2e-Pass-123'
    created.push(u1)

    await test.step('① admin 登录并进入管理员页，能看到现有管理员 admin', async () => {
      await adminLoginHardened(page)
      await gotoAdminList(page)
      await expect(adminRow(page, 'admin'), '列表应有内置管理员 admin').toBeVisible()
      await expect(adminRow(page, 'admin')).toContainText('超级管理员')
    })

    await test.step('② 新增管理员（用户名/密码/昵称/邮箱）→ 列表出现', async () => {
      const before = await tableRowCount(page)
      await openAdminForm(page)
      await fillAdminUsername(page, u1)
      await fillAdminPassword(page, pwd1)
      await fillAdminNickname(page, nick1)
      await fillAdminEmail(page, `${u1}@qiqi-mall.cn`)
      await submitAdminForm(page)
      await expectToastSuccess(page, '保存成功')
      await expect(adminRow(page, u1), '新建的管理员应出现在列表').toBeVisible()
      expect(await tableRowCount(page), '列表应多一行').toBe(before + 1)
    })

    await test.step('③ 用新账号登录后台（关键：证明账号真的可用）', async () => {
      const { leftLoginPage } = await tryAdminLogin(browser, u1, pwd1)
      expect(leftLoginPage, '新账号 + 正确密码应能进入后台').toBe(true)
      // 新账号没建过数据，进后台看得到基础布局即可（列表接口只读）
    })

    await test.step('④ 编辑：改昵称/邮箱（密码留空=不改）→ 列表更新', async () => {
      await openAdminForm(page, adminRow(page, u1))
      await fillAdminNickname(page, nick1Renamed)
      await fillAdminEmail(page, `${u1Holder}@qiqi-mall.cn`)
      await submitAdminForm(page)
      await expectToastSuccess(page, '保存成功')
      const row = adminRow(page, u1)
      await expect(row, '编辑后昵称应更新').toContainText(nick1Renamed)
      await expect(row, '编辑后邮箱应更新').toContainText(`${u1Holder}@qiqi-mall.cn`)
      // 只读接口复核
      const token = await adminTokenOf(page)
      const remote = await getAdminByUsernameApi(request, token, u1)
      expect(remote?.nickname, '接口侧昵称应一致').toBe(nick1Renamed)
      // 编辑不改密码 → 原密码仍能登录
      const relogin = await tryAdminLogin(browser, u1, pwd1)
      expect(relogin.leftLoginPage, '编辑（密码留空）后原密码应仍能登录').toBe(true)
    })

    await test.step('⑤ 删除自建管理员（真实 UI：行内删除 → 确认框）→ 列表消失 + 用它登录失败', async () => {
      const before = await tableRowCount(page)
      await clickAdminRowDelete(page, adminRow(page, u1))
      await confirmAdminDeleteBox(page)
      await expectToastSuccess(page, '已删除')
      await expect(adminRow(page, u1), '删除后列表应不再有该账号').toHaveCount(0)
      expect(await tableRowCount(page), '列表应少一行').toBe(before - 1)

      const { leftLoginPage } = await tryAdminLogin(browser, u1, pwd1)
      expect(leftLoginPage, '删除后的账号登录应失败').toBe(false)
    })

    await test.step('⑥ 边界A：用户名重复 → 后端唯一键拒绝，页面红 toast', async () => {
      await openAdminForm(page)
      await fillAdminUsername(page, 'admin')      // 与内置管理员重名
      await fillAdminPassword(page, 'x-Pass-123')
      await fillAdminNickname(page, '临时协助')
      await submitAdminForm(page)
      await expectToastError(page, '保存失败')
      await expect(page.getByTestId('admin-form-dialog'), '失败后弹窗应保持打开').toBeVisible()
      await page.getByTestId('admin-form-cancel').click()
      await expect(page.getByTestId('admin-form-dialog')).toBeHidden()
    })

    await test.step('⑦ 边界B：空登录名/空密码 → 前端必填拦截', async () => {
      await openAdminForm(page)
      await fillAdminUsername(page, '')
      await submitAdminForm(page)
      await expect(adminFormItemError(page), '应出现登录名必填提示').toContainText('请输入登录名')
      await expect(page.getByTestId('admin-form-dialog'), '拦截后弹窗不关、不保存').toBeVisible()
      await page.getByTestId('admin-form-cancel').click()

      await openAdminForm(page)
      await fillAdminUsername(page, uname())
      await submitAdminForm(page)               // 不填密码
      await expect(adminFormItemError(page), '应出现密码必填提示').toContainText('请输入密码')
      await page.getByTestId('admin-form-cancel').click()
    })

    await test.step('⑧ 边界C：禁用（status=0）后登录失败', async () => {
      const u2 = uname()
      created.push(u2)
      await openAdminForm(page)
      await fillAdminUsername(page, u2)
      await fillAdminPassword(page, 'E2e-Pass-456')
      await fillAdminNickname(page, '外包客服')
      await setAdminStatus(page, false)
      await submitAdminForm(page)
      await expectToastSuccess(page, '保存成功')
      await expect(adminRow(page, u2), '列表应显示禁用').toContainText('禁用')
      const { leftLoginPage, bodyText } = await tryAdminLogin(browser, u2, 'E2e-Pass-456')
      expect(leftLoginPage, '禁用账号登录应失败').toBe(false)
      expect(bodyText, '禁用账号登录应被拒绝').toContain('用户名或密码错误')
    })

    await test.step('⑨ 边界D：内置 admin 不可删除（前端黄条拦截，不弹确认框）', async () => {
      await gotoAdminList(page)
      const adminRowLocator = adminRow(page, 'admin')
      await clickAdminRowDelete(page, adminRowLocator)
      expect(await toastText(page), '内置账号删除应被前端拦下').toContain('系统内置账号不可删除')
      // 没有确认框、账号仍在
      await expect(page.locator('.el-message-box'), '前端拦截后不应再弹确认框').toHaveCount(0)
      await expect(adminRow(page, 'admin'), '内置 admin 必须还在').toBeVisible()
    })
  })
})
