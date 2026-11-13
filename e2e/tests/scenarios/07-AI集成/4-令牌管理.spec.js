import { test, expect } from '../../support/test.js'
import { ADMIN, API, stamp } from '../../support/env.js'
import { tid } from '../../support/tid.js'
import { adminLoginResilient } from '../../support/admin-ui-config.js'
import { confirmDanger, expectToastSuccess } from '../../support/admin-ui.js'

/**
 * 07-AI集成 / 4-令牌管理
 * 图：docs/flows/02-业务场景/07-AI集成/4-令牌管理/1-密钥生命周期图.mmd
 *
 * 这条用例补的是「AI 访问密钥（令牌）管理页」的场景验收：13 个 admin 模块里唯一四件套全空的那个，
 * 也是 MCP/AI 那条线里**人能点开看的入口**。
 *
 * 覆盖的完整生命周期（标题里的圈号与需求单一一对应）：
 *   ① 创建（填名称 + 勾 2 个权限 + 设 7 天过期）→ 明文弹窗只出现这一次 → 能复制 → 关闭后列表只剩前缀
 *   ② 用这把密钥真调一次 GET /admin/orders → 200，并且「最后使用」被刷新
 *   ③ 同一把密钥 POST /admin/products（商品写没勾）→ 403
 *   ④ 页面上补勾「商品-写」→ 再调 ③ 的接口 → 200（并在页面上把造出来的商品删掉还原）
 *   ⑤ 页面点禁用 → 同一把 key 调 ② 的接口 → 401
 *   ⑥ 点重新启用 → 又 200
 *   ⑦ 页面点删除 + 确认弹窗 → 调接口 → 401，列表里不再出现
 *   ⑧ 两条红线：同一把 key 调 /admin/api-keys 与 /admin/admins → 403（AI 不能管密钥自身、不能管管理员账号）
 *
 * 三条口径，写在这里免得后来人改坏：
 *   1) 所有业务状态推进（建 / 勾权限 / 禁用 / 启用 / 删除）只走页面真实点击；
 *      接口只在两个地方出现：**(a) 拿这把 key 去真调用**（这就是被测行为本身）、
 *      **(b) 只读核对**（读 categories/types 取参照 id、读列表核对服务端事实）。
 *      清场动作（删除造出来的商品、兜底删残留密钥）也走页面按钮，只有 afterEach 兜底那一层用接口。
 *   2) 定位一律 data-testid。页面共 14 个静态 testid + 2 类动态 testid（行锚点 api-key-row-<id>、
 *      权限勾选框 api-key-form-scope-<scope>），本用例用到 17 个，见每个 test.step 里的注释。
 *   3) 自给自足：名称带时间戳，跑完把自己造的密钥、自己造的商品都删掉；
 *      连跑两轮不 reset 库都应该全绿（第二轮看到的列表只是多了/少了自己的东西，与断言无关）。
 *
 * ⚠️ ⑧ 排在 ⑦ 前面执行：红线 403 只能用**仍然有效**的密钥验证。密钥删掉之后调什么都只会得到 401
 *    （"无效 key"），那样根本证明不了 403 是"红线"拦下的。⑦ 删除后本用例再复验一次
 *    /admin/api-keys 仍然 403（黑名单judgment在 key 校验之前），把这条实现细节也钉住。
 */

/** 表格行：用「行内操作锚点 api-key-row-<id>」反查 <tr>，不靠中文文本过滤 */
const keyRow = (page, keyId) => page.locator('.el-table__body tr').filter({ has: page.getByTestId(`api-key-row-${keyId}`) })

/** 列表里 key 的前缀单元（形如 ak_1a2b3… ） */
const keyPrefixCell = (page, keyId) => keyRow(page, keyId).locator('.key-prefix')

const authOf = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

/** 只读：后台密钥列表（含 id / keyPrefix / scopes / status / lastUsedAt），用来核对服务端事实 */
const readKeysApi = async (request, token) => {
  const res = await request.get(`${API}/admin/api-keys`, authOf(token))
  expect(res.status(), '管理端读密钥列表只认 JWT，应 200').toBe(200)
  return (await res.json()).data || []
}

/** 只读：取一份分类 id 与类型 id（商品表这两列是 NOT NULL 外键），给 ③/④ 的商品写请求用 */
const readCatalogRefsApi = async (request, token) => {
  const cats = await (await request.get(`${API}/admin/categories`, authOf(token))).json()
  const types = await (await request.get(`${API}/admin/product-types`, authOf(token))).json()
  const categoryId = cats?.data?.[0]?.id
  const typeId = types?.data?.[0]?.id
  expect(categoryId, '后台应至少有一个商品分类（商品写请求需要真实分类 id）').toBeTruthy()
  expect(typeId, '后台应至少有一个商品类型').toBeTruthy()
  return { categoryId, typeId }
}

