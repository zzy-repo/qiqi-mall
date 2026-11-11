<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>仓库管理</span>
        <el-button type="primary" :icon="Plus" data-testid="warehouse-list-create" @click="openForm()">新增仓库</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="warehouse-list-table">
      <el-table-column prop="name" label="仓库名称" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="warehouse-row-edit" @click="openForm(row)">编辑</el-button>
          <el-button link type="danger" size="small" data-testid="warehouse-row-remove" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑仓库' : '新增仓库'" width="460px" data-testid="warehouse-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="仓库名称" prop="name">
          <el-input v-model="form.name" data-testid="warehouse-form-name" maxlength="50" placeholder="如：深圳仓" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" data-testid="warehouse-form-status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="warehouse-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="warehouse-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listWarehouses, createWarehouse, updateWarehouse, deleteWarehouse } from '../../api/warehouse'

const list = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, name: '', status: 1 })
const rules = { name: [{ required: true, message: '请输入仓库名称', trigger: 'blur' }] }

const load = async () => {
  loading.value = true
  try {
    list.value = await listWarehouses()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, name: '', status: 1 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) await updateWarehouse(form.id, form)
    else await createWarehouse(form)
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  const ok = await ElMessageBox.confirm(`确认删除仓库「${row.name}」？`, '提示', { type: 'warning' })
    .then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteWarehouse(row.id)
    ElMessage.success('删除成功')
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
