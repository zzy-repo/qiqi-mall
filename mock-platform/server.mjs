#!/usr/bin/env node
/**
 * mock 平台服务（淘宝 / 抖音）—— **独立单文件、零依赖、不进 docker compose**。
 *
 * 它是「外部电商平台」的替身，给接入层两件事：
 *   GET  /mock/{taobao|douyin}/orders?since=...   拉单（平台格式 JSON）
 *   POST /mock/{taobao|douyin}/ship               回传发货（同运单号重复回传 → code=DUPLICATE）
 *
 * 三个刻意的能力（都是为了**证伪**，不是为了炫技）：
 *   ① 同一批单重复返回：每批 sn 在进程启动时生成一次，于是「再点一次拉单」必然重推同一批，
 *      幂等闸门有没有真的挡住，一看便知；
 *   ② 故障注入：`?fail=503` 让**本次**请求直接 503；
 *      `POST /mock/_admin/fail?code=503&count=1` 让**接下来 N 次**外呼失败
 *      —— count=1 正好对应「第一次失败、重试成功」，用来验证重试与 attempt 记账；
 *   ③ 状态查询：`GET /mock/{platform}/state` 给 e2e 断言「平台侧真的收到了几次回传」，
 *      而不是只信我们自己库里的日志。
 *
 * 启动（见同目录 README.md）：
 *   MOCK_RUN_ID=$(date +%s) MOCK_SKU_IDS=1,2,3 node mock-platform/server.mjs
 *
 * 环境变量：
 *   MOCK_PORT       监听端口，默认 19090（**独立端口**，与后端/前端/容器栈都不冲突）
 *   MOCK_RUN_ID     批次标识，默认进程启动时间戳；拼进平台单号，保证每次启动是「一批新单」
 *   MOCK_SKU_IDS    平台商品编码用到的本地 sku.id 列表（逗号分隔），默认 "1,2,3"
 *   MOCK_TAOBAO_ORDERS / MOCK_DOUYIN_ORDERS   两个平台各生成几单，默认 2 / 1
 */
import http from 'node:http'

const PORT = Number(process.env.MOCK_PORT || 19090)
const RUN_ID = process.env.MOCK_RUN_ID || String(Date.now())
const SKU_IDS = (process.env.MOCK_SKU_IDS || '1,2,3')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)
const TAOBAO_COUNT = Number(process.env.MOCK_TAOBAO_ORDERS || 2)
const DOUYIN_COUNT = Number(process.env.MOCK_DOUYIN_ORDERS || 1)

/** 平台路径段 → channel_shop.platform_type */
const PLATFORMS = {
  taobao: { platformType: 'TAOBAO_MOCK', snPrefix: 'TB', channel: 'TAOBAO' },
  douyin: { platformType: 'DOUYIN_MOCK', snPrefix: 'DY', channel: 'DOUYIN' }
}

/**
 * 平台时间口径：本地时间（Asia/Shanghai，UTC+8 固定，本服务只服务本地演示，无夏令时）。
 * 刻意不用 toISOString()：那给的是 UTC，接入层按 LocalDateTime 落库会整整差 8 小时，
 * 排查时表现为「平台说 18:56 付的，我们库里是 10:56」，很容易被当成时钟漂移。
 */
