<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>商品类型</span>
        <el-button type="primary" :icon="Plus" data-testid="product-type-list-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="product-type-list-table">
      <el-table-column prop="name" label="类型名称" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="product-type-row-edit" @click="openForm(row)">编辑</el-button>
          <el-button link type="primary" size="small" data-testid="product-type-row-attributes" @click="openAttributes(row)">属性</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑类型' : '新增类型'" width="460px" data-testid="product-type-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="类型名称" prop="name">
          <el-input v-model="form.name" data-testid="product-type-form-name" placeholder="如：手机、服装" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="product-type-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="product-type-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { listProductTypes, createProductType, updateProductType } from '../../api/productType'

const router = useRouter()

const list = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    list.value = await listProductTypes()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, name: '' })

const rules = {
  name: [{ required: true, message: '请输入类型名称', trigger: 'blur' }]
}

const openAttributes = (row) => router.push(`/product-type/${row.id}/attributes`)

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, name: '' })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) await updateProductType(form.id, form)
    else await createProductType(form)
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