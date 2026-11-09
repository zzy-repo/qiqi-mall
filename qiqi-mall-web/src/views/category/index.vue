<template>
  <el-card shadow="never" header="商品分类">
    <el-table :data="list" border row-key="id" default-expand-all>
      <el-table-column prop="id" label="分类ID" width="100" />
      <el-table-column prop="parentId" label="父分类ID" width="120" />
      <el-table-column prop="name" label="分类名称" />
    </el-table>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'

// 数据来源：后端接口（待接入）
const list = ref([])
</script>
