import { test, expect } from '@playwright/test'
import { stamp } from '../../support/env.js'
import { memberRegisterAndLogin } from '../../support/ui.js'
import {
  openAddresses,
  addressRowOf,
  addressDialog,
  addAddress,
  editAddress,
  deleteAddress,
  isDefaultAddress
} from '../../support/portal-ui.js'
import { getMemberAddressesApi, memberTokenOf } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { addressBook } from '../../support/fixtures.js'

/**
 * 买家侧 07 收货地址（docs/flows/02-业务场景/01-买家侧/7-收货地址）
 *
 * 覆盖「我的 → 收货地址」的全部已实现操作：
 *   新增 / 编辑 / 设为默认 / 删除 / 必填校验 / 超长文本截断 / 删除后的列表状态
 * 全程真实 UI 点击；接口只用于只读断言（地址簿落库情况）。
 */
// 地址数据取自 fixtures 的真实地址簿（姓名/地址都是自然中文，演示视频里不出现 E2E 字样）
const ADDR_A = addressBook[0]
const ADDR_B = addressBook[1]

/** 每个用例用独立账号，互不干扰 */
const newMember = async (page, tag) => {
  const username = `e2e_addr_${tag}_${stamp()}`
  await memberRegisterAndLogin(page, username)
  return username
}

