import { test, expect } from '../../support/test.js'
import { API, ADMIN } from '../../support/env.js'
import { dbOfApi, queryDb, execSql } from '../../support/db.js'
import { tid } from '../../support/tid.js'
import { adminLoginHardened, expectToastSuccess } from '../../support/admin-ui.js'
import { adminTokenOf } from '../../support/assert.js'

/**
 * 库存 / 库存对账（页面 /inventory-reconcile）
 *
 * 这个场景只证明两件事，缺一条这个页面就没有存在意义：
 *
 *  ① **对账只报结论，不写证据**（核心口径）。对账接口是只读的：它把「权威值 SUM(桶)」
 *     与「展示缓存 sku.stock」摆在一起算偏差，但**不许顺手把它修好**——一旦在只读接口里
 *     改数据，坏的是两样东西：证据没了（偏差为什么产生、产生多久了，从此查不到），
 *     而且「谁改的」也查不到（对账不是记账路径，它不写台账）。
 *     所以用例 1 反复调它、反复点页面，断言：偏差一动不动、库里三个数一个都没变、台账行数不变。
 *
 *  ② **缓存滞后是可以被追平的**（偏差归零）。偏差不是错误，是"缓存落后于权威值"；
 *     后台把库存设回权威值 → 扣减/重置路径标脏 → 250ms 的回写任务用 SUM(桶) 重写 sku.stock
 *     → 偏差回到 0，而**台账一行没多**（缓存回写不是动账）。
 *
 * ── 三个数不是一回事（本页最容易看错的地方，也是断言对象）───────────────
 *   · `bucketSum = SUM(sku_bucket.stock)`  —— 权威值（桶是事实源）
 *   · `skuStock  = sku.stock`              —— 展示缓存，本该等于权威值；`drift = 桶 − 缓存`
 *   · `ledgerNet = SUM(stock_ledger.delta)` —— **全生命周期进出和，不是余额**
 *   前两个应当相等；第三个**本就不该与它们对平**。所以用例里只断言
 *   "ledgerNet 等于库里的 SUM(delta)"、"页面明写它不是余额"，从不断言 ledgerNet == bucketSum。
 *
 * ── 依赖 ───────────────────────────────────────────────────────────────────
 *   分桶模式（qiqi.stock.bucket-enabled=true）。SKU 由库里的桶形态现挑：8 个桶、总量 > 0
 *   （挑法见 pickBucketedSku：不写死 sku id，否则换个种子库就红）。
 */
