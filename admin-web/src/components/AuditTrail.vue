<template>
  <div class="audit-trail" data-testid="audit-trail">
    <!-- 三段图例：决策 / 状态台账 / 操作审计 —— 点一下只留那一段（默认三段全开） -->
    <div class="trail-legend">
      <span class="legend-label">按时间拼接的三段证据：</span>
      <el-check-tag
        v-for="seg in SEGMENTS"
        :key="seg"
        :checked="visible.includes(seg)"
        :data-testid="`audit-trail-legend-${seg}`"
        @change="toggle(seg)"
      >
        <el-tag :type="auditSegmentTag(seg)" size="small" effect="dark" class="legend-tag">
          {{ auditSegmentText(seg) }}
        </el-tag>
        <span class="legend-count">{{ countOf(seg) }}</span>
      </el-check-tag>
      <el-button link type="primary" size="small" data-testid="audit-trail-refresh" @click="load">刷新</el-button>
    </div>

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
          <el-card shadow="never" class="trail-card" :class="`trail-${e.segment.toLowerCase()}`">
            <div class="trail-head">
              <el-tag :type="auditSegmentTag(e.segment)" size="small" effect="dark">
                {{ auditSegmentText(e.segment) }}
              </el-tag>
              <!-- 三种段各自的标题：决策段用中文结局替换后端的英文码，台账段翻成订单状态中文 -->
              <span class="trail-title" :data-testid="trailTestid(e.segment)">{{ titleOf(e) }}</span>
            </div>

            <!-- 决策段：命中策略 + 版本 + 留痕原因 -->
            <template v-if="e.segment === 'DECISION'">
              <div class="trail-line">
                <span class="label">命中策略：</span>
                <span v-if="rawOf(e).matched_policy_name || rawOf(e).matchedPolicyName" class="value">
                  「{{ rawOf(e).matched_policy_name || rawOf(e).matchedPolicyName }}」
                  <span class="muted">（第 {{ rawOf(e).matched_policy_version_no ?? rawOf(e).matchedPolicyVersionNo ?? '—' }} 版）</span>
                </span>
                <span v-else class="muted">无（没命中任何策略）</span>
              </div>
              <div v-if="rawOf(e).downgrade_reason || rawOf(e).downgradeReason" class="trail-line">
                <span class="label">预检降级：</span>
                <span class="value">{{ downgradeText(rawOf(e).downgrade_reason || rawOf(e).downgradeReason) }}</span>
              </div>
              <div v-if="e.detail" class="trail-detail">{{ e.detail }}</div>
              <!-- 决策 ↔ 台账：靠 decision.order_status_seq 对齐（后端没回传时如实说明，不假装对齐） -->
              <div class="trail-align">
                <template v-if="alignOf(e).aligned">
                  <el-tag size="small" type="success" effect="plain" data-testid="audit-trail-aligned">
                    已对齐状态台账 seq={{ alignOf(e).seq }}
                  </el-tag>
                </template>
                <template v-else-if="alignOf(e).seq !== null">
                  <el-tag size="small" type="info" effect="plain" data-testid="audit-trail-align-pending">
                    本轮状态序号 seq={{ alignOf(e).seq }}（台账未回传 seq，无法精确对齐）
                  </el-tag>
                </template>
                <template v-else>
                  <el-tag size="small" type="info" effect="plain" data-testid="audit-trail-align-missing">
                    未推进状态（无 order_status_seq）
                  </el-tag>
                </template>
              </div>
            </template>

            <!-- 状态台账段：7 → 8 —— 同一个数字在不同台账里含义不同，必须走 ledgerStatusText('ORDER_STATUS') -->
            <template v-else-if="e.segment === 'STATUS'">
              <div class="trail-line">
                <span class="label">状态变化：</span>
                <span class="value">
                  {{ orderStatusText(rawOf(e).from_status ?? rawOf(e).fromStatus) }}
                  <span class="arrow">→</span>
                  {{ orderStatusText(rawOf(e).to_status ?? rawOf(e).toStatus) }}
                </span>
              </div>
              <div class="trail-line">
                <span class="label">流转原因：</span>
                <span class="value mono">{{ rawOf(e).reason || '—' }}</span>
                <span v-if="rawOf(e).reason_detail" class="muted">（{{ rawOf(e).reason_detail }}）</span>
              </div>
              <div class="trail-line">
                <span class="label">操作人：</span>
                <span class="value">{{ rawOf(e).operator_name || '—' }}</span>
                <el-tag :type="operatorTypeTag(rawOf(e).operator_type)" size="small" effect="plain">
                  {{ operatorTypeText(rawOf(e).operator_type) }}
                </el-tag>
              </div>
            </template>

            <!-- 操作审计段：谁点了什么（策略改动 / 人工冻结解冻） -->
            <template v-else>
              <div class="trail-line">
                <span class="label">动作：</span>
                <span class="value mono">{{ rawOf(e).action || '—' }}</span>
              </div>
              <div v-if="e.detail" class="trail-detail">{{ e.detail }}</div>
              <div class="trail-line">
                <span class="label">操作人：</span>
                <span class="value">{{ rawOf(e).operator_name || '—' }}</span>
                <el-tag :type="operatorTypeTag(rawOf(e).operator_type)" size="small" effect="plain">
                  {{ operatorTypeText(rawOf(e).operator_type) }}
                </el-tag>
              </div>
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
 * 决策 ↔ 台账对齐。
 *
 * 口径：决策行的 order_status_seq 指的是「这一轮推进出的那条状态台账的序号」，
 * 台账侧的 idem_key 形如 {orderId}:{statusSeq}（见 order_status_ledger.uk_idem）。
 * 所以对齐 = 决策 seq 与台账 idem_key 尾巴（或台账自带的 status_seq）相等。
 * 后端当前这两列都没在 trail 里回传 → 返回 {aligned:false, seq}，界面上如实标注「无法精确对齐」，
 * 绝不按「时间最接近」去猜一条 —— 猜错的对齐比没有对齐更糟。
 */
