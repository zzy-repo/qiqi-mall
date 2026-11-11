import { test, expect } from '@playwright/test'
import { PORTAL, stamp } from '../../support/env.js'
import { addressOf, memberNickOf, pick } from '../../support/fixtures.js'
import { memberRegisterAndLogin, addSpecToCart } from '../../support/ui.js'
import { addAddress, openAddresses, loginError } from '../../support/portal-ui.js'
import {
  adminTokenOf,
  getProductApi,
  getMemberProfileApi,
  getMemberAddressesApi,
  getMemberCartApi
} from '../../support/assert.js'
import { expectToastSuccess, expectToastError } from '../../support/admin-ui.js'
import {
  gotoMemberList,
  memberTable,
  memberRow,
  memberRowCount,
  reloadMemberList,
  openMemberForm,
  memberFormHasPasswordField,
  fillMemberUsername,
  fillMemberNickname,
  fillMemberPhone,
  setMemberStatus,
  submitMemberForm,
  cancelMemberForm,
  memberFormError,
  openMemberAddresses,
  openMemberCarts,
  memberSubTableRows,
  memberSubPageName,
  backToMemberList,
  listMembersApi,
  getMemberDetailApi,
  deleteMemberApi,
  adminLoginResilient
} from '../../support/admin-ui-member.js'

/**
 * 05-账号与会员 / 3-会员管理（docs/flows/02-业务场景/05-账号与会员/3-会员管理/1-会员管理流程图.mmd）
 *
 * 覆盖：会员列表 → 聚合子页（收货地址 / 购物车，各查各的）→ 新增 → 重名 → 空必填 →
 *       编辑 → 禁用 → 删除；以及账号可用性联动（C 端注册的会员后台可见、后台改昵称不影响登录、
 *       禁用后登录被拒、恢复启用后能登录、删除后登录失败）。
 *
 * 断言口径全部来自代码实际行为，两处「文档设想 vs 代码现状」的差异在用例里显式钉住：
 *
 *  1) 新增/编辑会员弹窗**没有密码字段**（member.password 在库里是 NOT NULL DEFAULT ''），
 *     PortalAuthController.login 遇到空密码直接判「用户名或密码错误」。
 *     所以「后台新建的会员能不能登录 C 端」的答案是**不能**——这不是用例漏测，
 *     用例把它当现状断言（见测试②/③）。真正可登录的账号走 C 端注册（register 会 BCrypt 入库）。
 *
 *  2) 列表**没有「详情」按钮，也没有「删除」按钮**。操作列只有 收货地址 / 购物车 / 编辑，
 *     聚合详情以两个独立子路由呈现（/member/:id/addresses、/member/:id/carts）。
 *     因此「详情」按子页走，「删除」只能走接口（support/admin-ui-member.js 里 deleteMemberApi
 *     有「页面无入口」的自查说明），删完用真实 reload 断言列表变化。
 */

/**
 * 本用例自己建的会员，afterEach 统一清理，绝不碰种子会员。
 * 登录名会显示在会员列表的「登录名」列（子页标题也带它），所以不用 e2e_ 前缀，
 * 改成「拼音名 + 4 位数字」这类真实会员号；唯一性仍然靠那 4 位数字。
 */
const created = []

const LOGIN_BASES = ['linwan', 'shenzhiyao', 'chengxubai', 'sunian', 'guqinghe', 'yexishen', 'xuxingye', 'wenyining']
/**
 * 自然登录名：拼音基名 + 3 位序号 + 3 位随机数。
 * 序号是必须的：同一个用例里连续两次 loginOf() 会落在同一毫秒，只用时间戳会撞成同名
 * （曾经就这样让「新增会员」变成重名，toast 等不到）。
 */
let loginSeq = 0
const loginOf = () =>
  `${pick(LOGIN_BASES, stamp())}${String(loginSeq++ % 1000).padStart(3, '0')}${Math.floor(Math.random() * 900 + 100)}`

test.describe.configure({ timeout: 300_000 })

test.afterEach(async ({ page, request }) => {
  const token = await adminTokenOf(page).catch(() => null)
  if (token) {
    for (const username of created.splice(0)) {
      const rows = await listMembersApi(request, token, username).catch(() => [])
      for (const m of rows) await deleteMemberApi(request, token, m.id).catch(() => {})
    }
  } else {
    created.length = 0
  }
})

