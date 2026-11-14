<template>
  <div ref="pageRef" class="reconcile">
    <el-card shadow="never">
      <template #header>
        <div class="card-header card-header--baseline">
          <span>库存对账</span>
          <span class="header-tip">
            库存桶（sku_bucket）是权威事实源，sku.stock 只是展示缓存；对账就是把两者摆在一起看偏差
          </span>
        </div>
      </template>

      <!-- 选 SKU：可直接输入 skuId，也可从 SKU 列表里挑（列表按规格文本过滤） -->
      <div class="filters">
        <el-input
          v-model="skuIdText"
          data-testid="reconcile-sku-input"
          placeholder="输入 SKU ID"
          clearable
          style="width: 180px"
          @keyup.enter="loadReconcile"
          @clear="clearDetail"
        />
        <el-select
          v-model="pickedSkuId"
          data-testid="reconcile-sku-picker"
          filterable
          clearable
          placeholder="或从 SKU 列表选择（按规格文本过滤）"
          style="width: 300px"
          :loading="skusLoading"
          @change="onPickSku"
        >
          <el-option
            v-for="s in skus"
            :key="s.id"
            :label="`#${s.id} · ${s.specText || '默认规格'} · 缓存库存 ${s.stock ?? 0}`"
            :value="s.id"
          />
        </el-select>
        <el-button type="primary" :icon="Search" :loading="loadingDetail" data-testid="reconcile-search-submit" @click="loadReconcile">
          对账
        </el-button>
      </div>

      <template v-if="detail">
        <!-- ① 应当相等的一对：权威值 vs 展示缓存；② 偏差＝两者之差 -->
        <div class="cards">
          <div class="stat-card">
            <div class="stat-label">权威值（SUM 库存桶）</div>
            <div class="stat-value" data-testid="reconcile-bucket-sum">{{ num(detail.bucketSum) }}</div>
            <div class="stat-foot">
              桶数 {{ detail.bucketCount ?? '—' }} 个
              <el-text type="warning">（与默认 {{ EXPECTED_BUCKET_COUNT }} 个不一致，检查桶配置/是否被误删）</el-text>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-label">展示缓存（sku.stock）</div>
            <div class="stat-value" data-testid="reconcile-sku-stock">{{ num(detail.skuStock) }}</div>
            <!-- 不是「缓存最后回写」：sku 表没有回写时间列，后端给的是 MAX(sku_bucket.updated_at) -->
            <div class="stat-foot">
              桶最近变动：{{ detail.lastRefreshAt ? fmtTime(detail.lastRefreshAt) : '无记录' }}
              <div class="muted">
                数据来源 MAX(sku_bucket.updated_at)（该 SKU 库存桶最近一次变动）；
                库里没有「缓存回写时间」列，所以这不是缓存回写时刻
              </div>
            </div>
          </div>

          <div class="stat-card" :class="drift === 0 ? 'stat-ok' : 'stat-bad'">
            <div class="stat-label">偏差（权威 − 缓存）</div>
            <el-text class="stat-value" :type="drift === 0 ? 'success' : 'danger'" data-testid="reconcile-drift">
              {{ drift > 0 ? '+' : '' }}{{ drift }}
            </el-text>
            <div class="stat-foot">
              {{ drift === 0 ? '两者一致，缓存没有滞后' : '缓存与权威不一致：正的偏差＝缓存少记，负＝缓存多记' }}
            </div>
          </div>
        </div>

        <div class="sku-line">
          SKU #{{ detail.skuId }} · {{ detail.skuName || '（无名称）' }} · {{ detail.skuCode || '（无编码）' }}
        </div>

        <!-- ③ 台账净额：单独一块，且必须写清「不是余额」，不能与上面的权威值对平 -->
        <div class="net-block" data-testid="reconcile-ledger-net">
          <div class="net-head">
            <span class="net-label">台账净额（SUM stock_ledger.delta）</span>
            <span class="net-value">{{ num(detail.ledgerNet) > 0 ? '+' : '' }}{{ num(detail.ledgerNet) }}</span>
          </div>
          <div class="net-note">
            <strong>这是全生命周期进出和，不是余额。</strong>
            它把该 SKU 历史上所有流水增减累加在一起（进多少、出多少全算），
            所以<strong>不能与上面的「权威值」相互对平</strong>，两者本就不应相等。
            它只用来说明一件事：这单到底补过库存没有、补了多少。
          </div>
        </div>

        <!-- 最近 20 条流水 -->
        <el-text tag="b" size="small" class="block-title">最近 {{ ledgerRows.length }} 条库存流水</el-text>
        <el-table
          :data="ledgerRows"
          border
          size="small"
          empty-text="该 SKU 还没有库存流水"
          data-testid="reconcile-ledger-table"
        >
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="原因" min-width="160">
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
          <el-table-column label="增减" width="100">
            <template #default="{ row }">
              <el-text tag="b" :type="Number(row.delta) >= 0 ? 'success' : 'danger'">
                {{ Number(row.delta) > 0 ? '+' : '' }}{{ row.delta }}
              </el-text>
            </template>
          </el-table-column>
          <el-table-column label="落点桶" width="90">
            <template #default="{ row }">{{ row.bucketNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="关联单据" min-width="180">
            <template #default="{ row }">
              <span v-if="row.refType || row.refId">{{ row.refType || '—' }}#{{ row.refId ?? '—' }}</span>
              <span v-else class="muted">—</span>
              <span v-if="row.idemKey" class="muted idem">幂等键 {{ row.idemKey }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <el-empty v-else description="输入 SKU ID 后点「对账」" :image-size="90" />
    </el-card>

    <!-- 全库偏差 TOP -->
    <el-card shadow="never" class="drift-card">
      <template #header>
        <div class="card-header card-header--baseline">
          <span>全库偏差 TOP</span>
          <span class="header-tip">|偏差| ≥ 阈值的 SKU，按偏差倒序；点行可跳到该 SKU 的对账</span>
        </div>
      </template>

      <div class="filters">
        <el-text>偏差阈值</el-text>
        <el-input-number
          v-model="threshold"
          data-testid="reconcile-drift-threshold"
          :min="0"
          :step="10"
          :precision="0"
          :controls="false"
          style="width: 110px"
          @change="searchDrift"
        />
        <el-button :icon="Refresh" data-testid="reconcile-drift-refresh" @click="searchDrift">刷新</el-button>
      </div>

      <el-table
        v-loading="driftLoading"
        :data="driftList"
        border
        size="small"
        empty-text="没有超过阈值的偏差 —— 缓存与桶一致（或阈值设得太高）"
        data-testid="reconcile-drift-table"
        @row-click="onDriftRowClick"
      >
        <el-table-column prop="skuId" label="SKU ID" width="110" />
        <el-table-column label="SKU 编码" min-width="160">
          <template #default="{ row }">{{ row.skuCode || '—' }}</template>
        </el-table-column>
        <el-table-column label="权威值（SUM 桶）" width="150">
          <template #default="{ row }">{{ num(row.bucketSum) }}</template>
        </el-table-column>
        <el-table-column label="展示缓存（sku.stock）" width="170">
          <template #default="{ row }">{{ num(row.skuStock) }}</template>
        </el-table-column>
        <el-table-column label="偏差" width="110">
          <template #default="{ row }">
            <el-text tag="b" type="danger">{{ Number(row.drift) > 0 ? '+' : '' }}{{ row.drift }}</el-text>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="driftQuery.page"
          data-testid="reconcile-drift-pagination"
          :page-size="driftQuery.size"
          :total="driftTotal"
          layout="prev, pager, next, total"
          background
          @current-change="loadDrift"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getReconcile, listReconcileDrift } from '../../api/inventory'
