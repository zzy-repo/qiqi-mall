<template>
  <el-card shadow="never">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="全部订单" name="all" />
      <el-tab-pane label="待付款" name="unpaid" />
      <el-tab-pane label="待发货" name="shipping" />
      <el-tab-pane label="已完成" name="done" />
    </el-tabs>

    <el-table :data="list" stripe border>
      <el-table-column prop="orderNo" label="订单号" width="180" />
      <el-table-column prop="user" label="下单用户" width="120" />
      <el-table-column prop="amount" label="金额" width="120">
        <template #default="{ row }">¥ {{ row.amount.toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="下单时间" width="180" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right">
        <template #default>
          <el-button link type="primary" size="small">详情</el-button>
          <el-button link type="primary" size="small">发货</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'

const activeTab = ref('all')
const list = ref([
  { orderNo: 'QQ20261109001', user: '张三', amount: 399, createdAt: '2026-11-09 10:22', status: 'unpaid', statusText: '待付款' },
  { orderNo: 'QQ20261109002', user: '李四', amount: 158.5, createdAt: '2026-11-09 11:05', status: 'shipping', statusText: '待发货' },
  { orderNo: 'QQ20261109003', user: '王五', amount: 89, createdAt: '2026-11-09 12:41', status: 'done', statusText: '已完成' }
])

const statusType = (s) => ({ unpaid: 'warning', shipping: 'primary', done: 'success' }[s] || 'info')
</script>
