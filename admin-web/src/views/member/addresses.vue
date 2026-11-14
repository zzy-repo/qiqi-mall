<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div class="title-group">
          <el-button link :icon="ArrowLeft" @click="$router.push('/member')">返回</el-button>
          <el-divider direction="vertical" />
          <span>收货地址</span>
          <el-tag v-if="memberName" size="small" type="info">{{ memberName }}</el-tag>
        </div>
        <el-button type="primary" :icon="Plus" data-testid="member-address-list-create" @click="openForm()">新增地址</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="member-address-list-table">
      <el-table-column prop="name" label="收件人" width="120" />
      <el-table-column prop="phone" label="电话" width="140" />
      <el-table-column label="所在地区" min-width="200">
        <template #default="{ row }">{{ row.province }} {{ row.city }} {{ row.region }}</template>
      </el-table-column>
      <el-table-column prop="detail" label="详细地址" min-width="220" show-overflow-tooltip />
      <el-table-column label="默认" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.defaultStatus" type="success" size="small">默认</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="member-address-row-edit" @click="openForm(row)">编辑</el-button>
          <el-button link type="danger" size="small" data-testid="member-address-row-remove" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑地址' : '新增地址'" width="560px" data-testid="member-address-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="所属会员">
          <el-input :model-value="memberName || form.memberId" disabled />
        </el-form-item>
        <el-form-item label="收件人" prop="name">
          <el-input v-model="form.name" data-testid="member-address-form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
          <el-input v-model="form.phone" data-testid="member-address-form-phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="省" prop="province">
          <el-input v-model="form.province" data-testid="member-address-form-province" maxlength="50" />
        </el-form-item>
        <el-form-item label="市" prop="city">
          <el-input v-model="form.city" data-testid="member-address-form-city" maxlength="50" />
        </el-form-item>
        <el-form-item label="区/县" prop="region">
          <el-input v-model="form.region" data-testid="member-address-form-region" maxlength="50" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detail">
          <el-input v-model="form.detail" data-testid="member-address-form-detail" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <el-form-item label="默认地址">
          <el-switch v-model="form.defaultStatus" data-testid="member-address-form-default" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="member-address-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="member-address-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAddresses, createAddress, updateAddress, deleteAddress } from '../../api/memberAddress'
import { getMember } from '../../api/member'

const route = useRoute()
const memberId = Number(route.params.memberId)

const list = ref([])
const loading = ref(false)
const memberName = ref('')

const blank = () => ({
  id: null, memberId, name: '', phone: '',
  province: '', city: '', region: '', detail: '', defaultStatus: 0
})

const load = async () => {
  loading.value = true
  try {
    const [rows, member] = await Promise.all([
      listAddresses({ memberId }),
      getMember(memberId).catch(() => null)
    ])
    list.value = rows
    memberName.value = member?.username || ''
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive(blank())

const rules = {
  name: [{ required: true, message: '请输入收件人', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入电话', trigger: 'blur' }],
  province: [{ required: true, message: '请输入省', trigger: 'blur' }],
  city: [{ required: true, message: '请输入市', trigger: 'blur' }],
  region: [{ required: true, message: '请输入区/县', trigger: 'blur' }],
  detail: [{ required: true, message: '请输入详细地址', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : blank())
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const body = { ...form, memberId }
    if (form.id) await updateAddress(form.id, body)
    else await createAddress(body)
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
  const ok = await ElMessageBox.confirm(`确认删除「${row.name}」这条地址？`, '提示', { type: 'warning' })
    .then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteAddress(row.id)
    ElMessage.success('删除成功')
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

onMounted(load)
</script>
