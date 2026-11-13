<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <span>自动审核策略</span>
          <span class="header-tip">
            判定链顺序固定：<b>阻止 → 异常冻结 → 自动过审</b>；同类型内按优先级升序（数字小的先判）
          </span>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" data-testid="policy-refresh" @click="load">刷新</el-button>
          <el-button type="primary" :icon="Plus" data-testid="policy-create" @click="openEditor(null)">新建策略</el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="metaError"
      type="error"
      :closable="false"
      show-icon
      :title="`字段/操作符元数据加载失败：${metaError}`"
      description="数据从 GET /admin/audit-policies 的 fields / operators 取（后端 FieldRegistry 是唯一来源）。页面不内置字段清单，取不到就不让配，避免配出永远不命中的条件。"
    />

    <el-tabs v-model="activeType" data-testid="policy-tabs">
      <!-- 优先级的作用域：只在同类型内比较。跨类型顺序写死在引擎里（AuditEngine：BLOCK → EXCEPTION → AUTO），
           把异常策略 priority 调到 5 也抢不过阻止策略 —— 不写明就会有人以为「数字小就一定先判」。
           放在 default slot 里 = 渲染在 tab 栏下方、表格上方，且全局只有这一行（不随 tab 复制）。 -->
      <div class="priority-note" data-testid="policy-priority-scope-note">
        优先级只在<b>同类型</b>策略之间比较；跨类型的判定顺序固定为：阻止 → 异常 → 自动
      </div>
      <el-tab-pane
        v-for="t in TYPES"
        :key="t"
        :name="t"
        :label="`${auditPolicyTypeText(t)}（${policiesOf(t).length}）`"
        :data-testid="`policy-tab-${t}`"
      >
        <div class="tab-tip">{{ AUDIT_POLICY_TYPE_TIP[t] }}</div>

        <el-table
          v-loading="loading"
          :data="policiesOf(t)"
          :row-key="(row) => `${row.type}-${row.id}`"
          border
          empty-text="还没有这类策略"
          :data-testid="`policy-table-${t}`"
        >
          <el-table-column label="优先级" width="80" align="center">
            <template #default="{ row }"><b>{{ row.priority }}</b></template>
          </el-table-column>

          <el-table-column label="名称" min-width="170">
            <template #default="{ row }">
              <span :data-testid="`policy-row-${row.id}`" class="policy-name">{{ row.name }}</span>
              <div class="muted">ID {{ row.id }}</div>
            </template>
          </el-table-column>

          <el-table-column label="启用" width="80" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                :data-testid="`policy-enabled-${row.id}`"
                @change="(v) => toggleEnabled(row, v)"
              />
            </template>
          </el-table-column>

          <el-table-column label="版本" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">v{{ row.versionNo }}</el-tag>
            </template>
          </el-table-column>

          <!-- 条件摘要：一条条件一个 chip，chip 的 testid 就是 why-miss-${policyId}-${seq}
               （从队列「差在哪」跳过来时，命中那条会被高亮并标出「← 差在哪」） -->
          <el-table-column label="条件摘要" min-width="330">
            <template #default="{ row }">
              <div v-if="row.conditions.length" class="cond-chips">
                <span
                  v-for="c in row.conditions"
                  :key="c.seq"
                  class="cond-chip"
                  :class="{
                    'cond-chip-exclude': c.kind === 'EXCLUDE',
                    'cond-chip-focus': isFocused(row.id, c.seq)
                  }"
                  :data-testid="`why-miss-${row.id}-${c.seq}`"
                  :title="`第 ${c.seq} 条条件`"
                >
                  <span v-if="isFocused(row.id, c.seq)" class="why-mark">← 差在哪</span>
                  <span v-if="c.kind === 'EXCLUDE'" class="cond-chip-kind">排除</span>
                  {{ conditionSummary(c) }}
                </span>
              </div>
              <span v-else class="muted">无条（命不中任何单）</span>
            </template>
          </el-table-column>

          <el-table-column label="动作" min-width="200">
            <template #default="{ row }">{{ actionText(row) }}</template>
          </el-table-column>

          <el-table-column label="最近修改" width="150">
            <template #default="{ row }">
              <span v-if="row.updatedAt" class="datetime">{{ row.updatedAt }}</span>
              <!-- 后端 PolicyDto 目前没有 updatedAt：如实显示「—」，不编一个假时间 -->
              <span v-else class="muted" title="后端策略出参未返回 updatedAt">—</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row, $index }">
              <el-button
                link
                type="primary"
                size="small"
                :disabled="$index === 0"
                :data-testid="`policy-move-up-${row.id}`"
                @click="move(row, 'UP')"
              >上移</el-button>
              <el-button
                link
                type="primary"
                size="small"
                :disabled="$index === policiesOf(t).length - 1"
                :data-testid="`policy-move-down-${row.id}`"
                @click="move(row, 'DOWN')"
              >下移</el-button>
              <el-button link type="primary" size="small" :data-testid="`policy-edit-${row.id}`" @click="openEditor(row)">编辑</el-button>
              <el-button link type="danger" size="small" :data-testid="`policy-delete-${row.id}`" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 全局排除：不分策略，所有 AUTO 策略判定时都会并进来（保存在任一自动策略里一并提交） -->
        <el-card v-if="t === 'AUTO'" shadow="never" class="global-card" data-testid="policy-global-exclusions">
          <template #header>
            <div class="global-head">
              <span>全局排除（对所有自动策略生效）</span>
              <span class="muted">
                判定时每条 AUTO 策略都会并入这些条件；编辑入口在任意一条自动策略的编辑弹窗里
              </span>
            </div>
          </template>
          <div v-if="globalExclusions.length" class="cond-chips">
            <span v-for="g in globalExclusions" :key="g.seq" class="cond-chip cond-chip-exclude">
              <span class="cond-chip-kind">排除</span>
              {{ conditionSummary(g) }}
            </span>
          </div>
          <div v-else class="muted">还没有配置全局排除</div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ================= 编辑弹窗 ================= -->
    <el-dialog
      v-model="editorVisible"
      :title="editing.id ? `编辑策略 - ${editing.name}` : '新建策略'"
      width="900px"
      top="6vh"
      data-testid="policy-editor"
    >
      <el-form label-width="96px" size="small">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="类型">
              <el-select v-model="editing.type" data-testid="policy-form-type" style="width: 100%">
                <el-option v-for="t in TYPES" :key="t" :label="auditPolicyTypeText(t)" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="名称">
              <el-input v-model="editing.name" data-testid="policy-form-name" placeholder="同类型内唯一" />
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="优先级">
              <el-input-number v-model="editing.priority" :min="1" :max="9999" controls-position="right" data-testid="policy-form-priority" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="启用">
              <el-switch v-model="editing.enabled" data-testid="policy-form-enabled" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row v-if="editing.type === 'EXCEPTION'" :gutter="12">
          <el-col :span="10">
            <el-form-item label="冻结标码">
              <el-input v-model="editing.actionTag" data-testid="policy-form-action-tag" placeholder="FROZEN" />
            </el-form-item>
          </el-col>
          <el-col :span="14">
            <el-form-item label="冻结原因">
              <el-input v-model="editing.actionReason" data-testid="policy-form-action-reason" placeholder="留空则用策略名" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="匹配条件">
          <div class="cond-editor" data-testid="cond-editor">
            <div v-if="!editing.conditions.length" class="muted">还没有条件：一条条件都没有的策略永远不会命中</div>

            <div
              v-for="(c, i) in editing.conditions"
              :key="`cond-${i}`"
              class="cond-row"
              :data-testid="`cond-row-${i}`"
            >
              <span class="cond-seq">{{ i }}</span>

              <el-select v-model="c.kind" size="small" style="width: 86px" :data-testid="`cond-kind-${i}`">
                <el-option label="匹配" value="MATCH" />
                <el-option label="排除" value="EXCLUDE" />
              </el-select>

              <!-- 字段下拉：选项来自后端 FieldRegistry（GET /admin/audit-policies 的 fields） -->
              <el-select
                v-model="c.fieldCode"
                size="small"
                filterable
                placeholder="字段"
                style="width: 190px"
                :data-testid="`cond-field-${i}`"
                @change="(v) => onFieldChange(c, v)"
              >
                <el-option v-for="f in fieldsMeta" :key="f.code" :label="f.label" :value="f.code">
                  <span>{{ f.label }}</span>
                  <span class="opt-code">{{ f.code }} · {{ f.valueType }}</span>
                </el-option>
              </el-select>

              <!-- 操作符：按字段值类型过滤（NUM 才有 LT/GT…，TEXT 才有 含/不含，布尔只有 =/≠） -->
              <el-select v-model="c.opCode" size="small" style="width: 110px" :data-testid="`cond-op-${i}`">
                <el-option
                  v-for="op in opsOf(c)"
                  :key="op"
                  :label="`${auditOpText(op)} ${op}`"
                  :value="op"
                />
              </el-select>

              <!-- 值控件：按值类型切换（数字 / 文本 / 布尔）；EXISTS/NOT_EXISTS 不需要值 -->
              <template v-if="needsValue(c)">
                <el-input-number
                  v-if="typeOf(c) === 'NUM'"
                  v-model="c.valueNum"
                  size="small"
                  :controls="false"
                  placeholder="数值"
                  style="width: 130px"
                  :data-testid="`cond-value-num-${i}`"
                />
                <el-select
                  v-else-if="typeOf(c) === 'BOOL'"
                  v-model="c.valueNum"
                  size="small"
                  style="width: 130px"
                  :data-testid="`cond-value-bool-${i}`"
                >
                  <el-option label="是" :value="1" />
                  <el-option label="否" :value="0" />
                </el-select>
                <el-input
                  v-else
                  v-model="c.valueText"
                  size="small"
                  placeholder="文本（含/不含 按原样比较，% 不是通配符）"
                  style="width: 240px"
                  :data-testid="`cond-value-text-${i}`"
                />
              </template>
              <span v-else class="muted no-value">（该操作符不需要值）</span>

              <el-button link type="danger" size="small" :data-testid="`cond-remove-${i}`" @click="removeCondition(i)">删除</el-button>
            </div>

            <el-button size="small" :icon="Plus" data-testid="cond-add" @click="addCondition">添加条件</el-button>
            <div class="muted cond-tip">
              多条条件之间是 <b>AND</b>：全部满足才算命中。EXCLUDE 条件只要成立，这条策略就被排除。
            </div>
          </div>
        </el-form-item>

        <!-- 全局排除：只在自动策略里编辑（后端没有独立端点，随策略保存整组替换） -->
        <el-form-item v-if="editing.type === 'AUTO'" label="全局排除">
          <div class="cond-editor" data-testid="global-cond-editor">
            <div v-if="!editing.globalExclusions.length" class="muted">没有全局排除条件</div>
            <div
              v-for="(c, i) in editing.globalExclusions"
              :key="`global-${i}`"
              class="cond-row"
              :data-testid="`global-cond-row-${i}`"
            >
              <span class="cond-seq">G{{ i }}</span>
              <span class="cond-chip-kind">排除</span>
              <el-select
                v-model="c.fieldCode"
                size="small"
                filterable
                placeholder="字段"
                style="width: 190px"
                :data-testid="`global-cond-field-${i}`"
                @change="(v) => onFieldChange(c, v)"
              >
                <el-option v-for="f in fieldsMeta" :key="f.code" :label="f.label" :value="f.code" />
              </el-select>
              <el-select v-model="c.opCode" size="small" style="width: 110px" :data-testid="`global-cond-op-${i}`">
                <el-option v-for="op in opsOf(c)" :key="op" :label="`${auditOpText(op)} ${op}`" :value="op" />
              </el-select>
              <template v-if="needsValue(c)">
                <el-input-number
                  v-if="typeOf(c) === 'NUM'"
                  v-model="c.valueNum"
                  size="small"
                  :controls="false"
                  style="width: 130px"
                  :data-testid="`global-cond-value-num-${i}`"
                />
                <el-select v-else-if="typeOf(c) === 'BOOL'" v-model="c.valueNum" size="small" style="width: 130px">
                  <el-option label="是" :value="1" />
                  <el-option label="否" :value="0" />
                </el-select>
                <el-input v-else v-model="c.valueText" size="small" style="width: 240px" :data-testid="`global-cond-value-text-${i}`" />
              </template>
              <el-button link type="danger" size="small" @click="editing.globalExclusions.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" :icon="Plus" data-testid="global-cond-add" @click="addGlobalCondition">添加全局排除</el-button>
            <div class="muted cond-tip">
              全局排除随<b>本次保存</b>整组替换（后端 PolicyRequest.globalExclusions：null 表示不动，非 null 表示整套替换）
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="editor-footer">
          <!-- 影响预演：保存前 dry-run（当前队列里会命中几笔、其中几笔动作会变） -->
          <span v-if="impactText" class="impact" data-testid="policy-impact-preview">{{ impactText }}</span>
          <div class="footer-buttons">
            <el-button data-testid="policy-impact-dryrun" :loading="previewing" @click="dryRun">影响预演</el-button>
            <el-button data-testid="policy-editor-cancel" @click="editorVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" data-testid="policy-save" @click="save">保存</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  listAuditPolicies, policyBundle,
  createAuditPolicy, updateAuditPolicy, deleteAuditPolicy, moveAuditPolicy,
  previewAuditPolicy, listAuditQueue, queuePage, toConditionRequest, mapCondition
} from '../../api/audit'
import { handleSaveError } from '../../composables/useConflictHandler'
import { compareCondition, rowFieldValue, expectedText } from '../../composables/useAuditExplain'
import { AUDIT_POLICY_TYPE_TIP, auditPolicyTypeText, auditOpText } from '../../constants/status'

