<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>退货原因</span>
        <el-button type="primary" :icon="Plus" data-testid="return-reason-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="return-reason-list-table">
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="name" label="退货原因" min-width="240" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <span :data-testid="`return-reason-row-${row.id}`">
            <el-button link type="primary" size="small" data-testid="return-reason-row-edit" @click="openForm(row)">编辑</el-button>
            <el-button link type="danger" size="small" data-testid="return-reason-row-delete" @click="remove(row)">删除</el-button>
          </span>
        </template>
      </el-table-column>
    </el-table>

    <div class="tip">退货原因用于买家申请退货时选择；已被退货申请使用的原因不能删除。</div>

    <el-dialog v-model="visible" :title="form.id ? '编辑退货原因' : '新增退货原因'" width="460px" data-testid="return-reason-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="退货原因" prop="name">
          <el-input v-model="form.name" data-testid="return-reason-form-name" placeholder="如：质量问题" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="return-reason-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="return-reason-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listReturnReasons, createReturnReason, updateReturnReason, deleteReturnReason
} from '../../api/returnReason'

const list = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, name: '' })

const rules = {
  name: [{ required: true, message: '请输入退货原因', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  try {
    list.value = await listReturnReasons()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const openForm = (row) => {
  Object.assign(form, row ? { id: row.id, name: row.name } : { id: null, name: '' })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const name = form.name.trim()
  // 后端目前不校验重名，这里前端先提示一次（避免出现两条一模一样的选项）
  const duplicated = list.value.some((r) => r.name === name && r.id !== form.id)
  if (duplicated) {
    ElMessage.warning('已存在同名退货原因，请换一个名称')
    return
  }
  saving.value = true
  try {
    if (form.id) await updateReturnReason(form.id, { name })
    else await createReturnReason({ name })
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

/** 删除：被退货申请使用过时后端会拦住并说明原因 */
const remove = async (row) => {
  const ok = await ElMessageBox.confirm(
    `确认删除退货原因「${row.name}」？已被退货申请使用的原因无法删除。`, '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteReturnReason(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.tip { margin-top: 10px; font-size: 12px; color: #909399; }
</style>
