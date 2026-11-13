import { test, expect } from '../../support/test.js'
import { expectToastSuccess } from '../../support/admin-ui.js'
import {
  gotoOrderSetting,
  settingDaysInput,
  settingMinutesInput,
  readSettingFields,
  fillSettingField,
  typeSettingField,
  submitOrderSetting,
  settingFormError,
  readOrderSettingApi,
  adminLoginResilient
} from '../../support/admin-ui-config.js'
import { adminTokenOf } from '../../support/assert.js'

/**
 * 04-商家-基础配置 / 2-订单设置
 * 图：docs/flows/02-业务场景/04-商家-基础配置/2-订单设置/1-业务流程.mmd
 *
 * ⚠️ 这是一条**全局单行配置**：`order_setting` 表只有一行，保存是覆盖。
 * 所以本用例开头记下基线值（baseline），结尾无论成败都恢复，避免影响其它用例/其它同学。
 *
 * 断言口径来自代码实际行为（已实测确认，非推测）：
 *   - 页面回显来自 GET /admin/order-setting，没有记录时返回空对象、表单给默认 7 / 30；
 *   - OrderSettingController.update 是纯 upsert，**服务端零校验**（不校验范围、不校验空）；
 *   - 数字框 el-input-number 带 min=1、precision=0、required：
 *       0 与负数 → 失焦后自动纠正为下限 1，不弹任何提示；
 *       小数 → 失焦后取整；
 *       字母等非数字 → 浏览器 number 输入框直接吞掉字符，框变空；
 *       空着保存 → 前端 required 拦下，不发请求、配置不变。
 */