const route = useRoute()

const TYPES = ['AUTO', 'BLOCK', 'EXCEPTION']
const activeType = ref('AUTO')

const loading = ref(false)
const metaError = ref('')
const policies = ref([])
const globalExclusions = ref([])
const fieldsMeta = ref([])
// 后端给的全量操作符清单（OpCode 10 个）；具体某个字段能用哪些由 fields[].allowedOps 决定，
// 这份清单只作为「字段元数据缺失时」的兜底展示来源
const operators = ref([])

const policiesOf = (type) => policies.value.filter((p) => p.type === type).sort((a, b) => a.priority - b.priority || a.id - b.id)
const typeOf = (c) => (fieldsMeta.value.find((f) => f.code === c.fieldCode)?.valueType || c.valueType || 'TEXT').toUpperCase()

const conditionSummary = (c) => {
  const label = fieldsMeta.value.find((f) => f.code === c.fieldCode)?.label || c.fieldLabel || c.fieldCode
  const op = String(c.opCode || '').toUpperCase()
  const noValue = op === 'EXISTS' || op === 'NOT_EXISTS'
  return `${label} ${auditOpText(op)}${noValue ? '' : ' ' + expectedText({ ...c, valueType: typeOf(c) })}`
}

const actionText = (row) => {
  if (row.type === 'AUTO') return '自动过审（状态 7 → 8）'
  if (row.type === 'BLOCK') return '阻止（前置拦截，不推进状态）'
  return `冻结：${row.actionTag || 'FROZEN'}（${row.actionReason || row.name}）`
}

