<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <span>销售单审核队列</span>
          <span class="header-tip">
            每单只显示<b>最新一轮</b>的判定结论 · 徽标颜色 + 文案 + data-result 三重冗余
          </span>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" data-testid="audit-queue-refresh" @click="refreshAll">刷新</el-button>
          <el-button :icon="Tickets" data-testid="audit-queue-runs" @click="openRuns">执行记录</el-button>
          <el-button type="primary" :icon="VideoPlay" :loading="running" data-testid="audit-queue-run" @click="runNow">
            立即审核一轮
          </el-button>
        </div>
      </div>
    </template>

    <!-- 筛选 chips：既是筛选器也是计数（点一下只看这一类，再点一下取消） -->
    <div class="chips" data-testid="audit-queue-chips">
      <button
        class="chip chip-all"
        :class="{ 'chip-active': !query.result }"
        data-testid="audit-chip-all"
        @click="setFilter(null)"
      >
        <span class="chip-text">全部</span>
        <b class="chip-count">{{ counts.all }}</b>
      </button>

      <button
        v-for="chip in headChips"
        :key="chip.result"
        class="chip"
        :class="[`chip-${chip.result.toLowerCase()}`, { 'chip-active': query.result === chip.result }]"
        :data-testid="`audit-chip-${chip.result}`"
        :data-result="chip.result"
        @click="setFilter(chip.result)"
      >
        <span class="chip-text">{{ chip.label }}</span>
        <b class="chip-count">{{ counts[chip.result] }}</b>
      </button>

      <!-- 「被拦」展开两支：阻止（运营谈单）与冻结（客服跟进）处理人不同，必须分开派活 -->
      <span class="chip-group" data-testid="audit-chip-BLOCKED_GROUP">
        <span class="chip-group-label">被拦</span>
        <button
          v-for="chip in blockedChips"
          :key="chip.result"
          class="chip chip-sub"
          :class="[`chip-${chip.result.toLowerCase()}`, { 'chip-active': query.result === chip.result }]"
          :data-testid="`audit-chip-${chip.result}`"
          :data-result="chip.result"
          :title="auditResultHint(chip.result)"
          @click="setFilter(chip.result)"
        >
          <span class="chip-text">{{ chip.label }}</span>
          <b class="chip-count">{{ counts[chip.result] }}</b>
        </button>
      </span>

      <button
        v-for="chip in tailChips"
        :key="chip.result"
        class="chip"
        :class="[`chip-${chip.result.toLowerCase()}`, { 'chip-active': query.result === chip.result }]"
        :data-testid="`audit-chip-${chip.result}`"
        :data-result="chip.result"
        @click="setFilter(chip.result)"
      >
        <span class="chip-text">{{ chip.label }}</span>
        <b class="chip-count">{{ counts[chip.result] }}</b>
      </button>

      <span class="chip-note">
        阻止 = 拦截下单，需人工联系客户；冻结 = 已打标等客服处理
        <span v-if="counts.skipped" class="muted">· 另有 {{ counts.skipped }} 笔未参与本轮</span>
      </span>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      :row-key="rowKey"
      border
      empty-text="当前筛选下没有待处理的审核记录"
      :row-class-name="rowClass"
      data-testid="audit-queue-table"
    >
      <el-table-column label="单号" min-width="170">
        <template #default="{ row }">
          <!-- el-table 无法给 <tr> 加 data-testid，所以每行的 testid 挂在单号格上（同台账页做法） -->
          <el-link
            type="primary"
            :data-testid="`audit-queue-row-${row.orderSn}`"
            @click="openOrderDrawer(row)"
          >{{ row.orderSn || '—' }}</el-link>
          <div class="muted">ID {{ row.orderId ?? '—' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="店铺 / 渠道" min-width="150">
        <template #default="{ row }">
          <div>{{ row.shopName || '—' }}</div>
          <div class="muted">{{ row.channel || '—' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="应收" width="110" align="right">
        <template #default="{ row }">
          <span class="money">{{ money(row.totalAmount) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="商品种类数" width="105" align="right">
        <template #default="{ row }">
          <span>{{ row.itemKindCount ?? '—' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="毛利" width="110" align="right">
        <template #default="{ row }">
          <span :class="isNegative(row.grossProfit) ? 'money-negative' : 'money'">{{ money(row.grossProfit) }}</span>
        </template>
      </el-table-column>

      <!-- 判定徽标：颜色 + 中文文案 + data-result 三重冗余 -->
      <el-table-column label="判定" width="132">
        <template #default="{ row }">
          <el-tag
            :type="auditResultTag(row.result)"
            :class="auditResultClass(row.result)"
            effect="dark"
            size="small"
            data-testid="audit-row-badge"
            :data-result="row.result"
          >{{ auditResultText(row.result) }}</el-tag>
        </template>
      </el-table-column>

      <!-- 原因：第一行说「命中了哪条」，第二行灰字是后端的人话原因（含具体数值差） -->
      <el-table-column label="原因" min-width="320">
        <template #default="{ row }">
          <div class="reason-main" data-testid="audit-row-reason">
            {{ reasonHead(row) }}
          </div>
          <div class="reason-sub">{{ row.reason || '—' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="第几轮" width="90" align="center">
        <template #default="{ row }">
          <span v-if="row.roundNo">第 {{ row.roundNo }} 轮</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="时间" width="165">
        <template #default="{ row }">
          <span class="datetime">{{ fmtTime(row.createdAt) }}</span>
        </template>
      </el-table-column>

      <!-- 固定右列：差在哪 / 轨迹 / 冻结解冻 -->
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="audit-row-why" @click="openWhy(row)">
            差在哪
          </el-button>
          <el-button link type="primary" size="small" data-testid="audit-row-trail" @click="openOrderDrawer(row)">
            轨迹
          </el-button>
          <el-button
            v-if="row.result === 'FROZEN'"
            link
            type="warning"
            size="small"
            data-testid="audit-row-unfreeze"
            @click="unfreeze(row)"
          >解冻</el-button>
          <el-button
            v-else
            link
            type="danger"
            size="small"
            data-testid="audit-row-freeze"
            @click="freeze(row)"
          >冻结</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        data-testid="audit-queue-pager"
        @current-change="loadQueue"
        @size-change="onSizeChange"
      />
    </div>

    <!-- ============ 「差在哪」抽屉：逐策略逐条件 expected vs actual ============ -->
    <el-drawer
      v-model="whyVisible"
      :title="`差在哪 - ${whyRow?.orderSn || ''}`"
      size="880px"
      data-testid="audit-why-drawer"
    >
      <template v-if="whyRow">
        <div class="why-head">
          <el-tag
            :type="auditResultTag(whyRow.result)"
            :class="auditResultClass(whyRow.result)"
            effect="dark"
            size="small"
            data-testid="audit-why-badge"
            :data-result="whyRow.result"
          >{{ auditResultText(whyRow.result) }}</el-tag>
          <span class="why-hint">{{ auditResultHint(whyRow.result) }}</span>
        </div>
        <div class="why-reason">{{ whyRow.reason || '后端没有给出原因' }}</div>

        <div class="why-meta">
          <span>应收 {{ money(whyRow.totalAmount) }}</span>
          <span>商品种类数 {{ whyRow.itemKindCount ?? '—' }}</span>
          <span>毛利 {{ money(whyRow.grossProfit) }}</span>
          <span>第 {{ whyRow.roundNo ?? '—' }} 轮</span>
          <el-tag size="small" :type="whySource === 'BACKEND' ? 'success' : 'info'" effect="plain" data-testid="audit-why-source">
            {{ whySource === 'BACKEND' ? '结论取自后端留痕' : '本地对照（队列未回传条件留痕）' }}
          </el-tag>
        </div>

        <el-empty v-if="!whyGroups.length" description="这类结论没有可对照的策略（例如未参与本轮）" />

        <div v-for="g in whyGroups" :key="`${g.policyId}`" class="why-group" :data-testid="`audit-why-policy-${g.policyId}`">
          <div class="why-group-head">
            <span class="why-policy-name">「{{ g.policyName }}」</span>
            <el-tag size="small" effect="plain">优先级 {{ g.priority ?? '—' }}</el-tag>
            <el-tag size="small" effect="plain">第 {{ g.versionNo ?? '—' }} 版</el-tag>
            <el-tag v-if="g.allSatisfied" size="small" type="success" effect="plain">条件全部满足</el-tag>
            <el-tag v-else size="small" type="danger" effect="plain">未命中</el-tag>
          </div>

          <div v-if="g.reasonLines.length" class="why-backend-lines">
            <div class="muted">后端留痕：</div>
            <div v-for="(line, i) in g.reasonLines" :key="i" class="why-backend-line">· {{ line }}</div>
          </div>

          <el-table
            :data="g.conditions"
            border
            size="small"
            :row-class-name="whyRowClass"
            empty-text="这条策略没有可对照的条件"
          >
            <el-table-column label="序号" width="60" align="center">
              <template #default="{ row }">{{ row.seq }}</template>
            </el-table-column>
            <el-table-column label="字段" min-width="140">
              <template #default="{ row }">
                <div>{{ row.fieldLabel || row.fieldCode }}</div>
                <div class="muted mono">{{ row.fieldCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作符" width="90" align="center">
              <template #default="{ row }">{{ auditOpText(row.opCode) }}</template>
            </el-table-column>
            <el-table-column label="期望 expected" min-width="130">
              <template #default="{ row }"><span class="mono">{{ row.expected }}</span></template>
            </el-table-column>
            <el-table-column label="实际 actual" min-width="150">
              <template #default="{ row }"><span class="mono">{{ row.actual }}</span></template>
            </el-table-column>
            <el-table-column label="结论" width="110" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'satisfied'" size="small" type="success" effect="plain">满足</el-tag>
                <el-tag v-else-if="row.status === 'unsatisfied'" size="small" type="danger" effect="plain">不满足</el-tag>
                <!-- AND 短路 / 字段没回传：显式标「未求值」，不混进「不满足」 -->
                <el-tag v-else size="small" type="info" effect="plain" data-testid="audit-why-unevaluated">未求值</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="" width="120" fixed="right" align="center">
              <template #default="{ row }">
                <span v-if="row.status === 'unsatisfied'" class="why-mark" data-testid="audit-why-mark">← 差在哪</span>
                <span v-else-if="row.status === 'unevaluated'" class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>

      <!-- 全局排除：不分策略，判定时并入每条自动策略；留人工的单很多就是被它挡掉的 -->
      <div v-if="whyTargets.length && globalExclusions.length" class="why-global" data-testid="audit-why-global">
        <div class="why-group-head">
          <span class="why-policy-name">全局排除</span>
          <span class="muted">以下条件对每条自动策略一并求值：任一条成立，那条策略就被排除（结论转人工）</span>
        </div>
        <div v-for="g in globalExclusions" :key="`g-${g.seq}`" class="why-backend-line">· 排除：{{ condText(g) }}</div>
      </div>

      <template #footer>
        <div class="why-footer">
          <el-button data-testid="audit-why-goto-policy" type="primary" plain @click="gotoPolicy">去改这条策略</el-button>
          <el-button data-testid="audit-why-manual-pass" type="success" @click="manualPass">人工通过</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- ============ 单笔抽屉：审核轨迹 tab ============ -->
    <el-drawer
      v-model="orderVisible"
      :title="`订单审核详情 - ${orderRow?.orderSn || ''}`"
      size="920px"
      data-testid="audit-order-drawer"
    >
      <el-tabs v-model="orderTab" data-testid="audit-order-tabs">
        <el-tab-pane label="审核轨迹" name="trail">
          <AuditTrail :order-id="orderRow?.orderId" />
        </el-tab-pane>
        <el-tab-pane label="判定摘要" name="summary">
          <el-descriptions v-if="orderRow" :column="2" border size="small">
            <el-descriptions-item label="单号">{{ orderRow.orderSn }}</el-descriptions-item>
            <el-descriptions-item label="第几轮">第 {{ orderRow.roundNo ?? '—' }} 轮</el-descriptions-item>
            <el-descriptions-item label="判定">
              <el-tag :type="auditResultTag(orderRow.result)" :class="auditResultClass(orderRow.result)" effect="dark" size="small">
                {{ auditResultText(orderRow.result) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="命中策略">{{ orderRow.matchedPolicyName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="原因" :span="2">{{ orderRow.reason || '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- ============ 执行记录 ============ -->
    <el-dialog v-model="runsVisible" title="自动审核执行记录" width="900px" data-testid="audit-runs-dialog">
      <el-table v-loading="runsLoading" :data="runs" border size="small" empty-text="还没有执行过">
        <el-table-column label="runId" width="90" prop="id" />
        <el-table-column label="触发" width="100">
          <template #default="{ row }">{{ auditTriggerText(row.triggerType) }}</template>
        </el-table-column>
        <el-table-column label="范围内" width="90" prop="total" align="right" />
        <el-table-column label="自动过审" width="100" align="right">
          <template #default="{ row }"><span class="c-green">{{ row.autoPass }}</span></template>
        </el-table-column>
        <el-table-column label="留人工" width="90" align="right">
          <template #default="{ row }"><span class="c-yellow">{{ row.manual }}</span></template>
        </el-table-column>
        <el-table-column label="阻止" width="80" align="right">
          <template #default="{ row }"><span class="c-red">{{ row.blocked }}</span></template>
        </el-table-column>
        <el-table-column label="冻结" width="80" align="right">
          <template #default="{ row }"><span class="c-purple">{{ row.frozen }}</span></template>
        </el-table-column>
        <el-table-column label="不命中" width="90" align="right">
          <template #default="{ row }"><span class="c-gray">{{ row.noMatch }}</span></template>
        </el-table-column>
        <el-table-column label="未参与" width="90" align="right" prop="skipped" />
        <el-table-column label="时间" min-width="165">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="runsQuery.page"
          v-model:page-size="runsQuery.size"
          :page-sizes="[10, 20, 50]"
          :total="runsTotal"
          layout="total, prev, pager, next"
          @current-change="loadRuns"
        />
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Tickets, VideoPlay } from '@element-plus/icons-vue'
import AuditTrail from '../../components/AuditTrail.vue'
import {
  listAuditQueue, queuePage, listAuditPolicies, policyBundle,
  listAuditRuns, runPage, runAudit,
  freezeOrderAudit, unfreezeOrderAudit
} from '../../api/audit'
import { auditPassDryRun, auditPassOrder } from '../../api/delivery'
import { useDangerConfirm } from '../../composables/useDangerConfirm'
import { explainRow, policiesForResult, expectedText } from '../../composables/useAuditExplain'
import {
  AUDIT_RESULT_TEXT, auditResultText, auditResultTag, auditResultClass, auditResultHint,
  auditOpText, auditTriggerText
} from '../../constants/status'

const router = useRouter()
const danger = useDangerConfirm()

/** 四类结局（被拦拆成 阻止 / 冻结两支，所以共 5 个可筛选 chip） */
const headChips = [
  { result: 'AUTO_PASS', label: AUDIT_RESULT_TEXT.AUTO_PASS },
  { result: 'MANUAL', label: AUDIT_RESULT_TEXT.MANUAL }
]
const tailChips = [{ result: 'NO_MATCH', label: AUDIT_RESULT_TEXT.NO_MATCH }]
const blockedChips = [
  { result: 'BLOCKED', label: AUDIT_RESULT_TEXT.BLOCKED },
  { result: 'FROZEN', label: AUDIT_RESULT_TEXT.FROZEN }
]

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const running = ref(false)
const query = reactive({ page: 1, size: 20, result: null })
const counts = reactive({ all: 0, AUTO_PASS: 0, MANUAL: 0, BLOCKED: 0, FROZEN: 0, NO_MATCH: 0, skipped: 0 })

const policies = ref([])
const globalExclusions = ref([])

/** 表格 :key：结局 + 订单 id —— 六支 id 各自自增的教训，单用 id 会撞 */
const rowKey = (row) => `${row.result}-${row.orderId}`

const money = (v) => {
  const n = Number(v)
  if (v === null || v === undefined || v === '' || !Number.isFinite(n)) return '—'
  return `¥ ${n.toFixed(2)}`
}
const isNegative = (v) => {
  const n = Number(v)
  return Number.isFinite(n) && n < 0
}
const fmtTime = (at) => (at ? String(at).replace('T', ' ').slice(0, 19) : '—')

/** 原因栏第一行：说「命中了哪条 / 为什么没命中」，第二行灰字才是后端原话（含数值差） */
const reasonHead = (row) => {
  switch (row.result) {
    case 'AUTO_PASS': return row.matchedPolicyName ? `命中「${row.matchedPolicyName}」→ 自动过审` : '自动过审'
    case 'BLOCKED': return row.matchedPolicyName ? `命中阻止策略「${row.matchedPolicyName}」` : '被阻止策略拦截'
    case 'FROZEN': return row.matchedPolicyName ? `命中异常策略「${row.matchedPolicyName}」→ 已冻结` : '被异常策略命中'
    case 'MANUAL': return row.matchedPolicyName ? `「${row.matchedPolicyName}」条件满足但被排除` : '需要人工确认'
    case 'NO_MATCH': return '没有任何自动策略命中'
    case 'SKIPPED': return '本轮未参与判定'
    default: return row.result || '未知结论'
  }
}

/** 一条条件的可读文案（全局排除块用；EXISTS/NOT_EXISTS 不显示值） */
const condText = (c) => {
  const label = c.fieldLabel || c.fieldCode
  const op = String(c.opCode || '').toUpperCase()
  if (op === 'EXISTS' || op === 'NOT_EXISTS') return `${label} ${auditOpText(op)}`
  return `${label} ${auditOpText(op)} ${expectedText(c)}`
}

const rowClass = ({ row }) => (row.result === 'SKIPPED' ? 'row-skipped' : '')

const loadQueue = async () => {
  loading.value = true
  try {
    const page = queuePage(
      await listAuditQueue({ result: query.result, page: query.page, size: query.size })
    )
    rows.value = page.rows
    total.value = page.total
  } catch (e) {
    rows.value = []
    total.value = 0
    ElMessage.error('加载审核队列失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

/**
 * chips 上的计数：队列接口只回**当前筛选**的 total，所以按五种结果各发一个 size=1 的请求读 total。
 * （后端若加一个 /order-audit/queue/counts 汇总接口，这里换成一次请求即可，页面其它逻辑不动）
 */
const loadCounts = async () => {
  const results = ['AUTO_PASS', 'MANUAL', 'BLOCKED', 'FROZEN', 'NO_MATCH']
  try {
    const [all, ...rest] = await Promise.all([
      listAuditQueue({ page: 1, size: 1 }).then((p) => queuePage(p).total),
      ...results.map((r) => listAuditQueue({ result: r, page: 1, size: 1 }).then((p) => queuePage(p).total))
    ])
    counts.all = all
    results.forEach((r, i) => {
      counts[r] = rest[i]
    })
    counts.skipped = Math.max(0, all - results.reduce((s, r) => s + counts[r], 0))
  } catch (e) {
    // 计数失败不打断主流程：chips 显示上一轮的数字，表格照常可用
    ElMessage.warning('统计各类数量失败：' + (e?.message || e))
  }
}

const loadPolicies = async () => {
  try {
    const bundle = policyBundle(await listAuditPolicies())
    policies.value = bundle.policies
    // 全局排除：判 AUTO 时会被并进每条策略，所以抽屉里要单独列出来（否则解释不完整）
    globalExclusions.value = bundle.globalExclusions
  } catch (e) {
    ElMessage.error('加载审核策略失败：' + (e?.message || e))
  }
}

const setFilter = (result) => {
  query.result = query.result === result ? null : result
  query.page = 1
  loadQueue()
}

const onSizeChange = () => {
  query.page = 1
  loadQueue()
}

const refreshAll = () => {
  loadQueue()
  loadCounts()
}

const runNow = async () => {
  try {
    await ElMessageBox.confirm(
      '将立即对待审核（状态 7）的订单跑一轮自动审核：命中的会推进状态、拦截的会留痕。是否继续？',
      '立即审核一轮',
      { type: 'warning', confirmButtonText: '开始执行', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  running.value = true
  try {
    const r = (await runAudit()) || {}
    ElMessage.success(
      `第 ${r.runId ?? '—'} 轮完成：范围内 ${r.total ?? 0} 笔 —— 自动过审 ${r.autoPass ?? 0}、`
      + `留人工 ${r.manual ?? 0}、阻止 ${r.blocked ?? 0}、冻结 ${r.frozen ?? 0}、`
      + `不命中 ${r.noMatch ?? 0}、未参与 ${r.skipped ?? 0}`
    )
    refreshAll()
  } catch (e) {
    ElMessage.error('执行失败：' + (e?.message || e))
  } finally {
    running.value = false
  }
}

// ---------------------------------------------------------------- 差在哪

const whyVisible = ref(false)
const whyRow = ref(null)

const openWhy = (row) => {
  whyRow.value = row
  whyVisible.value = true
}

/** 抽屉里要对照的策略：按结局选类别（阻止看 BLOCK，冻结看 EXCEPTION，其余看 AUTO） */
const whyTargets = computed(() => policiesForResult(whyRow.value?.result, policies.value))

/**
 * 每个策略一块：逐条件 expected vs actual。
 * 数据来源两级（见 composables/useAuditExplain.js）：
 *   ① 后端回传了结构化留痕（队列行 missReasons / conditions）→ 直接用，标「结论取自后端留痕」；
 *   ② 没有 → 用队列行上确实有的字段本地对一遍，取不到的字段如实标「未求值」。
 */
const whyGroups = computed(() => {
  if (!whyRow.value) return []
  return whyTargets.value.map((p) => explainRow(whyRow.value, p))
})

const whySource = computed(() => {
  const groups = whyGroups.value
  if (!groups.length) return 'LOCAL'
  return groups.some((g) => g.source === 'BACKEND') ? 'BACKEND' : 'LOCAL'
})

const whyRowClass = ({ row }) => (row.status === 'unsatisfied' ? 'why-row-miss' : row.status === 'unevaluated' ? 'why-row-unevaluated' : '')

/** 去改这条策略：带上策略 id 与第一条「差在哪」的条件 seq，策略页会高亮那一行 */
const gotoPolicy = () => {
  const groups = whyGroups.value
  const target = groups.find((g) => g.conditions.some((c) => c.status === 'unsatisfied')) || groups[0]
  if (!target) {
    ElMessage.warning('这类结论没有对应的策略可改')
    return
  }
  const miss = target.conditions.find((c) => c.status === 'unsatisfied')
  router.push({
    path: '/audit-policy',
    query: {
      type: whyRow.value?.result === 'BLOCKED' ? 'BLOCK' : whyRow.value?.result === 'FROZEN' ? 'EXCEPTION' : 'AUTO',
      focusPolicy: target.policyId,
      ...(miss ? { focusSeq: miss.seq } : {})
    }
  })
}

/** 人工通过：走「预演 → 确认 → 执行」（7 → 8），不是无声写库 */
const manualPass = async () => {
  const row = whyRow.value
  if (!row) return
  await danger.run({
    dryRun: () => auditPassDryRun(row.orderId),
    exec: () => auditPassOrder(row.orderId),
    title: `人工通过订单 ${row.orderSn}`,
    confirmText: '确认通过',
    successText: `订单 ${row.orderSn} 已人工审核通过`,
    onSuccess: () => {
      whyVisible.value = false
      refreshAll()
    }
  })
}

// ---------------------------------------------------------------- 冻结 / 解冻

const freeze = async (row) => {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      '冻结只会打标，**不会**改订单状态；本轮之后这张单不再参与自动审核，需人工解冻。',
      `冻结订单 ${row.orderSn}`,
      {
        confirmButtonText: '确认冻结',
        cancelButtonText: '取消',
        inputPlaceholder: '冻结原因（例如：疑似刷单，等风控确认）',
        inputValidator: (v) => (v && v.trim() ? true : '请填写冻结原因')
      }
    )
    reason = res.value
  } catch {
    return
  }
  try {
    await freezeOrderAudit(row.orderId, { reason })
    ElMessage.success('已冻结，下一轮不再自动审核')
    refreshAll()
  } catch (e) {
    ElMessage.error('冻结失败：' + (e?.message || e))
  }
}

const unfreeze = async (row) => {
  try {
    await ElMessageBox.confirm(
      '解冻会解除这张单上所有冻结类标；下一轮 run 才会重新判定。',
      `解冻订单 ${row.orderSn}`,
      { type: 'warning', confirmButtonText: '确认解冻', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const r = (await unfreezeOrderAudit(row.orderId)) || {}
    ElMessage.success(`已解冻（解除 ${r.released ?? 0} 个标）`)
    refreshAll()
  } catch (e) {
    ElMessage.error('解冻失败：' + (e?.message || e))
  }
}

// ---------------------------------------------------------------- 单笔抽屉

const orderVisible = ref(false)
const orderRow = ref(null)
const orderTab = ref('trail')

const openOrderDrawer = (row) => {
  orderRow.value = row
  orderTab.value = 'trail'
  orderVisible.value = true
}

// ---------------------------------------------------------------- 执行记录

const runsVisible = ref(false)
const runsLoading = ref(false)
const runs = ref([])
const runsTotal = ref(0)
const runsQuery = reactive({ page: 1, size: 10 })

const loadRuns = async () => {
  runsLoading.value = true
  try {
    const page = runPage(await listAuditRuns({ page: runsQuery.page, size: runsQuery.size }))
    runs.value = page.rows
    runsTotal.value = page.total
  } catch (e) {
    runs.value = []
    ElMessage.error('加载执行记录失败：' + (e?.message || e))
  } finally {
    runsLoading.value = false
  }
}

const openRuns = () => {
  runsVisible.value = true
  runsQuery.page = 1
  loadRuns()
}

onMounted(() => {
  loadQueue()
  loadCounts()
  loadPolicies()
})
</script>

<style scoped>
.card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-header .header-tip { margin-left: 10px; font-size: 12px; color: #909399; }
.header-actions { display: flex; gap: 8px; }

/* ---- chips：既是筛选器也是计数 ---- */
.chips { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.chip {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 6px 14px; border-radius: 16px; cursor: pointer;
  border: 1px solid #dcdfe6; background: #fff; font-size: 13px; color: #303133;
  transition: all .15s;
}
.chip:hover { border-color: #c0c4cc; }
.chip-count { font-weight: 700; }
.chip-active { color: #fff; }
.chip-all.chip-active { background: #303133; border-color: #303133; }
/* 绿 / 黄 / 红 / 紫 / 灰 —— 与表格徽标同色系，一眼对上 */
.chip-auto_pass { border-color: #67c23a; color: #529b2e; }
.chip-auto_pass.chip-active { background: #67c23a; border-color: #67c23a; color: #fff; }
.chip-manual { border-color: #e6a23c; color: #b88230; }
.chip-manual.chip-active { background: #e6a23c; border-color: #e6a23c; color: #fff; }
.chip-blocked { border-color: #f56c6c; color: #c45656; }
.chip-blocked.chip-active { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.chip-frozen { border-color: #722ed1; color: #5b21b6; }
.chip-frozen.chip-active { background: #722ed1; border-color: #722ed1; color: #fff; }
.chip-no_match { border-color: #909399; color: #606266; }
.chip-no_match.chip-active { background: #909399; border-color: #909399; color: #fff; }
.chip-group {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 10px; border-radius: 18px; background: #fdf6ec; border: 1px dashed #f0c78a;
}
.chip-group-label { font-size: 12px; color: #b88230; }
.chip-sub { padding: 4px 12px; }
.chip-note { font-size: 12px; color: #909399; }
.chip-note .muted { color: #c0c4cc; }

.muted { color: #909399; font-size: 12px; }
.datetime { font-size: 13px; color: #606266; }
.money { font-family: Consolas, Monaco, monospace; }
.money-negative { font-family: Consolas, Monaco, monospace; color: #f56c6c; font-weight: 600; }
.mono { font-family: Consolas, Monaco, monospace; }
.reason-main { font-size: 13px; color: #303133; }
.reason-sub { font-size: 12px; color: #909399; margin-top: 2px; word-break: break-all; }
.row-skipped { opacity: .6; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }

.why-global { margin-bottom: 18px; padding: 10px 12px; background: #fff8f0; border: 1px dashed #f0c78a; border-radius: 4px; }
.why-global .why-backend-line { color: #b88230; }

/* ---- 徽标五色（FROZEN 用紫色：el-tag 没有紫档，这里覆盖） ---- */
:deep(.badge-frozen) { background: #722ed1 !important; border-color: #722ed1 !important; color: #fff !important; }
:deep(.badge-skipped) { opacity: .75; }

/* ---- 「差在哪」抽屉 ---- */
.why-head { display: flex; align-items: center; gap: 10px; }
.why-hint { font-size: 12px; color: #909399; }
.why-reason { margin-top: 8px; padding: 8px 10px; background: #fafafa; border-left: 3px solid #dcdfe6; font-size: 13px; color: #303133; }
.why-meta { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin: 12px 0; font-size: 13px; color: #606266; }
.why-group { margin-bottom: 20px; }
.why-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.why-policy-name { font-weight: 600; font-size: 14px; }
.why-backend-lines { font-size: 12px; color: #606266; margin-bottom: 6px; }
.why-backend-line { line-height: 20px; }
.why-mark { color: #f56c6c; font-weight: 600; white-space: nowrap; }
:deep(.why-row-miss) { background: #fef0f0; }
:deep(.why-row-unevaluated) { background: #fafafa; color: #909399; }
.why-footer { display: flex; justify-content: flex-end; gap: 10px; }
.c-green { color: #529b2e; font-weight: 600; }
.c-yellow { color: #b88230; font-weight: 600; }
.c-red { color: #c45656; font-weight: 600; }
.c-purple { color: #5b21b6; font-weight: 600; }
.c-gray { color: #606266; font-weight: 600; }
</style>
