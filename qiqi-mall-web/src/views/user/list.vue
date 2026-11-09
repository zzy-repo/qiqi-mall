<template>
  <el-card shadow="never">
    <el-table :data="list" stripe border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="头像" width="90">
        <template #default="{ row }">
          <el-avatar :size="30">{{ row.nickname[0] }}</el-avatar>
        </template>
      </el-table-column>
      <el-table-column prop="nickname" label="昵称" width="140" />
      <el-table-column prop="phone" label="手机号" width="150" />
      <el-table-column prop="orderCount" label="订单数" width="100" />
      <el-table-column prop="createdAt" label="注册时间" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
            {{ row.enabled ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
const list = [
  { id: 1, nickname: '小七', phone: '138****8888', orderCount: 12, createdAt: '2026-01-12', enabled: true },
  { id: 2, nickname: '阿宝', phone: '139****6666', orderCount: 3, createdAt: '2026-03-04', enabled: true },
  { id: 3, nickname: '橙子', phone: '137****1234', orderCount: 0, createdAt: '2026-06-21', enabled: false }
]
</script>
