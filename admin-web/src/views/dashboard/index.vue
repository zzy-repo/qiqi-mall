<template>
  <div class="dashboard" data-testid="dashboard-page" v-loading="loading">
    <!-- 顶部指标卡 -->
    <div class="metrics">
      <el-card
        v-for="m in metrics"
        :key="m.key"
        shadow="hover"
        class="metric"
        :data-testid="`dashboard-metric-${m.key}`"
      >
        <div class="metric-body">
          <div class="metric-icon" :style="{ background: m.bg, color: m.color }">
            <el-icon :size="20"><component :is="m.icon" /></el-icon>
          </div>
          <div class="metric-text">
            <div class="metric-title">{{ m.title }}</div>
            <div class="metric-value">
              <span class="num">{{ m.value }}</span>
              <span v-if="m.suffix" class="suffix">{{ m.suffix }}</span>
            </div>
            <div v-if="m.hint" class="metric-hint">{{ m.hint }}</div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 趋势 + 状态分布 -->
    <div class="row">
      <el-card shadow="never" class="panel grow">
        <template #header>
          <div class="panel-header">
            <span>近 7 天订单与成交额</span>
            <span class="panel-sub">左轴订单数 / 右轴成交额（元）</span>
          </div>
        </template>
        <div v-if="hasTrend" class="chart" data-testid="dashboard-chart-trend">
          <v-chart :option="trendOption" autoresize />
        </div>
        <el-empty v-else description="近 7 天还没有成交数据" :image-size="70" />
      </el-card>

      <el-card shadow="never" class="panel status-panel">
        <template #header><span>订单状态分布</span></template>
        <div v-if="hasStatus" class="chart" data-testid="dashboard-chart-status">
          <v-chart :option="statusOption" autoresize />
        </div>
        <el-empty v-else description="还没有订单" :image-size="70" />
      </el-card>
    </div>

    <!-- Top5 + 库存告警 -->
    <div class="row">
      <el-card shadow="never" class="panel grow">
        <template #header><span>热销商品 Top 5（按销量）</span></template>
        <div v-if="topProducts.length" class="chart" data-testid="dashboard-chart-top">
          <v-chart :option="topOption" autoresize />
        </div>
        <el-empty v-else description="还没有销售数据" :image-size="70" />
      </el-card>

      <el-card shadow="never" class="panel alerts-panel">
        <template #header>
          <div class="panel-header">
            <span>库存告警</span>
            <span class="panel-sub">库存 ≤ {{ stockThreshold }}</span>
          </div>
        </template>
        <div v-if="stockAlerts.length" class="alerts">
          <div
            v-for="a in stockAlerts"
            :key="a.skuId"
            class="alert-row"
            data-testid="dashboard-alert-row"
          >
            <div class="alert-main">
              <div class="alert-name">{{ a.productName }}</div>
              <div class="alert-spec">{{ a.specText || '默认规格' }}</div>
            </div>
            <el-tag :type="a.stock <= 5 ? 'danger' : 'warning'" size="small">{{ a.stock }} 件</el-tag>
          </div>
        </div>
        <el-empty v-else description="库存都还充足" :image-size="70" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Goods, Money, List, Bell, Van, RefreshLeft } from '@element-plus/icons-vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { getDashboardOverview } from '../../api/dashboard'

// 按需引入，控制打包体积（只用到折线/饼/柱 + 网格/提示/图例 + Canvas 渲染）
use([CanvasRenderer, LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent])

const loading = ref(false)
const data = ref(null)
const stockThreshold = 10

const ORDER_STATUS_TEXT = {
  0: '待付款', 1: '已付款', 2: '已发货', 3: '已完成', 4: '已关闭',
  5: '退款中', 6: '已退款', 7: '待审核', 8: '待发货', 9: '部分发货'
}

const load = async () => {
  loading.value = true
  try {
    data.value = await getDashboardOverview()
  } catch (e) {
    ElMessage.error('加载概览失败：' + e.message)
    data.value = null
  } finally {
    loading.value = false
  }
}

