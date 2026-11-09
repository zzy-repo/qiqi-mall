<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>商品列表</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="id" label="商品ID" width="100" />
      <el-table-column prop="name" label="商品名称" min-width="200" />
      <el-table-column prop="categoryId" label="分类ID" width="110" />
      <el-table-column prop="typeId" label="类型ID" width="110" />
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
        <el-form-item label="分类ID" prop="categoryId">
          <el-input-number v-model="form.categoryId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="类型ID" prop="typeId">
          <el-input-number v-model="form.typeId" :min="1" :controls="false" style="width: 100%" />
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
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { listProducts, createProduct, updateProduct } from '../../api/product'

const router = useRouter()

const list = ref([])
const loading = ref(false)

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

onMounted(load)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>