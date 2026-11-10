<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>订单列表</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="orderSn" label="订单号" min-width="160" />
      <el-table-column label="买家" width="110">
        <template #default="{ row }">{{ memberName.get(row.memberId) || row.memberId }}</template>
      </el-table-column>
      <el-table-column label="总金额" width="130">
        <template #default="{ row }">¥ {{ Number(row.totalAmount).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status]" size="small">{{ statusText[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="支付方式" width="100">
        <template #default="{ row }">{{ payText[row.payType] }}</template>
      </el-table-column>
      <el-table-column prop="receiverName" label="收货人" width="100" />
      <el-table-column prop="receiverPhone" label="收货电话" width="140" />
      <el-table-column label="支付时间" width="170">
        <template #default="{ row }">{{ row.paymentTime || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">明细</el-button>
          <el-button
            v-if="row.status === 1"
            link
            type="success"
            size="small"
            @click="ship(row)"
          >发货</el-button>
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑：订单 + 明细一起提交 -->
    <el-dialog v-model="visible" :title="form.id ? '编辑订单' : '新增订单'" width="640px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="订单号" prop="orderSn">
          <el-input v-model="form.orderSn" placeholder="留空自动生成" maxlength="50" />
        </el-form-item>
        <el-form-item label="买家" prop="memberId">
          <el-select v-model="form.memberId" placeholder="请选择会员" style="width: 100%">
            <el-option v-for="m in members" :key="m.id" :label="`${m.username}${m.nickname ? '（' + m.nickname + '）' : ''}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="收货人" prop="receiverName">
          <el-input v-model="form.receiverName" maxlength="50" />
        </el-form-item>
        <el-form-item label="收货电话">
          <el-input v-model="form.receiverPhone" maxlength="20" />
        </el-form-item>
        <el-form-item label="收货地址">
          <el-input v-model="form.receiverAddress" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option v-for="(t, i) in statusText" :key="i" :label="t" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="支付方式">
          <el-select v-model="form.payType" style="width: 100%">
            <el-option v-for="(t, i) in payText" :key="i" :label="t" :value="i" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">订单明细</el-divider>
        <el-table :data="form.items" border size="small">
          <el-table-column label="商品" width="170">
            <template #default="{ row }">
              <el-select
                v-model="row.productId"
                placeholder="选择商品"
                size="small"
                @change="(v) => onItemProductChange(row, v)"
              >
                <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="规格（SKU）" width="190">
            <template #default="{ row }">
              <el-select
                v-model="row.productSkuId"
                placeholder="选择规格"
                size="small"
                :disabled="!row.productId"
                @change="(v) => onItemSkuChange(row, v)"
              >
                <el-option
                  v-for="s in skuOptionsOf(row)"
                  :key="s.id"
                  :label="`${s.specJson || '默认规格'} · ¥${Number(s.price).toFixed(2)}`"
                  :value="s.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.productPrice" :min="0" :precision="2" :controls="false" style="width: 110px" />
            </template>
          </el-table-column>
          <el-table-column label="数量" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.productQuantity" :min="1" :precision="0" :controls="false" style="width: 90px" />
            </template>
          </el-table-column>
          <el-table-column label="" width="60">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="form.items.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button class="add-item" size="small" :icon="Plus" @click="addItem">加一条明细</el-button>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情：明细 + 退货申请聚合展示 -->
    <el-drawer v-model="detailVisible" :title="`订单明细 - ${detail?.order?.orderSn || ''}`" size="700px">
      <template v-if="detail">
        <h4>商品明细</h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="productName" label="商品名" min-width="160" />
          <el-table-column label="单价" width="100">
            <template #default="{ row }">¥ {{ Number(row.productPrice).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="productQuantity" label="数量" width="70" />
          <el-table-column label="小计" width="110">
            <template #default="{ row }">¥ {{ (Number(row.productPrice) * row.productQuantity).toFixed(2) }}</template>
          </el-table-column>
        </el-table>

        <h4 style="margin-top: 20px">退货申请</h4>
        <el-table :data="detail.returnApplies" border size="small">
          <el-table-column prop="returnReason" label="原因" min-width="120" />
          <el-table-column prop="description" label="说明" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="['warning', 'success', 'danger'][row.status]" size="small">
                {{ ['待处理', '已通过', '已拒绝'][row.status] }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!detail.returnApplies.length" description="无退货申请" :image-size="60" />
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listOrders, createOrder, updateOrder, shipOrder } from '../../api/order'
import { listMembers } from '../../api/member'
import { listProducts } from '../../api/product'
import { listSkus } from '../../api/sku'

