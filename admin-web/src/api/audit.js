import request from './request'

/**
 * 订单自动审核模块的接口层（策略配置 / 人工队列 / run / 单笔时间线 / 冻结解冻）。
 *
 * 请求层 baseURL 已经是 /admin（见 request.js），所以路径里只写 /audit-policies、/order-audit/…，
 * 实际打到 /admin/audit-policies、/admin/order-audit/queue。
 *
 * ⚠️ 这里除了转发请求，还负责**把后端返回的两种命名风格归一化**，原因见下面 pick() 的注释。
 * 三个页面只认归一化后的驼峰字段，snake_case ↔ camelCase 的差异只在这一个文件里处理。
 */

// ---------------------------------------------------------------- 归一化工具

/**
 * 取第一个「有值」的键。
 *
 * 为什么需要它：本模块的后端出参有两套命名。
 *  - 队列 / 时间线走的是 MyBatis 的 `Map<String,Object>`（selectQueue 直写列名），
 *    键是 snake_case：`order_sn` / `total_amount` / `matched_policy_name`；
 *  - run 列表走的是实体（AuditRun → Jackson），键是 camelCase：`triggerType` / `autoPass`。
 * 前端若写死其中一种，另一种就整列空白（而且不报错，最难查）。
 */
export const pick = (obj, ...keys) => {
  if (!obj) return undefined
  for (const k of keys) {
    const v = obj[k]
    if (v !== undefined && v !== null && v !== '') return v
  }
  return undefined
}

/** size 夹到 1..50（后端 MybatisPlusConfig 的上限是 100，但页面统一按需求夹 50，避免一次拉太多） */
export const clampSize = (size, fallback = 20) => {
  const n = Number(size)
  if (!Number.isFinite(n)) return fallback
  return Math.min(50, Math.max(1, Math.trunc(n)))
}

/** page 从 1 起，且必须是整数 */
export const clampPage = (page) => {
  const n = Number(page)
  if (!Number.isFinite(n) || n < 1) return 1
  return Math.trunc(n)
}

