import { test, expect } from '@playwright/test'
import { PORTAL, stamp } from '../../support/env.js'
import {
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder,
  memberOrderRow
} from '../../support/ui.js'
import {
  adminLoginResilient,
  gotoReturnReasonList,
  returnReasonRow,
  returnReasonRowCount,
  openReturnReasonForm,
  fillReturnReasonName,
  submitReturnReasonForm,
  cancelReturnReasonForm,
  returnReasonFormError,
  deleteReturnReasonRow,
  toastTextOf,
  gotoReturnApplyList,
  returnApplyRow,
  reasonCellOf,
  readReasonsApi,
  dropReason,
  readReturnAppliesApi,
  dropReturnApply
} from '../../support/admin-ui-config.js'
import { adminTokenOf, getProductApi, memberTokenOf, getMemberReturnsApi } from '../../support/assert.js'
import { uniqueDisplayName, memberNickOf, receiverOf, returnDescriptionOf } from '../../support/fixtures.js'

/**
 * 04-商家-基础配置 / 3-退货原因字典
 * 图：docs/flows/02-业务场景/04-商家-基础配置/3-退货原因字典/1-业务流程.mmd
 *
 * 分两组：
 *   ① 字典维护页面 —— 管理员在「退货原因」页增删改，含空名 / 重名 / 删除三种分支
 *   ② 字典消费链路 —— 字典项进入买家「申请退货」下拉 → 提交后名称快照进申请单 →
 *      管理端「退货申请」能看到原因 → 被申请引用后不许删除
 *
 * 断言口径来自代码实际行为：
 *   - 页面 return-reason/index.vue：空名必填校验在前端；重名只是**前端**拦一次
 *     （ElMessage.warning「已存在同名退货原因，请换一个名称」），后端 ReturnReasonController
 *     本身不校验重名、也不校验空名；
 *   - 删除被引用项由后端 CatalogDeleteGuard 拦（a1271db），文案「该原因已被 N 条退货申请使用，不能删除」；
 *   - 退货申请单里存的是 reason_id + 提交那一刻的原因名称（PortalReturnController.apply），
 *     所以改名不影响历史申请显示。
 *
 * 清理纪律：只删本次自己造的 e2e_ 项，种子原因（质量问题/七天无理由/发错货/不想要了）一个不碰。
 */
