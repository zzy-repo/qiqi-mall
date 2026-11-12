<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>退货申请</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="return-apply-list-table">
      <el-table-column label="订单号" width="180">
        <template #default="{ row }">{{ row.orderSn || row.orderId }}</template>
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
          <el-button link type="primary" size="small" data-testid="return-apply-row-audit" @click="openForm(row)">审核</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="visible"
      :title="form.id ? '审核退货申请' : '新增退货申请'"
      width="520px"
      data-testid="return-apply-form-dialog"
    >
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
          <el-select v-model="form.reasonId" data-testid="return-apply-form-reason" style="width: 100%" placeholder="请选择原因">
            <el-option v-for="r in reasons" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="详细说明">
          <el-input v-model="form.description" data-testid="return-apply-form-description" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <el-form-item v-if="form.id" label="处理结果">
          <el-radio-group v-model="form.status" data-testid="return-apply-form-status">
            <el-radio :value="0" data-testid="return-apply-form-status-pending">待处理</el-radio>
            <el-radio :value="1" data-testid="return-apply-form-status-pass">通过</el-radio>
            <el-radio :value="2" data-testid="return-apply-form-status-reject">拒绝</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <!-- 预演失败的原因留在表单里（不是只发一条会消失的 toast）：
           用户能看到「为什么」、并且已填的内容一个都没丢，改一下就能重新提交。 -->
      <el-alert
        v-if="previewError"
        type="error"
        :closable="false"
        show-icon
        :title="previewError"
        data-testid="return-apply-form-preview-error"
        style="margin-top: 4px"
      />
      <template #footer>
        <el-button data-testid="return-apply-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="return-apply-form-submit" @click="submit">确定</el-button>
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
import {
  listReturnApplies, createReturnApply, updateReturnApply, updateReturnApplyDryRun
} from '../../api/returnApply'
import { useDangerConfirm } from '../../composables/useDangerConfirm'

/** 审核退货（高危：通过会退款 + 回补库存）走「预演 → 确认 → 执行」 */
const danger = useDangerConfirm()

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
      listReturnApplies(),
      listOrders({ page: 1, size: 50 }).then((d) => d.records || []),
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
/** 预演失败的原因（显示在表单内；清空重试时会重置） */
const previewError = ref('')
const formRef = ref()
const form = reactive({ id: null, orderId: null, memberId: null, reasonId: null, description: '', status: 0, returnReason: '' })

const rules = {
  orderId: [{ required: true, message: '请选择订单', trigger: 'change' }],
  memberId: [{ required: true, message: '请选择申请人', trigger: 'change' }],
  reasonId: [{ required: true, message: '请选择退货原因', trigger: 'change' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, orderId: null, memberId: null, reasonId: null, description: '', status: 0, returnReason: '' })
  previewError.value = ''
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const body = { ...form, returnReason: reasonName.value.get(form.reasonId) || '' }

  // 新增：只是建单，不影响订单/库存，不走高危确认弹窗
  if (!form.id) {
    saving.value = true
    try {
      await createReturnApply(body)
      ElMessage.success('保存成功')
      visible.value = false
      await load()
    } catch (e) {
      ElMessage.error('保存失败：' + e.message)
    } finally {
      saving.value = false
    }
    return
  }

  // 审核：通过=退款+回补库存、拒绝=订单回已付款，两个方向都由后端预演说明清楚
  if (form.status !== 1 && form.status !== 2) {
    ElMessage.warning('请先选择审核结果：通过 / 拒绝')
    return
  }
  const applyId = form.id
  previewError.value = ''
  await danger.run({
    title: '⚠️ 确认审核退货申请',
    confirmText: form.status === 1 ? '确认通过' : '确认拒绝',
    successText: '审核完成',
    dryRun: () => updateReturnApplyDryRun(applyId, body),
    exec: () => updateReturnApply(applyId, body),
    // ✅ 预演通过后才收表单：预演失败时表单原地不动、用户填的内容全在，
    //    错误由 onPreviewError 写回表单内（旧版的体验：表单停住 + 错误提示 + 改一下就能重提）
    onPreviewOk: () => { visible.value = false },
    onPreviewError: (e) => {
      previewError.value = '预演失败：' + (e?.message || '后端没有返回具体原因')
    },
    onSuccess: () => load()
  })
}

onMounted(load)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
