<template>
  <div v-loading="loading">
    <el-page-header content="商品详情" @back="$router.back()" />
    <el-card v-if="product" shadow="never" class="detail">
      <div class="head">
        <div class="left">
          <div class="thumb" data-testid="product-detail-cover">
            <!-- 主图优先级：用户点的缩略图 > 当前规格图 > 商品主图（相册仍是 SPU 级） -->
            <el-image v-if="coverPic" :src="coverPic" fit="cover" class="cover">
              <template #error><el-icon :size="60"><Goods /></el-icon></template>
            </el-image>
            <el-icon v-else :size="60"><Goods /></el-icon>
          </div>
          <div v-if="gallery.length" class="gallery">
            <el-image
              v-for="g in gallery"
              :key="g.id"
              :src="g.url"
              data-testid="product-detail-gallery-item"
              class="gallery-item"
              :class="{ active: g.id === activeGallery }"
              fit="cover"
              @click="activeGallery = g.id"
            >
              <template #error><div class="gallery-fallback">无图</div></template>
            </el-image>
          </div>
        </div>
        <div class="info">
          <el-text class="name" tag="h2" data-testid="product-detail-name">{{ product.name }}</el-text>
          <el-text class="price" tag="div" type="danger" data-testid="product-detail-price">¥ {{ currentSku ? Number(currentSku.price).toFixed(2) : '—' }}</el-text>
          <div class="specs">
            <el-text class="label" type="info">规格：</el-text>
            <el-radio-group v-model="skuId">
              <el-radio-button
                v-for="s in skus"
                :key="s.id"
                :value="s.id"
                :data-testid="`product-detail-sku-${s.id}`"
              >
                {{ s.specText || '默认规格' }}
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="desc" v-if="product.description">
            <el-text class="label" type="info">商品描述：</el-text>
            <el-text class="desc-text" tag="p" data-testid="product-detail-desc">{{ product.description }}</el-text>
          </div>
          <div class="qty">
            <el-text class="label" type="info">数量：</el-text>
            <!-- 上限跟当前规格库存：以前没有 max，能填 99999，加购/下单时才失败（体验差且无提示） -->
            <el-input-number
              v-model="quantity"
              data-testid="product-detail-qty"
              :min="1"
              :max="currentSku?.stock || 1"
              :precision="0"
            />
            <el-text v-if="currentSku" class="stock" type="info" data-testid="product-detail-stock">库存 {{ currentSku.stock }}</el-text>
          </div>
          <el-button
            type="primary"
            size="large"
            :icon="ShoppingCart"
            data-testid="product-detail-add-cart"
            @click="addCart"
          >加入购物车</el-button>
          <el-button size="large" data-testid="product-detail-buy-now" @click="goCheckout">立即购买</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
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

const gallery = ref([])
/** 用户点过的缩略图 id（null = 没选中任何一张，不框蓝框） */
const activeGallery = ref(null)

const currentSku = computed(() => skus.value.find((s) => s.id === skuId.value) || null)
/** 当前选中的缩略图 url（没选中时为 ''） */
const activeGalleryUrl = computed(() => {
  if (activeGallery.value == null) return ''
  return gallery.value.find((g) => g.id === activeGallery.value)?.url || ''
})
/**
 * 详情页主图取值优先级：① 用户点的缩略图 → ② 当前规格图 → ③ 商品主图。
 * 相册是 SPU 级的，所以「点缩略图」必须能盖过规格图，否则点上去主图不动。
 */
const coverPic = computed(() => activeGalleryUrl.value || currentSku.value?.pic || product.value?.pic || '')

/**
 * 把缩略图选中态对齐到当前规格图：规格图正好在相册里 → 选中那一项；
 * 否则（规格没配图 / 配的图不在相册里）→ 清空选中，避免「蓝框框着 A、主图显示 B」。
 */
const syncGalleryToSku = () => {
  const pic = currentSku.value?.pic || ''
  activeGallery.value = gallery.value.find((g) => g.url === pic)?.id ?? null
}

// 切规格必须重置缩略图选中态，否则会残留上一张相册图的蓝框
watch(skuId, () => {
  syncGalleryToSku()
  // 切规格后数量必须夹到新规格库存内：element-plus 的 :max 不 watch props.max
  if (quantity.value > (currentSku.value?.stock || 1)) quantity.value = currentSku.value?.stock || 1
})

const load = async () => {
  loading.value = true
  try {
    const data = await getProductDetail(Number(route.params.id))
    product.value = data.product
    skus.value = data.skus || []
    gallery.value = data.gallery || []
    skuId.value = skus.value[0]?.id || null
    // 初始：缩略图选中态与当前规格图保持一致（没有对应相册项就清空）
    syncGalleryToSku()
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
      specText: currentSku.value.specText,
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
  // 「立即购买」= 加购后直奔结算页。
  // 原来这里跳的是 /cart，结果「立即购买」和「加入购物车」落到同一个页面，
  // 用户点「立即购买」后还得再点一次「去结算」，按钮文案和实际行为对不上。
  addToCart({ productSkuId: skuId.value, quantity: quantity.value })
    .then(() => router.push('/checkout'))
    .catch((e) => ElMessage.error('操作失败：' + e.message))
}

onMounted(load)
</script>

<style scoped>
.detail { margin-top: 16px; }
.head { display: flex; gap: 24px; }
.left { display: flex; flex-direction: column; gap: 8px; }
.thumb {
  width: 220px; height: 220px; background: #f0f2f5; border-radius: 6px; overflow: hidden;
  display: flex; align-items: center; justify-content: center; color: #c0c4cc;
}
.cover { width: 100%; height: 100%; }
.gallery-fallback { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; background: #f0f2f5; color: #c0c4cc; font-size: 11px; }
.gallery { display: flex; gap: 6px; width: 220px; overflow-x: auto; }
.gallery-item {
  width: 48px; height: 48px; object-fit: cover; border-radius: 4px;
  border: 2px solid transparent; cursor: pointer;
}
.gallery-item.active { border-color: #409eff; }
/* 标题 20px = --el-font-size-extra-large；字重 600 承自 <h2> 标签语义 */
.name { margin: 0 0 12px; --el-text-font-size: var(--el-font-size-extra-large); }
/* 价格 24px：EP 字号档里没有 24，走 el-text 官方变量；红字用 type="danger"。
   字重 600 是 el-text 唯一表达不了的一位（$text 只有 font-size/color），保留一行自有声明。 */
.price { margin-bottom: 16px; font-weight: 600; --el-text-font-size: 24px; }
.specs, .qty { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
/* 13px 走 el-text 变量，灰字用 type="info"；align-self:auto 保住 flex 的原有对齐 */
.label { --el-text-font-size: var(--el-font-size-small); align-self: auto; }
.stock { margin-left: 8px; --el-text-font-size: var(--el-font-size-small); align-self: auto; }
.desc { display: flex; gap: 8px; margin-bottom: 16px; align-items: flex-start; }
.desc-text { margin: 0; line-height: 1.7; --el-text-font-size: var(--el-font-size-small); }
</style>
