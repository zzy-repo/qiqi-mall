<template>
  <div class="home">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索商品"
        clearable
        style="width: 260px"
        @keyup.enter="load"
        @clear="load"
      >
        <template #append>
          <el-button :icon="Search" @click="load" />
        </template>
      </el-input>

      <el-radio-group v-model="categoryId" size="default" @change="load">
        <el-radio-button :value="null">全部</el-radio-button>
        <el-radio-button v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</el-radio-button>
      </el-radio-group>
    </div>

    <div v-loading="loading" class="grid">
      <el-card
        v-for="p in products"
        :key="p.id"
        class="card"
        shadow="hover"
        @click="$router.push(`/product/${p.id}`)"
      >
        <div class="thumb">
          <el-image v-if="p.pic" :src="p.pic" fit="cover" class="cover">
            <template #error><el-icon :size="40"><Goods /></el-icon></template>
          </el-image>
          <el-icon v-else :size="40"><Goods /></el-icon>
        </div>
        <div class="name">{{ p.name }}</div>
        <div class="cta">查看详情 →</div>
      </el-card>
      <el-empty v-if="!loading && !products.length" description="暂无商品" />
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
.thumb {
  height: 110px; background: #f0f2f5; border-radius: 4px; overflow: hidden;
  display: flex; align-items: center; justify-content: center; color: #c0c4cc; margin-bottom: 10px;
}
.cover { width: 100%; height: 100%; }
.cover :deep(img) { width: 100%; height: 100%; object-fit: cover; }
.name { font-size: 14px; color: #303133; min-height: 40px; }
.cta { font-size: 12px; color: #409eff; margin-top: 8px; }
</style>