import { listSkus } from '../../api/sku'

/** 桶数的默认配置值（后端 N，默认 8）。前端拿不到「真实配置」，所以只在不等时提示「与默认值不一致」 */
const EXPECTED_BUCKET_COUNT = 8

const pageRef = ref(null)
const skuIdText = ref('')
const pickedSkuId = ref(null)
const detail = ref(null)
const loadingDetail = ref(false)

const skus = ref([])
const skusLoading = ref(false)

const driftList = ref([])
const driftLoading = ref(false)
const driftTotal = ref(0)
const threshold = ref(100)
const driftQuery = reactive({ page: 1, size: 10 })

const num = (v) => (v === null || v === undefined || v === '' ? 0 : Number(v))
/** 偏差：权威值 − 缓存。后端已给 drift 字段，这里只做兜底计算 */
const drift = computed(() => {
  const d = detail.value
  if (!d) return 0
  return d.drift !== null && d.drift !== undefined ? Number(d.drift) : num(d.bucketSum) - num(d.skuStock)
})
const bucketCountOff = computed(() => {
  const c = detail.value?.bucketCount
  return c !== null && c !== undefined && Number(c) !== EXPECTED_BUCKET_COUNT
})
const ledgerRows = computed(() => (Array.isArray(detail.value?.ledgerRows) ? detail.value.ledgerRows : []))

