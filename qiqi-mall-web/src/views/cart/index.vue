<template>
  <el-row :gutter="16">
    <el-col :md="17" :xs="24">
      <el-card shadow="never" header="我的购物车">
        <el-table :data="items" border>
          <el-table-column label="商品" min-width="200">
            <template #default="{ row }">
              <div class="goods">
                <el-icon :size="20" color="#409eff"><Goods /></el-icon>
                <span>{{ row.name }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="price" label="单价" width="100">
            <template #default="{ row }">¥ {{ row.price }}</template>
          </el-table-column>
          <el-table-column label="数量" width="170">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="1" size="small" style="width: 120px" />
            </template>
          </el-table-column>
          <el-table-column label="小计" width="110">
            <template #default="{ row }">¥ {{ (row.price * row.qty).toFixed(2) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>
    <el-col :md="7" :xs="24">
      <el-card shadow="never" header="结算">
        <div class="sum-row"><span>商品件数</span><b>{{ totalQty }} 件</b></div>
        <div class="sum-row"><span>商品总额</span><b>¥ {{ totalAmount.toFixed(2) }}</b></div>
        <el-divider />
        <el-button type="danger" size="large" style="width: 100%">去结算</el-button>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { computed, ref } from 'vue'

const items = ref([
  { name: '七七精选蓝牙耳机 Pro', price: 399, qty: 1 },
  { name: '纯棉宽松卫衣', price: 159, qty: 2 }
])
const totalQty = computed(() => items.value.reduce((s, i) => s + i.qty, 0))
const totalAmount = computed(() => items.value.reduce((s, i) => s + i.price * i.qty, 0))
</script>

<style scoped>
.goods { display: flex; align-items: center; gap: 8px; }
.sum-row {
  display: flex; justify-content: space-between;
  font-size: 14px; color: #606266; margin-bottom: 12px;
}
.sum-row b { color: #f56c6c; }
</style>