const num = (v) => (v === null || v === undefined ? 0 : Number(v))
const money = (v) => '¥ ' + Number(v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

const metrics = computed(() => {
  const d = data.value || {}
  return [
    {
      key: 'today-amount', title: '今日成交额', value: money(d.todayAmount).replace('¥ ', ''),
      suffix: '元', icon: Money, color: '#f56c6c', bg: 'rgba(245,108,108,.12)'
    },
    {
      key: 'today-orders', title: '今日订单', value: num(d.todayOrderCount),
      suffix: '单', icon: List, color: '#409eff', bg: 'rgba(64,158,255,.12)'
    },
    {
      key: 'products', title: '商品总数', value: num(d.productTotal),
      suffix: '个', hint: `在架 ${num(d.productOnline)} 个`, icon: Goods, color: '#67c23a', bg: 'rgba(103,194,58,.12)'
    },
    {
      key: 'pending-audit', title: '待审核订单', value: num(d.orderPendingAudit),
      suffix: '单', icon: Bell, color: '#e6a23c', bg: 'rgba(230,162,60,.12)'
    },
    {
      key: 'pending-ship', title: '待发货订单', value: num(d.orderPendingShip),
      suffix: '单', icon: Van, color: '#9254de', bg: 'rgba(146,84,222,.12)'
    },
    {
      key: 'pending-return', title: '待处理退货', value: num(d.returnPending),
      suffix: '单', icon: RefreshLeft, color: '#13c2c2', bg: 'rgba(19,194,194,.12)'
    }
  ]
})

const trend = computed(() => data.value?.trend || [])
const hasTrend = computed(() => trend.value.some((t) => num(t.orderCount) > 0 || num(t.amount) > 0))
const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 56, top: 24, bottom: 32 },
  xAxis: { type: 'category', data: trend.value.map((t) => t.date.slice(5)), axisLine: { lineStyle: { color: '#dcdfe6' } }, axisLabel: { color: '#909399' } },
  yAxis: [
    { type: 'value', name: '订单数', nameTextStyle: { color: '#909399' }, splitLine: { lineStyle: { color: '#f0f2f5' } }, axisLabel: { color: '#909399' } },
    { type: 'value', name: '成交额', nameTextStyle: { color: '#909399' }, splitLine: { show: false }, axisLabel: { color: '#909399' } }
  ],
  series: [
    { name: '订单数', type: 'line', smooth: true, data: trend.value.map((t) => num(t.orderCount)), itemStyle: { color: '#409eff' }, areaStyle: { opacity: 0.08 } },
    { name: '成交额', type: 'line', smooth: true, yAxisIndex: 1, data: trend.value.map((t) => num(t.amount)), itemStyle: { color: '#f56c6c' } }
  ]
}))

const statusRows = computed(() => (data.value?.statusDistribution || []).filter((s) => num(s.count) > 0))
const hasStatus = computed(() => statusRows.value.length > 0)
const statusOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} 单（{d}%）' },
  legend: { bottom: 0, type: 'scroll', textStyle: { color: '#606266' } },
  series: [{
    type: 'pie',
    radius: ['46%', '70%'],
    center: ['50%', '44%'],
    avoidLabelOverlap: true,
    itemStyle: { borderColor: '#fff', borderWidth: 2 },
    label: { show: false },
    data: statusRows.value.map((s) => ({ name: ORDER_STATUS_TEXT[s.status] || `状态${s.status}`, value: num(s.count) }))
  }]
}))

const topProducts = computed(() => data.value?.topProducts || [])
const topOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 8, right: 24, top: 16, bottom: 8, containLabel: true },
  xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0f2f5' } }, axisLabel: { color: '#909399' } },
  yAxis: {
    type: 'category',
    inverse: true,
    data: topProducts.value.map((p) => (p.productName || '').slice(0, 12)),
    axisLabel: { color: '#606266' },
    axisLine: { lineStyle: { color: '#dcdfe6' } }
  },
  series: [{
    name: '销量',
    type: 'bar',
    barMaxWidth: 18,
    itemStyle: { color: '#409eff', borderRadius: [0, 4, 4, 0] },
    data: topProducts.value.map((p) => num(p.qty))
  }]
}))

const stockAlerts = computed(() => data.value?.stockAlerts || [])

onMounted(load)
</script>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: 16px; }

/* 指标卡：窄屏自动换行 */
.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
.metric { border-radius: 10px; }
.metric :deep(.el-card__body) { padding: 16px 18px; }
.metric-body { display: flex; align-items: center; gap: 14px; }
.metric-icon { width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex: none; }
.metric-title { font-size: 13px; color: #909399; }
.metric-value { margin-top: 4px; font-size: 22px; font-weight: 600; color: #1f2d3d; line-height: 1.2; }
.metric-value .suffix { margin-left: 4px; font-size: 12px; font-weight: 400; color: #909399; }
.metric-hint { margin-top: 2px; font-size: 12px; color: #909399; }

/* 图表区：宽屏两列、窄屏一列 */
.row { display: flex; gap: 16px; flex-wrap: wrap; }
.panel { border-radius: 10px; flex: 1 1 420px; min-width: 320px; }
.panel-header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.panel-sub { font-size: 12px; color: #909399; font-weight: 400; }
.chart { height: 280px; }
.status-panel { flex: 0 1 340px; }
.alerts-panel { flex: 0 1 340px; }

.alerts { display: flex; flex-direction: column; gap: 10px; }
.alert-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 10px; border-radius: 8px; background: #fafafa; }
.alert-main { min-width: 0; }
.alert-name { font-size: 13px; color: #1f2d3d; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.alert-spec { font-size: 12px; color: #909399; }
</style>
