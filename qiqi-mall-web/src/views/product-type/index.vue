<template>
  <el-card shadow="never" header="商品类型">
    <el-table :data="list" border>
      <el-table-column prop="id" label="类型ID" width="100" />
      <el-table-column prop="name" label="类型名称" />
    </el-table>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'

// 数据来源：后端接口（待接入）
const list = ref([])
</script>
