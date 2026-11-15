<template>
  <div class="home">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        data-testid="home-search-input"
        placeholder="搜索商品"
        clearable
        style="width: 260px"
        @keyup.enter="load"
        @clear="load"
      >
        <template #append>
          <el-button :icon="Search" data-testid="home-search-submit" @click="load" />
        </template>
      </el-input>

      <el-radio-group v-model="categoryId" size="default" @change="load">
        <el-radio-button :value="null" data-testid="home-category-all">全部</el-radio-button>
        <el-radio-button
          v-for="c in categories"
          :key="c.id"
          :value="c.id"
          :data-testid="`home-category-${c.id}`"
        >
          {{ c.name }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <div v-loading="loading" class="grid" data-testid="home-product-grid">
      <el-card
        v-for="p in products"
        :key="p.id"
        class="card"
        shadow="hover"
        data-testid="home-product-card"
        @click="$router.push(`/product/${p.id}`)"
      >
        <div class="thumb">
          <el-image v-if="p.pic" :src="p.pic" fit="cover" class="cover">
            <template #error><el-icon :size="40"><Goods /></el-icon></template>
          </el-image>
          <el-icon v-else :size="40"><Goods /></el-icon>
        </div>
        <div class="name" data-testid="home-product-card-name">{{ p.name }}</div>
        <el-text class="cta" tag="div" size="small" type="primary">查看详情 →</el-text>
      </el-card>
      <el-empty v-if="!loading && !products.length" description="暂无商品" data-testid="home-empty" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listCategories, listProducts } from '../../api/catalog'

const categories = ref([])
const products = ref([])
const keyword = ref('')
const categoryId = ref(null)
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    products.value = await listProducts({
      keyword: keyword.value || undefined,
      categoryId: categoryId.value || undefined
    })
  } catch (e) {
    ElMessage.error('加载商品失败：' + e.message)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  load()
  categories.value = await listCategories().catch(() => [])
})
</script>

<style scoped>
.toolbar { display: flex; gap: 16px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
.card { cursor: pointer; }
/* 封面位只管尺寸与居中（height/flex/overflow 都是布局）；灰底、灰字、4px 圆角删掉 ——
   没有图时 el-icon 用继承色就够，占位灰底纯装饰。 */
.thumb {
  height: 110px; overflow: hidden;
  display: flex; align-items: center; justify-content: center; margin-bottom: 10px;
}
.cover { width: 100%; height: 100%; }
/* 14px 是 el-text 默认档，字色也用默认 regular（不再钉 --el-text-color-primary） */
.name { min-height: 40px; }
.cta { margin-top: 8px; }
</style>