test.describe('04-商家-基础配置 › 2-订单设置', () => {
  // 模块级基线：只在第一次进页面时记一次，后面永远恢复到它，避免用例间互相污染
  let baseline = null

  test.beforeEach(async ({ page }) => {
    // 并发环境下登录可能撞上「验证码窗口」，这里给钩子留足时间（不影响断言强度）
    test.setTimeout(240_000)
    await adminLoginResilient(page)
    await gotoOrderSetting(page)
    const fields = await readSettingFields(page)
    if (baseline === null) baseline = { ...fields }
  })

  test.afterEach(async ({ page, request }) => {
    // 恢复基线：全局单行配置，任何人都不能被我们改脏
    if (!baseline) return
    await gotoOrderSetting(page)
    await fillSettingField(page, 'days', baseline.days)
    await fillSettingField(page, 'minutes', baseline.minutes)
    await submitOrderSetting(page)
    const token = await adminTokenOf(page)
    const now = await readOrderSettingApi(request, token)
    expect(now.autoConfirmDays, '用例结束必须把自动收货天数恢复成基线').toBe(baseline.days)
    expect(now.autoCloseMinutes, '用例结束必须把自动关单分钟数恢复成基线').toBe(baseline.minutes)
  })

  test('回显当前配置 → 改值保存并刷新校验 → 0/负数/小数/非数字/留空四种边界', async ({ page, request }) => {
    let token

    await test.step('① 打开页面：两个配置项都回显了当前值，且与后端读到的完全一致', async () => {
      const shown = await readSettingFields(page)
      token = await adminTokenOf(page)
      const server = await readOrderSettingApi(request, token)

      expect(shown.days, '自动收货天数应有值').not.toBeNull()
      expect(shown.minutes, '自动关单分钟数应有值').not.toBeNull()
      expect(shown.days, '页面回显的天数应等于后端存的天数').toBe(server.autoConfirmDays)
      expect(shown.minutes, '页面回显的分钟数应等于后端存的分钟数').toBe(server.autoCloseMinutes)
      // 两个框都是「天/分钟」这种正整数配置，下限 1
      expect(shown.days).toBeGreaterThanOrEqual(1)
      expect(shown.minutes).toBeGreaterThanOrEqual(1)
      await expect(page.getByText('发货后 N 天自动确认收货')).toBeVisible()
      await expect(page.getByText('下单后 N 分钟未付款自动关闭订单')).toBeVisible()
    })

    await test.step('② 改成明显不同的值（15 / 45）保存 → 提示成功 → 刷新后仍是新值 → 后端也变了', async () => {
      await fillSettingField(page, 'days', 15)
      await fillSettingField(page, 'minutes', 45)
      await submitOrderSetting(page)
      await expectToastSuccess(page, '保存成功')

      await page.reload()
      await page.waitForTimeout(1500)
      const afterReload = await readSettingFields(page)
      expect(afterReload.days, '刷新后应回显新填的天数').toBe(15)
      expect(afterReload.minutes, '刷新后应回显新填的分钟数').toBe(45)

      // 只读接口复核：真的落库了，不是只有前端记得
      token = await adminTokenOf(page)
      const server = await readOrderSettingApi(request, token)
      expect(server.autoConfirmDays).toBe(15)
      expect(server.autoCloseMinutes).toBe(45)
      expect(server.id, '全局只有一份配置，保存是覆盖同一行').toBe(1)
    })

    await test.step('③ 边界-填 0：失焦后被组件自动改成下限 1，不报错', async () => {
      await fillSettingField(page, 'days', 0)
      expect(await settingDaysInput(page).inputValue()).toBe('1')
      await expect(settingFormError(page), '下限纠正不是错误，不应有校验提示').toHaveCount(0)
    })

    await test.step('④ 边界-填负数：同样被纠成 1，不报错', async () => {
      await fillSettingField(page, 'days', -5)
      expect(await settingDaysInput(page).inputValue()).toBe('1')
      await expect(settingFormError(page)).toHaveCount(0)
    })

    await test.step('⑤ 边界-填小数：按整数配置取整（3.7 → 4）', async () => {
      await fillSettingField(page, 'days', '3.7')
      expect(await settingDaysInput(page).inputValue()).toBe('4')
    })

    await test.step('⑥ 边界-填字母：数字框直接吞掉字符，框变空，失焦后抱怨必填', async () => {
      await typeSettingField(page, 'days', 'abc')
      expect(await settingDaysInput(page).inputValue(), '非数字输入进不去').toBe('')
      await settingDaysInput(page).press('Tab')
      await page.waitForTimeout(500)
      await expect(settingFormError(page), '空值应提示必填').toContainText('请输入自动收货天数')
    })

    await test.step('⑦ 边界-空着点保存：前端拦下，不发请求，后端配置一字未改', async () => {
      await submitOrderSetting(page)
      await expect(settingFormError(page), '空值仍然拦住').toContainText('请输入自动收货天数')
      // 后端值还是第②步存的 15 / 45，说明这一步根本没提交
      token = await adminTokenOf(page)
      const server = await readOrderSettingApi(request, token)
      expect(server.autoConfirmDays, '被拦下的保存不能改到后端').toBe(15)
      expect(server.autoCloseMinutes).toBe(45)
    })

    await test.step('⑧ 收尾：填回基线值 → 保存成功 → 页面与后端都回到基线', async () => {
      await fillSettingField(page, 'days', baseline.days)
      await fillSettingField(page, 'minutes', baseline.minutes)
      await submitOrderSetting(page)
      await expectToastSuccess(page, '保存成功')
      await page.reload()
      await page.waitForTimeout(1500)
      const shown = await readSettingFields(page)
      expect(shown.days).toBe(baseline.days)
      expect(shown.minutes).toBe(baseline.minutes)
      token = await adminTokenOf(page)
      const server = await readOrderSettingApi(request, token)
      expect(server.autoConfirmDays).toBe(baseline.days)
      expect(server.autoCloseMinutes).toBe(baseline.minutes)
    })

    // 顺带守住一个容易踩的点：分钟框也支持同样的纠正，别只测了天数
    await test.step('⑨ 分钟框同样受下限约束（0 → 1）', async () => {
      await fillSettingField(page, 'minutes', 0)
      expect(await settingMinutesInput(page).inputValue()).toBe('1')
    })
  })
})
