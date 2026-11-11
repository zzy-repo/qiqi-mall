<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>订单列表</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <div class="filters">
      <el-input
        v-model="query.orderSn"
        placeholder="按订单号搜索"
        clearable
        style="width: 220px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
        <el-option v-for="(t, i) in statusText" :key="i" :label="t" :value="i" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
    </div>

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
      <el-table-column label="包裹" width="110">
        <template #default="{ row }">
          <el-tag
            v-if="packageCount(row.id) > 0"
            class="pkg-tag"
            size="small"
            type="success"
            effect="plain"
            @click="openDetail(row)"
          >{{ packageCount(row.id) }} 个包裹</el-tag>
          <span v-else class="snapshot">—</span>
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
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">明细</el-button>
          <el-button
            v-if="row.status === 7"
            link
            type="warning"
            size="small"
            @click="audit(row)"
          >审核</el-button>
          <el-button
            v-if="row.status === 8"
            link
            type="success"
            size="small"
            @click="openDetail(row)"
          >发货单</el-button>
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="prev, pager, next, total"
        background
        @current-change="load"
      />
    </div>

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
    <el-drawer v-model="detailVisible" :title="`订单明细 - ${detail?.order?.orderSn || ''}`" size="960px">
      <template v-if="detail">
        <h4>发货单</h4>
        <el-table :data="deliveries" border size="small" empty-text="暂无发货单（待审核通过后自动拆单生成）">
          <el-table-column prop="deliverySn" label="发货单号" min-width="170" />
          <el-table-column prop="warehouseName" label="发货仓" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="deliveryTag[row.status] || 'info'" size="small">{{ deliveryText[row.status] || row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="运单号" min-width="140">
            <template #default="{ row }">{{ row.trackNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="来源" width="95">
            <template #default="{ row }">
              <el-tag :type="row.source === 1 ? 'warning' : 'info'" size="small" effect="plain">
                {{ row.source === 1 ? '人工调整' : '自动拆单' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 0" link type="primary" size="small" @click="dispatch(row)">下发</el-button>
              <el-button v-if="row.status === 0" link type="danger" size="small" @click="cancel(row)">取消</el-button>
              <el-button v-if="row.status === 0" link type="warning" size="small" @click="openDeliveryEdit(row)">编辑</el-button>
              <el-button v-if="row.status === 0" link type="warning" size="small" @click="openDeliverySplit(row)">拆分</el-button>
              <el-button v-if="row.status === 0" link type="warning" size="small" @click="openDeliveryMerge(row)">合并</el-button>
              <el-button v-if="row.status === 0" link type="danger" size="small" @click="removeDelivery(row)">删除</el-button>
              <el-dropdown v-if="row.status >= 1 && row.status < 5" trigger="click" @command="(cmd) => callback(row, cmd)">
                <el-button link type="primary" size="small">模拟WMS回传 ▾</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="pick">拣货</el-dropdown-item>
                    <el-dropdown-item command="outbound">出库</el-dropdown-item>
                    <el-dropdown-item command="transit">揽收</el-dropdown-item>
                    <el-dropdown-item command="signed">签收</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>

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

    <!-- 审核弹窗里的「查看拆单方案」：dry-run 预览，不落库 -->
    <el-dialog v-model="previewVisible" title="拆单方案预览（只预览，不生成发货单）" width="560px">
      <div v-loading="previewLoading">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="点「通过」才会真正生成这些包裹；现在是只读预览。"
          style="margin-bottom: 12px"
        />
        <el-table :data="previewPlan" border size="small" empty-text="没有可拆的明细">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="warehouseName" label="发货仓" width="110" />
          <el-table-column label="包含明细" min-width="240">
            <template #default="{ row }">
              <div v-for="it in row.items" :key="it.orderItemId" class="preview-line">
                {{ it.productName || ('明细 #' + it.orderItemId) }} × {{ it.quantity }}
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="preview-tip">共 {{ previewPlan.length }} 个包裹</div>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 编辑 / 拆分待下发包裹 -->
    <el-dialog
      v-model="dlgVisible"
      :title="dlgMode === 'edit' ? '编辑包裹（改仓 / 改量）' : '拆分包裹'"
      width="600px"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="dlgMode === 'edit'
          ? '只有「待下发」的包裹可以改；保存后该包裹标记为人工调整。'
          : '把指定数量拆到一个新包裹；拆完后原包裹若已空会被自动删掉。'"
        style="margin-bottom: 12px"
      />
      <el-form label-width="88px">
        <el-form-item :label="dlgMode === 'edit' ? '发货仓' : '新包裹仓'">
          <el-select v-model="dlgWarehouseId" placeholder="沿用原仓库" clearable style="width: 200px">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="明细数量">
          <el-table :data="dlgItems" border size="small">
            <el-table-column label="明细（订单明细 / SKU）" min-width="200">
              <template #default="{ row }">#{{ row.orderItemId }} / SKU {{ row.productSkuId }}</template>
            </el-table-column>
            <el-table-column prop="current" label="当前数量" width="95" />
            <el-table-column prop="max" label="可改上限" width="95" />
            <el-table-column label="改后数量" width="150">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" :min="0" :max="row.max" size="small" />
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlgVisible = false">取消</el-button>
        <el-button type="primary" :loading="dlgSaving" @click="submitDeliveryDialog">确定</el-button>
      </template>
    </el-dialog>

    <!-- 合并待下发包裹 -->
    <el-dialog v-model="mergeVisible" title="合并包裹" width="480px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="mergeMode === 'delete'
          ? '该包裹还有明细，删除等同于并入另一个包裹。'
          : '把当前包裹并入另一个「待下发」包裹，合并后当前包裹消失。'"
        style="margin-bottom: 12px"
      />
      <el-form label-width="88px">
        <el-form-item label="并入到">
          <el-select v-model="mergeTargetId" placeholder="选择同订单的待下发包裹" style="width: 260px">
            <el-option
              v-for="d in mergeOptions"
              :key="d.id"
              :label="`${d.deliverySn}（${d.warehouseName}）`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :loading="mergeSaving" @click="submitMerge">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted, h } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listOrders, createOrder, updateOrder } from '../../api/order'
import {
  listDeliveries, dispatchDelivery, cancelDelivery, wmsCallback, auditPassOrder, auditRejectOrder,
  deliveryCounts, splitPreviewOrder, updateDelivery, splitDelivery, mergeDelivery, deleteDelivery
} from '../../api/delivery'
import { listWarehouses } from '../../api/warehouse'
import { Search } from '@element-plus/icons-vue'
import { listMembers } from '../../api/member'
import { listProducts } from '../../api/product'
import { listSkus } from '../../api/sku'

// 状态机：0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款 7待审核 8待发货 9部分发货
const statusText = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货']
const statusTag = ['warning', 'success', 'primary', 'success', 'info', 'warning', 'info', 'warning', 'warning', 'primary']
const payText = ['未支付', '支付宝', '微信']

const list = ref([])
const loading = ref(false)
const total = ref(0)
const query = reactive({ page: 1, size: 10, orderSn: '', status: null })

const search = () => {
  query.page = 1
  load()
}
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
    const pageData = await listOrders({
      page: query.page,
      size: query.size,
      orderSn: query.orderSn || undefined,
      status: query.status === null || query.status === '' ? undefined : query.status
    })
    list.value = pageData.records || []
    total.value = Number(pageData.total || 0)
    await loadPackageCounts()
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

// 发货单状态文案（0待下发1已下发2已拣货3已出库4运输中5已签收9已取消）
const deliveryText = { 0: '待下发', 1: '已下发', 2: '已拣货', 3: '已出库', 4: '运输中', 5: '已签收', 9: '已取消' }
const deliveryTag = { 0: 'warning', 1: 'primary', 2: 'primary', 3: 'success', 4: 'success', 5: 'success', 9: 'info' }
const deliveries = ref([])

/** 审核：通过 → 待发货并自动拆单；不通过 → 关闭订单并回补库存 */
const audit = async (row) => {
  // 默认一键通过；「查看拆单方案」是二级入口，不增加正常单的操作步数
  const message = h('div', { class: 'audit-box' }, [
    h('div', `订单 ${row.orderSn}：通过后自动拆单生成发货单；不通过将关闭订单并回补库存。`),
    h(
      'a',
      {
        class: 'split-preview-entry',
        onClick: () => openSplitPreview(row)
      },
      '查看拆单方案 ▸'
    )
  ])
  const action = await ElMessageBox.confirm(message, '订单审核', {
    confirmButtonText: '通过',
    cancelButtonText: '不通过',
    distinguishCancelAndClose: true,
    type: 'warning'
  }).then(() => 'pass').catch((e) => (e === 'cancel' ? 'reject' : 'close'))
  if (action === 'close') return
  try {
    if (action === 'pass') {
      await auditPassOrder(row.id)
      ElMessage.success('审核通过，已生成发货单')
    } else {
      await auditRejectOrder(row.id)
      ElMessage.success('已审核不通过，订单关闭并回补库存')
    }
    await load()
  } catch (e) {
    ElMessage.error('审核失败：' + e.message)
  }
}

// ---------------- 包裹数（列表页标签） ----------------
const packageCounts = ref({})
const packageCount = (orderId) => packageCounts.value[orderId] || 0
const loadPackageCounts = async () => {
  const ids = list.value.map((r) => r.id)
  if (!ids.length) {
    packageCounts.value = {}
    return
  }
  try {
    packageCounts.value = (await deliveryCounts(ids)) || {}
  } catch {
    packageCounts.value = {}
  }
}

// ---------------- 审核弹窗：拆单方案预览（dry-run，不落库） ----------------
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewPlan = ref([])
const openSplitPreview = async (row) => {
  previewVisible.value = true
  previewLoading.value = true
  previewPlan.value = []
  try {
    previewPlan.value = (await splitPreviewOrder(row.id)) || []
  } catch (e) {
    ElMessage.error('预览失败：' + e.message)
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

// ---------------- 待下发包裹的人工干预：编辑 / 拆分 / 合并 / 删除 ----------------
const warehouses = ref([])
const dlgVisible = ref(false)
const dlgSaving = ref(false)
const dlgMode = ref('edit')          // edit 改仓改量 | split 拆分
const dlgDelivery = ref(null)
const dlgWarehouseId = ref(null)
const dlgItems = ref([])
const mergeVisible = ref(false)
const mergeSaving = ref(false)
const mergeMode = ref('merge')       // merge 合并 | delete 删除并入
const mergeDeliveryRow = ref(null)
const mergeTargetId = ref(null)
const mergeOptions = ref([])

// 改量的上限是「该订单明细的下单数量」，这样既能减也能加回来
const orderItemMax = (orderItemId) => {
  const it = (detail.value?.items || []).find((x) => x.id === orderItemId)
  return it ? it.productQuantity : null
}

const itemsToDialogRows = (delivery, mode) =>
  (delivery.items || []).map((i) => ({
    deliveryItemId: i.id,
    orderItemId: i.orderItemId,
    productSkuId: i.productSkuId,
    current: i.quantity,
    max: orderItemMax(i.orderItemId) || i.quantity,
    // 编辑默认保持原数量；拆分默认 0（由管理员决定拆多少出去）
    quantity: mode === 'edit' ? i.quantity : 0
  }))

const openDeliveryEdit = (d) => {
  dlgMode.value = 'edit'
  dlgDelivery.value = d
  dlgWarehouseId.value = d.warehouseId
  dlgItems.value = itemsToDialogRows(d, 'edit')
  dlgVisible.value = true
}

const openDeliverySplit = (d) => {
  dlgMode.value = 'split'
  dlgDelivery.value = d
  dlgWarehouseId.value = null            // 默认沿用原仓
  dlgItems.value = itemsToDialogRows(d, 'split')
  dlgVisible.value = true
}

const submitDeliveryDialog = async () => {
  const d = dlgDelivery.value
  if (!d) return
  dlgSaving.value = true
  try {
    if (dlgMode.value === 'edit') {
      const items = dlgItems.value
        .filter((r) => r.quantity !== null && r.quantity !== undefined && r.quantity !== r.current)
        .map((r) => ({ deliveryItemId: r.deliveryItemId, quantity: r.quantity }))
      await updateDelivery(d.id, { warehouseId: dlgWarehouseId.value || null, items })
      ElMessage.success('已保存（该包裹标记为人工调整）')
    } else {
      const items = dlgItems.value
        .filter((r) => r.quantity > 0)
        .map((r) => ({ deliveryItemId: r.deliveryItemId, quantity: r.quantity }))
      if (!items.length) {
        ElMessage.warning('请至少给一条明细填写要拆出的数量')
        dlgSaving.value = false
        return
      }
      await splitDelivery(d.id, { newWarehouseId: dlgWarehouseId.value || null, items })
      ElMessage.success('已拆分')
    }
    dlgVisible.value = false
    await loadDeliveries(d.orderId)
    await load()
  } catch (e) {
    ElMessage.error('操作失败：' + e.message)
  } finally {
    dlgSaving.value = false
  }
}

const openDeliveryMerge = (d) => {
  mergeMode.value = 'merge'
  mergeDeliveryRow.value = d
  mergeTargetId.value = null
  mergeOptions.value = deliveries.value.filter((x) => x.status === 0 && x.id !== d.id)
  if (!mergeOptions.value.length) {
    ElMessage.warning('没有其他「待下发」包裹可以合并')
    return
  }
  mergeVisible.value = true
}

const submitMerge = async () => {
  const d = mergeDeliveryRow.value
  if (!d) return
  if (!mergeTargetId.value) {
    ElMessage.warning('请选择要并入的包裹')
    return
  }
  mergeSaving.value = true
  try {
    if (mergeMode.value === 'delete') {
      await deleteDelivery(d.id, mergeTargetId.value)
      ElMessage.success('已删除（明细已并入目标包裹）')
    } else {
      await mergeDelivery(d.id, mergeTargetId.value)
      ElMessage.success('已合并')
    }
    mergeVisible.value = false
    await loadDeliveries(d.orderId)
    await load()
  } catch (e) {
    ElMessage.error('操作失败：' + e.message)
  } finally {
    mergeSaving.value = false
  }
}

const removeDelivery = async (d) => {
  // 有明细的包裹不能凭空删掉，必须指定并入目标（等同合并到别处）
  if ((d.items || []).length) {
    const others = deliveries.value.filter((x) => x.status === 0 && x.id !== d.id)
    if (!others.length) {
      ElMessage.warning('这是唯一的待下发包裹，无法删除；如需放弃请用「取消」')
      return
    }
    mergeMode.value = 'delete'
    mergeDeliveryRow.value = d
    mergeTargetId.value = null
    mergeOptions.value = others
    mergeVisible.value = true
    return
  }
  const ok = await ElMessageBox.confirm(`确认删除空包裹 ${d.deliverySn}？`, '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  try {
    await deleteDelivery(d.id)
    ElMessage.success('已删除')
    await loadDeliveries(d.orderId)
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

const loadDeliveries = async (orderId) => {
  try {
    deliveries.value = await listDeliveries(orderId)
  } catch {
    deliveries.value = []
  }
}

const dispatch = async (d) => {
  try {
    await dispatchDelivery(d.id)
    ElMessage.success('已下发 WMS')
    await loadDeliveries(d.orderId)
  } catch (e) {
    ElMessage.error('下发失败：' + e.message)
  }
}

const cancel = async (d) => {
  const ok = await ElMessageBox.confirm(`确认取消发货单 ${d.deliverySn}？（会回补库存）`, '提示', { type: 'warning' })
    .then(() => true).catch(() => false)
  if (!ok) return
  try {
    await cancelDelivery(d.id)
    ElMessage.success('已取消')
    await loadDeliveries(d.orderId)
    await load()
  } catch (e) {
    ElMessage.error('取消失败：' + e.message)
  }
}

const callback = async (d, event) => {
  try {
    await wmsCallback(d.id, event)
    ElMessage.success('回传成功')
    await loadDeliveries(d.orderId)
    await load()
  } catch (e) {
    ElMessage.error('回传失败：' + e.message)
  }
}

const detail = ref(null)
const detailVisible = ref(false)
const openDetail = async (row) => {
  const { getOrder } = await import('../../api/order')
  detail.value = await getOrder(row.id)
  // 打开抽屉即加载发货单：此前 deliveries 只在 dispatch/cancel/callback 之后刷新，
  // 首次打开「发货单」区块永远为空，下发/回传入口不可达
  await loadDeliveries(row.id)
  detailVisible.value = true
}

onMounted(async () => {
  load()
  try {
    const [mems, prods, allSkus, whs] = await Promise.all([listMembers(), listProducts(), listSkus(), listWarehouses()])
    members.value = mems
    products.value = prods
    skus.value = allSkus
    warehouses.value = whs
  } catch (e) {
    // 参照数据失败不阻塞列表
  }
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.filters { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.add-item { margin-top: 10px; width: 100%; }
.snapshot { color: #606266; }
.pkg-tag { cursor: pointer; }
.audit-box .split-preview-entry { display: inline-block; margin-top: 8px; color: #409eff; cursor: pointer; font-size: 13px; }
.preview-line { line-height: 1.6; }
.preview-tip { margin-top: 10px; color: #909399; font-size: 13px; }
</style>
