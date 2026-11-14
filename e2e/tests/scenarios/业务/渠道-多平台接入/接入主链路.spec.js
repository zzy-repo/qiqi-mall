import { spawn } from 'node:child_process'
import { test, expect } from '../../../support/test.js'
import { API, ADMIN, stamp } from '../../../support/env.js'
import { memberRegisterAndLogin, addSpecToCart, submitCartOrder } from '../../../support/ui.js'
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
import { memberNickOf, receiverOf } from '../../../support/fixtures.js'
import { memberLoginOf } from '../../../support/member-login.js'
import { queryDb, dbOfApi } from '../../../support/db.js'

/**
 * 业务 / 渠道-多平台接入 / 接入主链路
 *
 * 闭环（只这一条链，不做策略、不做库存/退款同步、不做对账）：
 *
 *   三个店铺的平台订单 → 统一模型 PlatformOrder → 落成待审核订单(status=7，复用现有审核)
 *   → 审核通过 → 拆单 → 出库 → 回传发货到平台 → 全程写 channel_sync_log
 *
 * 五个阶段（与交付报告一一对应）：
 *   ① 起 mock 服务 → 三家「立即拉单」→ 4 笔 status=7（淘宝2 / 抖音1 / 商城1）+ map 4 行
 *   ② 再点一次（mock 重推同一批）→ 订单数不变 + sync_log 有 result=SKIP / reason=IDEMPOTENT
 *   ③ 审核通过一笔 → 拆单（自动）→ 下发 → 拣货 → 出库 → 点「回传发货」→ 平台侧 shipped=1 + sync_log OK
 *   ④ mock 注入 503 → 回传 → FAIL(attempt=1) 行 + 重试 OK(attempt=2) 行
 *   ⑤ 日志页看得见失败原因与重试次数（UI 断言）
 *
 * 断言口径：**直查库优先**（channel_order_map / order / channel_sync_log 是唯一事实来源），
 * UI 断言只用在「后台这一页真的把事实显示出来了」这件事上（阶段⑤）。
 *
 * 三个刻意的测试手法（都有原因，别照抄成坏习惯）：
 *  1) mock 服务由本 spec 用**精确 PID** 起停（`spawn` + `kill(SIGTERM)`，绝不 pkill 宽匹配）；
 *     `MOCK_RUN_ID` 每次唯一 → 平台单号每次都是一批新单，重复跑不会撞上一轮的映射；
 *  2) 商城那笔单来自**真实买家下单支付**（不是造假数据）→ 证明「商城开放接口读的是真订单」；
 *  3) 拉单前把 MALL 店铺水位钉到「刚创建那笔单的支付时间前 1 秒」：
 *     e2e 库里有别的场景留下的历史已付款单，不钉水位的话它们会被一起拉进来，
 *     「商城 1 笔」这条断言就成了「库里恰好有几笔」的运气，而不是本用例的结论。
 */
