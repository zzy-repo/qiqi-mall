<template>
  <div class="dashboard" data-testid="dashboard-page" v-loading="loading">
    <!-- 顶部指标卡 -->
    <div class="metrics">
      <el-card
        v-for="m in metrics"
        :key="m.key"
        shadow="hover"
        class="metric"
        :body-style="{ padding: '16px 18px' }"
        :data-testid="`dashboard-metric-${m.key}`"
      >
        <div class="metric-body">
          <!-- 彩色图标底片 = el-avatar（shape="square" 给圆角，--el-avatar-bg-color/-text-color 给语义色：
               avatar.scss:16-41 消费这组变量，默认值见 common/var.scss:1532-1542） -->
          <el-avatar
            class="metric-icon"
            shape="square"
            :size="44"
            :style="{ '--el-avatar-bg-color': m.bg, '--el-avatar-text-color': m.color }"
          >
            <el-icon :size="20"><component :is="m.icon" /></el-icon>
          </el-avatar>
          <div class="metric-text">
            <el-text class="metric-title" tag="div" size="small" type="info">{{ m.title }}</el-text>
            <!-- 大数字 = el-statistic：字号/字重/字色走 --el-statistic-content-*（var.scss:743-745），
                 千分位与精度用官方 precision/group-separator，不再手写 toLocaleString -->
            <el-statistic
              class="metric-value"
              :value="m.num"
              :precision="m.precision"
              group-separator=","
            >
              <template v-if="m.suffix" #suffix><el-text class="suffix" size="small" type="info">{{ m.suffix }}</el-text></template>
            </el-statistic>
            <el-text v-if="m.hint" class="metric-hint" tag="div" size="small" type="info">{{ m.hint }}</el-text>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 趋势 + 状态分布 -->
    <div class="row">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>近 7 天订单与成交额</span>
            <el-text class="panel-sub" size="small" type="info">左轴订单数 / 右轴成交额（元）</el-text>
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
      <el-card shadow="never" class="panel">
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
            <el-text class="panel-sub" size="small" type="info">库存 ≤ {{ stockThreshold }}</el-text>
          </div>
        </template>
        <div v-if="stockAlerts.length" class="alerts">
          <el-card
            v-for="a in stockAlerts"
            :key="a.skuId"
            class="alert-row"
            shadow="never"
            :body-style="alertRowBodyStyle"
            data-testid="dashboard-alert-row"
          >
            <div class="alert-main">
              <el-text class="alert-name" tag="div" truncated>{{ a.productName }}</el-text>
              <el-text class="alert-spec" tag="div" size="small" type="info">{{ a.specText || '默认规格' }}</el-text>
            </div>
            <el-tag :type="a.stock <= 5 ? 'danger' : 'warning'" size="small">{{ a.stock }} 件</el-tag>
          </el-card>
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

const alertRowBodyStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: '10px',
  padding: '8px 10px'
}

const metrics = computed(() => {
  const d = data.value || {}
  return [
    {
      key: 'today-amount', title: '今日成交额', num: Number(d.todayAmount ?? 0), precision: 2,
      suffix: '元', icon: Money, color: '#f56c6c', bg: 'rgba(245,108,108,.12)'
    },
    {
      key: 'today-orders', title: '今日订单', num: num(d.todayOrderCount), precision: 0,
      suffix: '单', icon: List, color: '#409eff', bg: 'rgba(64,158,255,.12)'
    },
    {
      key: 'products', title: '商品总数', num: num(d.productTotal), precision: 0,
      suffix: '个', hint: `在架 ${num(d.productOnline)} 个`, icon: Goods, color: '#67c23a', bg: 'rgba(103,194,58,.12)'
    },
    {
      key: 'pending-audit', title: '待审核订单', num: num(d.orderPendingAudit), precision: 0,
      suffix: '单', icon: Bell, color: '#e6a23c', bg: 'rgba(230,162,60,.12)'
    },
    {
      key: 'pending-ship', title: '待发货订单', num: num(d.orderPendingShip), precision: 0,
      suffix: '单', icon: Van, color: '#9254de', bg: 'rgba(146,84,222,.12)'
    },
    {
      key: 'pending-return', title: '待处理退货', num: num(d.returnPending), precision: 0,
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
/* 圆角走 el-card 自己的公开变量（card.scss:12）；body 内距已由 :body-style 给 */
.metric { --el-card-border-radius: 10px; }
.metric-body { display: flex; align-items: center; gap: 14px; }
/* 尺寸由 :size="44"、圆角由 --el-avatar-border-radius、底色/图标色由 --el-avatar-bg-color/-text-color 给 */
.metric-icon { flex: none; --el-avatar-border-radius: 10px; }
.metric-title { --el-text-font-size: var(--el-font-size-small); }
/* 22px 是设计令牌（EP 六档里没有 22）；字重/字色走 --el-statistic-content-* */
.metric-value {
  margin-top: 4px;
  line-height: 1.2;
  --el-statistic-content-font-size: var(--qiqi-font-size-num);
  --el-statistic-content-font-weight: 600;
  --el-statistic-content-color: var(--qiqi-color-ink);
}
/* el-statistic 的 suffix 插槽会继承 content 的 600 字重，而 el-text 没有 font-weight API，
   所以这里只剩这一行（margin 由 .el-statistic__suffix 的 4px 给，字号/字色走 el-text）。 */
.metric-value .suffix { font-weight: 400; }
.metric-hint { margin-top: 2px; }

/* 图表区：宽屏两列、窄屏一列 */
.row { display: flex; gap: 16px; flex-wrap: wrap; }
.panel { flex: 1 1 420px; min-width: 320px; --el-card-border-radius: 10px; }
.panel-header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
/* align-self:auto 是为了抵掉 .el-text 自带的 align-self:center —— 父级是 baseline 对齐，
   不写明就会从基线对齐变成居中（肉眼可辨）。 */
.panel-sub { align-self: auto; }
.chart { height: 280px; }
.status-panel { flex: 0 1 340px; }
.alerts-panel { flex: 0 1 340px; }

.alerts { display: flex; flex-direction: column; gap: 10px; }
/* 行框 = el-card：底色/圆角走它的公开变量，行内布局走 :body-style（见 alertRowBodyStyle） */
.alert-row {
  --el-card-bg-color: var(--el-fill-color-lighter);
  --el-card-border-radius: 8px;
}
.alert-main { min-width: 0; }
/* 13px 走变量；单行省略交给 el-text 的 truncated 属性（Text 文档 Attributes 有此项），不再手写三件套 */
.alert-name { --el-text-font-size: var(--el-font-size-small); --el-text-color: var(--qiqi-color-ink); }

</style>
