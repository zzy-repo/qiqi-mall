/**
 * 「差在哪」的判定解释器（纯函数，无副作用，可单独 node --check）。
 *
 * 为什么要有这一层：
 *  1. 抽屉要把「逐策略逐条件」的 expected vs actual 摆出来，而队列行到底带不带结构化留痕
 *     取决于后端是否回传 miss_reason_json / evidence_json —— 两种情形都要能渲染，
 *     所以统一成同一个视图模型 { policyId, policyName, source, conditions[] }。
 *  2. 判定链是 AND：一条不满足，后面的条件其实不影响结论。把它们混在「不满足」里会让人以为
 *     后面几条也检查过、也差着。这里显式区分三态：
 *        satisfied  满足（绿）
 *        unsatisfied 不满足（红，右侧固定列「← 差在哪」）
 *        unevaluated 未求值（灰）—— 两种情况都属于它：
 *            a) AND 短路：前面已经有条件不满足（或无法判断），这条不会再决定结论；
 *            b) 队列行没有这个字段的值（后端没回传），本地只能承认「不知道」，
 *               **绝不**把它当成 0 或「满足」——那样会编出一个假的结论。
 *  3. 策略页的「影响预演」也要用同一套比较规则做本地估算，两边不能有两套口径。
 */

/** 队列行上可能带的字段（后端补齐前只有 RECEIVABLE_AMOUNT 有值；其余一律按「未求值」处理） */
const ROW_FIELD_KEYS = {
  RECEIVABLE_AMOUNT: ['totalAmount', 'total_amount'],
  ITEM_KIND_COUNT: ['itemKindCount', 'item_kind_count'],
  ITEM_QTY_TOTAL: ['itemQtyTotal', 'item_qty_total'],
  GROSS_PROFIT: ['grossProfit', 'gross_profit'],
  STOCK_AVAILABLE: ['stockAvailable', 'stock_available'],
  CHANNEL: ['channel'],
  SHOP_NAME: ['shopName', 'shop_name'],
  CUSTOMER_TAG: ['customerTag', 'customer_tag'],
  CS_REMARK: ['csRemark', 'cs_remark'],
  RECEIVER_CITY: ['receiverCity', 'receiver_city'],
  HAS_RETURN_APPLY: ['hasReturnApply', 'has_return_apply'],
  ADDRESS_MULTI: ['addressMulti', 'address_multi'],
  PLATFORM_REMARK_UPDATED: ['platformRemarkUpdated', 'platform_remark_updated'],
  PAY_TIME: ['payTime', 'pay_time']
}

/** 金额类字段（展示时带 ¥，与后端 Fmt.isMoney 口径一致） */
const MONEY_FIELDS = ['RECEIVABLE_AMOUNT', 'GROSS_PROFIT', 'STOCK_AVAILABLE']

/** 不带配置值的操作符 */
const NO_VALUE_OPS = ['EXISTS', 'NOT_EXISTS']

/** 操作符符号（与后端 OpCode.symbol 一致）：文案里用 ≤ 而不是 LTE，人一眼看得懂 */
const OP_SYMBOL = {
  EQ: '=', NE: '≠', LT: '<', LTE: '≤', GT: '>', GTE: '≥',
  CONTAINS: '含', NOT_CONTAINS: '不含', EXISTS: '有值', NOT_EXISTS: '无值'
}
const sym = (op) => OP_SYMBOL[String(op || '').toUpperCase()] || String(op || '')

const pickFirst = (obj, keys) => {
  for (const k of keys) {
    const v = obj?.[k]
    if (v !== undefined && v !== null && v !== '') return v
  }
  return undefined
}

/** 数字格式化：金额 ¥1,234.00，其余最多两位小数（与后端 Fmt.num 的可读口径对齐） */
export const fmtNum = (v, money = false) => {
  const n = Number(v)
  if (v === null || v === undefined || v === '' || !Number.isFinite(n)) return String(v ?? '—')
  const body = Math.abs(n) >= 1000 ? n.toFixed(2) : String(Math.round(n * 100) / 100)
  return money ? `¥${body}` : body
}

/** 从队列行取某个字段的实际值；取不到返回 { known:false }（不是 0，也不是空串） */
export const rowFieldValue = (row, fieldCode) => {
  const keys = ROW_FIELD_KEYS[String(fieldCode || '').toUpperCase()]
  if (!keys) return { known: false, value: undefined }
  const raw = pickFirst(row, keys)
  if (raw === undefined) return { known: false, value: undefined }
  return { known: true, value: raw }
}

/** 一份「期望值」的可读文案（有值操作符才需要） */
export const expectedText = (cond) => {
  const op = String(cond.opCode || '').toUpperCase()
  const type = String(cond.valueType || 'TEXT').toUpperCase()
  if (NO_VALUE_OPS.includes(op)) return op === 'EXISTS' ? '（有值即可）' : '（无值即可）'
  if (type === 'BOOL') return Number(cond.valueNum) ? '是' : '否'
  if (type === 'NUM') return fmtNum(cond.valueNum, MONEY_FIELDS.includes(String(cond.fieldCode).toUpperCase()))
  return String(cond.valueText ?? '')
}

