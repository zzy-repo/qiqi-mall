import { test, expect } from '../../support/test.js'
import { API } from '../../support/env.js'
import { adminLoginHardened } from '../../support/admin-ui.js'
import {
  gotoMemberList,
  memberRow,
  memberTable,
  openMemberForm,
  fillMemberNickname,
  submitMemberForm,
  cancelMemberForm,
  listMembersApi,
  deleteMemberApi
} from '../../support/admin-ui-member.js'
import { adminTokenOf } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { memberLoginOf } from '../../support/member-login.js'
import { memberNickOf } from '../../support/fixtures.js'

/**
 * 商家侧 02-商品管理 / 8-会员资料编辑并发
 *
 * 一个场景：后台两个**独立 browser context**（两套 localStorage / 两个管理员令牌，
 * 等于两个客服同时打开了同一个会员的编辑弹窗 → 手里是同一个 version=0）
 *   ① ctxA 改昵称保存 → 成功，库里 version 0→1
 *   ② ctxB 拿旧 version 保存 → 后端 true HTTP 409「数据已被他人修改（可能其他人在编辑），请刷新后重试」
 *      → 一个字段都没写进去：库里昵称仍是 A 的值，version 仍是 1
 *   ③ ctxB 按提示刷新（关弹窗 → 重新拉列表 → 重开编辑弹窗）→ 表单回填的是 A 改后的昵称
 *      → 再改一次 → 保存成功，version 1→2
 *
 * 页面事实（admin-web/src/views/member/index.vue，照做不美化）：
 *   · 会员弹窗只有 登录名/昵称/手机号/状态，**没有密码项**；
 *   · 保存失败走 `ElMessage.error('保存失败：' + e.message)` —— 会员页**没有** ConflictDialog，
 *     所以「409 之后怎么刷新」这件事在 UI 上是「关掉弹窗 + 刷新列表后重开弹窗」，
 *     断言因此写成：toast 原文 = 「保存失败：」+ 后端 409 原话（原话本身就在提示「请刷新后重试」），
 *     然后真实走一遍刷新动作，并断言重开的弹窗里已经是 A 的值。
 *
 * 数据自造：本用例自己新建一个会员（登录名唯一），跑完删掉（会员删除页面上没有入口，
 * 见 admin-ui-member.js 的 deleteMemberApi 自查说明）—— 同一份库连跑两轮（不 reset）不留残留。
 *
 * 定位：一律 testid（`member-list-table` / `member-row-edit` / `member-form-*`），
 * 行按全局唯一的登录名过滤，不用「列表第一项」。
 */

/** 409 的两句后端原话（OptimisticLockGuard） */
const MSG_VERSION_CONFLICT = '数据已被他人修改（可能其他人在编辑），请刷新后重试'

/** 造一个与 taken 里都不重复的自然昵称（昵称会显示在列表里，不能带时间戳） */
function distinctNick(seedStr, taken = []) {
  let nick = memberNickOf(seedStr)
  for (let i = 1; taken.includes(nick); i++) nick = memberNickOf(`${seedStr}-${i}`)
  return nick
}

/** 从管理端列表里取这条会员（登录名全局唯一，按接口读，不看页面） */
async function fetchMember(request, token, username) {
  const [member] = await listMembersApi(request, token, username)
  expect(member, `会员 ${username} 应能按登录名查到`).toBeTruthy()
  return member
}

/** 打开该会员的编辑弹窗（行按登录名定位，按钮走 testid） */
async function openEditDialog(page, username) {
  await gotoMemberList(page)
  await openMemberForm(page, memberRow(page, username))
  await expect(tid(page, 'member-form-dialog'), '会员编辑弹窗应打开').toBeVisible()
  return tid(page, 'member-form-dialog')
}