test.describe('05-账号与会员 › 3-会员管理', () => {
  test('会员列表 + 收货地址子页：只看得到自己的地址', async ({ page, request }) => {
    const withData = loginOf()
    const noData = loginOf()
    created.push(withData, noData)
    const addr = addressOf(withData)

    await test.step('① 造一个「有地址」的会员：C 端注册 → 加收货地址', async () => {
      await memberRegisterAndLogin(page, withData, '123456', memberNickOf(withData))
      await openAddresses(page)
      await addAddress(page, {
        name: addr.name,
        phone: addr.phone,
        province: addr.province,
        city: addr.city,
        region: addr.region,
        detail: addr.detail,
        isDefault: true
      })
      const token = await page.evaluate(() => localStorage.getItem('qiqi_member_token'))
      expect((await getMemberAddressesApi(request, token)).length, '该会员应有 1 条地址').toBe(1)
    })

    await test.step('② 管理员登录 → 会员列表能看到这个会员', async () => {
      await adminLoginResilient(page, request)
      await gotoMemberList(page)
      await expect(memberTable(page), '会员列表应渲染').toBeVisible()
      await expect(memberRow(page, withData), 'C 端注册的会员应在后台会员列表出现').toBeVisible()
      await expect(memberRow(page, withData), '新会员默认启用').toContainText('启用')
    })

    await test.step('③ 再新增一个「没有任何数据」的会员（后台新增）', async () => {
      await openMemberForm(page)
      await fillMemberUsername(page, noData)
      await fillMemberNickname(page, memberNickOf(noData))
      await fillMemberPhone(page, '13900002222')
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(memberRow(page, noData), '后台新增的会员应出现在列表').toBeVisible()
    })

    await test.step('④ 子页-收货地址：只看得到自己的地址', async () => {
      const id = await openMemberAddresses(page, memberRow(page, withData))
      await expect(memberSubPageName(page), '子页标题应标明是谁的地址').toContainText(withData)
      await expect(memberSubTableRows(page), '该会员应有 1 条地址').toHaveCount(1)
      await expect(memberSubTableRows(page).first(), '地址应是该会员自己那条').toContainText(addr.name)

      const adminToken = await adminTokenOf(page)
      const detail = await getMemberDetailApi(request, adminToken, id)
      expect(detail.member.username).toBe(withData)
      expect(detail.addresses.length, '聚合详情里地址应为 1 条').toBe(1)
      expect(detail.addresses[0].name).toBe(addr.name)
      expect(detail.carts.length, '这个会员没加购，聚合详情里购物车应为 0').toBe(0)

      await backToMemberList(page)
      await openMemberAddresses(page, memberRow(page, noData))
      await expect(
        page.locator('[data-testid="member-address-list-table"]'),
        '没建过地址的会员，地址子页应是空的（看不到别人的地址）'
      ).toContainText('暂无数据')
    })
  })

  test('购物车子页：只看得到自己的购物车', async ({ page, request }) => {
    const withCart = loginOf()
    const noCart = loginOf()
    created.push(withCart, noCart)

    await test.step('① 造一个「有购物车」的会员：C 端注册 → 加购一件商品', async () => {
      await memberRegisterAndLogin(page, withCart, '123456', memberNickOf(withCart))
      const { product, skus } = await getProductApi(request, 1)
      await addSpecToCart(page, 1, skus[0].specText)
      test.info().annotations.push({ type: '商品', description: `${product.name} / ${skus[0].specText}` })
      const token = await page.evaluate(() => localStorage.getItem('qiqi_member_token'))
      expect((await getMemberCartApi(request, token)).length, '该会员应有 1 条购物车').toBe(1)
      expect((await getMemberAddressesApi(request, token)).length, '这个会员没地址，应为 0').toBe(0)
    })

    await test.step('② 管理员登录 → 再新增一个「没加过购」的会员', async () => {
      await adminLoginResilient(page, request)
      await gotoMemberList(page)
      await expect(memberRow(page, withCart), 'C 端注册的会员应出现在后台列表').toBeVisible()
      await openMemberForm(page)
      await fillMemberUsername(page, noCart)
      await fillMemberNickname(page, memberNickOf(noCart))
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(memberRow(page, noCart)).toBeVisible()
    })

    await test.step('③ 子页-购物车：只看得到自己的购物车', async () => {
      const id = await openMemberCarts(page, memberRow(page, withCart))
      await expect(memberSubPageName(page), '子页标题应标明是谁的购物车').toContainText(withCart)
      await expect(memberSubTableRows(page), '该会员购物车应有 1 条').toHaveCount(1)
      const status = await page.locator('[data-testid="member-cart-list-table"]').innerText()
      expect(status, '购物车行不能是空的').not.toContain('暂无数据')

      const adminToken = await adminTokenOf(page)
      const detail = await getMemberDetailApi(request, adminToken, id)
      expect(detail.carts.length, '聚合详情里购物车应为 1 条').toBe(1)
      expect(detail.carts[0].quantity).toBe(1)
      expect(detail.addresses.length, '这个会员没地址').toBe(0)

      await backToMemberList(page)
      await openMemberCarts(page, memberRow(page, noCart))
      await expect(
        page.locator('[data-testid="member-cart-list-table"]'),
        '没加过购的会员，购物车子页应是空的（看不到别人的购物车）'
      ).toContainText('暂无数据')
    })
  })

  test('新增 → 重名 → 空必填 → 编辑 → 禁用 → 删除（删完列表消失且无法登录）', async ({ page, request }) => {
    const username = loginOf()
    const nickname = memberNickOf(username)
    const renamed = `${pick(['林晚', '沈知遥', '程叙白', '苏念'], username)}（代收）`
    created.push(username)

    await test.step('① 管理员登录并进入会员列表', async () => {
      await adminLoginResilient(page, request)
      await gotoMemberList(page)
      await expect(memberTable(page)).toBeVisible()
    })

    const before = await memberRowCount(page)

    await test.step('② 新增会员：登录名/昵称/手机号 → 保存 → 列表出现', async () => {
      await openMemberForm(page)
      // 现状记录：弹窗里没有密码项，后台建出来的账号没有初始密码（后面③会验证它登录不了）
      expect(await memberFormHasPasswordField(page), '当前新增会员弹窗没有密码字段').toBe(false)
      await fillMemberUsername(page, username)
      await fillMemberNickname(page, nickname)
      await fillMemberPhone(page, '13700001234')
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(page.locator('[data-testid="member-form-dialog"]')).toBeHidden({ timeout: 10_000 })
      const row = memberRow(page, username)
      await expect(row, '新增后列表应出现该会员').toBeVisible()
      await expect(row).toContainText(nickname)
      await expect(row).toContainText('13700001234')
      await expect(row, '新增默认启用').toContainText('启用')
      expect(await memberRowCount(page), '列表应多一行').toBe(before + 1)

      const adminToken = await adminTokenOf(page)
      const [m] = await listMembersApi(request, adminToken, username)
      expect(m, '服务端应能按登录名查到').toBeTruthy()
      expect(m.nickname).toBe(nickname)
      expect(m.status, '新增默认启用(1)').toBe(1)
      expect(m.password, '密码只写不读，接口不应返回密码').toBeUndefined()
    })

    await test.step('③ 重名：登录名唯一约束，保存失败且列表不变', async () => {
      await openMemberForm(page)
      await fillMemberUsername(page, username)
      await fillMemberNickname(page, memberNickOf('dup'))
      await submitMemberForm(page)
      await expectToastError(page, '已存在相同记录')
      // 弹窗不关，改成取消退出；列表行数不变
      await cancelMemberForm(page)
      expect(await memberRowCount(page), '重名不应产生新行').toBe(before + 1)
    })

    await test.step('④ 空必填：登录名留空 → 就地报错，不提交', async () => {
      await openMemberForm(page)
      await fillMemberUsername(page, '')
      await submitMemberForm(page)
      await expect(memberFormError(page), '应提示「请输入登录名」').toContainText('请输入登录名')
      await expect(page.locator('[data-testid="member-form-dialog"]'), '校验不通过时弹窗不关').toBeVisible()
      await cancelMemberForm(page)
      expect(await memberRowCount(page), '校验失败不应产生新行').toBe(before + 1)
    })

    await test.step('⑤ 编辑：改昵称/手机号 → 列表与服务端同步更新', async () => {
      await openMemberForm(page, memberRow(page, username))
      await fillMemberNickname(page, renamed)
      await fillMemberPhone(page, '13700009999')
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(page.locator('[data-testid="member-form-dialog"]')).toBeHidden({ timeout: 10_000 })
      const row = memberRow(page, username)
      await expect(row, '列表应显示新昵称').toContainText(renamed)
      await expect(row, '列表应显示新手机号').toContainText('13700009999')
      // 昵称列逐格比对（不能整行 not.toContainText：新昵称里就带着旧昵称前缀）
      expect((await row.locator('td').nth(1).innerText()).trim(), '昵称列应只剩新昵称').toBe(renamed)

      const adminToken = await adminTokenOf(page)
      const [m] = await listMembersApi(request, adminToken, username)
      expect(m.nickname).toBe(renamed)
      expect(m.phone).toBe('13700009999')
    })

    await test.step('⑥ 边界：超长文本按 maxlength 就地截断（登录名/昵称 50、手机号 20）', async () => {
      await openMemberForm(page, memberRow(page, username))
      await fillMemberNickname(page, '昵'.repeat(60))
      await fillMemberPhone(page, '1'.repeat(30))
      // el-input maxlength：输入就截断，不报错、不拦截保存
      expect((await page.locator('[data-testid="member-form-nickname"]').inputValue()).length, '昵称应截断到 50').toBe(50)
      expect((await page.locator('[data-testid="member-form-phone"]').inputValue()).length, '手机号应截断到 20').toBe(20)
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(page.locator('[data-testid="member-form-dialog"]')).toBeHidden({ timeout: 10_000 })

      const adminToken = await adminTokenOf(page)
      const [m] = await listMembersApi(request, adminToken, username)
      expect(m.nickname.length, '服务端昵称长度不应超过 50').toBe(50)
      expect(m.phone.length, '服务端手机号长度不应超过 20').toBe(20)
    })

    await test.step('⑦ 禁用：关掉状态开关 → 列表标记禁用，服务端 status=0', async () => {
      await openMemberForm(page, memberRow(page, username))
      await setMemberStatus(page, false)
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(page.locator('[data-testid="member-form-dialog"]')).toBeHidden({ timeout: 10_000 })
      await expect(memberRow(page, username), '关闭状态后应显示禁用').toContainText('禁用')

      const adminToken = await adminTokenOf(page)
      const [m] = await listMembersApi(request, adminToken, username)
      expect(m.status, '服务端应记为禁用(0)').toBe(0)
    })

    await test.step('⑧ 删除：页面无删除入口，走接口删除后列表 reload 真的少一行', async () => {
      const adminToken = await adminTokenOf(page)
      const [m] = await listMembersApi(request, adminToken, username)
      const res = await deleteMemberApi(request, adminToken, m.id)
      expect(res.status, '删除应成功').toBe(200)
      await reloadMemberList(page)
      await expect(memberRow(page, username), '删除后列表不应再有该会员').toHaveCount(0)
      expect(await memberRowCount(page), '列表应回到新增前的行数').toBe(before)
      expect(await listMembersApi(request, adminToken, username), '服务端也应查不到').toHaveLength(0)
    })
  })

  test('账号可用性：C 端注册的会员后台可见 → 改昵称仍能登录 → 禁用拦截 → 删除后登录失败', async ({ page, request }) => {
    const acc = loginOf()
    const accRenamed = `${memberNickOf(acc)}（改）`
    const noPwd = loginOf()
    created.push(acc, noPwd)

    const portalLogout = async () => {
      if (!page.url().startsWith(PORTAL)) await page.goto(`${PORTAL}/`)
      const logout = page.getByText('退出', { exact: true }).first()
      if (await logout.count()) {
        await logout.click()
        await page.waitForTimeout(800)
      }
      await page.goto(`${PORTAL}/login`)
      await page.waitForTimeout(400)
    }

    const portalLogin = async (username, password) => {
      await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').fill(username)
      await page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input').fill(password)
      await page.getByRole('button', { name: '登录' }).click()
    }

    await test.step('① C 端注册一个会员（注册即登录）→ 账号可用', async () => {
      await memberRegisterAndLogin(page, acc, '123456', memberNickOf(acc))
      const token = await page.evaluate(() => localStorage.getItem('qiqi_member_token'))
      expect(token, '注册后应写入登录令牌').toBeTruthy()
      expect((await getMemberProfileApi(request, token)).username).toBe(acc)
    })

    await test.step('② 后台会员列表能看到它，并能编辑昵称', async () => {
      await adminLoginResilient(page, request)
      await gotoMemberList(page)
      await expect(memberRow(page, acc), '刚注册的会员应出现在后台列表').toBeVisible()
      await openMemberForm(page, memberRow(page, acc))
      await fillMemberNickname(page, accRenamed)
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(page.locator('[data-testid="member-form-dialog"]')).toBeHidden({ timeout: 10_000 })
      await expect(memberRow(page, acc), '列表应显示后台改过的昵称').toContainText(accRenamed)
    })

    await test.step('③ C 端退出后用原密码重新登录：后台改昵称不影响登录', async () => {
      await portalLogout()
      await portalLogin(acc, '123456')
      await expect(page, '后台改过昵称后仍应能登录').not.toHaveURL(/\/login/, { timeout: 20_000 })
      const token = await page.evaluate(() => localStorage.getItem('qiqi_member_token'))
      const profile = await getMemberProfileApi(request, token)
      expect(profile.nickname, 'C 端看到的应是后台改后的昵称').toBe(accRenamed)
    })

    await test.step('④ 后台禁用该会员 → C 端登录被拒', async () => {
      await gotoMemberList(page)
      await openMemberForm(page, memberRow(page, acc))
      await setMemberStatus(page, false)
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(memberRow(page, acc)).toContainText('禁用')

      await portalLogout()
      await portalLogin(acc, '123456')
      await expect(loginError(page), '禁用账号的密码正确也应登录失败').toContainText('用户名或密码错误', { timeout: 15_000 })
      await expect(page, '登录失败应停留在登录页').toHaveURL(/\/login/)
    })

    await test.step('⑤ 后台恢复启用 → C 端又能登录', async () => {
      await gotoMemberList(page)
      await openMemberForm(page, memberRow(page, acc))
      await setMemberStatus(page, true)
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(memberRow(page, acc)).toContainText('启用')

      await portalLogout()
      await portalLogin(acc, '123456')
      await expect(page, '恢复启用后应能登录').not.toHaveURL(/\/login/, { timeout: 20_000 })
    })

    await test.step('⑥ 现状：后台新增的会员没有初始密码，C 端登录不了', async () => {
      await gotoMemberList(page)
      await openMemberForm(page)
      expect(await memberFormHasPasswordField(page), '后台新增会员弹窗没有密码字段').toBe(false)
      await fillMemberUsername(page, noPwd)
      await fillMemberNickname(page, memberNickOf(noPwd))
      await submitMemberForm(page)
      await expectToastSuccess(page)
      await expect(memberRow(page, noPwd)).toBeVisible()

      // 服务端事实：password 为空串（接口不返回密码，这里用「登录必然失败」来证明）
      await portalLogout()
      await portalLogin(noPwd, '123456')
      await expect(loginError(page), '后台新建的会员没有初始密码，任何密码都登录不了').toContainText('用户名或密码错误', { timeout: 15_000 })
    })

    await test.step('⑦ 删除会员 → 列表消失且该账号登录失败', async () => {
      // 一定要先回到 admin 源再读后台令牌（令牌存在各自源的 localStorage 里，门户页读不到 admin 令牌）
      await gotoMemberList(page)
      const adminToken = await adminTokenOf(page)
      expect(adminToken, '应能读到后台令牌').toBeTruthy()
      const [m] = await listMembersApi(request, adminToken, acc)
      expect(m, '删除前应能按登录名查到').toBeTruthy()
      expect((await deleteMemberApi(request, adminToken, m.id)).status).toBe(200)
      await reloadMemberList(page)
      await expect(memberRow(page, acc), '删除后列表不应再有该会员').toHaveCount(0)

      await portalLogout()
      await portalLogin(acc, '123456')
      await expect(loginError(page), '账号被删后登录应失败').toContainText('用户名或密码错误', { timeout: 15_000 })
      await expect(page).toHaveURL(/\/login/)

      // 顺手把⑥建的无密码账号也删掉（本用例自建，只动自己建的）
      const [n] = await listMembersApi(request, adminToken, noPwd)
      if (n) await deleteMemberApi(request, adminToken, n.id)
    })
  })
})
