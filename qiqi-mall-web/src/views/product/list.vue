<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="商品名称">
          <el-input v-model="query.name" placeholder="请输入商品名称" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="上架" value="on" />
            <el-option label="下架" value="off" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search">查询</el-button>
          <el-button :icon="Refresh">重置</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :icon="Plus">新增商品</el-button>
    </div>

    <el-table :data="list" stripe border style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="商品名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column prop="price" label="价格" width="110">
        <template #default="{ row }">¥ {{ row.price.toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'on' ? 'success' : 'info'" size="small">
            {{ row.status === 'on' ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default>
          <el-button link type="primary" size="small">编辑</el-button>
          <el-button link type="danger" size="small">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="list.length"
      :page-size="10"
    />
  </el-card>
</template>

<script setup>
import { ref } from 'vue'
import { Search, Refresh, Plus } from '@element-plus/icons-vue'

const query = ref({ name: '', status: '' })

const list = ref([
  { id: 1001, name: '七七精选蓝牙耳机 Pro', category: '数码', price: 399, stock: 128, status: 'on' },
  { id: 1002, name: '纯棉宽松卫衣', category: '服饰', price: 159, stock: 62, status: 'on' },
  { id: 1003, name: '手冲咖啡壶套装', category: '家居', price: 289, stock: 34, status: 'on' },
  { id: 1004, name: '便携榨汁杯', category: '小家电', price: 99, stock: 0, status: 'off' },
  { id: 1005, name: '轻盈跑步鞋', category: '运动', price: 459, stock: 76, status: 'on' }
])
</script>

<style scoped>
.toolbar {
  display: flex; justify-content: space-between;
  align-items: flex-start; margin-bottom: 6px; flex-wrap: wrap; gap: 8px;
}
.search-form :deep(.el-form-item) { margin-bottom: 12px; }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
