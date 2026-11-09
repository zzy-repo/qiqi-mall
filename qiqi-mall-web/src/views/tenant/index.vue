<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>租户</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="name" label="公司名" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑租户' : '新增租户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="公司名" prop="name">
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="`租户详情 - ${detail?.tenant?.name || ''}`" size="560px">
      <template v-if="detail">
        <h4>用户</h4>
        <el-table :data="detail.users" border size="small">
          <el-table-column prop="username" label="用户名" />
        </el-table>
        <h4 style="margin-top:20px">套餐订阅</h4>
        <el-table :data="detail.plans" border size="small">
          <el-table-column label="套餐" width="120">
            <template #default="{ row }">{{ planName.get(row.planId) || row.planId }}</template>
          </el-table-column>
          <el-table-column prop="expireAt" label="到期时间" />
        </el-table>
        <el-empty v-if="!detail.plans.length" description="未订阅套餐" :image-size="60" />
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listTenants, createTenant, updateTenant, getTenantDetail } from '../../api/tenant'
import { listPlans } from '../../api/plan'

const list = ref([])
const plans = ref([])
const loading = ref(false)

const planName = computed(() => new Map(plans.value.map((p) => [p.id, p.name])))

const load = async () => {
  loading.value = true
  try {
    list.value = await listTenants()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, name: '', status: 1 })

const rules = {
  name: [{ required: true, message: '请输入公司名', trigger: 'blur' }]
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
    if (form.id) await updateTenant(form.id, form)
    else await createTenant(form)
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

const detail = ref(null)
const detailVisible = ref(false)
const openDetail = async (row) => {
  detail.value = await getTenantDetail(row.id)
  detailVisible.value = true
}

onMounted(async () => {
  load()
  plans.value = await listPlans().catch(() => [])
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
