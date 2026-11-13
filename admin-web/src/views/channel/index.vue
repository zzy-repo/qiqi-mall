<template>
  <div class="channel">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>多平台接入</span>
          <span class="header-tip">
            拉单 → 统一模型 → 待审核订单(7) → 审核/拆单/出库 → 回传发货；全程写同步日志。
            回传发货按钮在「订单列表 → 明细抽屉」里（要先有已出库的包裹）。
          </span>
        </div>
      </template>

      <!-- 三张店铺卡：平台 / 启用 / 水位 / 上次拉单 / 今日成功·失败 -->
      <div class="shop-cards" v-loading="shopsLoading">
        <div
          v-for="shop in shops"
          :key="shop.id"
          class="shop-card"
          :data-testid="`channel-shop-card-${shop.platformType}`"
        >
          <div class="shop-head">
            <span class="shop-platform">{{ platformText[shop.platformType] || shop.platformType }}</span>
            <el-tag :type="shop.enabled === 1 ? 'success' : 'info'" size="small" effect="plain">
              {{ shop.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </div>
          <div class="shop-name">{{ shop.shopName }}<span class="shop-code">{{ shop.shopCode }}</span></div>

          <div class="shop-meta">
            <div class="meta-row">
              <span class="meta-label">拉单水位</span>
              <span class="meta-value" :data-testid="`channel-shop-watermark-${shop.platformType}`">
                {{ fmtTime(shop.watermarkAt) }}
              </span>
            </div>
            <div class="meta-row">
              <span class="meta-label">上次拉单</span>
              <span class="meta-value">{{ fmtTime(shop.lastPullAt) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">今日成功 / 失败</span>
              <span class="meta-value">
                <span class="ok-num">{{ shop.todayOk ?? 0 }}</span>
                <span class="sep">/</span>
                <span class="fail-num">{{ shop.todayFail ?? 0 }}</span>
                <span class="skip-num">（跳过 {{ shop.todaySkip ?? 0 }}）</span>
              </span>
            </div>
          </div>

          <div class="shop-actions">
            <el-button
              type="primary"
              size="small"
              :loading="pulling === shop.id"
              :data-testid="`channel-pull-btn-${shop.platformType}`"
              @click="pull(shop)"
            >立即拉单</el-button>
            <span class="action-hint">回传发货 → 订单明细抽屉</span>
          </div>
        </div>
        <el-empty v-if="!shopsLoading && shops.length === 0" description="还没有渠道店铺（先跑 sql/migrate-channel.sql）" />
      </div>
    </el-card>

    <el-card shadow="never" class="log-card">
      <template #header>
        <div class="card-header">
          <span>同步日志</span>
          <div class="filters">
            <el-select
              v-model="query.shopId"
              data-testid="channel-log-filter-shop"
              placeholder="全部店铺"
              clearable
              style="width: 160px"
              @change="search"
            >
              <el-option v-for="s in shops" :key="s.id" :label="s.shopName" :value="s.id" />
            </el-select>
            <el-select
              v-model="query.action"
              data-testid="channel-log-filter-action"
              placeholder="全部动作"
              clearable
              style="width: 130px"
              @change="search"
            >
              <el-option label="拉单" value="PULL" />
              <el-option label="回传发货" value="SHIP_PUSH" />
            </el-select>
            <el-select
              v-model="query.result"
              data-testid="channel-log-filter-result"
              placeholder="全部结果"
              clearable
              style="width: 120px"
              @change="search"
            >
              <el-option label="成功" value="OK" />
              <el-option label="失败" value="FAIL" />
              <el-option label="跳过" value="SKIP" />
            </el-select>
            <el-button :icon="Refresh" data-testid="channel-log-refresh" @click="loadLogs">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="logsLoading" :data="logs" border size="small" data-testid="channel-sync-log-table">
        <!-- 行锚点落在「店铺」单元格内容上：el-table 的 <tr> 由组件渲染、挂不上属性，
             要按行断言就从这个 span 往上找 tr（tid.js 未覆盖清单第 4 条同一套办法） -->
        <el-table-column label="店铺" width="150">
          <template #default="{ row }">
            <span :data-testid="`channel-log-row-${row.id}`">{{ shopNameOf(row.shopId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ actionText[row.action] || row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="resultTag[row.result] || 'info'" size="small" data-testid="channel-log-result">
              {{ resultText[row.result] || row.result }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="220">
          <template #default="{ row }">
            <span v-if="row.reason" :data-testid="`channel-log-reason-${row.id}`" :class="{ 'reason-text': row.result === 'FAIL' }">
              {{ row.reason }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="重试次数" width="100" align="center">
          <template #default="{ row }">
            <!-- attempt = 第几次尝试；重试次数 = attempt - 1 -->
            <span :data-testid="`channel-log-attempt-${row.id}`">{{ Math.max((row.attempt || 1) - 1, 0) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">{{ row.latencyMs ?? 0 }} ms</template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          data-testid="channel-log-pagination"
          @current-change="loadLogs"
          @size-change="search"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listChannelShops, listChannelSyncLogs, pullChannelShop } from '../../api/channel'

const platformText = {
  MALL: '商城开放接口',
  TAOBAO_MOCK: '淘宝（模拟）',
  DOUYIN_MOCK: '抖音（模拟）'
}
const actionText = { PULL: '拉单', SHIP_PUSH: '回传发货' }
const resultText = { OK: '成功', FAIL: '失败', SKIP: '跳过' }
const resultTag = { OK: 'success', FAIL: 'danger', SKIP: 'warning' }

const shops = ref([])
const shopsLoading = ref(false)
const pulling = ref(null)

const logs = ref([])
const logsLoading = ref(false)
const total = ref(0)
const query = reactive({ page: 1, size: 10, shopId: null, action: null, result: null })

const shopNameOf = (shopId) => shops.value.find((s) => s.id === shopId)?.shopName || `#${shopId}`

const fmtTime = (v) => {
  if (!v) return '—'
  // 后端给 ISO（2026-11-13T18:56:10）；页面统一显示成「空格分隔 + 到秒」
  return String(v).replace('T', ' ').slice(0, 19)
}

const loadShops = async () => {
  shopsLoading.value = true
  try {
    shops.value = (await listChannelShops()) || []
  } finally {
    shopsLoading.value = false
  }
}

const loadLogs = async () => {
  logsLoading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.shopId) params.shopId = query.shopId
    if (query.action) params.action = query.action
    if (query.result) params.result = query.result
    const page = await listChannelSyncLogs(params)
    logs.value = page?.records || []
    total.value = page?.total || 0
  } finally {
    logsLoading.value = false
  }
}

const search = () => {
  query.page = 1
  loadLogs()
}

/**
 * 立即拉单：结果里 created/skipped/failed 三个数一起报出来 ——
 * 只说「拉单成功」会让人以为「没有新单」和「全是重复单」是一回事。
 */
const pull = async (shop) => {
  pulling.value = shop.id
  try {
    const r = await pullChannelShop(shop.id)
    ElMessage.success(
      `${shop.shopName}：拉回 ${r.total} 单（新建 ${r.created} / 跳过 ${r.skipped} / 失败 ${r.failed}）`
    )
    await Promise.all([loadShops(), loadLogs()])
  } catch (e) {
    ElMessage.error(e.message || '拉单失败')
  } finally {
    pulling.value = null
  }
}

onMounted(async () => {
  await loadShops()
  await loadLogs()
})
</script>

<style scoped>
.channel { display: flex; flex-direction: column; gap: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.header-tip { color: #909399; font-size: 12px; font-weight: normal; }
.filters { display: flex; gap: 8px; align-items: center; }

.shop-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.shop-card { border: 1px solid #ebeef5; border-radius: 8px; padding: 14px; background: #fafcff; }
.shop-head { display: flex; justify-content: space-between; align-items: center; }
.shop-platform { font-weight: 600; font-size: 15px; }
.shop-name { margin: 8px 0 10px; color: #303133; }
.shop-code { margin-left: 8px; color: #909399; font-size: 12px; }
.shop-meta { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.meta-row { display: flex; justify-content: space-between; gap: 8px; }
.meta-label { color: #909399; }
.meta-value { color: #303133; }
.ok-num { color: #67c23a; font-weight: 600; }
.fail-num { color: #f56c6c; font-weight: 600; }
.sep { color: #c0c4cc; margin: 0 4px; }
.skip-num { color: #909399; margin-left: 6px; font-size: 12px; }
.shop-actions { margin-top: 12px; display: flex; align-items: center; gap: 8px; }
.action-hint { color: #c0c4cc; font-size: 12px; }

.log-card { margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.reason-text { color: #f56c6c; }
.muted { color: #c0c4cc; }
</style>