/** 商品写请求体（③ 与 ④ 用同一个体：③ 被权限拦在过滤器上，④ 放行后才真的落库） */
const productBodyOf = (name, { categoryId, typeId }) => ({
  product: { name, categoryId, typeId, publishStatus: 0 },
  gallery: []
})

test.describe('07-AI集成 › 4-令牌管理', () => {
  /** 每轮唯一的令牌名；afterEach 靠它兜底清残留 */
  let keyName = ''
  /** 创建弹窗里只出现一次的明文（只在内存里用，绝不打印） */
  let plainKey = ''
  /** ④ 造出来的商品（正常路径会被页面上删掉，失败时 afterEach 兜底） */
  let createdProductId = null

  test.beforeEach(async () => {
    test.setTimeout(300_000)
    keyName = `E2E令牌-${stamp()}`
    plainKey = ''
    createdProductId = null
  })

  /**
   * 兜底清场：正常路径 ①~⑦ 全部走页面按钮，本钩子只在**中途失败**时兜住，
   * 免得上一轮的残留让「连跑两轮」变脏。全程只发接口，不跳页面（否则会录进演示视频里）。
   */
  test.afterEach(async ({ page, request }) => {
    let token = null
    try {
      token = await page.evaluate(() => localStorage.getItem('qiqi_admin_token'))
    } catch {
      // 早期失败时页面可能还没到 admin 源，localStorage 取不到 → 没有可用的 JWT，只能放弃兜底
    }
    if (!token) {
      if (plainKey || createdProductId) console.warn('[清理] 没有 admin 令牌，跳过兜底清理，请手动核对残留')
      return
    }
    try {
      const keys = await readKeysApi(request, token)
      for (const k of keys.filter((x) => x.name === keyName)) {
        await request.delete(`${API}/admin/api-keys/${k.id}`, authOf(token))
      }
      if (createdProductId) {
        await request.delete(`${API}/admin/products/${createdProductId}`, authOf(token))
        createdProductId = null
      }
    } catch (e) {
      console.warn('[清理] 兜底清理失败：' + e.message)
    }
  })

  test('密钥从生到死：创建（明文只显示一次）→ 真调用 200 → 越权 403 → 授权 200 → 禁用 401 → 启用 200 → 删除 401 + 两条红线 403', async ({ page, request }) => {
    /** ④ 造的商品名（断言与清理都要它） */
    let productName = ''
    /** 令牌 id：来自只读接口，用来做 api-key-row-<id> 行锚点 */
    let keyId = null
    let token = null
    let refs = null
    /** 实测响应码 / 明文前缀 —— 跑完打一行，人在日志里就能核对「哪一步是什么码」（不回显明文） */
    const evidence = { codes: [], prefix: '', plainLen: 0 }

    // 复制按钮走 navigator.clipboard；headless 下要先给权限，否则只能走 execCommand 兜底
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: ADMIN })

    await test.step('① 建令牌：名称 + 勾「订单-读」「商品-读」+ 设 7 天过期 → 明文弹窗（只此一次）→ 能复制 → 关闭后只剩前缀', async () => {
      await adminLoginResilient(page)
      await page.goto(`${ADMIN}/api-keys`)
      // testid：api-key-list-table（列表）、api-key-create（新增按钮）
      await expect(tid(page, 'api-key-list-table'), '令牌管理页应渲染出列表').toBeVisible({ timeout: 20_000 })
      await tid(page, 'api-key-create').click()

      // testid：api-key-form-dialog / -form-name / -form-scopes / -form-scope-<scope> / -form-expires / -form-expires-7d / -form-submit
      const dlg = tid(page, 'api-key-form-dialog')
      await expect(dlg, '点「新增令牌」应弹出新增弹窗').toBeVisible()
      await dlg.getByTestId('api-key-form-name').fill(keyName)

      await expect(dlg.getByTestId('api-key-form-scopes'), '权限区应有 9 个勾选项').toBeVisible()
      const orderRead = dlg.getByTestId('api-key-form-scope-order:read')
      const catalogRead = dlg.getByTestId('api-key-form-scope-catalog:read')
      await orderRead.click()
      await catalogRead.click()
      await expect(orderRead.locator('input'), '「订单-读」应被勾上').toBeChecked()
      await expect(catalogRead.locator('input'), '「商品-读」应被勾上').toBeChecked()
      // 故意不勾「商品-写」——③ 的 403 就是靠这个缺口
      await expect(dlg.getByTestId('api-key-form-scope-catalog:write').locator('input'), '「商品-写」这一步必须保持不勾').not.toBeChecked()

      await dlg.getByTestId('api-key-form-expires-7d').click()
      const expiresText = await dlg.getByTestId('api-key-form-expires').locator('input').inputValue()
      expect(expiresText, '7 天快捷按钮应把过期时间填成 yyyy-MM-dd HH:mm:ss').toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
      const days = (Date.parse(expiresText.replace(' ', 'T')) - Date.now()) / 86_400_000
      expect(days, '过期时间应落在 7 天后（允许 ±0.2 天误差）').toBeGreaterThan(6.8)
      expect(days, '过期时间应落在 7 天后（允许 ±0.2 天误差）').toBeLessThan(7.2)

      await dlg.getByTestId('api-key-form-submit').click()

      // 明文弹窗：testid api-key-reveal-dialog / -value / -copy / -close
      const reveal = tid(page, 'api-key-reveal-dialog')
      await expect(reveal, '创建成功后应弹出「令牌创建成功」弹窗').toBeVisible({ timeout: 20_000 })
      await expect(reveal).toContainText('请立即复制保存')
      plainKey = (await reveal.getByTestId('api-key-reveal-value').innerText()).trim()
      expect(plainKey, '明文密钥形如 ak_ + 64 位十六进制').toMatch(/^ak_[0-9a-f]{64}$/)

      await reveal.getByTestId('api-key-reveal-copy').click()
      await expect(page.locator('.el-message--success').first(), '点「复制」应提示已复制').toContainText('已复制')
      const copied = await page.evaluate(() => navigator.clipboard.readText())
      expect(copied.trim(), '剪贴板里应就是这把密钥的明文').toBe(plainKey)

      await reveal.getByTestId('api-key-reveal-close').click()
      await expect(reveal, '点「我已保存，关闭」后弹窗应消失').toBeHidden({ timeout: 10_000 })

      // 关闭后：页面上任何位置都不该再有明文，列表里只剩前缀
      token = await page.evaluate(() => localStorage.getItem('qiqi_admin_token'))
      const keys = await readKeysApi(request, token)
      const mine = keys.find((k) => k.name === keyName)
      expect(mine, `列表接口里应能找到「${keyName}」`).toBeTruthy()
      keyId = mine.id
      expect(mine.scopes.slice().sort(), '落库权限应正好是勾的两个').toEqual(['catalog:read', 'order:read'])
      expect(mine.lastUsedAt, '还没调用过，最后使用时间应为空').toBeFalsy()

      await expect(keyRow(page, keyId), '列表里应出现新建的这一行').toBeVisible()
      const prefixShown = (await keyPrefixCell(page, keyId).innerText()).trim()
      expect(prefixShown, '列表里 key 只能以前缀 + 省略号展示').toMatch(/^ak_[0-9a-z]+…$/)
      expect(prefixShown.slice(0, -1), '展示的前缀应等于服务端返回的 keyPrefix').toBe(mine.keyPrefix)
      expect(plainKey.startsWith(prefixShown.slice(0, -1)), '前缀应是明文的前几位').toBe(true)
      expect(prefixShown.length, '前缀必须远短于完整明文（完整 67 字符）').toBeLessThan(20)
      evidence.prefix = prefixShown
      evidence.plainLen = plainKey.length

      // 反证：明文此刻已经不在页面的任何可见文本里（弹窗 destroy-on-close，不会留在 DOM 里）
      expect(await page.locator('body').innerText(), '关闭弹窗后页面上不应还能看到明文').not.toContain(plainKey)
      await expect(tid(page, 'api-key-reveal-value'), '明文节点应随弹窗一起销毁').toHaveCount(0)
    })

    await test.step('② 拿这把密钥真调一次 GET /admin/orders → 200，并且「最后使用」被记上', async () => {
      // 这里刻意不带任何 JWT / cookie，只有 X-Api-Key —— 等价于 `curl -H 'X-Api-Key: …'`
      const res = await request.get(`${API}/admin/orders`, { headers: { 'X-Api-Key': plainKey } })
      const body = await res.json()
      expect(res.status(), `带有效 X-Api-Key 调订单列表应 200（实际 body: ${JSON.stringify(body)}）`).toBe(200)
      expect(body.code, '业务码应为 0').toBe(0)
      expect(body.data, '订单列表应返回分页对象').toHaveProperty('records')
      evidence.codes.push(`②有效key调订单列表=${res.status()}`)

      // 图里的「每次调用记一次最后使用时间」：服务端应已落痕（同一 key 60 秒内只写一次，这里正是首次）
      const mine = (await readKeysApi(request, token)).find((k) => k.id === keyId)
      expect(mine.lastUsedAt, '调用过一次之后最后使用时间不应再为空').toBeTruthy()
      await page.reload()
      await expect(keyRow(page, keyId), '刷新后这一行仍在').toBeVisible({ timeout: 20_000 })
      const lastUsedCell = keyRow(page, keyId).locator('td').nth(5)
      await expect(lastUsedCell, '页面上「最后使用」应显示成时间，而不是「从未使用」').not.toHaveText('从未使用')
    })

    await test.step('③ 越权：同一把密钥 POST /admin/products（商品写没勾）→ 403', async () => {
      refs = await readCatalogRefsApi(request, token)
      const res = await request.post(`${API}/admin/products`, {
        headers: { 'X-Api-Key': plainKey },
        data: productBodyOf(`不该被创建的商品-${stamp()}`, refs)
      })
      const body = await res.json()
      expect(res.status(), '只勾了商品-读却去写商品，必须 403').toBe(403)
      expect(body.message, '403 原因应点名缺哪个权限').toContain('catalog:write')
      evidence.codes.push(`③未授权写商品=${res.status()}`)
    })

    await test.step('④ 改权限：页面上补勾「商品-写」→ 同一个商品写接口现在成功 → 用页面按钮把商品删掉还原', async () => {
      // testid：api-key-row-edit（行内编辑）、api-key-form-scope-catalog:write、api-key-form-submit
      await keyRow(page, keyId).getByTestId('api-key-row-edit').click()
      const dlg = tid(page, 'api-key-form-dialog')
      await expect(dlg, '编辑弹窗应打开').toBeVisible()
      await expect(dlg.getByTestId('api-key-form-scope-order:read').locator('input'), '编辑应回显原有权限').toBeChecked()
      await dlg.getByTestId('api-key-form-scope-catalog:write').click()
      await dlg.getByTestId('api-key-form-submit').click()
      await expectToastSuccess(page, '保存成功')

      const mine = (await readKeysApi(request, token)).find((k) => k.id === keyId)
      expect(mine.scopes.slice().sort(), '服务端权限应变成三条').toEqual(['catalog:read', 'catalog:write', 'order:read'])

      productName = `E2E令牌商品-${stamp()}`
      const res = await request.post(`${API}/admin/products`, {
        headers: { 'X-Api-Key': plainKey },
        data: productBodyOf(productName, refs)
      })
      const body = await res.json()
      expect(res.status(), `补上商品-写之后同一个请求应放行（实际 body: ${JSON.stringify(body)}）`).toBe(200)
      expect(body.code, '业务码应为 0').toBe(0)
      expect(body.data?.name, '落库的商品名应是本次请求带来的').toBe(productName)
      createdProductId = body.data.id
      expect(createdProductId, '创建成功应带回自增 id').toBeTruthy()
      evidence.codes.push(`④授权后写商品=${res.status()}`)

      // 还原数据：商品列表页 → 行内删除 → 高危确认弹窗（testid 全在 DangerConfirmDialog 里）
      await page.goto(`${ADMIN}/product`)
      const productRow = tid(page, `product-row-${createdProductId}`)
      await expect(productRow, '商品列表里应能看到这把 key 造出来的商品').toBeVisible({ timeout: 20_000 })
      await productRow.getByTestId('product-row-delete').click()
      await confirmDanger(page, { willDo: '将删除商品' })
      await expect(page.locator('.el-message--success').first()).toContainText('已删除')
      await expect(tid(page, `product-row-${createdProductId}`), '清理后商品行应消失').toHaveCount(0)
      createdProductId = null

      // 回令牌页继续禁用/启用/删除
      await page.goto(`${ADMIN}/api-keys`)
      await expect(keyRow(page, keyId), '回到令牌页应还能看到这一行').toBeVisible({ timeout: 20_000 })
    })

    await test.step('⑤ 禁用：点行内状态开关 → 同一把 key 调 ② 的接口 → 401', async () => {
      // testid：api-key-row-status（行内启用/禁用开关）
      const sw = keyRow(page, keyId).getByTestId('api-key-row-status')
      await expect(sw, '禁用前开关应是「启用」态').toHaveClass(/is-checked/)
      await sw.click()
      await expect(page.locator('.el-message--success').first(), '应提示已禁用').toContainText('已禁用')
      await expect(sw, '禁用后开关应变为「禁用」态').not.toHaveClass(/is-checked/)

      const res = await request.get(`${API}/admin/orders`, { headers: { 'X-Api-Key': plainKey } })
      const body = await res.json()
      expect(res.status(), '被禁用的密钥应立即失效（401）').toBe(401)
      expect(body.message, '401 原因应说明密钥无效或已过期').toContain('API Key 无效或已过期')
      evidence.codes.push(`⑤禁用后调用=${res.status()}`)
      expect((await readKeysApi(request, token)).find((k) => k.id === keyId).status, '服务端状态应为 0（禁用）').toBe(0)
    })

    await test.step('⑥ 重新启用：同一把 key 立刻恢复 → 又 200', async () => {
      const sw = keyRow(page, keyId).getByTestId('api-key-row-status')
      await sw.click()
      await expect(page.locator('.el-message--success').first(), '应提示已启用').toContainText('已启用')
      await expect(sw, '重新启用后开关应回到「启用」态').toHaveClass(/is-checked/)

      const res = await request.get(`${API}/admin/orders`, { headers: { 'X-Api-Key': plainKey } })
      expect(res.status(), '重新启用后同一个请求应恢复 200').toBe(200)
      evidence.codes.push(`⑥重新启用后调用=${res.status()}`)
      expect((await readKeysApi(request, token)).find((k) => k.id === keyId).status, '服务端状态应为 1（启用）').toBe(1)
    })

    await test.step('⑧ 两条红线（必须用仍然有效的密钥来验）：/admin/api-keys 与 /admin/admins 一律 403', async () => {
      const resKeys = await request.get(`${API}/admin/api-keys`, { headers: { 'X-Api-Key': plainKey } })
      const bodyKeys = await resKeys.json()
      expect(resKeys.status(), 'AI 密钥不能管理密钥自身').toBe(403)
      expect(bodyKeys.message, '403 原因应点名这是红线').toContain('禁止访问')

      const resAdmins = await request.get(`${API}/admin/admins`, { headers: { 'X-Api-Key': plainKey } })
      const bodyAdmins = await resAdmins.json()
      expect(resAdmins.status(), 'AI 密钥不能管理管理员账号').toBe(403)
      evidence.codes.push(`⑧读密钥管理=${resKeys.status()}`, `⑧读管理员=${resAdmins.status()}`)
      expect(bodyAdmins.message, '403 原因应点名这是红线').toContain('管理员账号')
    })

    await test.step('⑦ 删除：行内删除 + 确认弹窗 → 调接口 401 → 列表里不再出现', async () => {
      // testid：api-key-row-delete（行内删除）→ ElMessageBox 的按钮没有模板锚点，按文案点（见 tid.js 未覆盖清单第 1 条）
      await keyRow(page, keyId).getByTestId('api-key-row-delete').click()
      const box = page.locator('.el-message-box')
      await expect(box, '删除应弹确认框').toBeVisible()
      await expect(box, '确认框应点名这把令牌').toContainText(keyName)
      await box.locator('.el-message-box__btns').getByRole('button', { name: '删除' }).click()
      await expect(box, '确认后确认框应消失').toBeHidden({ timeout: 10_000 })
      await expect(page.locator('.el-message--success').first(), '应提示已删除').toContainText('已删除')

      const res = await request.get(`${API}/admin/orders`, { headers: { 'X-Api-Key': plainKey } })
      const body = await res.json()
      expect(res.status(), '删掉之后这把 key 立即失效（401）').toBe(401)
      expect(body.message).toContain('API Key 无效或已过期')
      evidence.codes.push(`⑦删除后调用=${res.status()}`)

      expect((await readKeysApi(request, token)).some((k) => k.id === keyId), '服务端不应再有这条记录').toBe(false)
      await expect(keyRow(page, keyId), '列表里不应再出现这一行').toHaveCount(0)
      await expect(page.locator('.el-table__body tr').filter({ hasText: keyName }), '按名称也搜不到').toHaveCount(0)

      // 顺带钉住实现细节：红线黑名单在 key 校验**之前**判，所以拿着已删的 key 也还是 403（不是 401）
      const deadRedline = await request.get(`${API}/admin/api-keys`, { headers: { 'X-Api-Key': plainKey } })
      expect(deadRedline.status(), '红线先于密钥有效性判定，仍应是 403').toBe(403)
      evidence.codes.push(`⑦删除后读密钥管理=${deadRedline.status()}`)

      // 一行证据，给人看：实测响应码 + 列表里展示的前缀（明文长度只报位数，不回显内容）
      console.log(`[令牌验收证据] ${evidence.codes.join(' | ')} | 列表仅显示前缀=${evidence.prefix} | 明文长度=${evidence.plainLen}`)
    })
  })
})
