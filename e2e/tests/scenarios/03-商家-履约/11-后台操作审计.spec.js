import { test, expect } from '../../support/test.js'
import { API, ADMIN, stamp } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { tid, tidPrefix } from '../../support/tid.js'
import { receiverOf } from '../../support/fixtures.js'
import {
  adminLoginHardened,
  gotoOrderList,
  createOrderViaUi,
  expectToastSuccess,
} from '../../support/admin-ui.js'
import { adminSetSkuStock, adminSearchOrderRow } from '../../support/ui.js'
import { confirmDanger } from '../../support/danger-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi } from '../../support/assert.js'

/**
 * 03-商家-履约 / 11-后台操作审计
 *
 * 这个场景要证明的一件事：
 *   **一次请求一行，谁在什么时候把什么改成了什么，事后一定查得到；连点两次要留两条。**
 *
 * 审计和状态台账是两种东西，别混：
 *   · 状态台账回答「这单是怎么走到今天的」→ 幂等键由**业务键 + 流转序号**派生（一条流转一行）；
 *   · 操作审计回答「这个人点了什么」→ 幂等键只能由**请求号**派生（一次请求一行）。
 * 为什么要分开：审计要留的是「他确实点了第二次，第二次因为状态已变而失败」这个证据。
 * 如果审计也按业务键去重，第二次点击会撞上同一把唯一键 → INSERT 失败 → 整个事务回滚 →
 * 本该留下的失败证据被一起抹掉。所以这一条红线必须在用例里钉死，而不是写在注释里。
 *
 * 三条用例：
 *   ① 改库存这种敏感动作，一行审计里要有动作码、目标、摘要、改前改后快照、操作人；
 *   ② 红线：幂等键来自请求号（不是业务键），连改两次留两行、两个不同的请求号；
 *   ③ 目标被物理删除之后，审计里那一行仍然读得出来是哪一单（单号快照 + 订单ID）。
 *
 * ── 为什么直查库 ──────────────────────────────────────────────────────────
 *   台账页只显示摘要与原因，看不到快照 JSON、请求号、幂等键；而这三样正是本场景的论点。
 *   页面断言负责证明「人看得见」，库断言负责证明「写对了」。
 *
 * ── 依赖（当前状态）──────────────────────────────────────────────────────
 *   台账页 `/ledger` 前端已就绪；后端 `GET /admin/ledgers` 控制器尚未进仓，故**只写不跑**。
 */

/* ------------------------------------------------------------------ 台账页（UI 侧） */

const gotoLedger = async (page) => {
  await page.goto(`${ADMIN}/ledger`)
  await expect(tid(page, 'ledger-list-table'), '台账查询页应打开（ledger-list-table）').toBeVisible({ timeout: 20_000 })
}

/**
 * 只看「审计」这一类，不按业务ID过滤。
 *
 * 为什么不填业务ID：审计的目标是多态的（订单/包裹/售后/规格），
 * 到底按 target_id 还是 order_id 过滤属于接口契约还没定的事；
 * 而台账页按时间倒序，刚发生的这几行必然在第一页，用文本定位反而更稳。
 */