/** 实际值的可读文案 */
export const actualText = (cond, actual, known) => {
  if (!known) return '—（队列未回传该字段）'
  const type = String(cond.valueType || 'TEXT').toUpperCase()
  const op = String(cond.opCode || '').toUpperCase()
  if (NO_VALUE_OPS.includes(op)) {
    const present = actual !== null && actual !== undefined && String(actual).trim() !== ''
    return present ? `有值（${String(actual)}）` : '无值'
  }
  if (type === 'BOOL') return Number(actual) ? '是' : '否'
  if (type === 'NUM') return fmtNum(actual, MONEY_FIELDS.includes(String(cond.fieldCode).toUpperCase()))
  return String(actual)
}

/**
 * 单条条件的比较：返回 true / false / null（null = 无法判断，交给上层标「未求值」）。
 * 与后端 ConditionEvaluator 的口径保持一致：文本 EQ/NE/CONTAINS/NOT_CONTAINS，数值六种关系，布尔 EQ/NE。
 * 特别地：actual 为 null **不**等于 0 —— 缺失一律返回 null。
 */
export const compareCondition = (cond, actual, known) => {
  const op = String(cond.opCode || '').toUpperCase()
  if (NO_VALUE_OPS.includes(op)) {
    const present = known && actual !== null && actual !== undefined && String(actual).trim() !== ''
    return op === 'EXISTS' ? present : !present
  }
  if (!known) return null
  const type = String(cond.valueType || 'TEXT').toUpperCase()
  if (type === 'NUM') {
    const a = Number(actual)
    const b = Number(cond.valueNum)
    if (!Number.isFinite(a) || !Number.isFinite(b)) return null
    switch (op) {
      case 'EQ': return a === b
      case 'NE': return a !== b
      case 'LT': return a < b
      case 'LTE': return a <= b
      case 'GT': return a > b
      case 'GTE': return a >= b
      default: return null
    }
  }
  if (type === 'BOOL') {
    const a = Boolean(Number(actual))
    const b = Boolean(Number(cond.valueNum))
    if (op === 'EQ') return a === b
    if (op === 'NE') return a !== b
    return null
  }
  const a = String(actual ?? '')
  const b = String(cond.valueText ?? '')
  switch (op) {
    case 'EQ': return a === b
    case 'NE': return a !== b
    case 'CONTAINS': return a.includes(b)
    case 'NOT_CONTAINS': return !a.includes(b)
    default: return null
  }
}

const condText = (cond, expected, actual) => {
  const label = cond.fieldLabel || cond.fieldCode
  const op = String(cond.opCode || '').toUpperCase()
  switch (op) {
    case 'EQ': return `${label} = ${expected}（实际 ${actual}）`
    case 'NE': return `${label} ≠ ${expected}（实际 ${actual}）`
    case 'CONTAINS': return `${label} 含「${expected}」（实际 ${actual}）`
    case 'NOT_CONTAINS': return `${label} 不含「${expected}」（实际 ${actual}）`
    case 'EXISTS': return `${label} 有值（实际 ${actual}）`
    case 'NOT_EXISTS': return `${label} 无值（实际 ${actual}）`
    // 关系比较：把「毛利 −12 ≤ 0」直接写出来，数值差一眼可见
    default: return `${label} ${actual} ${sym(op)} ${expected}`
  }
}

/**
 * 后端已回传结构化留痕时：直接用它（BACKEND），不再本地重算 —— 判定是引擎的事，
 * 前端重算只会在「引擎改了、前端没跟上」时给出与事实相反的解释。
 * 兼容两种形态：
 *   a) { conditions: [{field,label,op,expected,actual,satisfied,evaluated}] }  ← 最理想
 *   b) { reasons: ["毛利 -12.00 ≤ 0", …] }                                    ← 当前 PolicyMiss 的形态（只有失败项的文案）
 */
const fromBackendMiss = (item, fallbackPolicy) => {
  const policyId = item.policyId ?? item.policy_id ?? fallbackPolicy?.id ?? null
  const policyName = item.policyName ?? item.policy_name ?? fallbackPolicy?.name ?? ''
  const conds = item.conditions || item.traces || null
  const reasonLines = (item.reasons || []).map((r) => (typeof r === 'string' ? r : r?.text || '')).filter(Boolean)
  if (Array.isArray(conds) && conds.length) {
    return {
      policyId,
      policyName,
      source: 'BACKEND',
      reasonLines,
      conditions: conds.map((c, i) => {
        const satisfied = c.satisfied === true
        return {
          seq: c.seq ?? i,
          fieldCode: c.field ?? c.fieldCode ?? '',
          fieldLabel: c.label ?? c.fieldLabel ?? c.field ?? '',
          opCode: String(c.op ?? c.opCode ?? '').toUpperCase(),
          expected: c.expected ?? '—',
          actual: c.actual ?? '—',
          status: satisfied ? 'satisfied' : c.evaluated === false ? 'unevaluated' : 'unsatisfied',
          text: c.text ?? ''
        }
      })
    }
  }
  return { policyId, policyName, source: 'BACKEND', reasonLines, conditions: [] }
}

