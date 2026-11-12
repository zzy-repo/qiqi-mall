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
        data-testid="order-list-search-sn"
        placeholder="按订单号搜索"
        clearable
        style="width: 220px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select
        v-model="query.status"
        data-testid="order-list-status-filter"
        placeholder="全部状态"
        clearable
        style="width: 140px"
        @change="search"
      >
        <el-option v-for="(t, i) in statusText" :key="i" :label="t" :value="i" />
      </el-select>
      <el-button type="primary" :icon="Search" data-testid="order-list-search-submit" @click="search">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border data-testid="order-list-table">
      <el-table-column prop="orderSn" label="订单号" min-width="160" />
      <el-table-column label="买家" width="110">
        <template #default="{ row }">{{ memberName.get(row.memberId) || row.memberId }}</template>
      </el-table-column>
      <el-table-column label="总金额" width="130">
        <template #default="{ row }">¥ {{ Number(row.totalAmount).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status]" size="small" data-testid="order-row-status">{{ statusText[row.status] }}</el-tag>
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
      <!-- 审核的两个分支（通过 / 不通过）原本塞在一个 ElMessageBox 里，靠按钮区分。
           现在「通过 / 不通过」各自是一级入口：点下去直接进「预演 → 确认」弹窗，
           少一层「先选分支再确认」的套娃（原二次确认已移除，见 DangerConfirmDialog）。 -->
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="order-row-detail" @click="openDetail(row)">明细</el-button>
          <el-button
            v-if="row.status === 7"
            link
            type="warning"
            size="small"
            data-testid="order-row-audit-pass"
            @click="auditPass(row)"
          >审核通过</el-button>
          <el-button
            v-if="row.status === 7"
            link
            type="danger"
            size="small"
            data-testid="order-row-audit-reject"
            @click="auditReject(row)"
          >不通过</el-button>
          <el-button
            v-if="row.status === 1"
            link
            type="success"
            size="small"
            data-testid="order-row-ship"
            @click="ship(row)"
          >发货</el-button>
          <el-button
            v-if="row.status === 8"
            link
            type="success"
            size="small"
            data-testid="order-row-delivery"
            @click="openDetail(row)"
          >发货单</el-button>
          <el-button link type="primary" size="small" data-testid="order-row-edit" @click="openForm(row)">编辑</el-button>
          <el-button link type="danger" size="small" data-testid="order-row-delete" @click="removeOrder(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        data-testid="list-pagination"
        :page-size="query.size"
        :total="total"
        layout="prev, pager, next, total"
        background
        @current-change="load"
      />
    </div>

    <!-- 新增/编辑：订单 + 明细一起提交 -->
    <el-dialog
      v-model="visible"
      :title="form.id ? '编辑订单' : '新增订单'"
      width="640px"
      data-testid="order-form-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="订单号" prop="orderSn">
          <el-input v-model="form.orderSn" data-testid="order-form-order-sn" placeholder="留空自动生成" maxlength="50" />
        </el-form-item>
        <el-form-item label="买家" prop="memberId">
          <el-select v-model="form.memberId" data-testid="order-form-member" placeholder="请选择会员" style="width: 100%">
            <el-option v-for="m in members" :key="m.id" :label="`${m.username}${m.nickname ? '（' + m.nickname + '）' : ''}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="收货人" prop="receiverName">
          <el-input v-model="form.receiverName" data-testid="order-form-receiver-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="收货电话">
          <el-input v-model="form.receiverPhone" data-testid="order-form-receiver-phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="收货地址">
          <el-input
            v-model="form.receiverAddress"
            data-testid="order-form-receiver-address"
            type="textarea"
            :rows="2"
            maxlength="255"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" data-testid="order-form-status" style="width: 100%">
            <el-option v-for="(t, i) in statusText" :key="i" :label="t" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="支付方式">
          <el-select v-model="form.payType" data-testid="order-form-pay-type" style="width: 100%">
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
        <el-button data-testid="order-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="order-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情：明细 + 退货申请聚合展示 -->
    <el-drawer
      v-model="detailVisible"
      :title="`订单明细 - ${detail?.order?.orderSn || ''}`"
      size="960px"
      data-testid="order-detail-drawer"
    >
      <template v-if="detail">
        <h4>发货单</h4>
        <el-table
          :data="deliveries"
          border
          size="small"
          empty-text="暂无发货单（待审核通过后自动拆单生成）"
          data-testid="order-detail-delivery-table"
        >
          <el-table-column prop="deliverySn" label="发货单号" min-width="170" />
          <el-table-column prop="warehouseName" label="发货仓" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="deliveryTag[row.status] || 'info'" size="small" data-testid="delivery-row-status">
                {{ deliveryText[row.status] || row.status }}
              </el-tag>
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
              <el-button
                v-if="row.status === 0"
                link
                type="primary"
                size="small"
                data-testid="delivery-row-dispatch"
                @click="dispatch(row)"
              >下发</el-button>
              <el-button
                v-if="row.status === 0"
                link
                type="danger"
                size="small"
                data-testid="delivery-row-cancel"
                @click="cancel(row)"
              >取消</el-button>
              <el-button
                v-if="row.status === 0"
                link
                type="warning"
                size="small"
                data-testid="delivery-row-edit"
                @click="openDeliveryEdit(row)"
              >编辑</el-button>
              <el-button
                v-if="row.status === 0"
                link
                type="warning"
                size="small"
                data-testid="delivery-row-split"
                @click="openDeliverySplit(row)"
              >拆分</el-button>
              <el-button
                v-if="row.status === 0"
                link
                type="warning"
                size="small"
                data-testid="delivery-row-merge"
                @click="openDeliveryMerge(row)"
              >合并</el-button>
              <el-button
                v-if="row.status === 0"
                link
                type="danger"
                size="small"
                data-testid="delivery-row-remove"
                @click="removeDelivery(row)"
              >删除</el-button>
              <el-dropdown
                v-if="row.status >= 1 && row.status < 5"
                trigger="click"
                data-testid="delivery-row-wms-callback"
                @command="(cmd) => callback(row, cmd)"
              >
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

    <!-- 编辑 / 拆分待下发包裹 -->
    <el-dialog
      v-model="dlgVisible"
      :title="dlgMode === 'edit' ? '编辑包裹（改仓 / 改量）' : '拆分包裹'"
      width="600px"
      data-testid="delivery-form-dialog"
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
          <el-select
            v-model="dlgWarehouseId"
            data-testid="delivery-form-warehouse"
            placeholder="沿用原仓库"
            clearable
            style="width: 200px"
          >
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
      <!-- 预演失败的原因留在表单里（不是只发一条会消失的 toast）：
           用户能看到「为什么」、刚填的数量/仓一个都没丢，改一下就能重新提交。 -->
      <el-alert
        v-if="dlgError"
        type="error"
        :closable="false"
        show-icon
        :title="dlgError"
        data-testid="delivery-form-preview-error"
      />
      <template #footer>
        <el-button data-testid="delivery-form-cancel" @click="dlgVisible = false">取消</el-button>
        <el-button type="primary" data-testid="delivery-form-submit" @click="submitDeliveryDialog">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 合并待下发包裹 -->
    <el-dialog v-model="mergeVisible" title="合并包裹" width="480px" data-testid="delivery-merge-dialog">
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
          <el-select
            v-model="mergeTargetId"
            data-testid="delivery-merge-target"
            placeholder="选择同订单的待下发包裹"
            style="width: 260px"
          >
            <el-option
              v-for="d in mergeOptions"
              :key="d.id"
              :label="`${d.deliverySn}（${d.warehouseName}）`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <!-- 合并 / 删除并入：预演失败的原因同样留在弹窗里，选好的目标包裹不丢 -->
      <el-alert
        v-if="mergeError"
        type="error"
        :closable="false"
        show-icon
        :title="mergeError"
        data-testid="delivery-merge-preview-error"
      />
      <template #footer>
        <el-button data-testid="delivery-merge-cancel" @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" data-testid="delivery-merge-submit" @click="submitMerge">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listOrders, getOrder, createOrder, updateOrder, shipOrder, shipOrderDryRun, deleteOrder, deleteOrderDryRun } from '../../api/order'
