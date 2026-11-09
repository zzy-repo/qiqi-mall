<template>
  <el-card shadow="never" header="属性">
    <el-table :data="list" border>
      <el-table-column prop="id" label="属性ID" width="100" />
      <el-table-column prop="typeId" label="所属类型ID" width="120" />
      <el-table-column prop="name" label="属性名称" />
      <el-table-column label="是否筛选" width="120">
        <template #default="{ row }">
          <el-tag :type="row.isFilter ? 'success' : 'info'" size="small">
            {{ row.isFilter ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'

// 数据来源：后端接口（待接入）
const list = ref([])
</script>
