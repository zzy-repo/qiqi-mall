<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>退货申请</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column label="订单号" width="170">
        <template #default="{ row }">{{ orderSn.get(row.orderId) || row.orderId }}</template>
      </el-table-column>
      <el-table-column label="申请人" width="110">
        <template #default="{ row }">{{ memberName.get(row.memberId) || row.memberId }}</template>
      </el-table-column>
      <el-table-column label="原因" min-width="140">
        <template #default="{ row }">{{ reasonName.get(row.reasonId) || row.returnReason }}</template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="['warning', 'success', 'danger'][row.status]" size="small">
            {{ ['待处理', '已通过', '已拒绝'][row.status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">审核</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '审核退货申请' : '新增退货申请'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="订单" prop="orderId">
          <el-select v-model="form.orderId" placeholder="请选择订单" style="width: 100%" :disabled="!!form.id">
            <el-option v-for="o in orders" :key="o.id" :label="o.orderSn" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请人" prop="memberId">
          <el-select v-model="form.memberId" placeholder="请选择会员" style="width: 100%">
            <el-option v-for="m in members" :key="m.id" :label="m.username" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="退货原因" prop="reasonId">
          <el-select v-model="form.reasonId" style="width: 100%" placeholder="请选择原因">
            <el-option v-for="r in reasons" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="详细说明">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <el-form-item v-if="form.id" label="处理结果">
          <el-radio-group v-model="form.status">
            <el-radio :value="0">待处理</el-radio>
            <el-radio :value="1">通过</el-radio>
            <el-radio :value="2">拒绝</el-radio>
          </el-radio-group>
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
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listReturnReasons } from '../../api/returnReason'
import { listOrders } from '../../api/order'
import { listMembers } from '../../api/member'
import request from '../../api/request'

const list = ref([])
const reasons = ref([])
const loading = ref(false)

const orders = ref([])
const members = ref([])
const reasonName = computed(() => new Map(reasons.value.map((r) => [r.id, r.name])))
const orderSn = computed(() => new Map(orders.value.map((o) => [o.id, o.orderSn])))
const memberName = computed(() => new Map(members.value.map((m) => [m.id, m.username])))

const load = async () => {
  loading.value = true
  try {
    const [rs, applies, ords, mems] = await Promise.all([
      listReturnReasons(),
      request.get('/return-applies'),
      listOrders(),
      listMembers()
    ])
    reasons.value = rs
    list.value = applies
    orders.value = ords
    members.value = mems
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, orderId: null, memberId: null, reasonId: null, description: '', status: 0, returnReason: '' })

const rules = {
  orderId: [{ required: true, message: '请选择订单', trigger: 'change' }],
  memberId: [{ required: true, message: '请选择申请人', trigger: 'change' }],
  reasonId: [{ required: true, message: '请选择退货原因', trigger: 'change' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, orderId: null, memberId: null, reasonId: null, description: '', status: 0, returnReason: '' })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const body = { ...form, returnReason: reasonName.value.get(form.reasonId) || '' }
    if (form.id) await request.put(`/return-applies/${form.id}`, body)
    else await request.post('/return-applies', body)
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