/** 高亮定位：从队列「差在哪」跳过来时会带 focusPolicy / focusSeq */
const focusPolicy = computed(() => Number(route.query.focusPolicy) || null)
const focusSeq = computed(() => (route.query.focusSeq === undefined ? null : Number(route.query.focusSeq)))
const isFocused = (policyId, seq) => focusPolicy.value === Number(policyId) && focusSeq.value !== null && Number(seq) === focusSeq.value

const load = async () => {
  loading.value = true
  metaError.value = ''
  try {
    const bundle = policyBundle(await listAuditPolicies())
    policies.value = bundle.policies
    globalExclusions.value = bundle.globalExclusions
    fieldsMeta.value = bundle.fields
    operators.value = bundle.operators
    if (!fieldsMeta.value.length) metaError.value = '后端没有返回 fields（字段清单）'
  } catch (e) {
    metaError.value = e?.message || String(e)
    ElMessage.error('加载策略失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- 编辑器

const editorVisible = ref(false)
const saving = ref(false)
const previewing = ref(false)
const impactText = ref('')

const editing = reactive({
  id: null,
  type: 'AUTO',
  name: '',
  priority: 100,
  enabled: true,
  actionTag: 'FROZEN',
  actionReason: '',
  conditions: [],
  globalExclusions: []
})

const blankCondition = () => ({
  kind: 'MATCH',
  scope: 'POLICY',
  fieldCode: '',
  fieldLabel: '',
  opCode: 'EQ',
  valueType: 'TEXT',
  valueNum: 0,
  valueText: ''
})

const openEditor = (policy) => {
  impactText.value = ''
  if (policy) {
    editing.id = policy.id
    editing.type = policy.type
    editing.name = policy.name
    editing.priority = policy.priority
    editing.enabled = policy.enabled
    editing.actionTag = policy.actionTag || 'FROZEN'
    editing.actionReason = policy.actionReason || ''
    // seq 重排成 0..n：后端保存时也是按数组下标重排的，两边口径一致（why-miss-${id}-${seq} 才对得上）
    editing.conditions = policy.conditions.map((c, i) => ({ ...mapCondition(c), seq: i }))
    editing.globalExclusions = globalExclusions.value.map((c, i) => ({ ...c, seq: i }))
  } else {
    editing.id = null
    editing.type = activeType.value
    editing.name = ''
    editing.priority = 100
    editing.enabled = true
    editing.actionTag = 'FROZEN'
    editing.actionReason = ''
    editing.conditions = [blankCondition()]
    editing.globalExclusions = globalExclusions.value.map((c, i) => ({ ...c, seq: i }))
  }
  editorVisible.value = true
}

const addCondition = () => editing.conditions.push(blankCondition())
const removeCondition = (i) => editing.conditions.splice(i, 1)
const addGlobalCondition = () =>
  editing.globalExclusions.push({ ...blankCondition(), kind: 'EXCLUDE', scope: 'ALL_AUTO' })

const opsOf = (c) => {
  const f = fieldsMeta.value.find((x) => x.code === c.fieldCode)
  const allowed = f?.allowedOps?.length ? f.allowedOps : []
  // 还没选字段时只给最安全的两个（EQ/NE）：NUM 与 TEXT 都支持，不会让人先配出非法组合
  return allowed.length ? allowed : ['EQ', 'NE']
}

const needsValue = (c) => {
  const op = String(c.opCode || '').toUpperCase()
  return op !== 'EXISTS' && op !== 'NOT_EXISTS'
}

/** 换字段：值类型、可选操作符、值控件全都要跟着换（旧值一律清掉，避免「毛利 含 100」这种脏组合） */
const onFieldChange = (c, code) => {
  const f = fieldsMeta.value.find((x) => x.code === code)
  c.fieldCode = code
  c.fieldLabel = f?.label || code
  c.valueType = f?.valueType || 'TEXT'
  const allowed = f?.allowedOps?.length ? f.allowedOps : ['EQ', 'NE']
  if (!allowed.includes(c.opCode)) c.opCode = allowed[0]
  c.valueNum = 0
  c.valueText = ''
}

const payloadOf = () => ({
  type: editing.type,
  priority: Number(editing.priority) || 100,
  name: (editing.name || '').trim(),
  enabled: Boolean(editing.enabled),
  actionTag: editing.type === 'EXCEPTION' ? editing.actionTag || 'FROZEN' : null,
  actionReason: editing.type === 'EXCEPTION' ? editing.actionReason || editing.name : null,
  conditions: editing.conditions.map((c) => toConditionRequest(c, { kind: c.kind, scope: 'POLICY' })),
  // null = 不动全局排除（改策略 B 不该顺手抹掉策略 A 里配的全局排除）
  globalExclusions: editing.type === 'AUTO'
    ? editing.globalExclusions.map((c) => toConditionRequest(c, { kind: 'EXCLUDE', scope: 'ALL_AUTO' }))
    : null
})

/** 提交前的本地校验：与后端 insertCondition 的校验口径一致，少一次「保存才知道配错」 */
const validate = () => {
  if (!editing.name.trim()) return '请填写策略名称'
  if (!editing.conditions.length) return '至少配一条匹配条件'
  for (const [i, c] of editing.conditions.entries()) {
    if (!c.fieldCode) return `第 ${i} 条条件还没选字段`
    if (!c.opCode) return `第 ${i} 条条件还没选操作符`
    if (needsValue(c)) {
      const t = typeOf(c)
      if (t === 'TEXT' && !String(c.valueText ?? '').trim()) return `第 ${i} 条条件还缺文本值`
      if (t === 'NUM' && (c.valueNum === null || c.valueNum === undefined || c.valueNum === '')) return `第 ${i} 条条件还缺数值`
    }
  }
  return ''
}

// ---------------------------------------------------------------- 影响预演

const OUTCOME = { AUTO: 'AUTO_PASS', BLOCK: 'BLOCKED', EXCEPTION: 'FROZEN' }

/**
 * 影响预演：保存前 dry-run —— 「当前队列 N 笔中，这条策略会命中 M 笔，其中 K 笔动作变化」。
 *
 * 数据来源两级：
 *  ① 后端若提供 POST /admin/audit-policies/preview（dry-run，不落库）→ 用它的精确值；
 *  ② 后端暂无该端点 → 本地用**当前队列里看得到的字段**对一遍，并在文案里明说是估算、
 *     有多少笔因为字段没回传而无法判定。绝不把估算值伪装成精确值。
 */
const computeImpact = async () => {
  const payload = payloadOf()
  try {
    const r = (await previewAuditPolicy(payload)) || {}
    return {
      queueTotal: Number(r.queueTotal ?? r.total ?? 0),
      hitCount: Number(r.hitCount ?? r.hit ?? 0),
      actionChanged: Number(r.actionChanged ?? r.changed ?? 0),
      unknown: 0,
      mode: 'BACKEND'
    }
  } catch {
    // 端点不存在 / 网络失败 → 本地估算
  }
  const page = queuePage(await listAuditQueue({ page: 1, size: 50 }))
  const conds = editing.conditions.map((c) => ({ ...c, valueType: typeOf(c) }))
  let hit = 0
  let changed = 0
  let unknown = 0
  for (const row of page.rows) {
    let allKnown = true
    let allSatisfied = true
    for (const c of conds) {
      const { known, value } = rowFieldValue(row, c.fieldCode)
      if (!known) {
        allKnown = false
        break
      }
      if (compareCondition(c, value, known) !== true) {
        allSatisfied = false
        break
      }
    }
    if (!allKnown) {
      unknown++
      continue
    }
    if (!allSatisfied) continue
    hit++
    if (String(row.result).toUpperCase() !== OUTCOME[editing.type]) changed++
  }
  return {
    queueTotal: page.total,
    hitCount: hit,
    actionChanged: changed,
    unknown,
    mode: 'LOCAL'
  }
}

const impactMessage = (r) =>
  `当前队列 ${r.queueTotal} 笔中，这条策略会命中 ${r.hitCount} 笔，其中 ${r.actionChanged} 笔动作变化`
  + (r.mode === 'BACKEND'
    ? '（后端 dry-run，未落库）'
    : `（本地估算：只用队列可见字段，另有 ${r.unknown} 笔因字段未回传无法判定；后端 /preview 上线后即为精确值）`)

const dryRun = async () => {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  previewing.value = true
  try {
    const r = await computeImpact()
    impactText.value = impactMessage(r)
    ElMessage.info(impactText.value)
  } catch (e) {
    ElMessage.error('影响预演失败：' + (e?.message || e))
  } finally {
    previewing.value = false
  }
}

// ---------------------------------------------------------------- 保存

const save = async () => {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  const payload = payloadOf()
  // 保存前先预演一次：让「会不会改到一大批单」这件事在点保存之前就看见
  let impact = null
  try {
    impact = await computeImpact()
    impactText.value = impactMessage(impact)
  } catch {
    impact = null
  }
  try {
    await ElMessageBox.confirm(
      impact
        ? `${impactText.value}\n\n保存后会立刻影响下一轮自动审核，是否继续？`
        : '预演暂时拿不到数据（队列读取失败），仍要保存吗？',
      editing.id ? '确认保存策略修改' : '确认新建策略',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    if (editing.id) {
      await updateAuditPolicy(editing.id, payload)
      ElMessage.success('策略已保存（版本号 +1）')
    } else {
      await createAuditPolicy(payload)
      ElMessage.success('策略已新建')
    }
    editorVisible.value = false
    load()
  } catch (e) {
    // 409 版本冲突 / 400 缺版本号交给统一冲突弹窗（会提示刷新后重填）
    if (handleSaveError(e, { onRefresh: load }) !== 'other') return
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------- 行操作

const move = async (row, direction) => {
  try {
    await moveAuditPolicy(row.id, direction)
    ElMessage.success(`已${direction === 'UP' ? '上移' : '下移'}「${row.name}」`)
    load()
  } catch (e) {
    ElMessage.error(`${direction === 'UP' ? '上移' : '下移'}失败：` + (e?.message || e))
  }
}

/** 启用/停用：把整条策略按当前内容提交（后端 PUT 是整体覆盖 + 版本 +1） */
const toggleEnabled = async (row, value) => {
  try {
    await updateAuditPolicy(row.id, {
      type: row.type,
      priority: row.priority,
      name: row.name,
      enabled: Boolean(value),
      actionTag: row.actionTag,
      actionReason: row.actionReason,
      conditions: row.conditions.map((c) => toConditionRequest(c, { kind: c.kind, scope: 'POLICY' })),
      globalExclusions: null // 不动全局排除
    })
    ElMessage.success(`已${value ? '启用' : '停用'}「${row.name}」`)
    load()
  } catch (e) {
    if (handleSaveError(e, { onRefresh: load }) !== 'other') return
    ElMessage.error('切换启用状态失败：' + (e?.message || e))
    load()
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除后这条策略立即不再参与判定（历史决策仍保留策略名与版本号的快照）。确认删除「${row.name}」？`,
      '删除策略',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteAuditPolicy(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || e))
  }
}

onMounted(async () => {
  if (route.query.type && TYPES.includes(String(route.query.type).toUpperCase())) {
    activeType.value = String(route.query.type).toUpperCase()
  }
  await load()
  // 从队列「差在哪 → 去改这条策略」跳过来：直接打开对应策略的编辑器，条件高亮由 isFocused 负责
  if (focusPolicy.value) {
    const target = policies.value.find((p) => p.id === focusPolicy.value)
    if (target) {
      activeType.value = target.type
      openEditor(target)
    }
  }
})
</script>

<style scoped>
.card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-header .header-tip { margin-left: 10px; font-size: 12px; color: #909399; }
.header-actions { display: flex; gap: 8px; }
.tab-tip { font-size: 12px; color: #909399; margin-bottom: 8px; }
.priority-note {
  font-size: 12px; color: #909399; line-height: 20px;
  margin: 4px 0 10px; padding: 4px 10px;
  background: #fafafa; border-left: 3px solid #dcdfe6; border-radius: 2px;
}
.policy-name { font-weight: 600; }
.muted { color: #909399; font-size: 12px; }
.datetime { font-size: 13px; color: #606266; }
.opt-code { float: right; color: #c0c4cc; font-size: 12px; margin-left: 12px; }

.cond-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.cond-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; border-radius: 4px; font-size: 12px;
  background: #f4f8ff; border: 1px solid #d0e0ff; color: #1f4fa3;
}
.cond-chip-exclude { background: #fff5f5; border-color: #fbc4c4; color: #b23232; }
.cond-chip-focus { background: #fef0f0; border: 1px solid #f56c6c; color: #c45656; font-weight: 600; }
.cond-chip-kind { font-size: 11px; padding: 0 4px; border-radius: 3px; background: rgba(0, 0, 0, .06); }
.why-mark { color: #f56c6c; font-weight: 700; }

.global-card { margin-top: 14px; background: #fcfcfd; }
.global-head { display: flex; align-items: baseline; gap: 10px; }

.cond-editor { width: 100%; }
.cond-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; margin-bottom: 6px;
  background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px;
}
.cond-seq { width: 26px; text-align: center; color: #909399; font-size: 12px; font-family: Consolas, Monaco, monospace; }
.no-value { width: 130px; }
.cond-tip { margin-top: 6px; }
.editor-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.editor-footer .impact { font-size: 12px; color: #606266; text-align: left; flex: 1; }
.footer-buttons { display: flex; gap: 8px; }
</style>
