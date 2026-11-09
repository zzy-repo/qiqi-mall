<template>
  <div class="dashboard">
    <el-row :gutter="16">
      <el-col v-for="card in stats" :key="card.label" :xs="24" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-inner">
            <div class="stat-icon" :style="{ background: card.color }">
              <el-icon :size="26" color="#fff"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-text">
              <div class="stat-label">{{ card.label }}</div>
              <div class="stat-value">{{ card.value }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt16">
      <el-col :md="16" :xs="24">
        <el-card shadow="never" header="近 7 日成交额">
          <div class="chart">
            <div
              v-for="(d, i) in chartData"
              :key="i"
              class="chart-col"
            >
              <div class="chart-bar" :style="{ height: d.value + '%' }"></div>
              <span class="chart-x">{{ d.day }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :md="8" :xs="24">
        <el-card shadow="never" header="系统状态" class="status-card">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="运行环境">
              <el-tag :type="env === 'prod' ? 'danger' : 'success'" size="small">
                {{ env }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="后端接口">{{ health }}</el-descriptions-item>
            <el-descriptions-item label="前端版本">v0.0.1</el-descriptions-item>
            <el-descriptions-item label="服务端口">
              {{ env === 'prod' ? '5174 / 8082' : '5173 / 8081' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getHealth } from '../../api/system'

const env = import.meta.env.VITE_APP_ENV
const health = ref('检测中...')

const stats = [
  { label: '今日订单', value: '128', icon: 'Document', color: '#409eff' },
  { label: '今日成交额', value: '¥ 26,480', icon: 'Money', color: '#67c23a' },
  { label: '在售商品', value: '1,024', icon: 'Goods', color: '#e6a23c' },
  { label: '注册用户', value: '8,912', icon: 'User', color: '#f56c6c' }
]

const chartData = [
  { day: '周一', value: 45 },
  { day: '周二', value: 62 },
  { day: '周三', value: 38 },
  { day: '周四', value: 78 },
  { day: '周五', value: 88 },
  { day: '周六', value: 96 },
  { day: '周日', value: 70 }
]

onMounted(async () => {
  try {
    const res = await getHealth()
    health.value = `${res.status} (${res.service})`
  } catch (e) {
    health.value = '不可达'
  }
})
</script>

<style scoped>
.stat-card :deep(.el-card__body) { padding: 18px; }
.stat-inner { display: flex; align-items: center; gap: 14px; }
.stat-icon {
  width: 52px; height: 52px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
}
.stat-label { font-size: 13px; color: #909399; margin-bottom: 6px; }
.stat-value { font-size: 22px; font-weight: 600; color: #303133; }
.mt16 { margin-top: 16px; }
.chart {
  height: 240px; display: flex; align-items: flex-end;
  gap: 18px; padding: 10px 6px 0;
}
.chart-col {
  flex: 1; height: 100%; display: flex;
  flex-direction: column; justify-content: flex-end; align-items: center;
}
.chart-bar {
  width: 100%; max-width: 42px; border-radius: 6px 6px 0 0;
  background: linear-gradient(180deg, #79bbff, #409eff);
  transition: height 0.3s;
}
.chart-x { font-size: 12px; color: #909399; margin-top: 8px; }
.status-card :deep(.el-card__body) { padding-top: 8px; }
</style>
