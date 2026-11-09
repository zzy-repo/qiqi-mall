<template>
  <el-row :gutter="16">
    <el-col :md="10" :xs="24">
      <el-card shadow="never" header="分类树">
        <el-tree
          :data="tree"
          default-expand-all
          node-key="id"
          :props="{ label: 'label', children: 'children' }"
        />
      </el-card>
    </el-col>
    <el-col :md="14" :xs="24">
      <el-card shadow="never" header="分类列表">
        <el-table :data="flat" border stripe>
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="label" label="分类名称" />
          <el-table-column prop="count" label="商品数" width="100" />
        </el-table>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
const tree = [
  { id: 1, label: '数码', children: [{ id: 11, label: '耳机' }, { id: 12, label: '智能穿戴' }] },
  { id: 2, label: '服饰', children: [{ id: 21, label: '上装' }, { id: 22, label: '鞋靴' }] },
  { id: 3, label: '家居', children: [{ id: 31, label: '厨房' }, { id: 32, label: '收纳' }] }
]
const flat = [
  { id: 1, label: '数码', count: 320 },
  { id: 2, label: '服饰', count: 286 },
  { id: 3, label: '家居', count: 194 },
  { id: 4, label: '运动', count: 124 }
]
</script>
