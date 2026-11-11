<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>管理员</span>
        <el-button type="primary" :icon="Plus" data-testid="admin-list-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="admin-list-table">
      <el-table-column prop="username" label="登录名" min-width="140" />
      <el-table-column prop="nickname" label="昵称" width="140" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="admin-row-edit" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑管理员' : '新增管理员'" width="500px" data-testid="admin-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="登录名" prop="username">
          <el-input v-model="form.username" data-testid="admin-form-username" maxlength="50" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            data-testid="admin-form-password"
            type="password"
            show-password
            maxlength="50"
            :placeholder="form.id ? '留空表示不修改密码' : '新增管理员必须设置密码'"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" data-testid="admin-form-nickname" maxlength="50" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" data-testid="admin-form-email" maxlength="100" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" data-testid="admin-form-status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="admin-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="admin-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listAdmins, createAdmin, updateAdmin } from '../../api/admin'

const list = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    list.value = await listAdmins()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, username: '', password: '', nickname: '', email: '', status: 1 })

const rules = computed(() => ({
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }],
  // 新增必须填密码；编辑时留空表示不修改
  password: form.id ? [] : [{ required: true, message: '请输入密码', trigger: 'blur' }]
}))

const openForm = (row) => {
  // 编辑时不要把后端返回的密码/hash 带进表单，留空即「不修改」
  Object.assign(form, row
    ? { id: row.id, username: row.username, password: '', nickname: row.nickname, email: row.email, status: row.status }
    : { id: null, username: '', password: '', nickname: '', email: '', status: 1 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      username: form.username,
      nickname: form.nickname,
      email: form.email,
      status: form.status
    }
    // 密码：新增必传；编辑时为空则不传（后端据此不改密码）
    if (form.password) payload.password = form.password
    if (form.id) await updateAdmin(form.id, payload)
    else await createAdmin(payload)
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