const alignOf = (e) => {
  const raw = rawOf(e)
  const seq = raw.order_status_seq ?? raw.orderStatusSeq ?? null
  if (seq === null || seq === undefined) return { aligned: false, seq: null }
  const hit = entries.value.find((x) => {
    if (x.segment !== 'STATUS') return false
    const r = rawOf(x)
    const ledgerSeq = r.status_seq ?? r.statusSeq
    if (ledgerSeq !== undefined && ledgerSeq !== null) return Number(ledgerSeq) === Number(seq)
    const idem = r.idem_key ?? r.idemKey
    return typeof idem === 'string' && idem.endsWith(`:${seq}`)
  })
  return { aligned: Boolean(hit), seq }
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
.trail-legend {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 8px 10px; margin-bottom: 12px;
  background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px;
}
.legend-label { font-size: 13px; color: #909399; }
.legend-tag { margin-right: 4px; }
.legend-count { font-size: 12px; color: #606266; }
.trail-card { border-left-width: 3px; }
.trail-decision { border-left-color: #409eff; }
.trail-status { border-left-color: #67c23a; }
.trail-audit { border-left-color: #e6a23c; }
.trail-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.trail-title { font-weight: 600; font-size: 14px; color: #303133; }
.trail-line { font-size: 13px; color: #303133; line-height: 22px; }
.trail-line .label { color: #909399; }
.trail-line .value { color: #303133; }
.trail-line .arrow { color: #909399; margin: 0 4px; }
.trail-detail { font-size: 12px; color: #909399; line-height: 20px; margin-top: 4px; word-break: break-all; }
.trail-align { margin-top: 6px; }
.muted { color: #909399; font-size: 12px; }
.mono { font-family: Consolas, Monaco, monospace; }
</style>