const filterAuditLedger = async (page) => {
  await tid(page, 'ledger-type-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '审计' }).first().click()
  await tid(page, 'ledger-search-reset').click()   // 清掉别的筛选项，避免上一段残留
  await tid(page, 'ledger-search-submit').click()
  // 注意这条断言的强度：ledger-list-table 在 gotoLedger() 里就已经断言过一次可见了，
  // 搜索只是给已有表格换数据 —— 所以它对「搜索请求回来没有」其实没有判别力（恒真）。
  // 删掉前面的 sleep 不会新增假绿（本来就断不到），只是不再白等 1 秒。
  // 真正想等「筛选生效」应改成 expect.poll(() => 行数/首行文本变化) —— 见报告待决栏。
  await expect(tid(page, 'ledger-list-table'), '审计台账列表应渲染').toBeVisible()
}

/* ------------------------------------------------------------------ 库上的事实（只读） */

const dbCount = (sql) => Number(queryDb(sql))

/** 审计表当前最大 id：用来只断言「本次动作新产生的那几行」，不误伤历史数据 */
const auditMaxId = () => dbCount('select coalesce(max(id), 0) from admin_audit_log')

/** 本次动作之后新增的审计行数（可按动作码收窄） */
const auditRowsSince = (fromId, action) => dbCount(
  `select count(*) from admin_audit_log where id > ${fromId}`
  + (action ? ` and action = '${action}'` : '')
)

/**
 * 本次动作新增的审计行（按 id 升序）。
 * 返回对象里保留 before/after 原文 ——「改前改后都快照下来」是这一行的价值所在。
 *
 * 一行一条 SQL 结果行（不再用 group_concat 拼多行）：group_concat 在没命中时返回 SQL NULL，
 * 那个 NULL 到 mysql 客户端会变成字符串 "NULL"，`rows.length` 于是等于 1 ——
 * 松断言就是这么假绿的（本用例第一次跑就撞上了：0 行的查询被当成有 1 行）。
 * 没命中 ⇒ 这里如实返回 []。
 */
const auditRowsDetail = (fromId, action, targetId) => queryDb(
  `select concat_ws('｜', id, operator_type, action, target_type,
        coalesce(target_id, 0), coalesce(target_sn, ''), coalesce(summary, ''),
        coalesce(request_id, ''), idem_key, coalesce(before_json, ''), coalesce(after_json, ''))
     from admin_audit_log where id > ${fromId}`
  + (action ? ` and action = '${action}'` : '')
  + (targetId !== undefined && targetId !== null ? ` and target_id = ${targetId}` : '')
  + ' order by id'
).split('\n').filter(Boolean).map((line) => {
  const [id, operatorType, act, targetType, targetId2, targetSn, summary, requestId, idemKey, before, after] =
    line.split('｜')
  return { id: Number(id), operatorType, action: act, targetType, targetId: Number(targetId2), targetSn, summary, requestId, idemKey, before, after }
})

/** 本次动作新增的审计行（按动作码 + 目标 id 精确收窄；不吃「同表里同动作的别人的行」） */
const auditRowsFor = (fromId, action, targetId) => auditRowsDetail(fromId, action, targetId)

const skuStockOf = (skuId) => Number(queryDb(`select stock from sku where id = ${skuId}`))

/** 清掉本用例自造的审计行（按主键精确删，不按时间/不按动作码批量删） */
const cleanupAuditRows = (ids) => {
  const list = ids.filter(Boolean).join(',')
  if (!list) return
  try {
    queryDb(`delete from admin_audit_log where id in (${list})`)
  } catch (e) {
    console.warn(`[11-后台操作审计] 清理审计行未完成（ids=${list}）：${e.message}`)
  }
}

async function cleanupSelfMadeOrder(request, { orderId, orderSn, token }) {
  const id = orderId
    || (orderSn ? Number(queryDb(`select id from \`order\` where order_sn = '${orderSn}'`) || 0) : 0)
  if (!id) return
  if (dbCount(`select count(*) from \`order\` where id = ${id}`) === 0) return
  try {
    queryDb(`delete from delivery_logistics where delivery_id in
               (select id from delivery_order where order_id = ${id})`)
    queryDb(`delete from return_apply where order_id = ${id}`)
    queryDb(`update delivery_order set status = 0 where order_id = ${id} and status <> 9`)
    const res = await request.delete(`${API}/admin/orders/${id}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const body = await res.json()
    if (body.code !== 0) console.warn(`[11-后台操作审计] 清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[11-后台操作审计] 清理抛错（orderId=${id}）：${e.message}`)
  }
}

test.describe('03-商家-履约 › 11-后台操作审计', () => {
  test('用例一：改库存留一行审计 —— 动作、目标、摘要、改前改后快照、操作人齐全', async ({ page }) => {
    test.setTimeout(180_000)
    const created = []
    let skuId = null
    let stockBefore = null
    let fromId = 0
    try {
      await test.step('① 管理员登录，取 SKU 列表第一行的规格作为基准（列表按主键升序，与库里同序）', async () => {
        await adminLoginHardened(page)
        skuId = Number(queryDb('select id from sku where product_id = 1 order by id limit 1'))
        stockBefore = skuStockOf(skuId)
        expect(stockBefore, `规格 ${skuId} 应能在库里读到库存`).toBeGreaterThanOrEqual(0)
        fromId = auditMaxId()
      })

      await test.step('② 在 SKU 管理页把库存改成一个明确的新值（敏感动作）', async () => {
        await adminSetSkuStock(page, 1, 0, stockBefore + 7)
        expect(skuStockOf(skuId), '库存应真的被改成「改前 + 7」').toBe(stockBefore + 7)
      })

      await test.step('③ 库：恰好新增一行审计，五个要素齐全', async () => {
        const rows = auditRowsFor(fromId, 'SKU_STOCK_CHANGE', skuId)
        expect(rows.length,
          '只改库存必须记成 SKU_STOCK_CHANGE（动作码按「哪个字段真的变了」选，不是按请求体里有没有 price）'
        ).toBe(1)
        const row = rows[0]
        created.push(row.id)
        expect(row.targetType, '目标类型应是规格').toBe('SKU')
        expect(row.targetId, `目标应是刚改的那个规格（${skuId}）`).toBe(skuId)
        expect(row.operatorType, '操作人类型应是管理员').toBe('ADMIN')
        expect(row.summary, '摘要应该是一句人话，且只说变化的部分')
          .toBe(`修改规格库存：${stockBefore} → ${stockBefore + 7}`)
        // 快照必须是合法 JSON，且**只含实际变化的字段**（没变的售价不许混进来，否则读的人还得自己比对）
        const beforeSnap = JSON.parse(row.before)
        const afterSnap = JSON.parse(row.after)
        expect(Object.keys(beforeSnap), '改前快照只放变化字段（stock）').toEqual(['stock'])
        expect(Object.keys(afterSnap), '改后快照只放变化字段（stock）').toEqual(['stock'])
        expect(beforeSnap.stock, `改前快照的 stock 应是 ${stockBefore}`).toBe(stockBefore)
        expect(afterSnap.stock, `改后快照的 stock 应是 ${stockBefore + 7}`).toBe(stockBefore + 7)
        expect(row.requestId, '每行审计都要带请求号（同一次请求里多条审计靠它串起来）').not.toBe('')
      })

      await test.step('③b 反向断言：把表单原样再提交一次（什么都没变）→ 台账一行都不新增', async () => {
        const beforeNoop = auditMaxId()
        await adminSetSkuStock(page, 1, 0, stockBefore + 7)   // 与当前值完全相同
        expect(skuStockOf(skuId), '前提：库存确实没变').toBe(stockBefore + 7)
        expect(
          dbCount(`select count(*) from admin_audit_log
                     where id > ${beforeNoop} and target_id = ${skuId}`),
          '没发生的改动不许进证据表（整份表单原样提交 = 没有变更 = 不写审计）'
        ).toBe(0)
      })

      await test.step('④ 台账页：类型选「审计」，第一页就能看到这条动作', async () => {
        await gotoLedger(page)
        await filterAuditLedger(page)
        await expect(tidPrefix(page, 'ledger-row-').first(), '审计台账应有行').toBeVisible()
        await expect(tid(page, 'ledger-list-table'), '刚发生的改库存动作应出现在审计第一页')
          .toContainText(`修改规格库存：${stockBefore} → ${stockBefore + 7}`)
        // 页面上「原因」列展示的是动作码本身：这一次必须是 SKU_STOCK_CHANGE，不能是 SKU_PRICE_CHANGE
        await expect(tid(page, 'ledger-list-table'), '动作码应如实写「改库存」')
          .toContainText('SKU_STOCK_CHANGE')
      })
    } finally {
      // 恢复库存（只动本用例改过的那个规格），再清掉自造的审计行
      try {
        if (skuId !== null && stockBefore !== null) {
          await adminSetSkuStock(page, 1, 0, stockBefore)
          // 只清「本次跑出来的」那几行（id > fromId 且目标就是这个规格），历史审计一条不动
          const ids = queryDb(
            `select group_concat(id) from admin_audit_log
              where id > ${fromId} and action = 'SKU_STOCK_CHANGE' and target_id = ${skuId}`
          )
          if (ids) ids.split(',').filter(Boolean).forEach((id) => created.push(Number(id)))
        }
      } catch (e) {
        console.warn(`[11-后台操作审计] 恢复库存未完成：${e.message}`)
      }
      cleanupAuditRows(created)
    }
  })

  test('用例二（红线）：幂等键来自请求号而非业务键 —— 连改两次留两行、两个请求号', async ({ page }) => {
    test.setTimeout(240_000)
    let skuId = null
    let stockBefore = null
    let fromId = 0
    const created = []
    try {
      await test.step('① 管理员登录 + 取基准库存', async () => {
        await adminLoginHardened(page)
        skuId = Number(queryDb('select id from sku where product_id = 1 order by id limit 1'))
        stockBefore = skuStockOf(skuId)
      })

      await test.step('② 连续两次把库存改成不同的值（两次点击 = 两个请求）', async () => {
        fromId = auditMaxId()
        await adminSetSkuStock(page, 1, 0, stockBefore + 3)
        await adminSetSkuStock(page, 1, 0, stockBefore + 9)
        expect(skuStockOf(skuId), '最终库存应是第二次改的值').toBe(stockBefore + 9)
      })

      await test.step('③ 库：两行、两个不同的请求号 —— 这就是「连点留两条」', async () => {
        const rows = auditRowsFor(fromId, 'SKU_STOCK_CHANGE', skuId)
        expect(rows.length, '两次点击必须留两行审计（两次请求 = 两条证据）').toBe(2)
        rows.forEach((r) => created.push(r.id))
        expect(rows[0].requestId, '第一行要带请求号').not.toBe('')
        expect(rows[1].requestId, '第二行要带请求号').not.toBe('')
        expect(rows[0].requestId, '两行的请求号必须不同（同一次请求才会相同）')
          .not.toBe(rows[1].requestId)
        expect(rows[0].idemKey, '两行的幂等键也不同 —— 业务键相同也不会撞键，这正是红线').not.toBe(rows[1].idemKey)
        // 两条证据各自记清「这一次从多少改到多少」：两次点击的起点/终点连续，不是各写一份一样的快照
        const snaps = rows.map((r) => ({ before: JSON.parse(r.before), after: JSON.parse(r.after) }))
        expect(snaps.map((s) => Object.keys(s.before))).toEqual([['stock'], ['stock']])
        expect(snaps.map((s) => Object.keys(s.after))).toEqual([['stock'], ['stock']])
        expect(snaps[0].before.stock, '第一行的改前值').toBe(stockBefore)
        expect(snaps[0].after.stock, '第一行的改后值').toBe(stockBefore + 3)
        expect(snaps[1].before.stock, '第二行的改前值应接着第一次的结果').toBe(stockBefore + 3)
        expect(snaps[1].after.stock, '第二行的改后值').toBe(stockBefore + 9)
      })

      await test.step('④ 库：幂等键 = 请求号 + 序号，且不含任何业务键痕迹', async () => {
        const rows = auditRowsFor(fromId, 'SKU_STOCK_CHANGE', skuId)
        for (const r of rows) {
          expect(r.requestId, '审计行必须带请求号').not.toBe('')
          expect(r.idemKey.startsWith(`${r.requestId}:`),
            `幂等键必须由请求号派生（期望以「${r.requestId}:」开头，实际：${r.idemKey}）`).toBe(true)
          // 强口径：幂等键**整串**只能是「请求号 + ':' + 序号」——
          // 多夹一位业务键就会破坏这个等式。
          // （反面教材：原来这里断的是「幂等键不含 String(skuId)」这种子串。skuId=1 时
          //   任何一个含数字 1 的请求号都会命中，这条断言既证明不了红线、还会假红。）
          const tail = r.idemKey.slice(r.requestId.length + 1)
          expect(tail, '请求号之后只能跟一个序号').toMatch(/^\d+$/)
          expect(r.idemKey, '幂等键 = 请求号 + 「:」 + 序号，中间不许夹任何业务键')
            .toBe(`${r.requestId}:${tail}`)
          expect(r.idemKey.includes('SKU'), '幂等键绝不能带动作码/业务键片段（那会让第二次点击撞键、把证据回滚掉）')
            .toBe(false)
        }
        expect(
          dbCount(`select count(*) from admin_audit_log where id > ${fromId} and request_id is null`),
          '审计行不允许没有请求号（一次请求一条，前提是有请求号）'
        ).toBe(0)
      })
    } finally {
      try {
        if (skuId !== null && stockBefore !== null) {
          await adminSetSkuStock(page, 1, 0, stockBefore)
          const ids = queryDb(
            `select group_concat(id) from admin_audit_log
              where id > ${fromId} and action = 'SKU_STOCK_CHANGE' and target_id = ${skuId}`
          )
          if (ids) ids.split(',').filter(Boolean).forEach((id) => created.push(Number(id)))
        }
      } catch (e) {
        console.warn(`[11-后台操作审计] 恢复库存未完成：${e.message}`)
      }
      cleanupAuditRows([...new Set(created)])
    }
  })

  test('用例三：删单审计在订单被物理删除之后仍然读得出是哪一单（单号快照不随目标消失）', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let token = null
    const created = []
    try {
      let productName
      let fromId
      await test.step('① 管理员登录 + 造一笔干净订单', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        const { skus } = await getProductApi(request, 1)
        if ((skus[0]?.stock ?? 0) < 20) await adminSetSkuStock(page, 1, 0, 50)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`audit-delete-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        fromId = auditMaxId()
      })

      await test.step('② 走高危弹窗真删这张单（预演 → 确认执行）', async () => {
        const row = await adminSearchOrderRow(page, orderSn)
        await row.getByTestId('order-row-delete').click()
        await confirmDanger(page, { willDo: '将删除订单' })
        await expectToastSuccess(page, '订单已删除')
        expect(dbCount(`select count(*) from \`order\` where id = ${orderId}`), '订单应已被物理删除').toBe(0)
      })

      await test.step('③ 库：删单审计只有一行，且单号/订单ID 仍在（目标没了，证据还在）', async () => {
        expect(auditRowsSince(fromId, 'ORDER_DELETE'), '删一次单应留一行审计').toBe(1)
        const [row] = auditRowsFor(fromId, 'ORDER_DELETE', orderId)
        created.push(row.id)
        expect(row.targetType, '目标类型应是订单').toBe('ORDER')
        expect(row.targetId, '目标ID应是被删的那张单').toBe(orderId)
        expect(row.targetSn, `目标单号快照应等于 ${orderSn}（订单行已经没了，这是唯一线索）`).toBe(orderSn)
        const orderIdOnAudit = Number(queryDb(
          `select coalesce(order_id, 0) from admin_audit_log where id = ${row.id}`
        ))
        expect(orderIdOnAudit, '审计还冗余了订单ID，便于按订单检索这类动作').toBe(orderId)
        // 快照是 JSON：结构断言（有 status 这个键、且 deleted 明确为 true），不是子串匹配 ——
        // 子串会被「{\"deleted\": true}」这种带空格的序列化格式骗过，也会被 notes 里的同名字样骗过
        const beforeSnap = JSON.parse(row.before)
        expect(Object.keys(beforeSnap), '改前快照应记下原状态（删除前的状态是审计要追的敏感字段）')
          .toContain('status')
        expect(JSON.parse(row.after), '改后快照应明确标记为已删除').toMatchObject({ deleted: true })
      })

      await test.step('④ 台账页：审计第一页能看到这条删除动作', async () => {
        await gotoLedger(page)
        await filterAuditLedger(page)
        await expect(tid(page, 'ledger-list-table'), '审计台账应能看到这次删单动作')
          .toContainText('删除订单')
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
      cleanupAuditRows(created)
    }
  })
})
