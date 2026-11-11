<template>
  <div>
    <div class="head">
      <h3 class="title">收货地址</h3>
      <el-button type="primary" :icon="Plus" data-testid="addresses-create" @click="openForm()">新增地址</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" border data-testid="address-list-table">
        <el-table-column prop="name" label="收件人" width="120" />
        <el-table-column prop="phone" label="电话" width="140" />
        <el-table-column label="所在地区" min-width="180">
          <template #default="{ row }">{{ row.province }} {{ row.city }} {{ row.region }}</template>
        </el-table-column>
        <el-table-column prop="detail" label="详细地址" min-width="200" show-overflow-tooltip />
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.defaultStatus" type="success" size="small" data-testid="addresses-row-default">默认</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <span :data-testid="`addresses-row-${row.id}`">
              <el-button link type="primary" size="small" data-testid="addresses-row-edit" @click="openForm(row)">编辑</el-button>
              <el-button link type="danger" size="small" data-testid="addresses-row-remove" @click="remove(row)">删除</el-button>
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="visible"
      :title="form.id ? '编辑地址' : '新增地址'"
      width="520px"
      data-testid="address-form-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="收件人" prop="name">
          <el-input v-model="form.name" data-testid="address-form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
          <el-input v-model="form.phone" data-testid="address-form-phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="省" prop="province">
          <el-input v-model="form.province" data-testid="address-form-province" maxlength="50" />
        </el-form-item>
        <el-form-item label="市" prop="city">
          <el-input v-model="form.city" data-testid="address-form-city" maxlength="50" />
        </el-form-item>
        <el-form-item label="区/县" prop="region">
          <el-input v-model="form.region" data-testid="address-form-region" maxlength="50" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detail">
          <el-input v-model="form.detail" data-testid="address-form-detail" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <el-form-item label="默认地址">
          <el-switch v-model="form.defaultStatus" data-testid="address-form-default" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="address-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="address-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAddresses, addAddress, updateAddress, deleteAddress } from '../../api/member'

const list = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const formRef = ref()

const blank = () => ({
  id: null, name: '', phone: '', province: '', city: '', region: '', detail: '', defaultStatus: 0
})
const form = reactive(blank())

const rules = {
  name: [{ required: true, message: '请输入收件人', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入电话', trigger: 'blur' }],
  province: [{ required: true, message: '请输入省', trigger: 'blur' }],
  city: [{ required: true, message: '请输入市', trigger: 'blur' }],
  region: [{ required: true, message: '请输入区/县', trigger: 'blur' }],
  detail: [{ required: true, message: '请输入详细地址', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  try {
    list.value = await listAddresses()
  } catch (e) {
    ElMessage.error('加载地址失败：' + e.message)
  } finally {
    loading.value = false
  }
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
    if (form.id) await updateAddress(form.id, form)
    else await addAddress(form)
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
  const ok = await ElMessageBox.confirm('确认删除该地址？', '提示', { type: 'warning' })
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

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { margin: 0; font-size: 16px; }
</style>
