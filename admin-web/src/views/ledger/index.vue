<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>台账查询</span>
        <span class="header-tip">
          全链路状态变更流水（订单 / 包裹 / 售后 / 审计 / WMS / 库存 / 支付）· 只读，按时间倒序
        </span>
      </div>
    </template>

    <!-- 筛选区：类型 / 业务ID / 操作人类型 / 时间范围 -->
    <div class="filters" data-testid="ledger-filters">
      <el-select
        v-model="query.type"
        data-testid="ledger-type-select"
        placeholder="全部类型"
        clearable
        style="width: 150px"
        @change="search"
      >
        <el-option v-for="t in LEDGER_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>

      <el-input
        v-model="query.bizId"
        data-testid="ledger-biz-id-input"
        placeholder="业务ID（订单/包裹/售后的数字 ID）"
        clearable
        style="width: 240px"
        @keyup.enter="search"
        @clear="search"
      />

      <el-select
        v-model="query.operatorType"
        data-testid="ledger-operator-type-select"
        placeholder="全部操作人"
        clearable
        style="width: 150px"
        @change="search"
      >
        <el-option v-for="o in OPERATOR_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>

      <!-- el-date-picker 不会把透传属性落到真实 input 上，testid 挂外层容器（同 api-key 的做法） -->
      <div data-testid="ledger-date-range">
        <el-date-picker
          v-model="range"
          type="daterange"
          unlink-panels
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          :clearable="true"
          style="width: 260px"
          @change="search"
        />
      </div>

      <el-button type="primary" :icon="Search" data-testid="ledger-search-submit" @click="search">查询</el-button>
      <el-button :icon="Refresh" data-testid="ledger-search-reset" @click="resetQuery">重置</el-button>
    </div>

    <!-- 列：时间 / 类型 / 业务单号 / 变更(前态→后态) / 原因 / 操作人 / 摘要 -->
    <el-table
      v-loading="loading"
      :data="list"
      :row-key="rowKey"
      border
      empty-text="没有符合条件的台账记录"
      data-testid="ledger-list-table"
    >
      <el-table-column label="时间" width="170">
        <template #default="{ row }">
          <!-- 每行的 testid 挂在时间格上：el-table 无法给 <tr> 直接加 data-testid -->
          <!-- key 必须带 type：七支台账的 id 各自自增，合并到一个列表后 id 会撞（见 rowKey） -->
          <span :data-testid="`ledger-row-${rowKey(row)}`" class="datetime">{{ fmtTime(row.createdAt) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="类型" width="112">
        <template #default="{ row }">
          <el-tag :type="ledgerTypeTag(row.type)" size="small" effect="plain">{{ ledgerTypeText(row.type) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="业务单号" min-width="180">
        <template #default="{ row }">
          <div class="biz-sn">{{ row.bizSn || '—' }}</div>
          <div class="muted">ID {{ row.bizId ?? '—' }}</div>
        </template>
      </el-table-column>

      <!-- 变更：同一个数字在不同 type 下含义不同，必须带上 row.type 才能翻译（见 constants/status.js） -->
      <el-table-column label="变更（前态 → 后态）" min-width="230">
        <template #default="{ row }">
          <span class="state-from">{{ ledgerStatusText(row.type, row.fromStatus) }}</span>
          <span class="state-arrow">→</span>
          <span class="state-to">{{ ledgerStatusText(row.type, row.toStatus) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="原因" min-width="200">
        <template #default="{ row }">
          <div class="reason">{{ row.reason || '—' }}</div>
          <div v-if="row.reasonDetail" class="muted">{{ row.reasonDetail }}</div>
        </template>
      </el-table-column>

      <el-table-column label="操作人" width="150">
        <template #default="{ row }">
          <div class="operator">{{ row.operatorName || '—' }}</div>
          <el-tag :type="operatorTypeTag(row.operatorType)" size="small" effect="plain">
            {{ operatorTypeText(row.operatorType) }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="摘要" min-width="220">
        <template #default="{ row }">
          <div class="summary">{{ row.summary || '—' }}</div>
          <div v-if="row.refType || row.refId" class="muted">
            关联：{{ row.refType || '—' }}#{{ row.refId ?? '—' }}
          </div>
        </template>
      </el-table-column>

      <!-- 操作：**只有「支付流水」行有动作**（重放渠道通知）。
           其余六支台账是纯状态流水，没有可重放的渠道通知，所以那些行的这一格是空的 ——
           刻意不显示「—」，因为七类里只有一类有动作，摆六个破折号反而像坏了。 -->
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="canReplay(row)"
            link
            type="warning"
            size="small"
            :data-testid="`ledger-replay-${rowKey(row)}`"
            @click="replay(row)"
          >重放渠道通知</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页：与 order/index.vue 同一套写法（参数名 page / size） -->
    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        data-testid="list-pagination"
        :page-size="query.size"
        :total="total"
        layout="prev, pager, next, total"
        background
        @current-change="load"
      />
    </div>

    <div class="tip">
      台账是追加型流水：记录的是「谁在什么时候把状态从哪改到哪、为什么」，
      所以它可解释、但不可改。查到空结果＝这段时间内确实没有对应变更，不是页面出错。
      <br />
      「支付流水」类型下的每一行都可以<b>重放渠道通知</b>（沙箱动作，只在非生产环境开着）：
      它拿这条流水的原 trade_no 再走一遍渠道回调，模拟「第三方把同一笔通知又发了一次」。
      已入过的账不会再记一次 —— 所以点完**行数不变才是正确结果**。
    </div>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listLedgers } from '../../api/ledger'
import { replayChannelCallback } from '../../api/payment'
import { useDangerConfirm } from '../../composables/useDangerConfirm'
import {
  LEDGER_TYPE_OPTIONS,
  OPERATOR_TYPE_OPTIONS,
  ledgerTypeText,
  ledgerTypeTag,
  ledgerStatusText,
  operatorTypeText,
  operatorTypeTag
} from '../../constants/status'

const list = ref([])
const loading = ref(false)
const total = ref(0)
/** 参数名固定 page / size（不是 current / pageSize）；size 上限 50，这里给 10 */
const query = reactive({ page: 1, size: 10, type: null, bizId: '', operatorType: null })
/** el-date-picker 的 daterange 值：[ 'YYYY-MM-DD', 'YYYY-MM-DD' ] */
const range = ref(null)

const search = () => {
  query.page = 1
  load()
}

const resetQuery = () => {
  query.type = null
  query.bizId = ''
  query.operatorType = null
  range.value = null
  search()
}

/**
 * 行唯一标识 = `${type}-${id}`。
 *
 * 七个分支（订单/包裹/售后/审计/WMS/库存/支付）的 id 来自**各自的自增序列**，UNION 到一张表后
 * 必然出现重复 id（订单台账第 12 行与包裹台账第 12 行是两条完全不同的记录）。
 * 所以行 key / data-testid 一律带 type：只用 row.id 会让 el-table 把两行当成同一行（复用错节点），
 * 自动化用例的 `ledger-row-<id>` 也会撞。E2E 用 `ledger-row-` 前缀匹配，格式变了仍然命中。
 */
const rowKey = (row) => `${row.type}-${row.id}`

/** 后端时间可能是 'YYYY-MM-DD HH:mm:ss' 或 ISO（带 T），统一成可读形式 */
const fmtTime = (value) => {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

/** 时间范围 → from / to：日期选择器只到天，补足当天首尾时刻，避免漏当天的记录 */
const rangeParams = () => {
  const r = range.value
  if (!Array.isArray(r) || r.length !== 2 || !r[0] || !r[1]) return {}
  return { from: `${r[0]} 00:00:00`, to: `${r[1]} 23:59:59` }
}

const load = async () => {
  loading.value = true
  try {
    const pageData = await listLedgers({
      page: query.page,
      size: query.size,
      type: query.type || undefined,
      bizId: query.bizId ? String(query.bizId).trim() || undefined : undefined,
      operatorType: query.operatorType || undefined,
      ...rangeParams()
    })
    list.value = pageData?.records || []
    total.value = Number(pageData?.total || 0)
  } catch (e) {
    // 失败时清空而不是留着上一页的旧数据，否则用户会以为「查询结果是这些」
    list.value = []
    total.value = 0
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

/* ------------------------------------------------------------------ *
 * 重放渠道通知（只对「支付流水」行开放的高危动作）
 *
 * 背景：后端 `POST /admin/payments/{id}/replay-channel-callback` 早就有了（用原 trade_no
 * 再走一遍渠道回调，模拟「第三方把同一笔通知又发了一次」），但前端**零入口** ——
 * 于是「重复通知被 uk_channel_trade 挡住」这件事在界面上永远演示不出来，只能靠直查库。
 * 这个按钮就是那个缺口：点一次，看见「重复通知，已忽略（流水未增加）」，而列表行数不变。
 * ------------------------------------------------------------------ */

const danger = useDangerConfirm()

/**
 * 按钮显示条件：**台账行的 `type` 恒等于 'PAYMENT'**。
 *
 * 判据取自后端投影本身，不是前端按 id/摘要猜的：`LedgerSql.PAYMENT_BRANCH` 第一列硬写
 * `SELECT 'PAYMENT' AS type`，而这一支是**唯一**读 `payment_record` 的分支（其余六支读视图
 * v_biz_status_timeline / admin_audit_log / wms_exchange_ledger / stock_ledger）。
 * 「这一行有 (channel, trade_no) 可重放」与「这一行来自 payment_record」是同一件事，
 * 所以判 type 就够；`id` 非空则是因为它要拼进 URL（`/admin/payments/{id}/...`）。
 *
 * 另外：支付流水在台账页没有独立 tab（它就是「类型」下拉里的第七项），所以按钮挂在该类型的行上，
 * 不新建页面；订单详情抽屉里刻意不加 —— 一张订单可能有多笔流水（支付 + N 笔退款），
 * 从订单层看不出来「你要重放哪一条」，加在那里只会制造歧义。
 */
const canReplay = (row) => row?.type === 'PAYMENT' && row?.id != null

/**
 * 本地构造的「预演」。
 *
 * 这个端点**没有** `?dryRun=true` 分支（它自己就是「只记账、不推订单状态」的沙箱动作，
 * 预演一遍等于把「会不会重复」提前答了）。而 `danger.run` 只要求 dryRun 是
 * 「返回 Promise<{ willDo }> 的函数」，不要求它真发请求 —— 与 views/member/index.vue 删除会员
 * 同一套做法：不硬套一个不存在的后端预演，也不因此绕开确认，该说清的后果一样说清。
 */
const replayDryRun = (row) => async () => ({
  willDo:
    `将用这条流水的原渠道流水号（trade_no=${row.reasonDetail || '（后端行内未给出）'}）` +
    '再走一遍渠道回调逻辑，等于模拟「第三方把同一笔通知又发了一次」。'
    + '已经入过的账不会再记一次：若库里已存在同一个 (channel, trade_no)，'
    + '这次不会新增任何流水（幂等键 uk_channel_trade 拦住），列表行数应保持不变。'
    + '该动作只记支付流水，不改订单状态。'
})

/** 弹窗明细区：把「要重放的是哪一条」摊开（全部取自行数据，不发新请求） */
const replayDetails = (row) => () => [
  { label: '订单号', value: row.bizSn || '（该流水没有订单号）' },
  { label: '支付流水', value: `#${row.id}` },
  { label: '渠道流水号', value: row.reasonDetail || '（无）' },
  { label: '摘要', value: row.summary || '—' }
]

/**
 * 重放渠道通知：高危确认 → 调接口 → **按三种结果分别提示** → 刷新当前页。
 *
 * 三态（后端 `data.outcome` 决定，别按 HTTP 状态或 code 猜）：
 *   · outcome=RECORDED → success「渠道通知已入账」（真的新增了 1 行）；
 *   · outcome=DUPLICATE → **warning**「重复通知，已忽略（流水未增加）」—— 这不是错误，是渠道的正常行为；
 *   · code=1（沙箱开关关着 / 流水不存在 / 没有 trade_no / 疑似重复扣款需人工对账）
 *     → error 弹后端中文原文，并且**不关窗**（原因留在弹窗里，与全站 14 个高危入口同口径）。
 *
 * 为什么 DUPLICATE 要在 exec 里 throw 一个带标记的异常：
 *   `danger.run().execute()` 在 exec 成功后**必定**弹一条 `success` 级 toast（文案取 successText），
 *   而这里「重复通知」必须用 warning 级 —— 直接把 successText 设成重复文案会把它显示成成功的绿色提示，
 *   把「没多记」说成「已入账」。所以用 throw 把控制权交给 onError：它认领这个标记、换成 warning 级提示、
 *   刷新列表并收起弹窗（`return true` = 已自行处理，绝不落进弹窗的红色错误区）。
 */
const replay = async (row) => {
  await danger.run({
    title: '⚠️ 确认重放渠道通知',
    confirmText: '确认重放',
    // 只有 RECORDED 会用到它；DUPLICATE 在 exec 里被转成标记异常，交给 onError 用 warning 级提示
    successText: '渠道通知已入账',
    dryRun: replayDryRun(row),
    details: replayDetails(row),
    exec: async () => {
      const data = await replayChannelCallback(row.id)
      if (data?.outcome === 'DUPLICATE') {
        const dup = new Error(data?.message || '重复通知，已忽略')
        dup.duplicateNotice = true
        throw dup
      }
      return data
    },
    onError: (e) => {
      // ★ 必须**同步**返回 true（useDangerConfirm 里判的是 `_onError(e) === true`，不会 await）：
      //   写成 async 会返回 Promise，`Promise === true` 为 false → 重复通知会掉进弹窗的红色错误区。
      if (e?.duplicateNotice) {
        ElMessage.warning('重复通知，已忽略（流水未增加）')
        // 刷新当前页：行数应当不变 —— 这正是这个入口存在的意义（幂等键挡住了第二次入账）。
        // 不 await：这里只负责「发起刷新」，run() 随即收起弹窗，列表自己带着 loading 更新。
        load()
        return true
      }
      // 真失败：后端中文原文原样弹出（不打码、不加工），弹窗保留原因
      ElMessage.error(e?.message || '重放失败')
      return false
    },
    onSuccess: () => load()
  })
}

onMounted(load)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: baseline; }
.header-tip { font-size: 12px; color: #909399; }
.filters { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.tip { margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.7; }
.datetime { font-size: 13px; color: #606266; }
.biz-sn { font-family: 'SFMono-Regular', Consolas, Monaco, monospace; font-size: 13px; color: #303133; }
.muted { color: #909399; font-size: 12px; }
.state-from { color: #909399; }
.state-arrow { margin: 0 6px; color: #c0c4cc; }
.state-to { color: #303133; font-weight: 600; }
.reason { color: #303133; }
.operator { color: #303133; margin-bottom: 2px; }
.summary { color: #303133; }
</style>