test.describe('业务 › 渠道-多平台接入 › 接入主链路', () => {
  const MOCK_PORT = 19090
  const MOCK = `http://127.0.0.1:${MOCK_PORT}`
  /** 本轮的批次标识：拼进 mock 平台单号，保证「这批单」每次运行都不同 */
  const RUN_ID = `E2E${stamp()}`

  let mockProcess = null

  /** 精确起停：只杀自己 spawn 的那个进程 */
  test.afterAll(async () => {
    if (mockProcess) {
      mockProcess.kill('SIGTERM')
      mockProcess = null
    }
  })

  const num = (sql) => Number(queryDb(sql) || 0)
  const one = (sql) => queryDb(sql)
  const inList = (values) => values.map((v) => `'${String(v).replace(/'/g, "''")}'`).join(', ')

  test('三平台拉单→待审核→审核/拆单/出库→回传发货（含幂等与失败重试）', async ({ page, request }) => {
    // 本用例收集到的事实，阶段之间传递
    const pullResults = {}
    const batchSns = []
    let logBaseline = 0

    // ====================================================================== ①
    await test.step('① 起 mock 服务 → 三家「立即拉单」→ 4 笔待审核订单 + 4 行映射', async () => {
      // ---- 真实 SKU：mock 的商品编码要指向库里真存在的 sku，否则会走 SKU_NOT_MATCHED 分支 ----
      const { skus } = await getProductApi(request, 1)
      const skuA = skus[0]
      const skuB = skus[1] || skus[0]
      const skuIds = [skuA.id, skuB.id]

      // ---- 起 mock 服务（独立端口 19090，不进 compose；零依赖单文件）----
      mockProcess = spawn(
        process.execPath,
        [new URL('../../../../../mock-platform/server.mjs', import.meta.url).pathname],
        {
          env: {
            ...process.env,
            MOCK_PORT: String(MOCK_PORT),
            MOCK_RUN_ID: RUN_ID,
            MOCK_SKU_IDS: skuIds.join(',')
          },
          stdio: 'pipe'
        }
      )
      mockProcess.stdout.on('data', (d) => process.stdout.write(`[mock] ${d}`))
      mockProcess.stderr.on('data', (d) => process.stderr.write(`[mock!] ${d}`))

      // 等健康（最多 15s）：起不来就直接红，不要让后面的断言去猜「为什么一单都没拉回来」
      let healthy = false
      for (let i = 0; i < 30; i++) {
        const res = await request.get(`${MOCK}/health`).catch(() => null)
        if (res && res.ok()) {
          healthy = true
          break
        }
        await page.waitForTimeout(500)
      }
      expect(healthy, `mock 平台服务应在 ${MOCK} 起来（端口被占？见 mock-platform/README.md）`).toBeTruthy()
      const health = await (await request.get(`${MOCK}/health`)).json()
      expect(health.runId, 'mock 批次号应等于本轮 RUN_ID').toBe(RUN_ID)

      // ---- 管理员登录（后台走真实登录页）----
      await adminLoginHardened(page)

      // ---- 商城那笔单：真实买家下单 + 支付（支付后即待审核 status=7）----
      const buyer = memberLoginOf(stamp())
      await memberRegisterAndLogin(page, buyer, '123456', memberNickOf(buyer))
      await addSpecToCart(page, 1, skuA.specText, skuA.id)
      const mallOrderSn = await submitCartOrder(page, receiverOf(buyer))
      expect(mallOrderSn, '商城买家下单后应拿到订单号').toBeTruthy()

      // ---- 把 MALL 店铺的水位钉到「这笔商城单支付前 1 秒」（见文件头第 3 条手法）----
      queryDb(
        `UPDATE channel_shop SET watermark_at = (SELECT DATE_SUB(payment_time, INTERVAL 1 SECOND) `
        + `FROM \`order\` WHERE order_sn = '${mallOrderSn}') WHERE platform_type = 'MALL'`
      )
      expect(
        one(`SELECT watermark_at IS NOT NULL FROM channel_shop WHERE platform_type='MALL'`),
        'MALL 店铺水位应被钉住（否则说明这笔商城单没查到）'
      ).toBe('1')

      // ---- 进后台这一页：三张店铺卡 ----
      await page.goto(`${ADMIN}/channel`)
      for (const platform of ['MALL', 'TAOBAO_MOCK', 'DOUYIN_MOCK']) {
        await expect(tid(page, `channel-shop-card-${platform}`), `${platform} 店铺卡应渲染`).toBeVisible()
      }
      await expect(tid(page, 'channel-sync-log-table'), '同步日志表应渲染').toBeVisible()

      logBaseline = num('SELECT COALESCE(MAX(id), 0) FROM channel_sync_log')

      // ---- 依次点三家「立即拉单」，把拉单响应抓下来（created/skipped/failed 是服务端给的事实）----
      for (const platform of ['MALL', 'TAOBAO_MOCK', 'DOUYIN_MOCK']) {
        const [response] = await Promise.all([
          page.waitForResponse(
            (r) => r.request().method() === 'POST'
              && /\/admin\/channel-shops\/\d+\/pull$/.test(new URL(r.url()).pathname),
            { timeout: 20_000 }
          ),
          tid(page, `channel-pull-btn-${platform}`).click()
        ])
        const body = await response.json()
        expect(body.code, `${platform} 拉单接口应返回成功`).toBe(0)
        pullResults[platform] = body.data
        batchSns.push(...body.data.platformSns)
        await expectToastSuccess(page)
      }

      // 三家各拉回几单：商城 1 / 淘宝 2 / 抖音 1（这是服务端回报的 created）
      expect(pullResults.MALL.created, '商城应新建 1 笔').toBe(1)
      expect(pullResults.TAOBAO_MOCK.created, '淘宝应新建 2 笔').toBe(2)
      expect(pullResults.DOUYIN_MOCK.created, '抖音应新建 1 笔').toBe(1)
      expect(batchSns.length, '本批平台单号应为 4 个').toBe(4)
      // 淘宝那 2 笔里有一笔是 2 条明细（价格/数量快照要落对）
      expect(pullResults.TAOBAO_MOCK.total, '淘宝返回 2 单').toBe(2)

      // ---- 直查库：4 行映射 + 4 笔 status=7 ----
      const snsSql = inList(batchSns)
      expect(
        num(`SELECT COUNT(*) FROM channel_order_map WHERE platform_sn IN (${snsSql})`),
        'channel_order_map 应有本批 4 行（uk_map 幂等闸门写入）'
      ).toBe(4)
      expect(
        num(`SELECT COUNT(*) FROM channel_order_map m JOIN \`order\` o ON o.id = m.order_id `
          + `WHERE m.platform_sn IN (${snsSql}) AND o.status = 7`),
        '4 笔映射对应的订单都应是 status=7 待审核'
      ).toBe(4)
      // 平台分布：抖音1 / 商城1 / 淘宝2（逐平台 COUNT，不用 GROUP_CONCAT —— 拼接顺序不是断言的一部分）
      for (const [platform, expected] of [['MALL', 1], ['TAOBAO_MOCK', 2], ['DOUYIN_MOCK', 1]]) {
        expect(
          num(`SELECT COUNT(*) FROM channel_order_map m JOIN channel_shop s ON s.id = m.shop_id `
            + `WHERE m.platform_sn IN (${snsSql}) AND s.platform_type = '${platform}'`),
          `${platform} 应落 ${expected} 笔`
        ).toBe(expected)
      }

      // 落单字段快照：渠道名、店铺名、平台买家
      expect(
        one(`SELECT GROUP_CONCAT(DISTINCT o.channel ORDER BY o.channel) FROM channel_order_map m `
          + `JOIN \`order\` o ON o.id = m.order_id WHERE m.platform_sn IN (${snsSql})`),
        'order.channel 应记下各自的平台（DOUYIN/TAOBAO/MALL）'
      ).toBe('DOUYIN,MALL,TAOBAO')
      expect(
        num(`SELECT COUNT(*) FROM channel_order_map m JOIN \`order\` o ON o.id = m.order_id `
          + `WHERE m.platform_sn IN (${snsSql}) AND o.member_id = `
          + `(SELECT id FROM member WHERE username = 'channel_platform_buyer')`),
        '渠道单统一挂「平台买家」账号（order.member_id 是 NOT NULL + 外键）'
      ).toBe(4)

      // 明细快照（价格/名称来自平台，不是重算的本地价）
      expect(
        num(`SELECT COUNT(*) FROM channel_order_map m JOIN order_item i ON i.order_id = m.order_id `
          + `WHERE m.platform_sn IN (${snsSql})`),
        '4 笔单共 5 条明细（淘宝第二单 2 条）'
      ).toBe(5)

      // 汇总行：一次外呼一行 PULL OK
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE id > ${logBaseline} AND action='PULL' AND result='OK'`),
        '三家各有一条 PULL 汇总行（OK）'
      ).toBeGreaterThanOrEqual(3)
    })

    // ====================================================================== ②
    await test.step('② 再点一次（mock 重推同一批）→ 订单数不变 + sync_log 有 SKIP', async () => {
      const ordersBefore = num(
        `SELECT COUNT(*) FROM channel_order_map WHERE platform_sn IN (${inList(batchSns)})`
      )
      const skipBaseline = num('SELECT COALESCE(MAX(id), 0) FROM channel_sync_log')

      for (const platform of ['TAOBAO_MOCK', 'DOUYIN_MOCK', 'MALL']) {
        const [response] = await Promise.all([
          page.waitForResponse(
            (r) => r.request().method() === 'POST'
              && /\/admin\/channel-shops\/\d+\/pull$/.test(new URL(r.url()).pathname),
            { timeout: 20_000 }
          ),
          tid(page, `channel-pull-btn-${platform}`).click()
        ])
        const body = await response.json()
        expect(body.code, `${platform} 第二次拉单应成功`).toBe(0)
        expect(body.data.created, `${platform} 第二次拉单不应再新建订单`).toBe(0)
      }

      // 订单数不变：映射行数、待审核订单数都不变
      expect(
        num(`SELECT COUNT(*) FROM channel_order_map WHERE platform_sn IN (${inList(batchSns)})`),
        '重复拉单后映射行数不变（uk_map 挡住）'
      ).toBe(ordersBefore)
      expect(
        num(`SELECT COUNT(*) FROM channel_order_map m JOIN \`order\` o ON o.id = m.order_id `
          + `WHERE m.platform_sn IN (${inList(batchSns)}) AND o.status = 7`),
        '重复拉单后订单数不变（仍 4 笔待审核）'
      ).toBe(4)

      // skip 证据：mock 重推的 3 张单各一行 SKIP / IDEMPOTENT
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE id > ${skipBaseline} `
          + `AND action = 'PULL' AND result = 'SKIP' AND reason = 'IDEMPOTENT'`),
        '重推的每一单都应留一行 SKIP / IDEMPOTENT'
      ).toBeGreaterThanOrEqual(3)
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE id > ${skipBaseline} AND action='PULL' AND result='FAIL'`),
        '重推不应产生 FAIL'
      ).toBe(0)
    })

    // ====================================================================== ③
    await test.step('③ 审核通过一笔 → 拆单 → 出库 → 回传发货 → 平台侧 shipped=1 + sync_log OK', async () => {
      const orderId = pullResults.TAOBAO_MOCK.orderIds[0]
      expect(orderId, '淘宝第一笔应拿到内部订单 id').toBeTruthy()
      const orderSn = one(`SELECT order_sn FROM \`order\` WHERE id = ${orderId}`)

      await gotoOrderList(page)
      let row = await searchOrderRow(page, orderSn)
      await auditPassOrder(page, row)
      await expectToastSuccess(page)
      // 审核通过 = 待审核(7) → 待发货(8)，并自动拆单生成包裹
      expect(num(`SELECT status FROM \`order\` WHERE id = ${orderId}`), '审核通过后应为待发货(8)').toBe(8)

      const delivery = one(
        `SELECT CONCAT(id, ':', delivery_sn, ':', status) FROM delivery_order WHERE order_id = ${orderId} `
        + `ORDER BY id LIMIT 1`
      )
      const [deliveryId, deliverySn] = delivery.split(':')
      expect(Number(delivery.split(':')[2]), '拆单生成了 1 个待下发包裹(0)').toBe(0)

      row = await searchOrderRow(page, orderSn)
      const table = await openOrderDrawer(page, row)
      await clickDeliveryAction(page, deliveryRowBySn(table, deliverySn), '下发')
      await expectToastSuccess(page)
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '拣货')
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '出库')
      expect(
        num(`SELECT status FROM delivery_order WHERE id = ${deliveryId}`),
        '出库后包裹应为已出库(3)，并生成运单号'
      ).toBe(3)
      expect(
        one(`SELECT track_no IS NOT NULL FROM delivery_logistics WHERE delivery_id = ${deliveryId}`),
        '出库应写运单号（回传发货要用它）'
      ).toBe('1')

      // 抽屉里的「回传发货」按钮（这一笔是渠道单，有 platform_sn 映射）。
      // 必须等接口响应再断言平台侧状态：只等 toast 会读到「上一次成功的残留提示」，
      // 于是回传还在飞的时候就先去查平台，必然读到 0（实测踩过）。
      const [pushResp] = await Promise.all([
        page.waitForResponse(
          (r) => r.request().method() === 'POST'
            && new URL(r.url()).pathname === '/admin/channel/push-shipment',
          { timeout: 30_000 }
        ),
        tid(page, 'order-push-shipment-btn').click()
      ])
      const pushBody = await pushResp.json()
      expect(pushBody.code, '回传发货接口应成功').toBe(0)
      expect(pushBody.data.ok, '1 个包裹应回传成功').toBe(1)
      expect(pushBody.data.platformSn, '回传用的应是平台单号').toBe(pullResults.TAOBAO_MOCK.platformSns[0])
      await expectToastSuccess(page)

      // 平台侧事实：真的收到 1 条发货（不是只信我们库里的日志）
      const state = await (await request.get(`${MOCK}/mock/taobao/state`)).json()
      expect(state.shippedCount, 'mock 淘宝侧应收到 1 条发货').toBe(1)
      expect(state.shipped[0].platformSn, '回传用的应是平台单号').toBe(pullResults.TAOBAO_MOCK.platformSns[0])

      // 我们这边：SHIP_PUSH OK 一行
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE action='SHIP_PUSH' AND result='OK' `
          + `AND ref_type='DELIVERY' AND ref_id = ${deliveryId} AND attempt = 1`),
        '回传成功应留一行 SHIP_PUSH/OK（attempt=1）'
      ).toBe(1)
    })

    // ====================================================================== ④
    await test.step('④ mock 注入 503 → 回传 → FAIL 行 + 重试 OK 行（attempt=2）', async () => {
      const orderId = pullResults.DOUYIN_MOCK.orderIds[0]
      const orderSn = one(`SELECT order_sn FROM \`order\` WHERE id = ${orderId}`)

      await gotoOrderList(page)
      let row = await searchOrderRow(page, orderSn)
      await auditPassOrder(page, row)
      await expectToastSuccess(page)

      const deliveryId = Number(
        one(`SELECT id FROM delivery_order WHERE order_id = ${orderId} ORDER BY id LIMIT 1`)
      )
      const deliverySn = one(`SELECT delivery_sn FROM delivery_order WHERE id = ${deliveryId}`)

      row = await searchOrderRow(page, orderSn)
      const table = await openOrderDrawer(page, row)
      await clickDeliveryAction(page, deliveryRowBySn(table, deliverySn), '下发')
      await expectToastSuccess(page)
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '拣货')
      await wmsCallback(page, deliveryRowBySn(table, deliverySn), '出库')
      expect(num(`SELECT status FROM delivery_order WHERE id = ${deliveryId}`), '包裹应已出库').toBe(3)

      // 注入：接下来 1 次外呼回 503（第 1 次失败、重试成功 —— 正好验证重试记账）
      const injected = await request.post(`${MOCK}/mock/_admin/fail?code=503&count=1`)
      expect(injected.ok(), '故障注入接口应可用').toBeTruthy()

      const logBaseline2 = num('SELECT COALESCE(MAX(id), 0) FROM channel_sync_log')
      const [pushResp] = await Promise.all([
        page.waitForResponse(
          (r) => r.request().method() === 'POST'
            && new URL(r.url()).pathname === '/admin/channel/push-shipment',
          { timeout: 30_000 }
        ),
        tid(page, 'order-push-shipment-btn').click()
      ])
      const pushBody = await pushResp.json()
      expect(pushBody.code, '回传发货接口应成功（重试后）').toBe(0)
      expect(pushBody.data.ok, '重试一次后应成功').toBe(1)

      // 两行：attempt=1 FAIL（带 503）+ attempt=2 OK
      const rows = queryDb(
        `SELECT CONCAT(attempt, '|', result, '|', COALESCE(reason, '')) FROM channel_sync_log `
        + `WHERE action='SHIP_PUSH' AND ref_id = ${deliveryId} AND id > ${logBaseline2} ORDER BY id`
      ).split('\n').filter(Boolean)
      expect(rows.length, '失败重试共留 2 行日志（第 1 次失败 + 第 2 次成功）').toBe(2)
      expect(rows[0], '第 1 行应是 attempt=1 的 FAIL，原因里带 503').toMatch(/^1\|FAIL\|.*503/)
      expect(rows[1], '第 2 行应是 attempt=2 的 OK').toMatch(/^2\|OK\|/)

      // 平台侧最终只收到 1 条（第一次 503 没落库，第二次成功）
      const state = await (await request.get(`${MOCK}/mock/douyin/state`)).json()
      expect(state.shippedCount, '抖音侧最终应收到 1 条发货').toBe(1)
    })

    // ====================================================================== ⑤
    await test.step('⑤ 日志页看得见失败原因与重试次数（UI 断言）', async () => {
      const failLogId = Number(one(
        `SELECT id FROM channel_sync_log WHERE action='SHIP_PUSH' AND result='FAIL' `
        + `ORDER BY id DESC LIMIT 1`
      ))
      const okRetryLogId = Number(one(
        `SELECT id FROM channel_sync_log WHERE action='SHIP_PUSH' AND result='OK' AND attempt = 2 `
        + `ORDER BY id DESC LIMIT 1`
      ))
      expect(failLogId, '库里应有一行 SHIP_PUSH/FAIL').toBeTruthy()
      expect(okRetryLogId, '库里应有一行 SHIP_PUSH/OK attempt=2').toBeTruthy()

      await page.goto(`${ADMIN}/channel`)
      await expect(tid(page, 'channel-sync-log-table'), '同步日志表应渲染').toBeVisible()

      // 只看失败的：下拉选「失败」
      await tid(page, 'channel-log-filter-result').click()
      await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '失败' }).first().click()
      await page.waitForTimeout(800)

      const failRow = tid(page, `channel-log-row-${failLogId}`)
      await expect(failRow, '筛选「失败」后应能看到那一行日志').toBeVisible()
      await expect(
        page.getByTestId(`channel-log-reason-${failLogId}`),
        '失败原因列应显示 503 这类具体原因（不是「失败」两个字）'
      ).toContainText('503')

      // 重试次数：attempt=2 的那行 → 重试次数显示 1（UI 口径：attempt - 1）
      await tid(page, 'channel-log-filter-result').click()
      await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '成功' }).first().click()
      await page.waitForTimeout(800)
      await expect(
        page.getByTestId(`channel-log-attempt-${okRetryLogId}`),
        '重试成功的行应显示「重试次数 = 1」'
      ).toHaveText('1')

      // 顺手把店铺维度也核一遍：卡片上的「今日成功/失败」不是 0
      const douyinShopId = num("SELECT id FROM channel_shop WHERE platform_type='DOUYIN_MOCK'")
      expect(
        num(`SELECT COUNT(*) FROM channel_sync_log WHERE shop_id = ${douyinShopId} AND result = 'OK'`),
        '抖音店铺应至少有 1 条成功日志（卡片上应显示非 0）'
      ).toBeGreaterThanOrEqual(1)
    })
  })

  // 让「库名」在失败信息里可见：断言红了能一眼看出打的是哪个分片库
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 渠道-多平台接入 / 接入主链路] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
  })
})