const isoNow = (offsetSeconds = 0) => {
  const t = new Date(Date.now() + offsetSeconds * 1000 + 8 * 3600 * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${t.getUTCFullYear()}-${p(t.getUTCMonth() + 1)}-${p(t.getUTCDate())}`
    + `T${p(t.getUTCHours())}:${p(t.getUTCMinutes())}:${p(t.getUTCSeconds())}`
}

/**
 * 一批固定订单：进程生命周期内**永不改变**（重复拉单就是为了验幂等）。
 * 每单 1~2 条明细，商品编码用 SKU-<id>，价格/数量与商城无关（平台自己说了算）。
 */
function buildBatch(platform, count) {
  const orders = []
  for (let i = 1; i <= count; i++) {
    const skuA = SKU_IDS[(i - 1) % Math.max(SKU_IDS.length, 1)] || '1'
    const skuB = SKU_IDS[i % Math.max(SKU_IDS.length, 1)] || skuA
    const items = [
      { platformSkuCode: `SKU-${skuA}`, productName: `${platform.platformType} 商品A`, qty: 1, price: 99.0 + i },
      ...(i % 2 === 0
        ? [{ platformSkuCode: `SKU-${skuB}`, productName: `${platform.platformType} 商品B`, qty: 2, price: 19.9 }]
        : [])
    ]
    orders.push({
      platformSn: `${platform.snPrefix}-${RUN_ID}-${i}`,
      channel: platform.channel,
      buyerName: `${platform.channel}买家${i}`,
      receiverName: `收货人${i}`,
      receiverPhone: `1390000000${i}`,
      receiverAddress: `浙江省杭州市余杭区 mock 路 ${i} 号`,
      totalAmount: Number(items.reduce((sum, it) => sum + it.price * it.qty, 0).toFixed(2)),
      paidAt: isoNow(-60 * i),
      items
    })
  }
  return orders
}

const BATCHES = {
  taobao: buildBatch(PLATFORMS.taobao, TAOBAO_COUNT),
  douyin: buildBatch(PLATFORMS.douyin, DOUYIN_COUNT)
}

/** 运行期状态：拉单次数 + 已回传的发货（按 platform → trackNo 去重） */
const STATE = {
  pulls: { taobao: 0, douyin: 0 },
  shipped: { taobao: [], douyin: [] }
}

/** 全局故障注入队列：{ code, remaining }。?fail= 是「这一次」，这里是「接下来 N 次」 */
let injectedFail = null
/** 注入一张 SKU 对不上的单（演示 SKU_NOT_MATCHED 分支） */
let injectBadSku = false

const json = (res, status, body) => {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json;charset=utf-8',
    'Content-Length': Buffer.byteLength(payload)
  })
  res.end(payload)
}

const readBody = (req) =>
  new Promise((resolve) => {
    let raw = ''
    req.on('data', (chunk) => {
      raw += chunk
    })
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {})
      } catch {
        resolve({})
      }
    })
  })

/** 本次请求要不要失败：?fail= 优先，其次全局队列（消费一次） */
function takeFailure(url) {
  const forced = url.searchParams.get('fail')
  if (forced) return Number(forced) || 503
  if (injectedFail && injectedFail.remaining > 0) {
    injectedFail.remaining -= 1
    return injectedFail.code
  }
  return 0
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const path = url.pathname
  const method = req.method || 'GET'

  if (method === 'GET' && path === '/health') {
    return json(res, 200, {
      ok: true,
      runId: RUN_ID,
      platforms: Object.keys(PLATFORMS),
      batches: { taobao: BATCHES.taobao.map((o) => o.platformSn), douyin: BATCHES.douyin.map((o) => o.platformSn) }
    })
  }

  // ---- 控制面（测试/演示用）：故障注入与状态重置 ----
  if (method === 'POST' && path === '/mock/_admin/fail') {
    const code = Number(url.searchParams.get('code') || 503)
    const count = Number(url.searchParams.get('count') || 1)
    injectedFail = { code, remaining: count }
    return json(res, 200, { ok: true, code, count })
  }
  if (method === 'POST' && path === '/mock/_admin/fail/clear') {
    injectedFail = null
    return json(res, 200, { ok: true })
  }
  if (method === 'POST' && path === '/mock/_admin/bad-sku') {
    injectBadSku = url.searchParams.get('on') !== '0'
    return json(res, 200, { ok: true, badSku: injectBadSku })
  }
  if (method === 'POST' && path === '/mock/_admin/reset') {
    injectedFail = null
    injectBadSku = false
    STATE.pulls.taobao = 0
    STATE.pulls.douyin = 0
    STATE.shipped.taobao = []
    STATE.shipped.douyin = []
    return json(res, 200, { ok: true })
  }
  if (method === 'GET' && path.startsWith('/mock/_admin/state')) {
    return json(res, 200, { ok: true, runId: RUN_ID, pulls: STATE.pulls, shipped: STATE.shipped, injectedFail, injectBadSku })
  }

  const match = path.match(/^\/mock\/(taobao|douyin)\/(orders|ship|state)$/)
  if (!match) return json(res, 404, { ok: false, code: 'NOT_FOUND', message: `未知路径 ${path}` })

  const [, platformKey, action] = match
  const meta = PLATFORMS[platformKey]
  const failure = takeFailure(url)
  if (failure && action !== 'state') {
    // 故障注入：非 2xx + 平台风格错误体（接入层据此记 FAIL 并把 body 写进日志）
    return json(res, failure, {
      ok: false,
      code: `HTTP_${failure}`,
      message: `mock 平台注入故障：${failure}`
    })
  }

  if (action === 'state' && method === 'GET') {
    return json(res, 200, {
      ok: true,
      platform: platformKey,
      platformType: meta.platformType,
      pulls: STATE.pulls[platformKey],
      shipped: STATE.shipped[platformKey],
      shippedCount: STATE.shipped[platformKey].length
    })
  }

  if (action === 'orders' && method === 'GET') {
    STATE.pulls[platformKey] += 1
    const orders = BATCHES[platformKey].map((o) => ({ ...o }))
    if (injectBadSku) {
      // 只多给一张「商品编码对不上」的单：它必须被跳过（FAIL/SKU_NOT_MATCHED），且不影响同批其它单
      orders.push({
        platformSn: `${meta.snPrefix}-${RUN_ID}-BAD`,
        channel: meta.channel,
        buyerName: `${meta.channel}买家X`,
        receiverName: '收货人X',
        receiverPhone: '13900000000',
        receiverAddress: '浙江省杭州市余杭区 mock 路 99 号',
        totalAmount: 1.0,
        paidAt: isoNow(-5),
        items: [{ platformSkuCode: 'SKU-NOT-EXIST-999999', productName: '平台独有的不存在商品', qty: 1, price: 1.0 }]
      })
    }
    // since 只用于说明「平台支持增量」，本 mock 的批次是固定的：重复拉单必须返回同一批（这就是幂等测试的前提）
    void url.searchParams.get('since')
    return json(res, 200, {
      platformType: meta.platformType,
      shopCode: url.searchParams.get('shopCode'),
      orders
    })
  }

  if (action === 'ship' && method === 'POST') {
    const body = await readBody(req)
    const { platformSn, carrier, trackNo } = body || {}
    if (!platformSn) return json(res, 400, { ok: false, code: 'PLATFORM_SN_REQUIRED', message: '缺少 platformSn' })
    const already = STATE.shipped[platformKey].find(
      (s) => s.platformSn === platformSn && s.trackNo === (trackNo || null)
    )
    if (already) {
      // 平台侧已存在：不是失败，是幂等命中（接入层据此记 SKIP）
      return json(res, 200, { ok: true, code: 'DUPLICATE', message: '该运单号已回传过（幂等命中）' })
    }
    const record = {
      platformSn,
      carrier: carrier || null,
      trackNo: trackNo || null,
      shippedAt: isoNow(),
      receivedAt: new Date().toISOString()
    }
    STATE.shipped[platformKey].push(record)
    return json(res, 200, { ok: true, code: 'OK', message: '受理成功', record })
  }

  return json(res, 405, { ok: false, code: 'METHOD_NOT_ALLOWED', message: `${method} ${path}` })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock-platform] listening on http://127.0.0.1:${PORT} runId=${RUN_ID}`)
  console.log(`[mock-platform] taobao batch: ${BATCHES.taobao.map((o) => o.platformSn).join(', ')}`)
  console.log(`[mock-platform] douyin batch: ${BATCHES.douyin.map((o) => o.platformSn).join(', ')}`)
})

// 精确退场：只关自己（e2e 与手工都用 SIGTERM/SIGINT），不用 pkill 宽匹配
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log(`[mock-platform] ${sig} received, closing`)
    server.close(() => process.exit(0))
  })
}