test.describe('库存 › 库存对账', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[库存 / 库存对账] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
  })

  /* ---------------------------------------------------------------- 库上的事实（只读） */

  const one = (sql, what) => {
    const v = queryDb(sql)
    if (v === '') throw new Error(`查不到 ${what}（SQL 返回空）：${sql}`)
    return v
  }

  /** 挑一个「分桶模式正常形态」的 SKU：恰好 8 个桶、总量 ≥ 1000（便于造 500 的偏差） */
  const pickBucketedSku = () => {
    const line = one(
      'select concat(sku_id, \',\', sum(stock), \',\', count(*)) from sku_bucket '
      + 'group by sku_id having count(*) = 8 and sum(stock) >= 1000 order by sku_id limit 1',
      '一个「8 个桶且总量 ≥ 1000」的 SKU'
    )
    const [skuId, sum, buckets] = line.split(',').map(Number)
    const productId = Number(one(`select product_id from sku where id = ${skuId}`, `SKU ${skuId} 的 product_id`))
    return { skuId, productId, bucketSum: sum, buckets }
  }

  const bucketSumOf = (skuId) => Number(one(`select coalesce(sum(stock), 0) from sku_bucket where sku_id = ${skuId}`, 'SUM(桶)'))
  const stockOf = (skuId) => Number(one(`select stock from sku where id = ${skuId}`, 'sku.stock'))
  const ledgerNetOf = (skuId) => Number(one(`select coalesce(sum(delta), 0) from stock_ledger where sku_id = ${skuId}`, 'SUM(delta)'))
  const ledgerRowsOf = (skuId) => Number(one(`select count(*) from stock_ledger where sku_id = ${skuId}`, '台账行数'))

  /** 造偏差：把展示缓存写成「桶总量 + delta」（delta > 0 = 缓存多记 ⇒ drift 为负） */
  const plantDrift = (skuId, delta = 500) => {
    const target = bucketSumOf(skuId) + delta
    execSql(`update sku set stock = ${target} where id = ${skuId}`)
    const got = stockOf(skuId)
    if (got !== target) throw new Error(`造偏差失败：写 ${target}、读回 ${got}`)
    return target
  }

  /**
   * 把展示缓存按权威值写回（**不经过任何业务流程**，只用于 cleanup）：
   * 用例中途失败时不能让库里留着一份假缓存，否则后面的用例一开跑就是"有偏差"。
   */
  const restoreCache = (skuId) => {
    execSql('update sku s join (select sku_id, sum(stock) as total from sku_bucket where sku_id = '
      + `${skuId} group by sku_id) b on b.sku_id = s.id set s.stock = greatest(b.total, 0) where s.id = ${skuId}`)
    const left = bucketSumOf(skuId) - stockOf(skuId)
    if (left !== 0) throw new Error(`缓存没复原：偏差还剩 ${left}`)
  }

  /* ---------------------------------------------------------------- 页面小工具 */

  const gotoReconcile = async (page) => {
    await page.goto(`${ADMIN}/inventory-reconcile`)
    await expect(tid(page, 'reconcile-sku-input'), '对账页应打开（reconcile-sku-input）').toBeVisible({ timeout: 20_000 })
    await page.waitForTimeout(600)
  }

  /** 输入 skuId → 点「对账」→ 等三块卡片出现 */
  const reconcileViaUi = async (page, skuId) => {
    await tid(page, 'reconcile-sku-input').fill(String(skuId))
    await tid(page, 'reconcile-search-submit').click()
    await expect(tid(page, 'reconcile-bucket-sum'), `SKU ${skuId} 的对账卡片应出现`).toBeVisible({ timeout: 20_000 })
    await page.waitForTimeout(500)
  }

  /** 页面上那三个数（+ 台账净额） */
  const numbersOnPage = async (page) => ({
    bucketSum: Number((await tid(page, 'reconcile-bucket-sum').innerText()).trim()),
    skuStock: Number((await tid(page, 'reconcile-sku-stock').innerText()).trim()),
    drift: Number((await tid(page, 'reconcile-drift').innerText()).replace('+', '').trim()),
    ledgerNet: Number((await tid(page, 'reconcile-ledger-net').locator('.net-value').innerText()).replace('+', '').trim())
  })

  /** 全库偏差 TOP 里有没有这个 SKU 的行（有就返回该行文字，没有返回空串） */
  const driftRowTextOf = async (page, skuId) => {
    const rows = await tid(page, 'reconcile-drift-table').locator('.el-table__body tr').allInnerTexts()
    return rows.map((s) => s.replace(/\s+/g, ' ').trim()).find((s) => s.startsWith(`${skuId} `)) ?? ''
  }

  /** 阈值 + 刷新（阈值 100 是页面默认值，这里显式写一遍，避免默认值被改后本用例"静默不测"） */
  const refreshDriftTop = async (page, threshold = 100) => {
    const input = tid(page, 'reconcile-drift-threshold').locator('input')
    await input.fill(String(threshold))
    await input.press('Tab')
    await tid(page, 'reconcile-drift-refresh').click()
    await page.waitForTimeout(1200)
  }

  /* ================================================================ ① 只报不改 */

  test('对账只报结论：造一个偏差 → 页面列出 → 反复调接口/点页面都不修它，台账一行不多', async ({ page, request }) => {
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)
    const { skuId } = pickBucketedSku()

    try {
      await test.step('① 先把该 SKU 拨到「无偏差」基线（缓存 = 桶总量）', async () => {
        restoreCache(skuId)
        expect(bucketSumOf(skuId) - stockOf(skuId), '基线：桶总量应等于展示缓存').toBe(0)
      })

      const bucketSum = bucketSumOf(skuId)
      const ledgerRowsBefore = ledgerRowsOf(skuId)
      const ledgerNetDb = ledgerNetOf(skuId)
      let planted = 0

      await test.step('② 造一个 500 件的偏差（把展示缓存直接写成「桶 + 500」，模拟没追平的缓存）', async () => {
        planted = plantDrift(skuId, 500)
        expect(stockOf(skuId), '展示缓存已被写成 桶总量 + 500').toBe(bucketSum + 500)
        expect(bucketSumOf(skuId), '桶是权威值，造偏差不该动桶').toBe(bucketSum)
      })

      await test.step('③ 连续调 3 次对账接口 → 三次偏差都是 −500（接口不自己修）', async () => {
        for (let i = 0; i < 3; i++) {
          const r = await request.get(`${API}/admin/inventory/reconcile?skuId=${skuId}`, {
            headers: { Authorization: `Bearer ${token}` }
          })
          expect(r.status(), '对账接口应 200').toBe(200)
          const body = await r.json()
          expect(body.code, '对账接口应成功').toBe(0)
          const d = body.data
          expect(Number(d.bucketSum), `第 ${i + 1} 次：权威值 = SUM(桶)`).toBe(bucketSum)
          expect(Number(d.skuStock), `第 ${i + 1} 次：展示缓存仍是那份假值`).toBe(planted)
          expect(Number(d.drift), `第 ${i + 1} 次：偏差恒为 −500（对账不许顺手修）`).toBe(-500)
          expect(Number(d.bucketCount), '桶数应是 8（分桶模式正常形态）').toBe(8)
        }
      })

      await test.step('④ 库里三个数一个都没被"顺手修正"：缓存仍是假值、桶不变、台账行数不变', async () => {
        expect(stockOf(skuId), '展示缓存必须仍是假值（对账没写它）').toBe(planted)
        expect(bucketSumOf(skuId), '桶总量不变').toBe(bucketSum)
        expect(ledgerRowsOf(skuId), '库存台账行数不变 —— 对账不是记账路径，不写证据').toBe(ledgerRowsBefore)
        expect(ledgerNetOf(skuId), '台账净额不变').toBe(ledgerNetDb)
      })

      await test.step('⑤ 页面上把三数摆出来：权威值 / 展示缓存 / 偏差，并明写「台账净额不是余额」', async () => {
        await gotoReconcile(page)
        await reconcileViaUi(page, skuId)
        const nums = await numbersOnPage(page)
        expect(nums.bucketSum, '页面权威值 = SUM(桶)').toBe(bucketSum)
        expect(nums.skuStock, '页面展示缓存 = sku.stock（仍是假值）').toBe(planted)
        expect(nums.drift, '页面偏差 = 桶 − 缓存 = −500').toBe(-500)
        expect(nums.ledgerNet, '页面台账净额 = SUM(stock_ledger.delta)').toBe(ledgerNetDb)

        const netBlock = await tid(page, 'reconcile-ledger-net').innerText()
        expect(netBlock, '台账净额那块必须写明「不是余额」，且不能与权威值对平')
          .toContain('不是余额')
        expect(netBlock, '还要说明「不能与上面的权威值相互对平」').toContain('不能与')
      })

      await test.step('⑥ 再点两次「对账」→ 数字一动不动（页面也不会顺手修）', async () => {
        for (let i = 0; i < 2; i++) {
          await tid(page, 'reconcile-search-submit').click()
          await page.waitForTimeout(700)
          expect((await numbersOnPage(page)).drift, `第 ${i + 1} 次重查：偏差仍是 −500`).toBe(-500)
        }
        expect(stockOf(skuId), '表里的缓存依旧没被改').toBe(planted)
        expect(ledgerRowsOf(skuId), '台账行数依旧没变').toBe(ledgerRowsBefore)
      })

      await test.step('⑦ 全库偏差 TOP：阈值 100 能列出它、阈值 600 就列不出（阈值真的生效）', async () => {
        await refreshDriftTop(page, 100)
        const hit = await driftRowTextOf(page, skuId)
        expect(hit, `偏差 500 应被阈值 100 的 TOP 列表列出（SKU ${skuId}）`).toContain('-500')

        await refreshDriftTop(page, 600)
        const miss = await driftRowTextOf(page, skuId)
        expect(miss, '阈值抬到 600 之后，500 的偏差不该再出现在 TOP 里').toBe('')
      })
    } finally {
      restoreCache(skuId)
    }
  })

  /* ================================================================ ② 追平 */

  test('缓存追平：后台把库存设回权威值 → 回写任务把缓存刷成桶总量 → 偏差归零（且台账没多一行）', async ({ page }) => {
    await adminLoginHardened(page)
    const { skuId, productId } = pickBucketedSku()

    try {
      await test.step('① 基线无偏差，再造一个 500 的偏差', async () => {
        restoreCache(skuId)
        plantDrift(skuId, 500)
        expect(bucketSumOf(skuId) - stockOf(skuId), '现在应当是 −500 的偏差').toBe(-500)
      })

      const bucketSum = bucketSumOf(skuId)
      const ledgerRowsBefore = ledgerRowsOf(skuId)

      await test.step('② 页面上先看见它：对账卡片偏差 −500，且出现在偏差 TOP 里', async () => {
        await gotoReconcile(page)
        await reconcileViaUi(page, skuId)
        expect((await numbersOnPage(page)).drift, '对账卡片应显示 −500').toBe(-500)
        await refreshDriftTop(page, 100)
        expect(await driftRowTextOf(page, skuId), 'TOP 列表里应有这一行').toContain('-500')
      })

      await test.step('③ 后台把该 SKU 的库存设回权威值（走真实入口：SKU 编辑 → 重置分桶并标脏）', async () => {
        await page.goto(`${ADMIN}/product/${productId}/skus`)
        await expect(tid(page, 'sku-list-table'), 'SKU 列表页应打开').toBeVisible({ timeout: 20_000 })
        await page.waitForTimeout(600)
        await tid(page, `sku-row-${skuId}`).getByTestId('sku-row-edit').click()
        const dlg = tid(page, 'sku-form-dialog')
        await expect(dlg, 'SKU 编辑弹窗应打开').toBeVisible({ timeout: 15_000 })
        const stockInput = tid(page, 'sku-form-stock').locator('input')
        await stockInput.click()
        await stockInput.fill(String(bucketSum))
        await stockInput.press('Tab')
        await tid(page, 'sku-form-submit').click()
        await expectToastSuccess(page, '')
        await page.waitForTimeout(500)
      })

      await test.step('④ 回写任务（默认 250ms 一轮）把 sku.stock 刷成 SUM(桶)：库里偏差自己归零', async () => {
        await expect.poll(() => bucketSumOf(skuId) - stockOf(skuId), {
          timeout: 15_000,
          intervals: [300, 600, 1200],
          message: '展示缓存应在几个回写周期内被刷成 SUM(桶)'
        }).toBe(0)
        expect(bucketSumOf(skuId), '权威值不变（重置成同一个总量，桶只是重新铺了一遍）').toBe(bucketSum)
      })

      await test.step('⑤ 页面刷新 → 偏差归零、TOP 里那一行消失、台账一行没多', async () => {
        await gotoReconcile(page)
        await reconcileViaUi(page, skuId)
        const nums = await numbersOnPage(page)
        expect(nums.bucketSum, '权威值仍是原值').toBe(bucketSum)
        expect(nums.skuStock, '展示缓存已追平').toBe(bucketSum)
        expect(nums.drift, '偏差归零').toBe(0)

        await refreshDriftTop(page, 100)
        expect(await driftRowTextOf(page, skuId), '追平后该 SKU 不该再出现在偏差 TOP 里').toBe('')
        expect(ledgerRowsOf(skuId), '全程台账一行没多 —— 缓存回写不是动账').toBe(ledgerRowsBefore)
      })
    } finally {
      restoreCache(skuId)
    }
  })
})
