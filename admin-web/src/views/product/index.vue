<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>商品列表</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="name" label="商品名称" min-width="200" />
      <el-table-column label="上下架" width="110">
        <template #default="{ row }">
          <el-tag :type="row.publishStatus ? 'success' : 'info'" size="small">
            {{ row.publishStatus ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openSkus(row)">SKU</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑商品' : '新增商品'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入商品名称" maxlength="100" />
        </el-form-item>
        <el-form-item label="分类" prop="categoryId">
          <el-tree-select
            v-model="form.categoryId"
            :data="categoryOptions"
            :render-after-expand="false"
            default-expand-all
            style="width: 100%"
            placeholder="请选择分类"
          />
        </el-form-item>
        <el-form-item label="类型" prop="typeId">
          <el-select v-model="form.typeId" placeholder="请选择类型" style="width: 100%">
            <el-option v-for="t in types" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="上下架">
          <el-radio-group v-model="form.publishStatus">
            <el-radio :value="1">上架</el-radio>
            <el-radio :value="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { listProducts, createProduct, updateProduct } from '../../api/product'
import { listCategories } from '../../api/category'
import { listProductTypes } from '../../api/productType'

const router = useRouter()

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
const form = reactive({ id: null, name: '', categoryId: null, typeId: null, publishStatus: 0 })

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请输入分类ID', trigger: 'blur' }],
  typeId: [{ required: true, message: '请输入类型ID', trigger: 'blur' }]
}

const openSkus = (row) => router.push(`/product/${row.id}/skus`)

const openForm = (row) => {
  Object.assign(form, row
    ? { ...row }
    : { id: null, name: '', categoryId: null, typeId: null, publishStatus: 0 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) await updateProduct(form.id, form)
    else await createProduct(form)
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

onMounted(() => { load(); loadRefs() })
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>