/**
 * 本地解释一条策略在这张单上的情况（队列行没带结构化留痕时的降级路径）。
 *
 * 只对**队列行上确实有的字段**下结论；其余一律「未求值（列不返回该字段）」。
 * AND 短路：第一条不满足/无法判断之后的条件标 unevaluated。
 */
export const explainPolicyLocal = (row, policy) => {
  const matchConds = (policy.conditions || [])
    .filter((c) => String(c.kind).toUpperCase() === 'MATCH' && String(c.scope).toUpperCase() === 'POLICY')
    .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))

  let shortCircuited = false
  const conditions = matchConds.map((c) => {
    const { known, value } = rowFieldValue(row, c.fieldCode)
    const expected = expectedText(c)
    const actual = actualText(c, value, known)
    if (shortCircuited) {
      return {
        seq: c.seq,
        fieldCode: c.fieldCode,
        fieldLabel: c.fieldLabel || c.fieldCode,
        opCode: c.opCode,
        expected,
        actual,
        status: 'unevaluated',
        text: `${c.fieldLabel || c.fieldCode} 未求值（前面的条件已不满足）`
      }
    }
    const verdict = compareCondition(c, value, known)
    if (verdict === null) {
      shortCircuited = true
      return {
        seq: c.seq,
        fieldCode: c.fieldCode,
        fieldLabel: c.fieldLabel || c.fieldCode,
        opCode: c.opCode,
        expected,
        actual,
        status: 'unevaluated',
        text: `${c.fieldLabel || c.fieldCode} 未求值（${known ? '取值无法比较' : '队列未回传该字段'}）`
      }
    }
    if (!verdict) shortCircuited = true
    return {
      seq: c.seq,
      fieldCode: c.fieldCode,
      fieldLabel: c.fieldLabel || c.fieldCode,
      opCode: c.opCode,
      expected,
      actual,
      status: verdict ? 'satisfied' : 'unsatisfied',
      text: condText(c, expected, actual)
    }
  })

  // 排除条件单独列（它们不参与 AND 短路：只要成立就排除，前端只如实展示）
  const excludeConds = (policy.conditions || [])
    .filter((c) => String(c.kind).toUpperCase() === 'EXCLUDE')
    .map((c) => {
      const { known, value } = rowFieldValue(row, c.fieldCode)
      const expected = expectedText(c)
      const actual = actualText(c, value, known)
      const verdict = compareCondition(c, value, known)
      return {
        seq: c.seq,
        fieldCode: c.fieldCode,
        fieldLabel: c.fieldLabel || c.fieldCode,
        opCode: c.opCode,
        expected,
        actual,
        status: verdict === null ? 'unevaluated' : verdict ? 'unsatisfied' : 'satisfied',
        // 排除条件「成立」= 这条策略被排除；对策略命中来说它是阻碍，所以状态取反显示
        text: `排除：${condText(c, expected, actual)}`
      }
    })

  const allSatisfied = conditions.length > 0 && conditions.every((c) => c.status === 'satisfied')
  return {
    policyId: policy.id,
    policyName: policy.name,
    priority: policy.priority,
    versionNo: policy.versionNo,
    source: 'LOCAL',
    reasonLines: [],
    conditions: [...conditions, ...excludeConds],
    allSatisfied
  }
}

/** 判定结局 → 该关注哪一类策略（决定抽屉里列哪些策略） */
export const policiesForResult = (result, policies) => {
  const list = (policies || []).slice()
  const byType = (type) =>
    list.filter((p) => p.type === type).sort((a, b) => (a.priority ?? 100) - (b.priority ?? 100) || a.id - b.id)
  switch (String(result || '').toUpperCase()) {
    case 'BLOCKED': return byType('BLOCK')
    case 'FROZEN': return byType('EXCEPTION')
    // 留人工 = 条件满足但被排除，或预检降级；自动过审时也列 AUTO（命中那条在最前）
    case 'MANUAL':
    case 'AUTO_PASS':
    case 'NO_MATCH': return byType('AUTO')
    default: return []
  }
}

/**
 * 抽屉的数据源选择：结构化留痕优先，缺失则本地解释。
 * @returns {{policyId,policyName,source,reasonLines,conditions[],allSatisfied}}
 */
export const explainRow = (row, policy) => {
  const raw = row?.missReasons
  if (Array.isArray(raw) && raw.length) {
    const hit = raw.find((m) => Number(m.policyId ?? m.policy_id) === Number(policy.id))
    if (hit) return { ...fromBackendMiss(hit, policy), priority: policy.priority, versionNo: policy.versionNo }
  }
  return explainPolicyLocal(row, policy)
}
