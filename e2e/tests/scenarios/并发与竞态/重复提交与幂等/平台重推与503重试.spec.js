import { spawn } from 'node:child_process'
import { test, expect } from '../../../support/test.js'
import { API, ADMIN, stamp } from '../../../support/env.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  auditPassOrder,
  openOrderDrawer,
  deliveryRowBySn,
  clickDeliveryAction,
  wmsCallback,
  expectToastSuccess
} from '../../../support/admin-ui.js'
import { getProductApi } from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'
import { queryDb, dbOfApi } from '../../../support/db.js'

/**
 * 并发与竞态 / 重复提交与幂等 / 平台重推与503重试
 *
 * 这一页讲的是「外部世界会抽风」那一半：平台会重复推同一批单，它也会 503。
 * 所以这里只有两条主线，四条断言，全部直查库 + 直查平台侧，不看 toast：
 *
 *   ① 同一批重推两次 → 订单数不变，第二遍只有 N 条 SKIP/IDEMPOTENT（不是又建一批单）；
 *   ② mock 注入 503 → 回传留一行 FAIL 且 attempt=1（失败本身要留痕，原因里带 503）；
 *   ③ 同一次点击里自动重试一次 → 第二行 OK 且 attempt=2，**老那一行一个字段都不改**（只追加）；
 *   ④ 平台侧真的收到并受理了几次回传 = 我们记了几行成功（不重复发、也不重复记）。
 *
 * 为什么这四条要单独成文件、不搭 09/1 的车：09/1 走的是「一条主链路能跑通」，
 * 幂等与重试在那条链路里只是顺带；这一页是**故障与重复**专题，断言必须独立存在，
 * 否则「能演却没演」——页面讲了幂等，断言却在别的文件里。
 *
 * 三条测试手法（与 09/1 同一套，别照抄成坏习惯）：
 *  1) mock 由本 spec 用精确 PID 起停（spawn + kill(SIGTERM)）；MOCK_RUN_ID 每轮唯一 → 平台单号每轮都是新的一批。
 *     **端口只能用 19090**：后端 channel.mock.base-url 的默认值就是它（ChannelConfig），换端口服务端根本不理你，
 *     拉单会直接返回 code=1（这一版实测踩过）。代价是它与 09/1 抢同一个端口，所以起 mock 前先等端口空出来
 *     （同批跑的两个 09 spec 会被分到不同 shard 并行，谁先跑谁占，另一个排队——不是 bug，是唯一正确的做法）。
 *  2) 断言优先直查库（channel_order_map / order / channel_sync_log 是唯一事实来源）；
 *  3) 「老行不改」用**全列快照前后比对**证明，不是只比行数 —— 只比行数的话，
 *     改字段、改时间戳这类污染会全被放过。
 */