const putRespOf = (page, memberId) =>
  page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/members/${memberId}`),
    { timeout: 30_000 }
  )

/** 管理员令牌（用于跑完删掉本用例自造的会员，不参与业务断言） */
const adminTokenForCleanup = async (request) => {
  const res = await request.post(`${API}/admin/auth/login`, {
    data: { username: 'admin', password: process.env.ADMIN_PASSWORD || '123456' }
  })
  return (await res.json()).data.accessToken
}

test.describe('商家侧 8 会员资料编辑并发', () => {
  test('两个会话同时编辑同一会员：甲保存生效，乙被 409 拦住（库里没被覆盖），刷新后拿到甲的值再改才成功', async ({ browser, page, request }) => {
    test.setTimeout(300_000)

    const username = memberLoginOf(Date.now() + 21)
    // 三个昵称两两不同，才能断言「库里到底是谁的值」
    const nick0 = distinctNick(`${username}-0`)
    const nickA = distinctNick(`${username}-a`, [nick0])
    const nickB = distinctNick(`${username}-b`, [nick0, nickA])

    const ctxA = await browser.newContext()
    const pageA = await ctxA.newPage()
    // 会话乙用 page fixture（与 02/6-商品编辑并发冲突、02/7-SKU编辑并发冲突 同一写法）：
    // 被 409 拦住的是乙，用 fixture 才能录到；甲仍走独立 context，两套 storageState 互不可见。
    const pageB = page

    let memberId = null
    let tokenA = null

    try {
      await test.step('① 自造一个会员（后台新增弹窗），初始 version=0', async () => {
        await adminLoginHardened(pageA)
        tokenA = await adminTokenOf(pageA)
        expect(tokenA, '甲的管理员令牌应拿到').toBeTruthy()

        await gotoMemberList(pageA)
        await openMemberForm(pageA)
        await tid(pageA, 'member-form-username').fill(username)
        await fillMemberNickname(pageA, nick0)
        await tid(pageA, 'member-form-phone').fill('13700007777')
        await submitMemberForm(pageA)
        await expect(pageA.locator('.el-message--success').first(), '新增会员应成功').toBeVisible({ timeout: 15_000 })
        await expect(tid(pageA, 'member-form-dialog'), '保存后弹窗应收起').toBeHidden({ timeout: 15_000 })

        const created = await fetchMember(request, tokenA, username)
        memberId = created.id
        expect(created.nickname, '库里昵称应是新建时填的').toBe(nick0)
        expect(created.version, '新建会员的初始版本号应为 0').toBe(0)
        await expect(memberTable(pageA).locator('.el-table__body tr').filter({ hasText: username }).first(),
          '列表里应出现这条会员').toBeVisible()
        console.log(`[08 造数据] member id=${memberId} username=${username} nickname=${created.nickname} version=${created.version}`)
      })

      await test.step('② 两个会话各自登录后台，都打开该会员的编辑弹窗（两边拿到同一个 version=0）', async () => {
        await openEditDialog(pageA, username)
        await adminLoginHardened(pageB)
        const tokenB = await adminTokenOf(pageB)
        expect(tokenB, '乙的管理员令牌应拿到').toBeTruthy()
        // 两个 browser context 的 localStorage 互不可见（否则就不是两个会话）
        await pageA.evaluate(() => localStorage.setItem('concurrency-probe', 'only-in-context-A'))
        expect(await pageB.evaluate(() => localStorage.getItem('concurrency-probe')),
          '两个 context 的 localStorage 必须互不可见').toBeNull()

        await openEditDialog(pageB, username)
        await expect(tid(pageA, 'member-form-nickname'), '甲打开时看到的应是原始昵称').toHaveValue(nick0)
        await expect(tid(pageB, 'member-form-nickname'), '乙打开时看到的也是原始昵称（同一份 V=0）').toHaveValue(nick0)
      })

      await test.step('③ 甲改昵称保存 → 成功，库里 version 0→1', async () => {
        await fillMemberNickname(pageA, nickA)
        const respP = putRespOf(pageA, memberId)
        await submitMemberForm(pageA)
        const resp = await respP
        expect(resp.status(), '甲的保存应成功（HTTP 200）').toBe(200)
        expect((await resp.json()).code, '甲保存的业务码应为 0').toBe(0)
        await expect(pageA.locator('.el-message--success').first(), '甲应看到「保存成功」').toBeVisible({ timeout: 15_000 })
        await expect(tid(pageA, 'member-form-dialog'), '甲保存后弹窗应收起').toBeHidden({ timeout: 15_000 })

        const afterA = await fetchMember(request, tokenA, username)
        expect(afterA.nickname, '库里昵称应变成甲改的值').toBe(nickA)
        expect(afterA.version, '甲的这次保存应把版本号推进到 1').toBe(1)
        console.log(`[08 甲保存成功] PUT http=${resp.status()}；库里 nickname=${afterA.nickname} version=${afterA.version}`)
      })

      await test.step('④ 乙用旧 version 保存 → 真 HTTP 409，页面原样显示后端那句中文', async () => {
        await fillMemberNickname(pageB, nickB)
        const respP = putRespOf(pageB, memberId)
        await submitMemberForm(pageB)
        const resp = await respP
        const body = await resp.json()
        expect(resp.status(), '乙的保存必须被拒绝（真 HTTP 409，不是 200 + body 里说失败）').toBe(409)
        expect(body.code, 'body 里的业务码也应是 409').toBe(409)
        expect(body.message, '后端拒因应是那句中文原文').toBe(MSG_VERSION_CONFLICT)

        const errToast = pageB.locator('.el-message--error').first()
        await expect(errToast, '乙应看到保存失败提示').toBeVisible({ timeout: 15_000 })
        await expect(errToast, '提示文案必须是「保存失败：」+ 后端 409 原话')
          .toHaveText(`保存失败：${MSG_VERSION_CONFLICT}`)
        await expect(pageB.locator('.el-message--success'), '乙不应看到任何成功提示').toHaveCount(0)
        await expect(tid(pageB, 'member-form-dialog'), '失败时弹窗不自动关闭（乙的数据还在，可取消/刷新）').toBeVisible()

        // 乙这次一个字段都没写进去
        const afterB = await fetchMember(request, tokenA, username)
        expect(afterB.nickname, '乙没有覆盖甲：库里昵称应仍是甲的值').toBe(nickA)
        expect(afterB.version, '被拒的那次保存不应推进版本号（仍是 1）').toBe(1)

        const errText = (await errToast.innerText()).trim()
        console.log(`[08 乙被拒] PUT /admin/members/${memberId} → http=${resp.status()} body=${JSON.stringify(body)}`)
        console.log(`[08 页面文案] 乙 toast="${errText}"；库里仍是 甲 的值 nickname=${afterB.nickname} version=${afterB.version}`)
        test.info().annotations.push({
          type: '409 响应原文',
          description: `PUT /admin/members/${memberId} → http=${resp.status()}, body=${JSON.stringify(body)}`
        })
      })

      await test.step('⑤ 乙按提示刷新（取消 → 重新拉列表 → 重开弹窗）：表单回填的是甲的值', async () => {
        await cancelMemberForm(pageB)
        await pageB.reload()
        await expect(memberTable(pageB), '刷新后会员列表应仍渲染').toBeVisible({ timeout: 20_000 })
        await pageB.waitForTimeout(1200)
        const row = memberTable(pageB).locator('.el-table__body tr').filter({ hasText: username }).first()
        await expect(row, '刷新后列表里应还能看到这条会员').toBeVisible()
        await expect(row, '列表昵称列应已显示甲改后的值').toContainText(nickA)

        await openMemberForm(pageB, row)
        await expect(tid(pageB, 'member-form-nickname'), '重开的弹窗应回填甲改后的昵称')
          .toHaveValue(nickA)

        await fillMemberNickname(pageB, nickB)
        const respP = putRespOf(pageB, memberId)
        await submitMemberForm(pageB)
        const resp = await respP
        expect(resp.status(), '乙刷新后重新保存应成功（HTTP 200）').toBe(200)
        expect((await resp.json()).code, '这次保存的业务码应为 0').toBe(0)
        await expect(pageB.locator('.el-message--success').first(), '乙应看到「保存成功」').toBeVisible({ timeout: 15_000 })
        await expect(tid(pageB, 'member-form-dialog'), '乙保存后弹窗应收起').toBeHidden({ timeout: 15_000 })

        const final = await fetchMember(request, tokenA, username)
        expect(final.nickname, '库里昵称应变成乙改的值').toBe(nickB)
        expect(final.version, '乙的这次保存应把版本号再推到 2').toBe(2)
        await expect(memberTable(pageB).locator('.el-table__body tr').filter({ hasText: username }).first(),
          '列表昵称列应已刷新成乙的值').toContainText(nickB)

        console.log(`[08 最终状态] member(id=${memberId}) nickname=${final.nickname} version=${final.version}`)
        test.info().annotations.push({
          type: '最终状态',
          description: `member(id=${memberId}) nickname=${final.nickname} version=${final.version}`
        })
      })
    } finally {
      // 清掉本用例自造的会员（页面无删除入口，只能走接口，见 deleteMemberApi 注释）
      try {
        if (memberId) {
          const token = tokenA ?? (await adminTokenForCleanup(request))
          const { status, body } = await deleteMemberApi(request, token, memberId)
          const left = await listMembersApi(request, token, username)
          console.log(`[08 清理] 会员 id=${memberId} 删除 http=${status} code=${body?.code}；列表里剩余同名会员 ${left.length} 条`)
          test.info().annotations.push({ type: '清理', description: `会员 id=${memberId} 已删除=${status === 200}` })
        }
      } catch (e) {
        test.info().annotations.push({ type: '清理失败', description: String(e.message || e) })
      }
      await ctxA.close().catch(() => {})
    }
  })
})
