<template>
  <div class="audit-trail" data-testid="audit-trail">
    <!-- 三段图例：决策 / 状态台账 / 操作审计 —— 点一下只留那一段（默认三段全开） -->
    <el-card class="trail-legend" shadow="never" :body-style="legendBodyStyle">
      <el-text type="info">按时间拼接的三段证据：</el-text>
      <el-check-tag
        v-for="seg in SEGMENTS"
        :key="seg"
        :checked="visible.includes(seg)"
        :data-testid="`audit-trail-legend-${seg}`"
        @change="toggle(seg)"
      >
        <el-tag :type="auditSegmentTag(seg)" size="small" effect="dark" >
          {{ auditSegmentText(seg) }}
        </el-tag>
        <el-text size="small">{{ countOf(seg) }}</el-text>
      </el-check-tag>
      <el-button link type="primary" size="small" data-testid="audit-trail-refresh" @click="load">刷新</el-button>
    </el-card>

    <div v-loading="loading" class="trail-body">
      <el-empty v-if="!loading && !entries.length" description="这张单还没有审核记录" data-testid="audit-trail-empty" />

      <el-timeline v-else>
        <el-timeline-item
          v-for="(e, i) in shown"
          :key="`${e.segment}-${e.at}-${i}`"
          :timestamp="fmtTime(e.at)"
          placement="top"
          :type="auditSegmentTag(e.segment)"
          :hollow="e.segment === 'AUDIT'"
        >
          <el-card shadow="never">
            <el-space :size="8">
              <el-tag :type="auditSegmentTag(e.segment)" size="small" effect="dark">
                {{ auditSegmentText(e.segment) }}
              </el-tag>
              <!-- 三种段各自的标题：决策段用中文结局替换后端的英文码，台账段翻成订单状态中文 -->
              <el-text tag="b" :data-testid="trailTestid(e.segment)">{{ titleOf(e) }}</el-text>
            </el-space>

            <!-- 决策段：命中策略 + 版本 + 留痕原因 -->
            <template v-if="e.segment === 'DECISION'">
              <el-text class="trail-line" tag="div">
                <el-text class="label" type="info">命中策略：</el-text>
                <span v-if="rawOf(e).matched_policy_name || rawOf(e).matchedPolicyName" class="value">
                  「{{ rawOf(e).matched_policy_name || rawOf(e).matchedPolicyName }}」
                  <span class="muted">（第 {{ rawOf(e).matched_policy_version_no ?? rawOf(e).matchedPolicyVersionNo ?? '—' }} 版）</span>
                </span>
                <span v-else class="muted">无（没命中任何策略）</span>
              </el-text>
              <el-text v-if="rawOf(e).downgrade_reason || rawOf(e).downgradeReason" class="trail-line" tag="div">
                <el-text class="label" type="info">预检降级：</el-text>
                <span class="value">{{ downgradeText(rawOf(e).downgrade_reason || rawOf(e).downgradeReason) }}</span>
              </el-text>
              <el-text v-if="e.detail" class="trail-detail" tag="div" size="small" type="info">{{ e.detail }}</el-text>
              <!-- 决策 ↔ 台账：直接读后端每段的 aligned（旧载荷没有时退回按 raw 算，见 alignTag） -->
              <div class="trail-align">
                <el-tag
                  size="small"
                  :type="alignTag(e).type"
                  effect="plain"
                  :data-testid="alignTag(e).testid"
                >{{ alignTag(e).text }}</el-tag>
              </div>
            </template>

            <!-- 状态台账段：7 → 8 —— 同一个数字在不同台账里含义不同，必须走 ledgerStatusText('ORDER_STATUS') -->
            <template v-else-if="e.segment === 'STATUS'">
              <el-text class="trail-line" tag="div">
                <el-text class="label" type="info">状态变化：</el-text>
                <span class="value">
                  {{ orderStatusText(rawOf(e).from_status ?? rawOf(e).fromStatus) }}
                  <el-text class="arrow" type="info">→</el-text>
                  {{ orderStatusText(rawOf(e).to_status ?? rawOf(e).toStatus) }}
                </span>
              </el-text>
              <el-text class="trail-line" tag="div">
                <el-text class="label" type="info">流转原因：</el-text>
                <span class="value mono">{{ rawOf(e).reason || '—' }}</span>
                <span v-if="rawOf(e).reason_detail" class="muted">（{{ rawOf(e).reason_detail }}）</span>
              </el-text>
              <el-text class="trail-line" tag="div">
                <el-text class="label" type="info">操作人：</el-text>
                <span class="value">{{ rawOf(e).operator_name || '—' }}</span>
                <el-tag :type="operatorTypeTag(rawOf(e).operator_type)" size="small" effect="plain">
                  {{ operatorTypeText(rawOf(e).operator_type) }}
                </el-tag>
              </el-text>
            </template>

            <!-- 操作审计段：谁点了什么（策略改动 / 人工冻结解冻） -->
            <template v-else>
              <el-text class="trail-line" tag="div">
                <el-text class="label" type="info">动作：</el-text>
                <span class="value mono">{{ rawOf(e).action || '—' }}</span>
              </el-text>
              <el-text v-if="e.detail" class="trail-detail" tag="div" size="small" type="info">{{ e.detail }}</el-text>
              <el-text class="trail-line" tag="div">
                <el-text class="label" type="info">操作人：</el-text>
                <span class="value">{{ rawOf(e).operator_name || '—' }}</span>
                <el-tag :type="operatorTypeTag(rawOf(e).operator_type)" size="small" effect="plain">
                  {{ operatorTypeText(rawOf(e).operator_type) }}
                </el-tag>
              </el-text>
            </template>
          </el-card>
        </el-timeline-item>
      </el-timeline>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getAuditTrail, trailEntries } from '../api/audit'