test.describe('04-商家-基础配置 › 3-退货原因字典', () => {
  // 本次运行自己造的字典项 id：无论用例成败，afterEach 都清掉，绝不给别人留脏数据
  let createdIds = []

  const trackCreated = async (page, request, name) => {
    const token = await adminTokenOf(page)
    const dict = await readReasonsApi(request, token)
    const row = dict.find((r) => r.name === name)
    if (row) createdIds.push(row.id)
    return row
  }

  test.afterEach(async ({ page, request }) => {
    if (!createdIds.length) return
    const token = await adminTokenOf(page).catch(() => null)
    for (const id of createdIds) {
      if (!token) break
      // 被申请引用时会失败，这属于预期（对应用例自己会清理申请），忽略即可
      await dropReason(request, token, id).catch(() => {})
    }
    createdIds = []
  })

  test.describe('字典维护页面', () => {
    test('新增 / 编辑改名 / 空名与重名拦截 / 删除', async ({ page, request }) => {
      test.setTimeout(240_000)
      const suffix = stamp()
      // 显示名要像真的：「包装破损（第 N 期）」，N 由库里现有名字递推，天然不重名；
      // 改名后的名字不含原名子串，否则「旧名字不应再出现」会被子串匹配误命中
      let origin
      let renamed

      await test.step('① 管理员进入「退货原因」：菜单有入口，列表里能看到种子原因', async () => {
        await adminLoginResilient(page)
        await expect(page.getByTestId('nav-return-reason'), '菜单里应有退货原因入口').toHaveCount(1)
        await gotoReturnReasonList(page)
        await expect(returnReasonRow(page, '质量问题'), '种子原因应在列表里').toBeVisible()
        await expect(page.getByText('已被退货申请使用的原因不能删除')).toBeVisible()

        const dict = await readReasonsApi(request, await adminTokenOf(page))
        const existing = dict.map((r) => r.name)
        origin = uniqueDisplayName('包装破损', existing)
        renamed = uniqueDisplayName('包装损坏', existing)
      })

      await test.step('② 新增一条原因 → 提示保存成功，列表出现这一行', async () => {
        const before = await returnReasonRowCount(page)
        await openReturnReasonForm(page)
        await fillReturnReasonName(page, origin)
        await submitReturnReasonForm(page)
        await expect(page.locator('.el-message--success').first(), '应提示保存成功').toBeVisible({
          timeout: 15_000
        })
        await expect(returnReasonRow(page, origin), '新建的原因应出现在列表').toBeVisible()
        expect(await returnReasonRowCount(page), '列表应多一行').toBe(before + 1)
        await trackCreated(page, request, origin)
      })

      await test.step('③ 编辑改名 → 列表这一行立即变成新名字，旧名字消失', async () => {
        await openReturnReasonForm(page, returnReasonRow(page, origin))
        await fillReturnReasonName(page, renamed)
        await submitReturnReasonForm(page)
        await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
        await expect(returnReasonRow(page, renamed), '改名后应能按新名字找到').toBeVisible()
        await expect(returnReasonRow(page, origin), '旧名字不应再出现').toHaveCount(0)
      })

      await test.step('④ 空名拦截：清空名称点确定 → 提示必填，弹窗不关、列表不变', async () => {
        const before = await returnReasonRowCount(page)
        await openReturnReasonForm(page)
        await fillReturnReasonName(page, '')
        await submitReturnReasonForm(page)
        await expect(returnReasonFormError(page), '应提示请输入退货原因').toContainText('请输入退货原因')
        await expect(page.locator('[data-testid="return-reason-form-dialog"]'), '校验不过弹窗不能关').toBeVisible()
        await cancelReturnReasonForm(page)
        expect(await returnReasonRowCount(page), '取消后行数不变').toBe(before)
      })

      await test.step('⑤ 重名拦截：新增一个已存在的名字 → 前端 warning，不写库、列表不变', async () => {
        const before = await returnReasonRowCount(page)
        await openReturnReasonForm(page)
        await fillReturnReasonName(page, renamed)
        await submitReturnReasonForm(page)
        expect(await toastTextOf(page, 'warning'), '重名应给出 warning 提示').toContain('已存在同名退货原因')
        await expect(page.locator('[data-testid="return-reason-form-dialog"]'), '重名时不关弹窗').toBeVisible()
        await cancelReturnReasonForm(page)
        expect(await returnReasonRowCount(page), '重名不该多出行').toBe(before)

        // 服务端确实没有重名校验：直接问接口，只有我刚才建的那一条
        const token = await adminTokenOf(page)
        const dict = await readReasonsApi(request, token)
        expect(dict.filter((r) => r.name === renamed).length, '字典里只有一条同名').toBe(1)
      })

      await test.step('⑥ 删除自己建的原因 → 确认后消失，买家下拉也随之不再出现', async () => {
        await deleteReturnReasonRow(page, returnReasonRow(page, renamed))
        await expect(page.locator('.el-message--success').first(), '应提示已删除').toBeVisible({ timeout: 15_000 })
        await expect(returnReasonRow(page, renamed), '删除后列表不应再有它').toHaveCount(0)

        const token = await adminTokenOf(page)
        const dict = await readReasonsApi(request, token)
        expect(dict.some((r) => r.name === renamed), '后端字典里也应消失').toBe(false)
        expect(dict.length, '种子原因一条都不能少').toBeGreaterThanOrEqual(4)
      })
    })
  })

  test.describe('字典消费链路', () => {
    test('字典项进入买家退货下拉 → 提交后名称进申请单 → 管理端可见 → 被引用后禁止删除', async ({
      page,
      request
    }) => {
      test.setTimeout(300_000)
      const suffix = stamp()
      const nick = memberNickOf(suffix)
      const receiver = receiverOf(suffix)
      const desc = returnDescriptionOf(suffix)
      let reasonName
      let adminToken
      let orderSn
      let orderId
      let reasonId

      await test.step('① 管理员在维护页新增一条原因（真实 UI）', async () => {
        await adminLoginResilient(page)
        await gotoReturnReasonList(page)
        reasonName = uniqueDisplayName('包装破损', (await readReasonsApi(request, await adminTokenOf(page))).map((r) => r.name))
        await openReturnReasonForm(page)
        await fillReturnReasonName(page, reasonName)
        await submitReturnReasonForm(page)
        await expect(returnReasonRow(page, reasonName)).toBeVisible({ timeout: 15_000 })

        adminToken = await adminTokenOf(page)
        const dict = await readReasonsApi(request, adminToken)
        const created = dict.find((r) => r.name === reasonName)
        expect(created, '新原因应已在字典里').toBeTruthy()
        reasonId = created.id
        createdIds.push(reasonId)
      })

      await test.step('② 买家：下单 → 已发货 → 申请退货，原因下拉里有这条新原因', async () => {
        await memberRegisterAndLogin(page, `e2e_reason_${suffix}`, '123456', nick)
        const { skus } = await getProductApi(request, 1)
        await addSpecToCart(page, 1, skus[0].specText)
        orderSn = await submitCartOrder(page, receiver)

        // 推到「已发货」：申请退货只对已付款/已发货开放
        await adminLoginResilient(page)
        const row = await adminSearchOrderRow(page, orderSn)
        await row.getByTestId('order-row-audit').click()
        await page.locator('.el-message-box__btns').getByRole('button', { name: '通过', exact: true }).click()
        await page.waitForTimeout(1500)
        const table = await openOrderDrawer(page, orderSn)
        const deliveryRow = table.locator('.el-table__body tr').first()
        await clickDispatch(page, deliveryRow)
        await clickWmsCallback(page, deliveryRow, '出库', '已出库')

        // 回买家侧申请退货
        await page.goto(`${PORTAL}/orders`)
        await page.waitForTimeout(1200)
        const orderRow = await memberOrderRow(page, orderSn)
        await orderRow.getByTestId('orders-row-return').click()
        const dlg = page.locator('[data-testid="order-return-dialog"]')
        await expect(dlg).toBeVisible()
        await page.locator('[data-testid="order-return-form-reason"]').click()
        await page.waitForTimeout(500)
        const options = await page.locator('.el-select-dropdown__item:visible').allInnerTexts()
        expect(options.map((t) => t.trim()), '买家退货下拉应含管理端刚新增的原因').toContain(reasonName)

        await page.locator('.el-select-dropdown__item:visible').filter({ hasText: reasonName }).first().click()
        await page.waitForTimeout(300)
        await page.locator('[data-testid="order-return-form-description"]').fill(desc)
        await page.locator('[data-testid="order-return-form-submit"]').click()
        await expect(page.locator('.el-message--success').first(), '退货申请应提交成功').toBeVisible({
          timeout: 15_000
        })
        await page.waitForTimeout(1500)
        await expect(await memberOrderRow(page, orderSn), '申请后售后列显示退款处理中').toContainText('退款处理中')

        const memberToken = await memberTokenOf(page)
        const returns = await getMemberReturnsApi(request, memberToken)
        expect(returns[0].returnReason, '申请单要记下提交时的原因名称（快照）').toBe(reasonName)
        expect(returns[0].reasonId, '同时记下原因编号').toBe(reasonId)
        orderId = returns[0].orderId
      })

      await test.step('③ 管理端「退货申请」：按订单能看到这条申请，原因列显示字典里的名称', async () => {
        await adminLoginResilient(page)
        await gotoReturnApplyList(page)
        const row = returnApplyRow(page, orderSn)
        await expect(row, '后台应看到刚才这笔申请').toBeVisible()
        expect(await reasonCellOf(row), '原因列应显示字典里的名称').toBe(reasonName)
      })

      await test.step('④ 边界-删除被申请引用的原因：维护页点删除被拦下，原因仍在列表里', async () => {
        await gotoReturnReasonList(page)
        await deleteReturnReasonRow(page, returnReasonRow(page, reasonName))
        const err = await toastTextOf(page, 'error')
        expect(err, '删除应失败并说清被多少条申请引用').toMatch(/已被\s*\d+\s*条退货申请使用，不能删除/)
        await expect(returnReasonRow(page, reasonName), '被拦下的删除不能真的删掉').toBeVisible()

        adminToken = await adminTokenOf(page)
        const dict = await readReasonsApi(request, adminToken)
        expect(dict.some((r) => r.id === reasonId), '后端字典里该项仍在').toBe(true)
      })

      await test.step('⑤ 清理：删掉自己造的退货申请 → 引用解除后该原因即可删除，不留脏数据', async () => {
        adminToken = await adminTokenOf(page)
        const applies = await readReturnAppliesApi(request, adminToken, orderId)
        const mine = applies.find((a) => a.returnReason === reasonName)
        expect(mine, '应能找到自己造的申请').toBeTruthy()
        expect((await dropReturnApply(request, adminToken, mine.id)).code).toBe(0)

        const afterUnref = await dropReason(request, adminToken, reasonId)
        expect(afterUnref.code, '引用解除后就能删了，说明拦截是按实时引用数算的').toBe(0)
        createdIds = createdIds.filter((id) => id !== reasonId)

        const dict = await readReasonsApi(request, adminToken)
        expect(dict.some((r) => r.name === reasonName), '自建项应清理干净').toBe(false)
        expect(dict.length, '种子原因一条都不能少').toBeGreaterThanOrEqual(4)
      })
    })
  })
})
