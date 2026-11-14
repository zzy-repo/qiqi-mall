<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>商品列表</span>
        <el-button type="primary" :icon="Plus" data-testid="product-list-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="product-list-table">
      <el-table-column label="主图" width="90">
        <template #default="{ row }">
          <el-image v-if="row.pic" :src="row.pic" fit="cover" class="thumb" data-testid="product-row-cover">
            <template #error><span class="empty-pic">无图</span></template>
          </el-image>
          <span v-else class="empty-pic">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="商品名称" min-width="200" />
      <el-table-column label="上下架" width="110">
        <template #default="{ row }">
          <el-tag :type="row.publishStatus ? 'success' : 'info'" size="small" data-testid="product-row-status">
            {{ row.publishStatus ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <span :data-testid="`product-row-${row.id}`">
            <el-button link type="primary" size="small" data-testid="product-row-edit" @click="openForm(row)">编辑</el-button>
            <el-button link type="primary" size="small" data-testid="product-row-skus" @click="openSkus(row)">SKU</el-button>
            <el-button link type="danger" size="small" data-testid="product-row-delete" @click="remove(row)">删除</el-button>
          </span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑商品' : '新增商品'" width="560px" data-testid="product-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" data-testid="product-form-name" placeholder="请输入商品名称" maxlength="100" />
        </el-form-item>
        <el-form-item label="商品描述">
          <el-input
            v-model="form.description"
            data-testid="product-form-description"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="商品卖点/材质/适用场景等，详情页会展示"
          />
        </el-form-item>
        <el-form-item label="分类" prop="categoryId">
          <el-tree-select
            v-model="form.categoryId"
            data-testid="product-form-category"
            :data="categoryOptions"
            check-strictly
            :render-after-expand="false"
            default-expand-all
            style="width: 100%"
            placeholder="请选择分类"
          />
        </el-form-item>
        <el-form-item label="类型" prop="typeId">
          <el-select v-model="form.typeId" data-testid="product-form-type" placeholder="请选择类型" style="width: 100%">
            <el-option v-for="t in types" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="主图">
          <ImageUploader v-model="form.pic" data-testid="product-form-cover" placeholder="主图URL（可直接上传）" />
        </el-form-item>
        <el-form-item label="商品相册">
          <div class="gallery">
            <div
              v-for="(img, index) in form.gallery"
              :key="index"
              class="gallery-row"
              data-testid="product-form-gallery-row"
            >
              <ImageUploader v-model="img.url" class="grow" data-testid="product-form-gallery-url" placeholder="相册图URL" />
              <el-input-number
                v-model="img.sort"
                data-testid="product-form-gallery-sort"
                :min="0"
                :precision="0"
                style="width: 110px"
                controls-position="right"
              />
              <el-button
                link
                type="danger"
                size="small"
                data-testid="product-form-gallery-remove"
                @click="form.gallery.splice(index, 1)"
              >删除</el-button>
            </div>
            <el-button
              size="small"
              :icon="Plus"
              data-testid="product-form-gallery-add"
              @click="form.gallery.push({ url: '', sort: form.gallery.length + 1 })"
            >
              添加图片
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="上下架">
          <el-radio-group v-model="form.publishStatus" data-testid="product-form-publish">
            <el-radio :value="1">上架</el-radio>
            <el-radio :value="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="product-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="product-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import ImageUploader from '../../components/ImageUploader.vue'
import { listProducts, getProduct, createProduct, updateProduct, deleteProduct, deleteProductDryRun } from '../../api/product'
import { useDangerConfirm } from '../../composables/useDangerConfirm'
import { handleSaveError } from '../../composables/useConflictHandler'
import { listCategories } from '../../api/category'
import { listProductTypes } from '../../api/productType'

const router = useRouter()
/** 删除商品（高危）：预演 → 确认 → 执行 */
const danger = useDangerConfirm()

const list = ref([])
const loading = ref(false)
const categories = ref([])
const types = ref([])

// 分类树（仅叶子可选？不，任何分类都可挂商品，全部可选）
const categoryTree = computed(() => {
  const map = new Map(categories.value.map((r) => ({ ...r, children: [] })).map((r) => [r.id, r]))
  const roots = []
  map.forEach((node) => {
    const parent = node.parentId ? map.get(node.parentId) : null
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
})
const categoryOptions = computed(() => {
  const toNode = (c) => ({ value: c.id, label: c.name, children: (c.children || []).map(toNode) })
  return categoryTree.value.map(toNode)
})
const loadRefs = async () => {
  try {
    const [cats, tps] = await Promise.all([listCategories(), listProductTypes()])
    categories.value = cats
    types.value = tps
  } catch (e) {
    // 参照数据加载失败不阻塞主列表
    console.warn('加载分类/类型失败', e)
  }
}

const load = async () => {
  loading.value = true
  try {
    list.value = await listProducts()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const blankForm = () => ({
  id: null, name: '', description: '', categoryId: null, typeId: null, publishStatus: 1, pic: '', gallery: [],
  /** 乐观锁版本号：编辑时从详情取、保存时原样带回；新建保持 null（不传，让后端置 0） */
  version: null
})
const form = reactive(blankForm())

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请输入分类ID', trigger: 'blur' }],
  typeId: [{ required: true, message: '请输入类型ID', trigger: 'blur' }]
}

const openSkus = (row) => router.push(`/product/${row.id}/skus`)

const openForm = async (row) => {
  Object.assign(form, blankForm())
  if (row) {
    try {
      const data = await getProduct(row.id)
      Object.assign(form, { ...data.product, gallery: data.gallery || [] })
    } catch (e) {
      ElMessage.error('加载商品失败：' + e.message)
      return
    }
  }
  visible.value = true
  formRef.value?.clearValidate()
}

/** 重新拉一次当前商品详情并重填表单（409/400 后点「刷新」用；弹窗保持打开，让用户在新数据上再改） */
const reloadForm = async () => {
  if (!form.id) return
  const data = await getProduct(form.id)
  Object.assign(form, { ...data.product, gallery: data.gallery || [] })
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      product: {
        name: form.name,
        // 描述必须一起提交：之前这里只白名单了 5 个字段，弹窗里填的描述被静默丢掉
        description: form.description,
        categoryId: form.categoryId,
        typeId: form.typeId,
        publishStatus: form.publishStatus,
        pic: form.pic
      },
      gallery: form.gallery.filter((g) => g.url)
    }
    if (form.id) {
      // 编辑必须带 version（乐观锁，fail-closed）：不带 → 后端 400；版本过期 → 409。
      // 新建不传，让后端置 0。
      payload.product.version = form.version
      await updateProduct(form.id, payload)
    } else {
      await createProduct(payload)
    }
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    // 409（被别人改过）/ 400（漏传版本号）交给统一冲突处理器：弹窗里说清楚 + 给「刷新」按钮，
    // 绝不用一句 2 秒就消失的 toast 打发（用户会以为存上了，改的内容就真丢了）。
    if (handleSaveError(e, { onRefresh: reloadForm }) !== 'other') return
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

/**
 * 删除商品：先预演，弹窗里把「会被几条订单明细 / 几个购物车引用、删不掉建议改下架」说清楚，
 * 用户确认后才执行。被引用时后端拒绝，中文原因直接显示在弹窗里
 * （不走 ElMessage.error：失败时弹窗不关，用户能看着原因决定下一步）。
 */
const remove = async (row) => {
  await danger.run({
    title: `⚠️ 确认删除商品「${row.name}」`,
    confirmText: '确认删除',
    successText: '已删除',
    dryRun: () => deleteProductDryRun(row.id),
    exec: () => deleteProduct(row.id),
    onSuccess: () => load()
  })
}

onMounted(() => { load(); loadRefs() })
</script>

<style scoped>
.thumb { width: 44px; height: 44px; border-radius: 4px; }
.empty-pic { color: #c0c4cc; }
.gallery { width: 100%; display: flex; flex-direction: column; gap: 8px; }
.gallery-row { display: flex; gap: 8px; align-items: flex-start; }
.grow { flex: 1; }
</style>