// 状态机：0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款
const statusText = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款']
const statusTag = ['warning', 'success', 'primary', 'success', 'info', 'warning', 'info']
const payText = ['未支付', '支付宝', '微信']

const list = ref([])
const loading = ref(false)
const members = ref([])
const products = ref([])
const skus = ref([])

/** 某一行可选规格：只列该商品的 SKU */
const skuOptionsOf = (row) => skus.value.filter((s) => s.productId === row.productId)

/** 换商品时清空已选规格与快照 */
const onItemProductChange = (row, productId) => {
  row.productSkuId = null
  row.productName = ''
  row.productPrice = 0
  const p = products.value.find((x) => x.id === productId)
  if (p) row.productName = p.name
}

/** 选规格时带出商品名与成交价快照 */
const onItemSkuChange = (row, skuId) => {
  const s = skus.value.find((x) => x.id === skuId)
  if (!s) return
  row.productPrice = Number(s.price)
  const p = products.value.find((x) => x.id === s.productId)
  if (p) row.productName = p.name
}
const memberName = computed(() => new Map(members.value.map((m) => [m.id, m.username])))

const load = async () => {
  loading.value = true
  try {
    list.value = await listOrders()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const emptyItem = () => ({ productId: null, productSkuId: null, productName: '', productPrice: 0, productQuantity: 1 })
const blankForm = () => ({
  id: null, orderSn: '', memberId: null, receiverName: '',
  receiverPhone: '', receiverAddress: '',
  status: 0, payType: 0, items: [emptyItem()]
})

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive(blankForm())

const rules = {
  memberId: [{ required: true, message: '请选择买家', trigger: 'change' }],
  receiverName: [{ required: true, message: '请输入收货人', trigger: 'blur' }]
}

const addItem = () => form.items.push(emptyItem())

const openForm = (row) => {
  Object.assign(form, row ? { ...row, items: form.items } : blankForm())
  if (row) {
    // 编辑时先拉明细
    import('../../api/order').then(({ getOrder }) =>
      getOrder(row.id).then((d) => { form.items = d.items.length ? d.items : [emptyItem()] }))
  } else {
    form.items = [emptyItem()]
  }
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = { order: { ...form }, items: form.items }
    if (form.id) await updateOrder(form.id, payload.order)
    else await createOrder(payload)
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

/** 发货（状态 1→2，服务端有守卫） */
const ship = async (row) => {
  const ok = await ElMessageBox.confirm(`确认对订单 ${row.orderSn} 发货？`, '发货确认', { type: 'warning' })
    .then(() => true).catch(() => false)
  if (!ok) return
  try {
    await shipOrder(row.id)
    ElMessage.success('发货成功')
    await load()
  } catch (e) {
    ElMessage.error('发货失败：' + e.message)
  }
}

const detail = ref(null)
const detailVisible = ref(false)
const openDetail = async (row) => {
  const { getOrder } = await import('../../api/order')
  detail.value = await getOrder(row.id)
  detailVisible.value = true
}

onMounted(async () => {
  load()
  try {
    const [mems, prods, allSkus] = await Promise.all([listMembers(), listProducts(), listSkus()])
    members.value = mems
    products.value = prods
    skus.value = allSkus
  } catch (e) {
    // 参照数据失败不阻塞列表
  }
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.add-item { margin-top: 10px; width: 100%; }
.snapshot { color: #606266; }
</style>
