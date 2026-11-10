<template>
  <div v-loading="loading">
    <el-page-header content="商品详情" @back="$router.back()" />
    <el-card v-if="product" shadow="never" class="detail">
      <div class="head">
        <div class="thumb"><el-icon :size="60"><Goods /></el-icon></div>
        <div class="info">
          <h2>{{ product.name }}</h2>
          <div class="price">¥ {{ currentSku ? Number(currentSku.price).toFixed(2) : '—' }}</div>
          <div class="specs">
            <span class="label">规格：</span>
            <el-radio-group v-model="skuId">
              <el-radio-button v-for="s in skus" :key="s.id" :value="s.id">
                {{ s.specJson || '默认规格' }}
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="qty">
            <span class="label">数量：</span>
            <el-input-number v-model="quantity" :min="1" :precision="0" />
            <span class="stock" v-if="currentSku">库存 {{ currentSku.stock }}</span>
          </div>
          <el-button type="primary" size="large" :icon="ShoppingCart" @click="addCart">加入购物车</el-button>
          <el-button size="large" @click="goCheckout">立即购买</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ShoppingCart } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getProductDetail } from '../../api/catalog'
import { addToCart } from '../../api/cart'
import { getToken } from '../../utils/auth'
import { addLocalCart } from '../../utils/localCart'

const route = useRoute()
const router = useRouter()

const product = ref(null)
const skus = ref([])
const skuId = ref(null)
const quantity = ref(1)
const loading = ref(false)

const currentSku = computed(() => skus.value.find((s) => s.id === skuId.value) || null)

const load = async () => {
  loading.value = true
  try {
    const data = await getProductDetail(Number(route.params.id))
    product.value = data.product
    skus.value = data.skus || []
    skuId.value = skus.value[0]?.id || null
  } catch (e) {
    ElMessage.error('加载商品失败：' + e.message)
  } finally {
    loading.value = false
  }
}

/** 已登录走云端购物车，未登录存本地 */
const addCart = async () => {
  if (!currentSku.value) return ElMessage.warning('请选择规格')
  if (getToken()) {
    try {
      await addToCart({ productSkuId: skuId.value, quantity: quantity.value })
      ElMessage.success('已加入购物车')
    } catch (e) {
      ElMessage.error('加入失败：' + e.message)
    }
  } else {
    addLocalCart({
      productSkuId: skuId.value,
      productId: product.value.id,
      productName: product.value.name,
      specJson: currentSku.value.specJson,
      price: currentSku.value.price,
      quantity: quantity.value
    })
    ElMessage.success('已加入本地购物车（登录后自动合并）')
  }
  router.push('/cart')
}

const goCheckout = () => {
  if (!currentSku.value) return ElMessage.warning('请选择规格')
  if (!getToken()) {
    return router.push({ path: '/login', query: { redirect: `/product/${product.value.id}` } })
  }
  addToCart({ productSkuId: skuId.value, quantity: quantity.value })
    .then(() => router.push('/cart'))
    .catch((e) => ElMessage.error('操作失败：' + e.message))
}

onMounted(load)
</script>

<style scoped>
.detail { margin-top: 16px; }
.head { display: flex; gap: 24px; }
.thumb {
  width: 220px; height: 220px; background: #f0f2f5; border-radius: 6px;
  display: flex; align-items: center; justify-content: center; color: #c0c4cc;
}
.info h2 { margin: 0 0 12px; font-size: 20px; }
.price { color: #f56c6c; font-size: 24px; font-weight: 600; margin-bottom: 16px; }
.specs, .qty { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
.label { color: #909399; font-size: 13px; }
.stock { color: #909399; font-size: 13px; margin-left: 8px; }
</style>