import {
  auditSegmentTag, auditSegmentText,
  auditResultText, orderStatusText, operatorTypeTag, operatorTypeText
} from '../constants/status'

const props = defineProps({
  orderId: { type: [Number, String], default: null }
})

const SEGMENTS = ['DECISION', 'STATUS', 'AUDIT']
const DOWNGRADE_TEXT = { STOCK_SHORT: '库存不足（SUM 库存桶 < 需求）', COST_MISSING: '成本价缺失，毛利无法确认' }

/* 图例条的行内布局：Card 的 body-style 是官方 Attributes，比 :deep 撬 .el-card__body 稳。
   这里只有居中/间距/内距，没有颜色字号。 */
const legendBodyStyle = {
  display: 'flex',
  alignItems: 'center',
  flexWrap: 'wrap',
  gap: '10px',
  padding: '8px 10px'
}

const loading = ref(false)
const entries = ref([])
const visible = ref([...SEGMENTS])

const rawOf = (e) => e?.raw || {}
const shown = computed(() => entries.value.filter((e) => visible.value.includes(e.segment)))
const countOf = (seg) => entries.value.filter((e) => e.segment === seg).length

const toggle = (seg) => {
  visible.value = visible.value.includes(seg) ? visible.value.filter((s) => s !== seg) : [...visible.value, seg]
}

const fmtTime = (at) => {
  if (!at) return '—'
  // 后端给的是 '2026-11-13 18:47:16.123' 这类字符串，秒后面的毫秒对人没用
  return String(at).replace('T', ' ').slice(0, 19)
}

/** 决策段标题：把后端的英文 result 换成中文，保留「第几轮」 */
const titleOf = (e) => {
  const raw = rawOf(e)
  if (e.segment === 'DECISION') {
    const round = raw.round_no ?? raw.roundNo
    const result = String(raw.result || '').toUpperCase()
    if (round !== undefined && round !== null && result) {
      return `第 ${round} 轮判定 → ${auditResultText(result)}`
    }
    return e.title || '决策'
  }
  if (e.segment === 'STATUS') {
    const from = raw.from_status ?? raw.fromStatus
    const to = raw.to_status ?? raw.toStatus
    if (to !== undefined && to !== null) {
      return `订单状态 ${orderStatusText(from)} → ${orderStatusText(to)}（${raw.reason || '—'}）`
    }
    return e.title || '状态台账'
  }
  return e.title || '操作审计'
}

const downgradeText = (code) => DOWNGRADE_TEXT[String(code || '').toUpperCase()] || String(code)

const trailTestid = (segment) => {
  const map = { DECISION: 'audit-trail-decision', STATUS: 'audit-trail-status', AUDIT: 'audit-trail-audit' }
  return map[segment] || 'audit-trail-item'
}

/**
 * 决策 ↔ 台账对齐（读后端 entry.aligned，不再自己重算）。
 *
 * 后端给每段都算了 aligned：
 *   { matchedBy: 'RUN_REF'|'STATUS_SEQ'|null, strong, ledgerRowId, decisionId, roundNo, statusSeq, aligned }
 * 口径（见 AuditQueryService#trail）：
 *   · RUN_REF   ＝ 强对齐：台账行 ref_type='AUDIT_RUN' 且 ref_id=本轮 run_id，**同一条因果**；
 *   · STATUS_SEQ＝ 弱对齐：order_status_seq 与台账 status_seq 相等，但台账不是本轮写的
 *                 （典型：这一轮没推进状态，seq 还是上一个值）；
 *   · null      ＝ 没配上 → 如实说「无法精确对齐」。绝不按时间最接近硬猜一条：
 *                 猜错的对齐比没有对齐更糟。
 * aligned 缺失时（老后端）退回按 raw 里的 order_status_seq / status_seq / idem_key 本地判一遍。
 */
