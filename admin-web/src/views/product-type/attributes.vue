<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div class="title-group">
          <el-button link :icon="ArrowLeft" @click="$router.push('/product-type')">返回</el-button>
          <el-divider direction="vertical" />
          <span>属性管理</span>
          <el-tag v-if="typeName" size="small" type="info">{{ typeName }}</el-tag>
        </div>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增属性</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="id" label="属性ID" width="100" />
      <el-table-column prop="typeId" label="所属类型ID" width="120" />
      <el-table-column prop="name" label="属性名称" />
      <el-table-column label="是否筛选" width="120">
        <template #default="{ row }">
          <el-tag :type="row.isFilter ? 'success' : 'info'" size="small">
            {{ row.isFilter ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑属性' : '新增属性'" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="所属类型">
          <el-input :model-value="typeName || form.typeId" disabled />
        </el-form-item>
        <el-form-item label="属性名称" prop="name">
          <el-input v-model="form.name" placeholder="如：颜色、内存" maxlength="50" />
        </el-form-item>
        <el-form-item label="是否可筛选">
          <el-switch v-model="form.isFilter" :active-value="1" :inactive-value="0" />
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
import { useRoute } from 'vue-router'
import { Plus, ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listAttributes, createAttribute, updateAttribute } from '../../api/attribute'
import { getProductType } from '../../api/productType'

const route = useRoute()
const typeId = Number(route.params.typeId)

const list = ref([])
const loading = ref(false)
const typeName = ref('')

const load = async () => {
  loading.value = true
  try {
    const [rows, type] = await Promise.all([
      listAttributes({ typeId }),
      getProductType(typeId).catch(() => null)
    ])
    list.value = rows
    typeName.value = type?.name || ''
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, typeId, name: '', isFilter: 0 })

const rules = {
  name: [{ required: true, message: '请输入属性名称', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, typeId, name: '', isFilter: 0 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) await updateAttribute(form.id, form)
    else await createAttribute({ ...form, typeId })
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
.title-group { display: flex; align-items: center; gap: 8px; }
</style>
