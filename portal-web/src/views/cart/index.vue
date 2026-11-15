<template>
  <div>
    <!-- 标题与提示分成两个 el-text：h3 的粗体只作用于标题，提示不必再用 font-weight:normal 去抵消 -->
    <div class="title-row">
      <el-text class="title" tag="h3" size="large">购物车</el-text>
      <el-text v-if="!logged" class="hint" size="small" type="info" data-testid="cart-local-hint">（未登录，暂存在本浏览器）</el-text>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border data-testid="cart-list-table">
        <el-table-column prop="productName" label="商品" min-width="180" />
        <el-table-column label="规格" min-width="180">
          <template #default="{ row }">{{ row.specText || '默认规格' }}</template>
        </el-table-column>
        <el-table-column label="单价" width="120">
          <template #default="{ row }">¥ {{ Number(row.price).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="150">
          <template #default="{ row }">
            <span :data-testid="`cart-row-${row.productSkuId}`">
              <el-input-number
                :model-value="row.quantity"
                data-testid="cart-row-qty"
                :min="1"
                :precision="0"
                size="small"
                @change="(v) => changeQty(row, v)"
              />
            </span>
          </template>
        </el-table-column>
        <el-table-column label="小计" width="130">
          <template #default="{ row }">¥ {{ (Number(row.price) * row.quantity).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" size="small" data-testid="cart-row-remove" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="footer">
        <el-text class="total" data-testid="cart-total">合计：<el-text class="total-num" tag="b" size="large" type="danger">¥ {{ total }}</el-text></el-text>
        <el-button
          type="primary"
          size="large"
          :disabled="!rows.length"
          data-testid="cart-checkout"
          @click="checkout"
        >去结算</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listCart, updateCartQuantity, removeCartItem } from '../../api/cart'
import { getToken } from '../../utils/auth'
import { listLocalCart, updateLocalQuantity, removeLocalCart } from '../../utils/localCart'

const router = useRouter()
const rows = ref([])
const loading = ref(false)
const logged = computed(() => !!getToken())
const total = computed(() => rows.value.reduce((sum, i) => sum + Number(i.price) * i.quantity, 0).toFixed(2))

const load = async () => {
  loading.value = true
  try {
    rows.value = logged.value ? await listCart() : listLocalCart()
  } catch (e) {
    ElMessage.error('加载购物车失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const changeQty = async (row, quantity) => {
  try {
    if (logged.value) {
      await updateCartQuantity(row.id, quantity)
    } else {
      updateLocalQuantity(row.productSkuId, quantity)
    }
    await load()
  } catch (e) {
    ElMessage.error('修改数量失败：' + e.message)
  }
}

const remove = async (row) => {
  try {
    if (logged.value) {
      await removeCartItem(row.id)
    } else {
      removeLocalCart(row.productSkuId)
    }
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

/** 去结算：未登录先拦截跳登录 */
const checkout = () => {
  if (!getToken()) {
    ElMessage.warning('请先登录后再结算')
    return router.push({ path: '/login', query: { redirect: '/checkout' } })
  }
  router.push('/checkout')
}

onMounted(load)
</script>

<style scoped>
.title-row { display: flex; align-items: baseline; margin: 0 0 12px; }
.title { margin: 0; }
.hint { margin: 0; }
.total { margin: 0; }
/* 金额 = el-text size="large"(16px) + tag="b" + type="danger"；不再钉 18px */
.footer { display: flex; justify-content: flex-end; align-items: center; gap: 16px; margin-top: 16px; }
</style>