test.describe('买家侧 07 收货地址', () => {
  test('新增 → 编辑 → 设为默认 → 删除：地址簿全流程', async ({ page, request }) => {
    await newMember(page, 'flow')
    await openAddresses(page)

    // ---------- 新增两条 ----------
    await addAddress(page, ADDR_A)
    await addAddress(page, ADDR_B)
    expect(await isDefaultAddress(page, ADDR_A.name), '新增时不勾默认，列表不应标默认').toBe(false)
    expect(await isDefaultAddress(page, ADDR_B.name)).toBe(false)

    // 只读复核：两条都落库了，且此时没有默认地址
    const token = await memberTokenOf(page)
    let saved = await getMemberAddressesApi(request, token)
    expect(saved.length, '应有 2 条地址').toBe(2)
    expect(saved.filter((a) => a.defaultStatus === 1).length, '此时不应有默认地址').toBe(0)

    // ---------- 编辑：改写收件人与详细地址 ----------
    const renamed = { name: '王小明', detail: '科技园南路 2 号 302' }
    await editAddress(page, ADDR_A.name, renamed)
    await expect(addressRowOf(page, renamed.name), '编辑后列表应显示新收件人').toBeVisible()
    // 收件人列逐格比对（不能整表 not.toContainText：'张三改' 里也含 '张三'）
    const nameCells = await tid(page, 'address-list-table').locator('.el-table__body tr td:first-child').allInnerTexts()
    expect(nameCells, '旧收件人不该再出现在收件人列').not.toContain(ADDR_A.name)
    await expect(addressRowOf(page, renamed.name)).toContainText(renamed.detail)

    // ---------- 设为默认：默认标记落到该条，且排到列表第一位 ----------
    await editAddress(page, renamed.name, { detail: renamed.detail, isDefault: true })
    await expect.poll(() => isDefaultAddress(page, renamed.name), {
      message: '设为默认后该行应标「默认」',
      timeout: 10_000
    }).toBe(true)
    const firstRow = tid(page, 'address-list-table').locator('.el-table__body tr').first()
    await expect(firstRow, '默认地址应排在列表第一位').toContainText(renamed.name)

    saved = await getMemberAddressesApi(request, token)
    expect(saved.find((a) => a.name === renamed.name).defaultStatus, '服务端应记为默认(1)').toBe(1)

    // ---------- 删除：只删选中的那条，其余不受影响 ----------
    await deleteAddress(page, ADDR_B.name)
    await expect(tid(page, 'address-list-table'), '被删的地址不应再出现').not.toContainText(ADDR_B.name)
    await expect(addressRowOf(page, renamed.name), '未删除的地址应仍在').toBeVisible()

    saved = await getMemberAddressesApi(request, token)
    expect(saved.length, '删除后服务端只剩 1 条').toBe(1)
    expect(saved[0].name).toBe(renamed.name)
  })

  test('必填校验：六项必填都不填，就地报错且不提交', async ({ page }) => {
    await newMember(page, 'required')
    await openAddresses(page)

    await tid(page, 'addresses-create').click()
    const dialog = addressDialog(page)
    await expect(dialog).toBeVisible()
    await tid(page, 'address-form-submit').click()
    await page.waitForTimeout(500)

    // 六个必填项各自给出提示
    for (const msg of ['请输入收件人', '请输入电话', '请输入省', '请输入市', '请输入区/县', '请输入详细地址']) {
      await expect(tid(page, 'address-form-dialog').locator('.el-form-item__error').filter({ hasText: msg }), `应提示「${msg}」`).toBeVisible()
    }
    // 弹窗不关闭、列表仍是空的
    await expect(dialog, '校验不通过时弹窗不应关闭').toBeVisible()
    await expect(tid(page, 'address-list-table').locator('.el-table__body tr'), '校验不通过不应产生数据').toHaveCount(0)

    // 补一个必填项后再提交，其它错误提示仍在（逐项校验，不是全清）
    await tid(page, 'address-form-name').fill('王五')
    await tid(page, 'address-form-submit').click()
    await expect(tid(page, 'address-form-dialog').locator('.el-form-item__error').filter({ hasText: '请输入电话' })).toBeVisible()

    await tid(page, 'address-form-cancel').click()
    await expect(dialog).toBeHidden()
  })

  test('超长文本：收件人 50、详细地址 200 字上限生效', async ({ page, request }) => {
    await newMember(page, 'maxlen')
    await openAddresses(page)

    const longName = '长'.repeat(80)
    const longDetail = '详'.repeat(300)
    await tid(page, 'addresses-create').click()
    const dialog = addressDialog(page)
    await expect(dialog).toBeVisible()

    const nameInput = tid(page, 'address-form-name')
    const detailInput = tid(page, 'address-form-detail')
    await nameInput.fill(longName)
    await detailInput.fill(longDetail)

    expect((await nameInput.inputValue()).length, '收件人输入框应截断到 50 字').toBe(50)
    expect((await detailInput.inputValue()).length, '详细地址应截断到 200 字').toBe(200)

    await tid(page, 'address-form-phone').fill('13700003333')
    await tid(page, 'address-form-province').fill('北京市')
    await tid(page, 'address-form-city').fill('北京市')
    await tid(page, 'address-form-region').fill('朝阳区')
    await tid(page, 'address-form-submit').click()
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(800)

    // 落库也是截断后的长度（不是前端骗人）
    const token = await memberTokenOf(page)
    const saved = await getMemberAddressesApi(request, token)
    expect(saved.length).toBe(1)
    expect(saved[0].name.length, '库里收件人应为 50 字').toBe(50)
    expect(saved[0].detail.length, '库里详细地址应为 200 字').toBe(200)
    await expect(addressRowOf(page, saved[0].name.slice(0, 10))).toBeVisible()
  })

  test('设默认时取消原默认：一个账号只留一条默认地址', async ({ page, request }) => {
    await newMember(page, 'default')
    await openAddresses(page)

    await addAddress(page, { ...ADDR_A, isDefault: true })
    await expect.poll(() => isDefaultAddress(page, ADDR_A.name), { timeout: 10_000 }).toBe(true)

    // 第二条也设默认 → 第一条的默认必须被取消
    await addAddress(page, { ...ADDR_B, isDefault: true })
    await expect.poll(() => isDefaultAddress(page, ADDR_B.name), {
      message: 'B 应成为默认', timeout: 10_000
    }).toBe(true)
    await expect.poll(() => isDefaultAddress(page, ADDR_A.name), {
      message: 'A 的原默认应被取消', timeout: 10_000
    }).toBe(false)

    const token = await memberTokenOf(page)
    const saved = await getMemberAddressesApi(request, token)
    expect(saved.filter((a) => a.defaultStatus === 1).length, '库里只应有 1 条默认地址').toBe(1)
    expect(saved.find((a) => a.defaultStatus === 1).name).toBe(ADDR_B.name)

    // 再把 A 改回默认，验证「编辑」路径同样会取消 B 的默认
    await editAddress(page, ADDR_A.name, { detail: ADDR_A.detail, isDefault: true })
    await expect.poll(() => isDefaultAddress(page, ADDR_A.name), { timeout: 10_000 }).toBe(true)
    await expect.poll(() => isDefaultAddress(page, ADDR_B.name), {
      message: '编辑时设默认也要取消 B 的默认', timeout: 10_000
    }).toBe(false)
  })

  test('删除默认地址：剩下的最早一条自动补位；删光则不报错', async ({ page, request }) => {
    await newMember(page, 'deldefault')
    await openAddresses(page)
    const token = await memberTokenOf(page)

    // 三条地址，把中间那条设为默认
    await addAddress(page, ADDR_A)
    await addAddress(page, { ...ADDR_B, isDefault: true })
    await addAddress(page, addressBook[2])
    await expect.poll(() => isDefaultAddress(page, ADDR_B.name), { timeout: 10_000 }).toBe(true)

    // 删掉默认地址 → 剩下 id 最小的那条自动成为默认
    await deleteAddress(page, ADDR_B.name)
    await expect.poll(() => isDefaultAddress(page, ADDR_A.name), {
      message: '删掉默认地址后，最早创建的那条应自动成为默认', timeout: 10_000
    }).toBe(true)
    let saved = await getMemberAddressesApi(request, token)
    expect(saved.length).toBe(2)
    expect(saved.filter((a) => a.defaultStatus === 1).length, '补位后仍只有 1 条默认').toBe(1)
    expect(saved.find((a) => a.defaultStatus === 1).name).toBe(ADDR_A.name)

    // 把剩下的全删光 → 无默认且不报错
    await deleteAddress(page, ADDR_A.name)
    await deleteAddress(page, addressBook[2].name)
    await expect(tid(page, 'address-list-table').locator('.el-table__body tr'), '地址应全部删除').toHaveCount(0)
    saved = await getMemberAddressesApi(request, token)
    expect(saved.length, '服务端应为空').toBe(0)
  })
})