test.describe('并发与竞态 › 重复提交与幂等 › 平台重推与503重试', () => {
  const MOCK_PORT = 19090
  const MOCK = `http://127.0.0.1:${MOCK_PORT}`
  const RUN_ID = `E2E${stamp()}`

  let mockProcess = null

  test.afterAll(async () => {
    if (mockProcess) {
      mockProcess.kill('SIGTERM')
      mockProcess = null
    }
  })

  const num = (sql) => Number(queryDb(sql) || 0)
  const one = (sql) => queryDb(sql)
  const inList = (values) => values.map((v) => `'${String(v).replace(/'/g, "''")}'`).join(', ')

  /** 某个包裹在同步日志里的全列快照（每行一个字符串）—— 「老行一条不改」就靠它逐字对比 */
  const shipPushRowsOf = (deliveryId) => queryDb(
    `SELECT CONCAT_WS('|', id, attempt, result, COALESCE(reason, '∅'),
                      COALESCE(latency_ms, '∅'), COALESCE(request_payload, '∅'),
                      COALESCE(response_payload, '∅'), created_at)
       FROM channel_sync_log
      WHERE action = 'SHIP_PUSH' AND ref_id = ${deliveryId}
      ORDER BY id`
  ).split('\n').filter(Boolean)

  test('同一批重推两次只留 SKIP；503 → FAIL(attempt=1) 后重试 OK(attempt=2)，老行不改、平台侧只受理一次', async ({ page, request }) => {
    test.setTimeout(540_000)
    /** 本用例的包裹 id（阶段③ 生成，④⑤ 用它收敛同步日志） */
    let deliveryId = null

    // ====================================================================== ①
    await test.step('① 起 mock → 淘宝/抖音各拉一次 → 3 笔待审核 + 3 行映射（订单集合基线）', async () => {
      const { skus } = await getProductApi(request, 1)
      const skuIds = [skus[0].id, (skus[1] || skus[0]).id]

      // 端口 19090 是后端写死的 mock 地址 → 与 09/1 共用。先等它空出来（对面那个 spec 跑完会自己 kill）。
      let portFree = false
      for (let i = 0; i < 360 && !portFree; i++) {
        const res = await request.get(`${MOCK}/health`).catch(() => null)
        if (!res || !res.ok()) portFree = true
        else await page.waitForTimeout(500)
      }
      expect(portFree, `mock 端口 ${MOCK_PORT} 应能被本用例占用（同批另一个 09 spec 一直占着？）`).toBeTruthy()

      mockProcess = spawn(
        process.execPath,
        [new URL('../../../../../mock-platform/server.mjs', import.meta.url).pathname],
        {
          env: { ...process.env, MOCK_PORT: String(MOCK_PORT), MOCK_RUN_ID: RUN_ID, MOCK_SKU_IDS: skuIds.join(',') },
          stdio: 'pipe'
        }
      )
      mockProcess.stdout.on('data', (d) => process.stdout.write(`[mock] ${d}`))
      mockProcess.stderr.on('data', (d) => process.stderr.write(`[mock!] ${d}`))

      let health = null
      for (let i = 0; i < 40; i++) {
        health = await (await request.get(`${MOCK}/health`).catch(() => null))?.json().catch(() => null)
        if (health && health.runId === RUN_ID) break
        await page.waitForTimeout(500)
      }
      expect(health && health.runId, `mock 平台服务应在本轮 RUN_ID=${RUN_ID} 起来（端口 ${MOCK_PORT}）`).toBe(RUN_ID)

      await adminLoginHardened(page)
      await page.goto(`${ADMIN}/channel`)
      await expect(tid(page, 'channel-sync-log-table'), '同步日志表应渲染').toBeVisible()

      const pulled = {}
      for (const platform of ['TAOBAO_MOCK', 'DOUYIN_MOCK']) {
        const [response] = await Promise.all([
          page.waitForResponse(
            (r) => r.request().method() === 'POST'
              && /\/admin\/channel-shops\/\d+\/pull$/.test(new URL(r.url()).pathname),
            { timeout: 20_000 }
          ),
          tid(page, `channel-pull-btn-${platform}`).click()
        ])
        const body = await response.json()
        expect(body.code, `${platform} 首次拉单应成功`).toBe(0)
        pulled[platform] = body.data
        await expectToastSuccess(page)
      }
      expect(pulled.TAOBAO_MOCK.created, '淘宝首次应新建 2 笔').toBe(2)
      expect(pulled.DOUYIN_MOCK.created, '抖音首次应新建 1 笔').toBe(1)

      const batchSns = [...pulled.TAOBAO_MOCK.platformSns, ...pulled.DOUYIN_MOCK.platformSns]
      expect(batchSns.length, '本批平台单号应为 3 个').toBe(3)
      expect(
        num(`SELECT COUNT(*) FROM channel_order_map WHERE platform_sn IN (${inList(batchSns)})`),
        '首次拉单应落 3 行映射'
      ).toBe(3)
      // 平台侧事实：两家各被拉过一次
      const taobaoState = await (await request.get(`${MOCK}/mock/taobao/state`)).json()
      const douyinState = await (await request.get(`${MOCK}/mock/douyin/state`)).json()
      expect(taobaoState.pulls, '淘宝侧首次拉单应被打过 1 次').toBe(1)
      expect(douyinState.pulls, '抖音侧首次拉单应被打过 1 次').toBe(1)
    })

    // ====================================================================== ②
    await test.step('② 同一批再推一次 → 每单一行 SKIP/IDEMPOTENT，订单数一个不多', async () => {
      const mapsBefore = num('SELECT COUNT(*) FROM channel_order_map')
      const ordersBefore = num('SELECT COUNT(*) FROM `order`')
      const skipBaseline = num('SELECT COALESCE(MAX(id), 0) FROM channel_sync_log')

      for (const platform of ['TAOBAO_MOCK', 'DOUYIN_MOCK']) {
        const [response] = await Promise.all([
          page.waitForResponse(
            (r) => r.request().method() === 'POST'
              && /\/admin\/channel-shops\/\d+\/pull$/.test(new URL(r.url()).pathname),
            { timeout: 20_000 }
          ),
          tid(page, `channel-pull-btn-${platform}`).click()
        ])
        const body = await response.json()
        expect(body.code, `${platform} 第二次拉单应成功（重复推不是错误）`).toBe(0)
        expect(body.data.created, `${platform} 第二次拉单不应再新建订单`).toBe(0)
        expect(body.data.skipped, `${platform} 第二次拉单应全部记为 skipped`).toBeGreaterThanOrEqual(1)
        await expectToastSuccess(page)
      }

      // 断言一：订单数不变（映射表与新订单都没有第二份）
      expect(num('SELECT COUNT(*) FROM channel_order_map'), '重复拉单后映射行数不变').toBe(mapsBefore)
      expect(num('SELECT COUNT(*) FROM `order`'), '重复拉单后订单数不变').toBe(ordersBefore)

      // 断言二：重推的每一单各留一行 SKIP / IDEMPOTENT（淘宝 2 + 抖音 1 = 3），且没有 FAIL
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE id > ${skipBaseline}
              AND action = 'PULL' AND result = 'SKIP' AND reason = 'IDEMPOTENT'`),
        '重推的 3 单应各留一行 SKIP/IDEMPOTENT'
      ).toBe(3)
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE id > ${skipBaseline} AND result = 'FAIL'`),
        '重推不该产生 FAIL（重复推是预期行为，不是故障）'
      ).toBe(0)

      // 平台侧事实：两次都真的打到了平台（幂等是我们这边挡的，不是平台没被调用）
      const taobaoState = await (await request.get(`${MOCK}/mock/taobao/state`)).json()
      expect(taobaoState.pulls, '淘宝侧应被打过 2 次（第二次被我们的幂等挡住）').toBe(2)
    })

    // ====================================================================== ③
    await test.step('③ 审核通过抖音那笔 → 拆单 → 下发 → 拣货 → 出库（准备一个可回传的包裹）', async () => {
      const orderId = Number(one(
        `SELECT m.order_id FROM channel_order_map m JOIN channel_shop s ON s.id = m.shop_id
          WHERE s.platform_type = 'DOUYIN_MOCK' ORDER BY m.id DESC LIMIT 1`
      ))
      expect(orderId, '应能按抖音店铺反查到内部订单 id').toBeTruthy()
      const orderSn = one(`SELECT order_sn FROM \`order\` WHERE id = ${orderId}`)

      await gotoOrderList(page)
      const row = await searchOrderRow(page, orderSn)
      await auditPassOrder(page, row)
      await expectToastSuccess(page)
      expect(num(`SELECT status FROM \`order\` WHERE id = ${orderId}`), '审核通过后应为待发货(8)').toBe(8)

      deliveryId = Number(one(
        `SELECT id FROM delivery_order WHERE order_id = ${orderId} ORDER BY id LIMIT 1`
      ))
      const deliverySn = one(`SELECT delivery_sn FROM delivery_order WHERE id = ${deliveryId}`)
      expect(deliveryId, '审核通过应生成包裹').toBeGreaterThan(0)

      // admin-ui.js 的 openOrderDrawer 收「行 Locator」（ui.js 里那个同名函数才收订单号字符串），
      // 这一条从 admin-ui.js 导入，所以传 row —— 传 orderSn 会在 getByTestId 上炸。
      const table = await openOrderDrawer(page, row)
      await clickDeliveryAction(page, deliveryRowBySn(table, deliverySn), '下发')
      await expectToastSuccess(page)
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '拣货')
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '出库')
      expect(num(`SELECT status FROM delivery_order WHERE id = ${deliveryId}`), '出库后包裹应为已出库(3)').toBe(3)
      expect(
        one(`SELECT track_no IS NOT NULL FROM delivery_logistics WHERE delivery_id = ${deliveryId}`),
        '出库应写运单号（回传发货要用它）'
      ).toBe('1')

    })

    // ====================================================================== ④
    await test.step('④ mock 注入 503 → 回传：一行 FAIL(attempt=1，原因带 503) + 一行 OK(attempt=2)', async () => {
      expect(deliveryId, '上一阶段应已拿到包裹 id').toBeTruthy()
      const baseline = num('SELECT COALESCE(MAX(id), 0) FROM channel_sync_log')

      const injected = await request.post(`${MOCK}/mock/_admin/fail?code=503&count=1`)
      expect(injected.ok(), '故障注入接口应可用').toBeTruthy()

      const [pushResp] = await Promise.all([
        page.waitForResponse(
          (r) => r.request().method() === 'POST'
            && new URL(r.url()).pathname === '/admin/channel/push-shipment',
          { timeout: 30_000 }
        ),
        tid(page, 'order-push-shipment-btn').click()
      ])
      const pushBody = await pushResp.json()
      expect(pushBody.code, '回传接口应成功（内部重试后）').toBe(0)
      expect(pushBody.data.ok, '重试一次后应有 1 个包裹回传成功').toBe(1)

      const rows = shipPushRowsOf(deliveryId)
      expect(rows.length, '一次失败重试应留 2 行（attempt=1 FAIL + attempt=2 OK）').toBe(2)
      expect(rows[0], '第 1 行应是 attempt=1 的 FAIL，原因里带 503').toMatch(/^[^|]+\|1\|FAIL\|.*503/)
      expect(rows[1], '第 2 行应是 attempt=2 的 OK').toMatch(/^[^|]+\|2\|OK\|/)

      // 断言四：平台侧受理了几次 = 我们记了几行成功（503 那次没被平台受理，成功那次才落库）
      const state = await (await request.get(`${MOCK}/mock/douyin/state`)).json()
      expect(state.shippedCount, '抖音侧只应受理 1 条发货（第一次 503 没被受理）').toBe(1)
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE id > ${baseline}
              AND action = 'SHIP_PUSH' AND result = 'OK'`),
        '成功行数应与平台侧受理次数一致'
      ).toBe(state.shippedCount)
    })

    // ====================================================================== ⑤
    await test.step('⑤ 再点一次回传（幂等命中）→ 库里零新增、老两行逐字不变、接口回报 skipped=1', async () => {
      const before = shipPushRowsOf(deliveryId)
      expect(before.length, '上一阶段应已留下 2 行').toBe(2)

      const [pushResp] = await Promise.all([
        page.waitForResponse(
          (r) => r.request().method() === 'POST'
            && new URL(r.url()).pathname === '/admin/channel/push-shipment',
          { timeout: 30_000 }
        ),
        tid(page, 'order-push-shipment-btn').click()
      ])
      const pushBody = await pushResp.json()
      expect(pushBody.code, '重复回传接口应正常返回（幂等命中不是错误）').toBe(0)

      const after = shipPushRowsOf(deliveryId)
      // 为什么是「零新增」而不是「追加一行 SKIP」——这是后端的设计，不是没生效：
      //   uk_sync_idem = {shopId}:SHIP_PUSH:{refId}:{attempt}，人工再点一次时 attempt 又从 1 开始，
      //   必然撞上第一次那条 attempt=1 的行 ⇒ ChannelSyncLogMapper#insertOrTouch 走 ODKU 空转分支
      //   ⇒ 不插入、不报错、不覆盖，ChannelSyncLogWriter.write() 返回 false（计为 skipped）。
      //   即「同一个包裹的同一次尝试被重放（人工连点）只会留下第一行」——幂等闸挡在唯一索引上。
      //   （原断言写的是"追加一行 SKIP"，那等于在考"幂等闸失灵"，方向错了）
      expect(after.length, '重复回传不应在库里追加任何行（幂等闸挡在 uk_sync_idem 上）').toBe(before.length)
      // 「老行一条不改」的硬证据：全列快照逐字相同（id/attempt/result/reason/耗时/报文/时间戳全比）
      expect(after, '老的两行必须逐字不变（不是重写历史）').toEqual(before)
      expect(pushBody.data?.skipped, '重复回传应回报 skipped=1（幂等命中，不是错误）').toBe(1)
      expect(pushBody.data?.ok, '重复回传不应再算一次成功').toBe(0)

      // 平台侧：没有第二次受理（不重复发）
      const state = await (await request.get(`${MOCK}/mock/douyin/state`)).json()
      expect(state.shippedCount, '重复回传不应让平台侧再多收一条').toBe(1)
      expect(
        new Set(state.shipped.map((s) => s.platformSn)).size,
        '平台侧同一平台单号只应有一条受理记录'
      ).toBe(1)
    })
  })

  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[并发与竞态 / 重复提交与幂等 / 平台重推与503重试] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
  })
})
