<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>会员列表</span>
        <el-button type="primary" :icon="Plus" data-testid="member-list-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="member-list-table">
      <el-table-column prop="username" label="登录名" min-width="140" />
      <el-table-column prop="nickname" label="昵称" width="120" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="注册时间" width="180" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="member-row-addresses" @click="openAddresses(row)">收货地址</el-button>
          <el-button link type="primary" size="small" data-testid="member-row-carts" @click="openCarts(row)">购物车</el-button>
          <el-button link type="primary" size="small" data-testid="member-row-edit" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑会员' : '新增会员'" width="500px" data-testid="member-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="登录名" prop="username">
          <el-input v-model="form.username" data-testid="member-form-username" maxlength="50" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" data-testid="member-form-nickname" maxlength="50" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" data-testid="member-form-phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" data-testid="member-form-status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="member-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="member-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listMembers, createMember, updateMember } from '../../api/member'

const router = useRouter()

const list = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    list.value = await listMembers()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const openAddresses = (row) => router.push(`/member/${row.id}/addresses`)
const openCarts = (row) => router.push(`/member/${row.id}/carts`)

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, username: '', nickname: '', phone: '', status: 1 })

const rules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, username: '', nickname: '', phone: '', status: 1 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) await updateMember(form.id, form)
    else await createMember(form)
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
