/**
 * 自动审核模块的**只读**接口访问 —— 只发 GET，用来核对「服务端事实」（队列行 / 时间线 / 策略）。
 *
 * 三条边界（与 support/assert.js 一致）：
 *  1) 本文件**不**发 run / freeze / unfreeze / 策略改动 —— 那些必须走 UI 点击（被测对象）；
 *  2) 每个调用自带 HTTP 200 + body.code === 0 校验，失败抛中文错误并带服务端原文，
 *     造数/读数的失败必须是响亮的红，不能悄悄退化成空数组让断言变成「测空气」；
 *  3) 返回的是**后端原样字段**（队列是 snake_case，来自 MyBatis 的 Map；trail 是 segment/aligned），
 *     不做前端那套归一化 —— 断言要看的就是「服务端到底给了什么」。
 *
 * 为什么还要单独读接口：有两件事页面**不显示**（已作为缺口登记）：
 *   · 队列行的 `would_be_auto_policy_name`（在前端 mapQueueRow 里被丢掉）；
 *   · trail 条目的 `aligned.matchedBy / aligned.strong`（前端 mapTrailEntry 只留 raw）。
 * 这些结论只能从接口 + 库证明，页面不背这个锅。
 */
import { API } from './env.js'

const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

async function data(res, what) {
  const status = res.status()
  const body = await res.json().catch(() => ({}))
  if (status !== 200 || body.code !== 0) {
    throw new Error(`${what} 失败：HTTP ${status} code=${body.code} message=${body.message || '(空)'}`)
  }
  return body.data
}

/** 人工队列：{total, page, size, rows[]}；rows 是 snake_case（order_sn / matched_policy_name / miss_reasons…） */
export async function getAuditQueue(request, adminToken, { result, page = 1, size = 100 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (result) params.set('result', result)
  return data(
    await request.get(`${API}/admin/order-audit/queue?${params.toString()}`, auth(adminToken)),
    '读取审核队列'
  )
}

/**
 * 按订单号取队列行（每单最新一轮）。
 * 一次拉 100 行：本套用例只关心自己这六笔单，队列总数在 shared 库里可能很大，不做全量翻页。
 *
 * ⚠️ 行里的键是**队列 SQL 的列名/别名**（snake_case），两个容易踩的点：
 *   · 人话原因叫 `reason_text`（`JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.reason')) AS reason_text`），
 *     不叫 `reason` —— 前端 mapQueueRow 同时认这两个键，接口本身只有 reason_text；
 *   · `miss_reasons / matched_conditions / excluded_by / evidence` 是**已解析的结构**（不是 JSON 字符串）。
 */
export async function getQueueRowOf(request, adminToken, orderSn) {
  const payload = await getAuditQueue(request, adminToken, { size: 100 })
  const rows = payload.rows || []
  const hit = rows.find((r) => r.order_sn === orderSn)
  if (!hit) {
    throw new Error(`队列里没有订单 ${orderSn}（共 ${rows.length} 行，total=${payload.total}）—— 这一轮 run 真的判过它吗？`)
  }
  return hit
}

/** 单张订单的审核时间线：三段（DECISION / STATUS / AUDIT）按时间拼好的数组 */
export async function getAuditTrail(request, adminToken, orderId) {
  return data(
    await request.get(`${API}/admin/order-audit/orders/${orderId}/trail`, auth(adminToken)),
    `读取订单 ${orderId} 的审核时间线`
  )
}

/** 时间线里某一段的条目 */
export const trailSegments = (entries, segment) =>
  (entries || []).filter((e) => String(e.segment).toUpperCase() === segment)

/** 时间线里的决策条目（按 round_no 升序） */
export const trailDecisions = (entries) => trailSegments(entries, 'DECISION')
  .sort((a, b) => Number(a.raw?.round_no ?? 0) - Number(b.raw?.round_no ?? 0))

/** 策略页一次拿全：{ policies[], globalExclusions[], fields[], operators[] } */
export async function getAuditPolicies(request, adminToken) {
  return data(await request.get(`${API}/admin/audit-policies`, auth(adminToken)), '读取审核策略')
}

/** 按名字取一条策略（策略 id / versionNo / priority 都是断言要用的定位信息） */
export async function getPolicyOf(request, adminToken, name) {
  const bundle = await getAuditPolicies(request, adminToken)
  const hit = (bundle.policies || []).find((p) => p.name === name)
  if (!hit) throw new Error(`接口里没有策略「${name}」—— 策略集缺失？`)
  return hit
}

/** run 列表（倒序）：{total, page, size, rows[{id, triggerType, total, autoPass…}]} */
export async function getAuditRuns(request, adminToken, { page = 1, size = 10 } = {}) {
  return data(
    await request.get(`${API}/admin/audit-runs?page=${page}&size=${size}`, auth(adminToken)),
    '读取自动审核执行记录'
  )
}
