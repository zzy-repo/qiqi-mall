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
            <!-- 每个缩略图 = 一张 el-card：边框/圆角走组件默认，选中态只改 --el-card-border-color
                 （公开变量）。原来是自己写 2px 透明边框 + .active 改 border-color，
                 等于手写了一遍"卡片的边框"。 -->
            <el-card
              v-for="g in gallery"
              :key="g.id"
              class="gallery-item"
              :class="{ active: g.id === activeGallery }"
              shadow="never"
              :body-style="{ padding: '0' }"
              data-testid="product-detail-gallery-item"
              @click="activeGallery = g.id"
            >
              <el-image :src="g.url" class="gallery-img" fit="cover">
                <template #error><el-text class="gallery-fallback" size="small" type="info">无图</el-text></template>
              </el-image>
            </el-card>
          </div>
        </div>
        <div class="info">
          <el-text class="name" tag="h2" size="large" data-testid="product-detail-name">{{ product.name }}</el-text>
          <!-- 价格 = el-statistic：与全站指标数字同一档（20px），比正文（14px）显著大，但不自造字号。
               红色是 C 类保留：el-statistic 没有 type prop，只能走 --el-statistic-content-color。
               group-separator="" 是数据语义：价格要逐字符核，且 e2e 按 Number(price).toFixed(2) 字面比对。 -->
          <el-statistic
            v-if="currentSku"
            class="price"
            :value="Number(currentSku.price)"
            :precision="2"
            prefix="¥ "
            group-separator=""
            data-testid="product-detail-price"
          />
          <el-text v-else class="price" tag="b" size="large" type="danger" data-testid="product-detail-price">—</el-text>
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
            <p class="desc-text" data-testid="product-detail-desc">{{ product.description }}</p>
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
/* 主图位：只管尺寸与居中（灰底/圆角删掉——占位色由 el-icon 的继承色表达） */
.thumb {
  width: 220px; height: 220px; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.cover, .gallery-img { width: 100%; height: 100%; display: block; }
.gallery-fallback { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; }
.gallery { display: flex; gap: 6px; width: 220px; overflow-x: auto; }
/* 缩略图 = el-card：48px 是布局，圆角/边框走组件默认 */
.gallery-item { width: 48px; height: 48px; cursor: pointer; }
/* 「当前主图是哪张」这个信息必须有指示器（相册里没有别的指示）；做法是把卡片边框改成主色，
   用的是 el-card 的公开变量，不是自写边框。 */
.gallery-item.active { --el-card-border-color: var(--el-color-primary); }
/* 标题 = el-text size="large"(16px) + <h2> 标签语义；原来是钉死的 20px（EP 最大档是 16） */
.name { margin: 0 0 12px; }
/* 价格 = el-statistic 默认档（20px / 400），与全站其它数字同一个样子；
   只留一条 C 类覆盖：红色（el-statistic 无 type prop，价格在电商里必须是红的）。 */
.price {
  display: block;
  margin-bottom: 16px;
  --el-statistic-content-color: var(--el-color-danger);
}
.specs, .qty { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
/* 13px 走 el-text 变量，灰字用 type="info"；align-self:auto 保住 flex 的原有对齐 */
.stock { margin-left: 8px; }
.desc { display: flex; gap: 8px; margin-bottom: 16px; align-items: flex-start; }
.desc-text { margin: 0; line-height: 1.7; }
</style>
