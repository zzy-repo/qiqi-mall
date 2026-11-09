<template>
  <el-card shadow="never" header="SKU">
    <el-table :data="list" border>
      <el-table-column prop="id" label="SKU ID" width="100" />
      <el-table-column prop="productId" label="商品ID" width="110" />
      <el-table-column prop="specJson" label="规格组合" min-width="220" show-overflow-tooltip />
      <el-table-column prop="price" label="价格" width="120" />
      <el-table-column prop="stock" label="库存" width="100" />
    </el-table>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'

// 数据来源：后端接口（待接入）
const list = ref([])
</script>
