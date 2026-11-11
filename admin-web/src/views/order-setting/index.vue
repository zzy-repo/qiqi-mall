<template>
  <el-card shadow="never" header="订单设置">
    <el-form v-loading="loading" ref="formRef" :model="form" :rules="rules" label-width="180px" style="max-width: 520px">
      <el-form-item label="自动收货天数" prop="autoConfirmDays">
        <el-input-number
          v-model="form.autoConfirmDays"
          data-testid="order-setting-form-auto-confirm-days"
          :min="1"
          :precision="0"
          style="width: 100%"
        />
        <div class="tip">发货后 N 天自动确认收货</div>
      </el-form-item>
      <el-form-item label="未付款自动关单（分钟）" prop="autoCloseMinutes">
        <el-input-number
          v-model="form.autoCloseMinutes"
          data-testid="order-setting-form-auto-close-minutes"
          :min="1"
          :precision="0"
          style="width: 100%"
        />
        <div class="tip">下单后 N 分钟未付款自动关闭订单</div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" data-testid="order-setting-form-submit" @click="submit">保存</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getOrderSetting, updateOrderSetting } from '../../api/orderSetting'

const loading = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, autoConfirmDays: 7, autoCloseMinutes: 30 })

const rules = {
  autoConfirmDays: [{ required: true, message: '请输入自动收货天数', trigger: 'blur' }],
  autoCloseMinutes: [{ required: true, message: '请输入自动关单分钟数', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  try {
    Object.assign(form, await getOrderSetting())
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    Object.assign(form, await updateOrderSetting(form))
    ElMessage.success('保存成功')
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.tip { font-size: 12px; color: #909399; line-height: 1.6; }
</style>