const alignOf = (e) => {
  const a = e?.aligned
  if (a && typeof a === 'object') {
    return {
      strong: a.strong === true,
      matchedBy: a.matchedBy || null,
      ledgerRowId: a.ledgerRowId ?? null,
      seq: a.statusSeq ?? null
    }
  }
  const raw = rawOf(e)
  const seq = raw.order_status_seq ?? raw.orderStatusSeq ?? null
  if (seq === null || seq === undefined) return { strong: false, matchedBy: null, ledgerRowId: null, seq: null }
  const hit = entries.value.find((x) => {
    if (x.segment !== 'STATUS') return false
    const r = rawOf(x)
    const ledgerSeq = r.status_seq ?? r.statusSeq
    if (ledgerSeq !== undefined && ledgerSeq !== null) return Number(ledgerSeq) === Number(seq)
    const idem = r.idem_key ?? r.idemKey
    return typeof idem === 'string' && idem.endsWith(`:${seq}`)
  })
  return {
    strong: false,
    matchedBy: hit ? 'STATUS_SEQ' : null,
    ledgerRowId: hit ? (rawOf(hit).id ?? null) : null,
    seq
  }
}

/**
 * 对齐标签的三态文案（testid 与文案一一对应，e2e 直接读 testid 就知道是哪一态）：
 *   强对齐 → audit-trail-aligned       「已对齐状态台账 #<台账行id>（同一因果：ref_type=AUDIT_RUN）」
 *   弱对齐 → audit-trail-align-pending 「按 status_seq=<n> 对应（本轮未推进状态）」
 *   没配上 → audit-trail-align-pending 「…无法精确对齐」（保持原来的如实说明）
 * 强对齐那句里额外写了 seq=<n>：序号是运营核对时唯一能同时指到两边的数字，去掉会看不懂。
 */
const alignTag = (e) => {
  const a = alignOf(e)
  if (a.strong) {
    return {
      testid: 'audit-trail-aligned',
      type: 'success',
      text: `已对齐状态台账 #${a.ledgerRowId}（同一因果：ref_type=AUDIT_RUN，seq=${a.seq}）`
    }
  }
  if (a.matchedBy === 'STATUS_SEQ') {
    return {
      testid: 'audit-trail-align-pending',
      type: 'warning',
      text: `按 status_seq=${a.seq} 对应（本轮未推进状态）`
    }
  }
  if (a.seq !== null && a.seq !== undefined) {
    return {
      testid: 'audit-trail-align-pending',
      type: 'info',
      text: `本轮状态序号 seq=${a.seq}（台账里没有对应的一行，无法精确对齐）`
    }
  }
  return {
    testid: 'audit-trail-align-missing',
    type: 'info',
    text: '未推进状态（无 order_status_seq）'
  }
}

const load = async () => {
  if (props.orderId === null || props.orderId === undefined || props.orderId === '') return
  loading.value = true
  try {
    entries.value = trailEntries(await getAuditTrail(props.orderId))
  } catch (e) {
    entries.value = []
    ElMessage.error('加载审核轨迹失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.orderId, load)
</script>

<style scoped>
.audit-trail { width: 100%; }
/* 图例条 = 普通 el-card（原来把三个 --el-card-* 逐值钉成"改前那张图例卡"的样子，
   于是它和本页其它 el-card 不是同一个圆角/底色 —— 本轮回退成组件默认）。
   行内布局走 :body-style（Card 的官方 Attributes），不能用 :deep 撬 .el-card__body。 */
.trail-legend { margin-bottom: 12px; }
.trail-body { min-height: 40px; }
/* 卡片左边那条 3px 分段色边删掉了：段类型已经用同一套 type 编码了两次
   （el-timeline-item 的圆点 :type + 段名 el-tag 的 type），第三次用色边纯属重复。 */
/* 轨迹行 = el-text 默认档（14px + regular 灰）；label/arrow 用 type="info" 再淡一档。
   原来整行钉成 13px + primary 墨色 —— 为追旧值让 el-text 在全站出现第 4 种字号，本轮回退。 */
.trail-line { line-height: 22px; }
.trail-line .arrow { margin: 0 4px; }
/* 12px + secondary 灰都由 el-text 的 size/type 给 */
.trail-detail { line-height: 20px; margin-top: 4px; word-break: break-all; }
.trail-align { margin-top: 6px; }
</style>
