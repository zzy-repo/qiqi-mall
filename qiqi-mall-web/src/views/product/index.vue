<template>
  <el-card shadow="never" header="商品">
    <el-table :data="list" border>
      <el-table-column prop="id" label="商品ID" width="100" />
      <el-table-column prop="name" label="商品名称" min-width="200" />
      <el-table-column prop="categoryId" label="分类ID" width="110" />
      <el-table-column prop="typeId" label="类型ID" width="110" />
      <el-table-column label="上下架" width="110">
        <template #default="{ row }">
          <el-tag :type="row.publishStatus ? 'success' : 'info'" size="small">
            {{ row.publishStatus ? '上架' : '下架' }}
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
