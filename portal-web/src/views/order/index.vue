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
        <el-table-column label="售后" width="110">
          <template #default="{ row }">
            <el-tag v-if="afterSaleText(row.id)" :type="afterSaleTag(row.id)" size="small">
              {{ afterSaleText(row.id) }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="paymentTime" label="支付时间" width="180">
          <template #default="{ row }">{{ row.paymentTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" size="small" @click="openParcels(row)">包裹</el-button>
            <el-button v-if="row.status === 0" link type="primary" size="small" @click="pay(row)">模拟支付</el-button>
            <el-button v-if="row.status === 2" link type="success" size="small" @click="confirmReceive(row)">确认收货</el-button>
            <el-button
              v-if="row.status === 1 || row.status === 2"
              link
              type="warning"
              size="small"
              @click="openReturn(row)"
            >申请退货</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next, total"
          background
          @current-change="load"
        />
      </div>
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

    <el-drawer v-model="parcelVisible" :title="`包裹 - ${parcelOrderSn}`" size="480px">
      <el-table :data="parcels" border size="small" empty-text="暂无包裹（商家审核后将拆单发货）">
        <el-table-column prop="deliverySn" label="包裹号" min-width="160" />
        <el-table-column prop="warehouseName" label="发货仓" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="parcelTag[row.status] || 'info'" size="small">{{ parcelText[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="运单号" min-width="140">
          <template #default="{ row }">
            <div v-if="row.trackNo">{{ row.company }} {{ row.trackNo }}</div>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

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
import { listOrders, getOrder, payOrder, confirmOrder } from '../../api/order'
import { listReturnReasons, listMyReturns, applyReturn } from '../../api/returns'
import { listMyDeliveries } from '../../api/delivery'

const route = useRoute()

// 状态机：0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款
// 状态机：0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款 7待审核 8待发货 9部分发货
const statusText = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货']
const statusTag = ['warning', 'success', 'primary', 'success', 'info', 'warning', 'info', 'warning', 'warning', 'primary']

const orders = ref([])
const loading = ref(false)
// 分页（后端返回 {records, total, current, size, pages}）
const page = ref(1)
const size = ref(10)
const total = ref(0)
const detail = ref(null)
const detailVisible = ref(false)
const reasons = ref([])
const myReturns = ref([])
// 包裹（发货单）
const parcelVisible = ref(false)

const parcelOrderSn = ref('')
const parcels = ref([])
const parcelText = { 0: '待下发', 1: '已下发', 2: '已拣货', 3: '已出库', 4: '运输中', 5: '已签收', 9: '已取消' }
const parcelTag = { 0: 'warning', 1: 'primary', 2: 'primary', 3: 'success', 4: 'success', 5: 'success', 9: 'info' }

const openParcels = async (row) => {
  parcelOrderSn.value = row.orderSn
  parcels.value = []
  parcelVisible.value = true
  try {
    parcels.value = await listMyDeliveries(row.id)
  } catch {
    parcels.value = []
  }
}

/** 订单 → 售后状态文案（来自 GET /portal/returns） */
const returnOf = (orderId) => myReturns.value.find((r) => r.orderId === orderId)
const afterSaleText = (orderId) => {
  const apply = returnOf(orderId)
  if (!apply) return ''
  if (apply.status === 0) return '退款处理中'
  if (apply.status === 1) return '退款成功'
  return '退货被拒绝'
}
const afterSaleTag = (orderId) => {
  const apply = returnOf(orderId)
  if (!apply) return 'info'
  if (apply.status === 0) return 'warning'
  if (apply.status === 1) return 'success'
  return 'danger'
}
const returnVisible = ref(false)
const returning = ref(false)
const returnFormRef = ref()
const returnForm = reactive({ orderId: null, orderSn: '', reasonId: null, description: '' })
const returnRules = { reasonId: [{ required: true, message: '请选择退货原因', trigger: 'change' }] }

const load = async () => {
  loading.value = true
  try {
    const [pageData, returnRows] = await Promise.all([
      listOrders(page.value, size.value),
      listMyReturns().catch(() => [])
    ])
    orders.value = pageData.records || []
    total.value = Number(pageData.total || 0)
    myReturns.value = returnRows
  } catch (e) {
    ElMessage.error('加载订单失败：' + e.message)
  } finally {
    loading.value = false
  }
}

/** 确认收货（状态 2→3，服务端有守卫） */
const confirmReceive = async (row) => {
  try {
    await confirmOrder(row.id)
    ElMessage.success('已确认收货，订单完成')
    await load()
  } catch (e) {
    ElMessage.error('确认收货失败：' + e.message)
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
    await load()
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
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
h4 { margin: 16px 0 8px; font-size: 14px; }
</style>