import {
  listDeliveries, deliveryCounts,
  // 订单审核 / 发货单流转：预演（*DryRun）+ 执行
  auditPassOrder, auditPassDryRun, auditRejectOrder, auditRejectDryRun,
  dispatchDelivery, dispatchDeliveryDryRun, cancelDelivery, cancelDeliveryDryRun,
  wmsCallback, wmsCallbackDryRun,
  updateDelivery, updateDeliveryDryRun, splitDelivery, splitDeliveryDryRun,
  mergeDelivery, mergeDeliveryDryRun, deleteDelivery, deleteDeliveryDryRun
} from '../../api/delivery'
import { useDangerConfirm } from '../../composables/useDangerConfirm'
import { handleSaveError } from '../../composables/useConflictHandler'
import { listWarehouses } from '../../api/warehouse'
import { Search } from '@element-plus/icons-vue'
import { listMembers } from '../../api/member'
import { listProducts } from '../../api/product'
import { listSkus } from '../../api/sku'

/** 14 个高危写操作共用的一套「预演 → 展示 → 确认 → 执行」流程（本页占 11 个） */
const danger = useDangerConfirm()

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
    getOrder(row.id).then((d) => { form.items = d.items.length ? d.items : [emptyItem()] })
  } else {
    form.items = [emptyItem()]
  }
  visible.value = true
  formRef.value?.clearValidate()
}

