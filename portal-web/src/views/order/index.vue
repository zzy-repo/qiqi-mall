<template>
  <div>
    <h3 class="title">我的订单</h3>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="orders" border>
        <el-table-column prop="orderSn" label="订单号" min-width="170" />
        <el-table-column label="金额" width="120">
          <template #default="{ row }">¥ {{ Number(row.totalAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag[row.status]" size="small">{{ statusText[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="receiverName" label="收货人" width="100" />
        <el-table-column prop="paymentTime" label="支付时间" width="180">
          <template #default="{ row }">{{ row.paymentTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 0" link type="primary" size="small" @click="pay(row)">模拟支付</el-button>
            <el-button v-if="row.status >= 1" link type="warning" size="small" @click="openReturn(row)">申请退货</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" title="订单详情" width="620px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="订单号">{{ detail.order.orderSn }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusText[detail.order.status] }}</el-descriptions-item>
          <el-descriptions-item label="收货人">{{ detail.order.receiverName }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ detail.order.receiverPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="地址" :span="2">{{ detail.order.receiverAddress }}</el-descriptions-item>
        </el-descriptions>

        <h4>商品明细</h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="productName" label="商品" min-width="160" />
          <el-table-column label="单价" width="100">
            <template #default="{ row }">¥ {{ Number(row.productPrice).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="productQuantity" label="数量" width="70" />
        </el-table>
      </template>
    </el-dialog>

    <el-dialog v-model="returnVisible" title="申请退货" width="520px">
      <el-form ref="returnFormRef" :model="returnForm" :rules="returnRules" label-width="90px">
        <el-form-item label="订单号">
          <el-input :model-value="returnForm.orderSn" disabled />
        </el-form-item>
        <el-form-item label="退货原因" prop="reasonId">
          <el-select v-model="returnForm.reasonId" placeholder="请选择原因" style="width: 100%">
            <el-option v-for="r in reasons" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="详细说明">
          <el-input v-model="returnForm.description" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returnVisible = false">取消</el-button>
        <el-button type="primary" :loading="returning" @click="submitReturn">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listOrders, getOrder, payOrder } from '../../api/order'
import { listReturnReasons, applyReturn } from '../../api/returns'

const route = useRoute()

const statusText = ['待付款', '已付款', '已发货', '已完成', '已关闭']
const statusTag = ['warning', 'success', 'primary', 'success', 'info']

const orders = ref([])
const loading = ref(false)
const detail = ref(null)
const detailVisible = ref(false)
const reasons = ref([])
const returnVisible = ref(false)
const returning = ref(false)
const returnFormRef = ref()
const returnForm = reactive({ orderId: null, orderSn: '', reasonId: null, description: '' })
const returnRules = { reasonId: [{ required: true, message: '请选择退货原因', trigger: 'change' }] }

const load = async () => {
  loading.value = true
  try {
    orders.value = await listOrders()
  } catch (e) {
    ElMessage.error('加载订单失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const openDetail = async (row) => {
  detail.value = await getOrder(row.id)
  detailVisible.value = true
}

/** 模拟支付 */
const pay = async (row) => {
  try {
    await payOrder(row.id, 1)
    ElMessage.success('支付成功（模拟）')
    await load()
  } catch (e) {
    ElMessage.error('支付失败：' + e.message)
  }
}

const openReturn = async (row) => {
  Object.assign(returnForm, { orderId: row.id, orderSn: row.orderSn, reasonId: null, description: '' })
  if (!reasons.value.length) reasons.value = await listReturnReasons().catch(() => [])
  returnVisible.value = true
  returnFormRef.value?.clearValidate()
}

const submitReturn = async () => {
  const valid = await returnFormRef.value.validate().catch(() => false)
  if (!valid) return
  returning.value = true
  try {
    await applyReturn({ orderId: returnForm.orderId, reasonId: returnForm.reasonId, description: returnForm.description })
    ElMessage.success('退货申请已提交，等待商家处理')
    returnVisible.value = false
  } catch (e) {
    ElMessage.error('提交失败：' + e.message)
  } finally {
    returning.value = false
  }
}

onMounted(async () => {
  await load()
  // 从结算页跳来时可自动弹出支付
  if (route.query.pay) {
    const target = orders.value.find((o) => String(o.id) === String(route.query.pay))
    if (target && target.status === 0) await pay(target)
  }
})
</script>

<style scoped>
.title { margin: 0 0 12px; font-size: 16px; }
h4 { margin: 16px 0 8px; font-size: 14px; }
</style>