const num = (v) => {
  if (v === undefined || v === null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 统一分页外壳：后端队列/run 列表给的是 { total, page, size, rows }（不是 MP 的 records/current） */
const pageOf = (payload, mapper) => {
  const raw = payload || {}
  const rows = Array.isArray(raw) ? raw : raw.rows || raw.records || []
  return {
    total: Number(pick(raw, 'total') ?? rows.length) || 0,
    page: Number(pick(raw, 'page', 'current') ?? 1) || 1,
    size: Number(pick(raw, 'size') ?? rows.length) || rows.length,
    rows: rows.map(mapper)
  }
}

// ---------------------------------------------------------------- 队列

/**
 * 人工队列：每单最新一轮决策。
 * 参数：result（单值过滤，可为空）/ page / size。
 * 后端**只收一个 result**（不支持多选），所以「被拦」这个分组在页面上拆成 阻止 / 冻结 两个 chip。
 */
export const listAuditQueue = (params = {}) =>
  request.get('/order-audit/queue', {
    params: {
      result: params.result || undefined,
      page: clampPage(params.page),
      size: clampSize(params.size)
    }
  })

/** 队列行归一化（同时兼容 snake_case 与 camelCase，字段缺失时给 null 而不是 undefined，模板里统一兜底） */
export const mapQueueRow = (r) => ({
  decisionId: num(pick(r, 'decisionId', 'decision_id', 'id')),
  orderId: num(pick(r, 'orderId', 'order_id')),
  orderSn: pick(r, 'orderSn', 'order_sn') ?? '',
  shopName: pick(r, 'shopName', 'shop_name') ?? null,
  channel: pick(r, 'channel') ?? null,
  totalAmount: num(pick(r, 'totalAmount', 'total_amount')),
  itemKindCount: num(pick(r, 'itemKindCount', 'item_kind_count')),
  itemQtyTotal: num(pick(r, 'itemQtyTotal', 'item_qty_total')),
  grossProfit: num(pick(r, 'grossProfit', 'gross_profit')),
  // 判定结局：六种之一（AUTO_PASS / MANUAL / BLOCKED / FROZEN / NO_MATCH / SKIPPED）
  result: String(pick(r, 'result') ?? '').toUpperCase(),
  matchedPolicyId: num(pick(r, 'matchedPolicyId', 'matched_policy_id')),
  matchedPolicyName: pick(r, 'matchedPolicyName', 'matched_policy_name') ?? null,
  matchedPolicyVersionNo: num(pick(r, 'matchedPolicyVersionNo', 'matched_policy_version_no')),
  /**
   * 「假如没被拦，本来会命中哪条自动策略」——后端 selectQueue 直接回 d.would_be_auto_policy_name。
   * 只有 BLOCKED / FROZEN 才有值（判定链跑到 [3][4] 命中后仍会继续跑 [5]，只看不执行）。
   * 之前这一列没被映射，于是「策略其实满足、只是被前面的策略先拦下」这个故事在页面上完全看不见。
   */
  wouldBeAutoPolicyName: pick(r, 'wouldBeAutoPolicyName', 'would_be_auto_policy_name') ?? null,
  downgradeReason: pick(r, 'downgradeReason', 'downgrade_reason') ?? null,
  // 人话原因（含具体数值差），后端来自 evidence_json.reason
  reason: pick(r, 'reason', 'reason_text') ?? null,
  roundNo: num(pick(r, 'roundNo', 'round_no')),
  createdAt: pick(r, 'createdAt', 'created_at') ?? null,
  // 「差在哪」的结构化留痕：后端补齐 miss_reason_json / evidence_json 后这里直接就有值，
  // 没有时页面降级为「按策略条件 + 队列可见字段本地对一遍」（见 views/order-audit/index.vue）。
  missReasons: pick(r, 'missReasons', 'miss_reasons'),
  matchedConditions: pick(r, 'matchedConditions', 'matched_conditions'),
  excludedBy: pick(r, 'excludedBy', 'excluded_by'),
  evidence: pick(r, 'evidence') ?? null
})

export const queuePage = (payload) => pageOf(payload, mapQueueRow)

// ---------------------------------------------------------------- run

/** run 列表（倒序） */
export const listAuditRuns = (params = {}) =>
  request.get('/audit-runs', { params: { page: clampPage(params.page), size: clampSize(params.size, 10) } })

export const mapRunRow = (r) => ({
  id: num(pick(r, 'id', 'runId', 'run_id')),
  triggerType: pick(r, 'triggerType', 'trigger_type') ?? null,
  total: num(pick(r, 'total')) ?? 0,
  autoPass: num(pick(r, 'autoPass', 'auto_pass')) ?? 0,
  manual: num(pick(r, 'manual')) ?? 0,
  blocked: num(pick(r, 'blocked')) ?? 0,
  frozen: num(pick(r, 'frozen')) ?? 0,
  noMatch: num(pick(r, 'noMatch', 'no_match')) ?? 0,
  skipped: num(pick(r, 'skipped')) ?? 0,
  createdAt: pick(r, 'createdAt', 'created_at') ?? null
})

export const runPage = (payload) => pageOf(payload, mapRunRow)

/** 立即触发一轮（MANUAL）：返回 { runId, triggerType, total, autoPass, manual, blocked, frozen, noMatch, skipped, decisions[] } */
export const runAudit = () => request.post('/order-audit/run')

// ---------------------------------------------------------------- 单笔时间线

/**
 * 单张订单的审核时间线：后端把三段（决策 / 状态台账 / 操作审计）**合并成一个按时间排序的数组**，
 * 每项形如 { segment: DECISION|STATUS|AUDIT, at, title, detail, raw }。
 * 注意：这不是 { decisions[], ledger[], audits[] } 三个数组 —— 页面按 segment 分色展示。
 */
export const getAuditTrail = (orderId) => request.get(`/order-audit/orders/${orderId}/trail`)

export const mapTrailEntry = (e) => ({
  segment: String(pick(e, 'segment') ?? '').toUpperCase(),
  at: pick(e, 'at', 'created_at', 'createdAt') ?? null,
  title: pick(e, 'title') ?? '',
  detail: pick(e, 'detail') ?? '',
  /**
   * 决策 ↔ 台账的配对结论，后端已经算好并放在条目顶层，**整对象透传**别拆：
   *   { matchedBy: 'RUN_REF'|'STATUS_SEQ'|null, strong, ledgerRowId, decisionId, roundNo, statusSeq, aligned }
   * 语义：strong=true 表示台账行就是这一轮 run 写下的（ref_type=AUDIT_RUN 同一条因果）；
   *       matchedBy=STATUS_SEQ 只是「序号恰好相同」（本轮没推进状态）；
   *       null 表示压根没配上，页面如实说「无法精确对齐」，绝不按时间最近硬猜一条。
   * AUDIT（操作审计）段不参与状态对齐，后端给 null —— 别把 null 读成「没对齐」。
   */
  aligned: pick(e, 'aligned') ?? null,
  raw: pick(e, 'raw') || e || {}
})

export const trailEntries = (payload) => (Array.isArray(payload) ? payload : []).map(mapTrailEntry)

// ---------------------------------------------------------------- 冻结 / 解冻

/** 人工冻结：打标 + 审计（不动 status，不写状态台账） */
export const freezeOrderAudit = (orderId, { reason, tagCode } = {}) =>
  request.post(`/order-audit/orders/${orderId}/freeze`, { tagCode, reason })

/** 人工解冻：解除所有冻结类标；下一轮 run 才会重新判定 */
export const unfreezeOrderAudit = (orderId) => request.post(`/order-audit/orders/${orderId}/unfreeze`)

// ---------------------------------------------------------------- 策略

/**
 * 策略页一次拿全：
 *   { policies[], globalExclusions[], fields[{code,label,valueType,allowedOps}], operators[] }
 * 字段/操作符的闭集住后端（FieldRegistry / OpCode），前端**不许**自己抄一份下拉 ——
 * 抄一份的下场是前端有个「毛利」后端没有，配了永远不命中且没人看得出来。
 */
export const listAuditPolicies = () => request.get('/audit-policies')

export const createAuditPolicy = (data) => request.post('/audit-policies', data)
export const updateAuditPolicy = (id, data) => request.put(`/audit-policies/${id}`, data)
export const deleteAuditPolicy = (id) => request.delete(`/audit-policies/${id}`)
/** direction 只支持 UP / DOWN（跨类型换位没有意义，后端只与同类型相邻策略换位） */
export const moveAuditPolicy = (id, direction) =>
  request.post(`/audit-policies/${id}/move`, { direction })

/**
 * 影响预演（保存前 dry-run）。
 * ⚠️ 后端目前**没有**这个端点（AuditPolicyController 里只有 CRUD + move），
 * 调用会失败，页面据此降级为「用当前队列可见字段本地对一遍」的估算，
 * 并在弹窗里明说这是估算。补上后前端无需改动（形如
 * { queueTotal, hitCount, actionChanged, mode: 'BACKEND' }）。
 */
export const previewAuditPolicy = (data) => request.post('/audit-policies/preview', data)

/** 条件行入参 → 后端 ConditionRequest（valueType 由后端按字段决定，这里带上只为本地预演用） */
export const toConditionRequest = (c, { kind, scope } = {}) => {
  const op = String(c.opCode || '').toUpperCase()
  const needsValue = op !== 'EXISTS' && op !== 'NOT_EXISTS'
  const valueType = String(c.valueType || 'TEXT').toUpperCase()
  return {
    kind: kind || c.kind || 'MATCH',
    scope: scope || c.scope || 'POLICY',
    fieldCode: c.fieldCode,
    opCode: op,
    valueType,
    valueNum: !needsValue || valueType === 'TEXT' ? null : Number(c.valueNum ?? 0),
    valueText: !needsValue || valueType === 'TEXT' ? (needsValue ? String(c.valueText ?? '') : null) : null,
    valueJson: null
  }
}

/** 策略出参归一化 */
export const mapPolicy = (p) => ({
  id: num(pick(p, 'id')),
  type: String(pick(p, 'type') ?? '').toUpperCase(),
  priority: num(pick(p, 'priority')) ?? 100,
  name: pick(p, 'name') ?? '',
  enabled: pick(p, 'enabled') !== false,
  versionNo: num(pick(p, 'versionNo', 'version_no')) ?? 1,
  actionTag: pick(p, 'actionTag', 'action_tag') ?? null,
  actionReason: pick(p, 'actionReason', 'action_reason') ?? null,
  // 后端 PolicyDto 目前没有 updatedAt —— 列里显示「—」而不是假时间（见报告⑤）
  updatedAt: pick(p, 'updatedAt', 'updated_at') ?? null,
  conditions: (pick(p, 'conditions') || []).map(mapCondition)
})

export const mapCondition = (c, index = 0) => ({
  id: num(pick(c, 'id')),
  seq: num(pick(c, 'seq')) ?? index,
  kind: String(pick(c, 'kind') ?? 'MATCH').toUpperCase(),
  scope: String(pick(c, 'scope') ?? 'POLICY').toUpperCase(),
  fieldCode: pick(c, 'fieldCode', 'field_code') ?? '',
  fieldLabel: pick(c, 'fieldLabel', 'field_label') ?? '',
  opCode: String(pick(c, 'opCode', 'op_code') ?? 'EQ').toUpperCase(),
  valueType: String(pick(c, 'valueType', 'value_type') ?? 'TEXT').toUpperCase(),
  valueNum: num(pick(c, 'valueNum', 'value_num')),
  valueText: pick(c, 'valueText', 'value_text') ?? null,
  valueJson: pick(c, 'valueJson', 'value_json') ?? null
})

/** 策略页出参归一化：策略 + 全局排除 + 字段/操作符元数据 */
export const policyBundle = (payload) => {
  const raw = payload || {}
  return {
    policies: (pick(raw, 'policies') || []).map(mapPolicy),
    globalExclusions: (pick(raw, 'globalExclusions', 'global_exclusions') || []).map(mapCondition),
    fields: (pick(raw, 'fields') || []).map((f, i) => ({
      code: pick(f, 'code') ?? '',
      label: pick(f, 'label') ?? '',
      valueType: String(pick(f, 'valueType', 'value_type') ?? 'TEXT').toUpperCase(),
      // 后端给的是一串逗号分隔的操作符码（NUM: "EQ,NE,LT,…"），这里切成数组按值类型过滤下拉
      allowedOps: String(pick(f, 'allowedOps', 'allowed_ops') ?? '')
        .split(',')
        .map((s) => s.trim().toUpperCase())
        .filter(Boolean),
      order: i
    })),
    operators: (pick(raw, 'operators') || []).map((s) => String(s).toUpperCase())
  }
}