/** 重新拉一次订单详情并重填表单（409/400 后点「刷新」用；弹窗保持打开） */
const reloadForm = async () => {
  if (!form.id) return
  const d = await getOrder(form.id)
  Object.assign(form, { ...d.order, items: d.items.length ? d.items : [emptyItem()] })
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 明细是订单的核心内容，商品/规格没选全时后端只回一句「数据处理失败，请稍后重试」，
  // 用户在弹窗里既看不出哪一行错、也不知道订单根本没建成。这里先拦住并说清怎么改。
  if (!form.items.length || form.items.some((i) => !i.productId || !i.productSkuId)) {
    ElMessage.warning('请至少填写一条完整的订单明细（先选商品，再选规格）')
    return
  }
  saving.value = true
  try {
    // 整行 spread：form 来自 openForm 里对列表行（含 version）的展开，
    // 所以 payload.order 自然带上 version —— 这里的乐观锁不需要额外拼字段。
    const payload = { order: { ...form }, items: form.items }
    if (form.id) await updateOrder(form.id, payload.order)
    else await createOrder(payload)
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    // 409 被别人改过 / 400 漏传版本号 → 统一冲突弹窗（带「刷新」按钮重新填表单）
    if (handleSaveError(e, { onRefresh: reloadForm }) !== 'other') return
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

// 发货单状态文案（0待下发1已下发2已拣货3已出库4运输中5已签收9已取消）
const deliveryText = { 0: '待下发', 1: '已下发', 2: '已拣货', 3: '已出库', 4: '运输中', 5: '已签收', 9: '已取消' }
const deliveryTag = { 0: 'warning', 1: 'primary', 2: 'primary', 3: 'success', 4: 'success', 5: 'success', 9: 'info' }
const deliveries = ref([])

/**
 * 审核通过：待审核 → 待发货 + 按方案拆单。
 * 「预演 → 展示将要发生什么 → 确认 → 执行」全交给 useDangerConfirm：
 * 预演走 split-preview（真预览），它回 { willDo, plan } —— 顺带把
 * 「拆成几个包裹、每包哪个仓几条明细」列进弹窗明细区，原来那个独立的「拆单方案预览」弹窗因此不再需要。
 */
const auditPass = async (row) => {
  await danger.run({
    title: '⚠️ 确认审核通过',
    confirmText: '确认通过',
    successText: '审核通过，已生成发货单',
    dryRun: () => auditPassDryRun(row.id),
    exec: () => auditPassOrder(row.id),
    // 预演返回的 plan 转成弹窗明细区（只读展示，让用户先看清这次要拆成什么样）
    details: (preview) =>
      (preview.plan || []).map((pkg) => ({
        label: `${pkg.warehouseName || ('仓库 ' + pkg.warehouseId)}（${(pkg.items || []).length} 条明细）`,
        value: (pkg.items || [])
          .map((it) => `${it.productName || ('明细 #' + it.orderItemId)} × ${it.quantity}`)
          .join('、')
      })),
    onSuccess: () => load()
  })
}

/** 审核不通过：待审核 → 已关闭，并回补库存 */
const auditReject = async (row) => {
  await danger.run({
    title: '⚠️ 确认审核不通过',
    confirmText: '确认不通过',
    successText: '已审核不通过，订单关闭并回补库存',
    dryRun: () => auditRejectDryRun(row.id),
    exec: () => auditRejectOrder(row.id),
    onSuccess: () => load()
  })
}

/** 订单发货：已付款 → 已发货（直发路径，不拆单） */
const ship = async (row) => {
  await danger.run({
    title: '⚠️ 确认订单发货',
    confirmText: '确认发货',
    successText: '已发货',
    dryRun: () => shipOrderDryRun(row.id),
    exec: () => shipOrder(row.id),
    onSuccess: () => load()
  })
}

/** 删除订单：级联删明细 / 发货单，不可恢复 */
const removeOrder = async (row) => {
  await danger.run({
    title: '⚠️ 确认删除订单',
    confirmText: '确认删除',
    successText: '订单已删除',
    dryRun: () => deleteOrderDryRun(row.id),
    exec: () => deleteOrder(row.id),
    onSuccess: () => load()
  })
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

// ---------------- 待下发包裹的人工干预：编辑 / 拆分 / 合并 / 删除 ----------------
const warehouses = ref([])
const dlgVisible = ref(false)
const dlgMode = ref('edit')          // edit 改仓改量 | split 拆分
const dlgDelivery = ref(null)
const dlgWarehouseId = ref(null)
const dlgItems = ref([])
/** 编辑/拆分弹窗里预演失败的原因（留在弹窗内，用户填的数量不丢） */
const dlgError = ref('')
const mergeVisible = ref(false)
const mergeMode = ref('merge')       // merge 合并 | delete 删除并入
const mergeDeliveryRow = ref(null)
const mergeTargetId = ref(null)
const mergeOptions = ref([])
/** 合并/删除弹窗里预演失败的原因（留在弹窗内，选好的目标包裹不丢） */
const mergeError = ref('')

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
  dlgError.value = ''
  dlgVisible.value = true
}

const openDeliverySplit = (d) => {
  dlgMode.value = 'split'
  dlgDelivery.value = d
  dlgWarehouseId.value = null            // 默认沿用原仓
  dlgItems.value = itemsToDialogRows(d, 'split')
  dlgError.value = ''
  dlgVisible.value = true
}

/**
 * 拆 / 合 / 删 的 409/400 → 统一冲突弹窗（与商品/SKU/发货单编辑同一套）。
 *
 * 为什么这三个操作也要 409：别人可能同时在同一包裹上拆分 / 合并 / 删除，
 * 版本号对不上就说明「你看到的那一行已经变了」，必须刷新后重来，而不是只 toast 一句。
 * 返回 true 表示已接管（DangerConfirmDialog 收起，改弹 ConflictDialog）。
 *
 * @param {any} d 操作发起时的那一行（用于重拉它所属订单的发货单）
 * @param {() => any} [afterRefresh] 刷新完成后的额外动作（例如重新打开编辑表单）
 */
const deliveryConflict = (d, afterRefresh) => (e) => {
  if (e?.bizCode !== 409 && e?.bizCode !== 400) return false
  handleSaveError(e, {
    onRefresh: async () => {
      await loadDeliveries(d.orderId)
      await load()
      const fresh = deliveries.value.find((x) => x.id === d.id)
      if (!fresh) {
        ElMessage.warning('该包裹已不存在（可能已被他人合并或删除），请重新打开订单详情')
        return
      }
      if (afterRefresh) afterRefresh(fresh)
    }
  })
  return true
}

/**
 * 编辑 / 拆分待下发包裹：先把表单收成 payload，再交给 danger 走
 * 「预演（同路径 + ?dryRun=true）→ 确认 → 执行」。
 *
 * version（乐观锁）：编辑、拆分都要带，取自 GET /admin/deliveries?orderId= 的该行 `d.version`，
 * 由 danger.run 通过 `exec(version)` 交给执行接口（预演不带 version，后端在版本校验之前就返回）。
 *
 * 表单收起时机：**预演通过后才收**（onPreviewOk）。预演失败时表单原地不动、用户填的仓/数量全在，
 * 原因写在弹窗内（onPreviewError）—— 早先是「先关表单再预演」，预演被守卫拦下就只剩一条 toast，
 * 用户输入全丢，属于体验回退。
 */
const submitDeliveryDialog = async () => {
  const d = dlgDelivery.value
  if (!d) return
  const isEdit = dlgMode.value === 'edit'
  let payload
  if (isEdit) {
    const items = dlgItems.value
      .filter((r) => r.quantity !== null && r.quantity !== undefined && r.quantity !== r.current)
      .map((r) => ({ deliveryItemId: r.deliveryItemId, quantity: r.quantity }))
    // version 来自 GET /admin/deliveries?orderId= 的每一行；缺了后端 400、过期 409
    payload = { warehouseId: dlgWarehouseId.value || null, items, version: d.version }
  } else {
    const items = dlgItems.value
      .filter((r) => r.quantity > 0)
      .map((r) => ({ deliveryItemId: r.deliveryItemId, quantity: r.quantity }))
    if (!items.length) {
      ElMessage.warning('请至少给一条明细填写要拆出的数量')
      return
    }
    // 拆分的 version 由 danger.run 的 exec(version) 拼进 body（见下）
    payload = { newWarehouseId: dlgWarehouseId.value || null, items }
  }
  dlgError.value = ''
  await danger.run({
    title: isEdit ? '⚠️ 确认修改包裹' : '⚠️ 确认拆分包裹',
    confirmText: isEdit ? '确认修改' : '确认拆分',
    successText: isEdit ? '已保存（该包裹标记为人工调整）' : '已拆分',
    version: d.version,
    dryRun: () => (isEdit ? updateDeliveryDryRun(d.id, payload) : splitDeliveryDryRun(d.id, payload)),
    exec: (version) => (isEdit ? updateDelivery(d.id, payload) : splitDelivery(d.id, payload, version)),
    // ✅ 预演通过后才收表单；预演失败时表单不动、原因写回弹窗内
    onPreviewOk: () => { dlgVisible.value = false },
    onPreviewError: (e) => {
      dlgError.value = '预演失败：' + (e?.message || '后端没有返回具体原因')
    },
    // 409/400 交给统一冲突处理器：DangerConfirmDialog 收起，改弹冲突弹窗；
    // 点「刷新」→ 重拉该订单的发货单；编辑分支顺带用最新那一行重新打开表单让用户再改，
    // 拆分分支让用户在新数据上重新决定拆多少（拆多少本来就依赖最新数量）。
    onError: deliveryConflict(d, isEdit ? (fresh) => openDeliveryEdit(fresh) : null),
    onSuccess: async () => {
      await loadDeliveries(d.orderId)
      await load()
    }
  })
}

const openDeliveryMerge = (d) => {
  mergeMode.value = 'merge'
  mergeDeliveryRow.value = d
  mergeTargetId.value = null
  mergeError.value = ''
  mergeOptions.value = deliveries.value.filter((x) => x.status === 0 && x.id !== d.id)
  if (!mergeOptions.value.length) {
    ElMessage.warning('没有其他「待下发」包裹可以合并')
    return
  }
  mergeVisible.value = true
}

/** 合并 / 删除并入：与编辑/拆分同一套「预演通过才收表单 + 409 交冲突弹窗」 */
const submitMerge = async () => {
  const d = mergeDeliveryRow.value
  if (!d) return
  const targetId = mergeTargetId.value
  if (!targetId) {
    ElMessage.warning('请选择要并入的包裹')
    return
  }
  const isDelete = mergeMode.value === 'delete'
  mergeError.value = ''
  await danger.run({
    title: isDelete ? '⚠️ 确认删除包裹（并入目标包裹）' : '⚠️ 确认合并包裹',
    confirmText: isDelete ? '确认删除' : '确认合并',
    successText: isDelete ? '已删除（明细已并入目标包裹）' : '已合并',
    // 合并 / 删除都带发起方那一行的 version（乐观锁）；预演不需要
    version: d.version,
    dryRun: () => (isDelete ? deleteDeliveryDryRun(d.id, targetId) : mergeDeliveryDryRun(d.id, targetId)),
    exec: (version) => (isDelete ? deleteDelivery(d.id, targetId, version) : mergeDelivery(d.id, targetId, version)),
    onPreviewOk: () => { mergeVisible.value = false },
    onPreviewError: (e) => {
      mergeError.value = '预演失败：' + (e?.message || '后端没有返回具体原因')
    },
    onError: deliveryConflict(d),
    onSuccess: async () => {
      await loadDeliveries(d.orderId)
      await load()
    }
  })
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
    mergeError.value = ''
    mergeOptions.value = others
    mergeVisible.value = true
    return
  }
  // 空包裹才走这里（有明细的上面已经转到「并入」流程，因为后端要求带 targetId）
  await danger.run({
    title: '⚠️ 确认删除空包裹',
    confirmText: '确认删除',
    successText: '已删除',
    // DELETE 的 version 走 query（见 api/delivery.js 的注释：DELETE 带 body 过代理不可靠）
    version: d.version,
    dryRun: () => deleteDeliveryDryRun(d.id),
    exec: (version) => deleteDelivery(d.id, null, version),
    onError: deliveryConflict(d),
    onSuccess: async () => {
      await loadDeliveries(d.orderId)
      await load()
    }
  })
}

const loadDeliveries = async (orderId) => {
  try {
    deliveries.value = await listDeliveries(orderId)
  } catch {
    deliveries.value = []
  }
}

/** 下发 WMS：待下发 → 已下发 */
const dispatch = async (d) => {
  await danger.run({
    title: '⚠️ 确认下发 WMS',
    confirmText: '确认下发',
    successText: '已下发 WMS',
    dryRun: () => dispatchDeliveryDryRun(d.id),
    exec: () => dispatchDelivery(d.id),
    onSuccess: async () => {
      await loadDeliveries(d.orderId)
      await load()
    }
  })
}

/** 取消发货单：待下发 → 已取消（回补库存） */
const cancel = async (d) => {
  await danger.run({
    title: '⚠️ 确认取消发货单',
    confirmText: '确认取消',
    successText: '已取消',
    dryRun: () => cancelDeliveryDryRun(d.id),
    exec: () => cancelDelivery(d.id),
    onSuccess: async () => {
      await loadDeliveries(d.orderId)
      await load()
    }
  })
}

/** 模拟 WMS 回传：event 在 query */
const callback = async (d, event) => {
  const label = { pick: '拣货', outbound: '出库', transit: '揽收', signed: '签收' }[event] || event
  await danger.run({
    title: `⚠️ 确认模拟 WMS 回传（${label}）`,
    confirmText: '确认回传',
    successText: '回传成功',
    dryRun: () => wmsCallbackDryRun(d.id, event),
    exec: () => wmsCallback(d.id, event),
    onSuccess: async () => {
      await loadDeliveries(d.orderId)
      await load()
    }
  })
}

const detail = ref(null)
const detailVisible = ref(false)
const openDetail = async (row) => {
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
</style>