/** 后端时间可能是 'YYYY-MM-DD HH:mm:ss' 或 ISO，统一成可读形式 */
const fmtTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 19) : '—')

const clearDetail = () => {
  detail.value = null
  pickedSkuId.value = null
}

const loadReconcile = async () => {
  const id = String(skuIdText.value ?? '').trim()
  if (!id) {
    ElMessage.warning('请先输入或选择一个 SKU')
    return
  }
  if (!/^\d+$/.test(id)) {
    ElMessage.warning('SKU ID 只能是数字')
    return
  }
  loadingDetail.value = true
  try {
    detail.value = await getReconcile(id)
    pickedSkuId.value = Number(id)
  } catch (e) {
    // 失败不留旧结果：旧卡片的数字会被误当成这次查询的结果
    detail.value = null
    ElMessage.error('对账失败：' + e.message)
  } finally {
    loadingDetail.value = false
  }
}

const onPickSku = (id) => {
  if (id === null || id === undefined || id === '') {
    clearDetail()
    return
  }
  skuIdText.value = String(id)
  loadReconcile()
}

const loadSkus = async () => {
  skusLoading.value = true
  try {
    // 这里只为了给人挑：全量 SKU 列表（id / specText / 缓存库存）
    const rows = await listSkus()
    skus.value = Array.isArray(rows) ? rows : []
  } catch {
    // 选择器拉不到不影响主流程（仍可直接输入 skuId），静默降级
    skus.value = []
  } finally {
    skusLoading.value = false
  }
}

const searchDrift = () => {
  driftQuery.page = 1
  loadDrift()
}

const loadDrift = async () => {
  driftLoading.value = true
  try {
    const pageData = await listReconcileDrift({
      page: driftQuery.page,
      size: driftQuery.size,
      threshold: threshold.value ?? 100
    })
    driftList.value = pageData?.records || []
    driftTotal.value = Number(pageData?.total || 0)
  } catch (e) {
    driftList.value = []
    driftTotal.value = 0
    ElMessage.error('偏差 TOP 加载失败：' + e.message)
  } finally {
    driftLoading.value = false
  }
}

const onDriftRowClick = (row) => {
  if (!row?.skuId) return
  skuIdText.value = String(row.skuId)
  loadReconcile()
  // 对账卡片在页面上方，滚回去，别让人以为点了没反应。
  // 注意滚动容器是 layout 的 .layout-main（不是 window），所以用 scrollIntoView 让最近的
  // 可滚动祖先自己滚，window.scrollTo 在这里是无效的。
  pageRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
}

onMounted(() => {
  loadSkus()
  loadDrift()
})
</script>

<style scoped>
.reconcile { display: flex; flex-direction: column; gap: 16px; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.sku-line { margin: 4px 0 14px; font-size: 13px; color: #606266; }

/* 三块卡片：权威值 / 展示缓存 / 偏差 —— 前两块才是应当相等的一对 */
.cards { display: flex; gap: 14px; flex-wrap: wrap; }
.stat-card {
  flex: 1 1 220px;
  min-width: 220px;
  padding: 14px 16px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fafafa;
}
.stat-label { font-size: 13px; color: #606266; margin-bottom: 6px; }
.stat-value { font-size: 26px; font-weight: 600; color: #303133; line-height: 1.2; }
.stat-foot { margin-top: 8px; font-size: 12px; color: #909399; line-height: 1.6; }
.stat-ok { border-color: #c2e7b0; background: #f0f9eb; }
.stat-bad { border-color: #fbc4c4; background: #fef0f0; }

/* 台账净额：故意单独一块、视觉上与上面三块隔开，避免被当成「第四个应当对平的数」 */
.net-block {
  margin: 16px 0 18px;
  padding: 12px 16px;
  border-left: 4px solid #e6a23c;
  background: #fdf6ec;
  border-radius: 4px;
}
.net-head { display: flex; align-items: baseline; gap: 10px; }
.net-label { font-size: 13px; color: #7a5c1f; }
.net-value { font-size: 20px; font-weight: 600; color: #b88230; }
.net-note { margin-top: 8px; font-size: 12px; color: #8a6d3b; line-height: 1.8; }

/* 区块小标题：字重/字号交给 el-text，这里只剩"独占一行 + 下边距" */
.block-title { display: block; margin: 0 0 8px; }
.idem { display: block; }
.drift-card { margin-bottom: 8px; }
</style>
