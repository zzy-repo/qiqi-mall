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
    </div>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listLedgers } from '../../api/ledger'
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
