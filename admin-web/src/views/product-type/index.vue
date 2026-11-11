<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>商品类型</span>
        <el-button type="primary" :icon="Plus" data-testid="product-type-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="product-type-list-table">
      <el-table-column prop="name" label="类型名称" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <span :data-testid="`product-type-row-${row.id}`">
            <el-button link type="primary" size="small" data-testid="product-type-row-edit" @click="openForm(row)">编辑</el-button>
            <el-button link type="danger" size="small" data-testid="product-type-row-delete" @click="remove(row)">删除</el-button>
            <el-button link type="primary" size="small" data-testid="product-type-row-attributes" @click="openAttributes(row)">属性</el-button>
          </span>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { listProductTypes, createProductType, updateProductType, deleteProductType } from '../../api/productType'

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

/** 删除类型：后端会拦「还被属性/商品引用」的情况，并把原因带回来 */
const remove = async (row) => {
  const ok = await ElMessageBox.confirm(
    `确认删除类型「${row.name}」？删除后不可恢复。`, '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteProductType(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    // 直接展示后端中文原因（如「该类型下还有 N 个属性 / M 个商品，不能删除」）
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>