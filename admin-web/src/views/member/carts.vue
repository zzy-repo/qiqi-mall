<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div class="title-group">
          <el-button link :icon="ArrowLeft" @click="$router.push('/member')">返回</el-button>
          <el-divider direction="vertical" />
          <span>购物车</span>
          <el-tag v-if="memberName" size="small" type="info">{{ memberName }}</el-tag>
        </div>
        <el-button type="primary" :icon="Plus" data-testid="member-cart-list-create" @click="openForm()">新增条目</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" border data-testid="member-cart-list-table">
      <el-table-column label="商品" min-width="180">
        <template #default="{ row }">{{ productName.get(row.productId) || row.productId }}</template>
      </el-table-column>
      <el-table-column label="规格（SKU）" min-width="200">
        <template #default="{ row }">{{ skuLabel(row.productSkuId) }}</template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" width="100" />
      <el-table-column prop="createTime" label="加入时间" width="180" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="member-cart-row-edit" @click="openForm(row)">编辑</el-button>
          <el-button link type="danger" size="small" data-testid="member-cart-row-remove" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="visible"
      :title="form.id ? '编辑购物车条目' : '新增购物车条目'"
      width="520px"
      data-testid="member-cart-form-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="所属会员">
          <el-input :model-value="memberName || form.memberId" disabled />
        </el-form-item>
        <el-form-item label="商品" prop="productId">
          <el-select
            v-model="form.productId"
            data-testid="member-cart-form-product"
            placeholder="请选择商品"
            style="width: 100%"
            @change="onProductChange"
          >
            <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格（SKU）" prop="productSkuId">
          <el-select
            v-model="form.productSkuId"
            data-testid="member-cart-form-sku"
            placeholder="请选择规格"
            style="width: 100%"
            :disabled="!form.productId"
          >
            <el-option
              v-for="s in skuOptions"
              :key="s.id"
              :label="`${s.specJson || '默认规格'} · ¥${Number(s.price).toFixed(2)}`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number v-model="form.quantity" data-testid="member-cart-form-quantity" :min="1" :precision="0" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="member-cart-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="member-cart-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listCarts, createCart, updateCart, deleteCart } from '../../api/cart'
import { getMember } from '../../api/member'
import { listProducts } from '../../api/product'
import { listSkus } from '../../api/sku'

const route = useRoute()
const memberId = Number(route.params.memberId)

const rows = ref([])
const loading = ref(false)
const memberName = ref('')
const products = ref([])
const skus = ref([])

const productName = computed(() => new Map(products.value.map((p) => [p.id, p.name])))
const skuLabel = (skuId) => {
  const s = skus.value.find((x) => x.id === skuId)
  if (!s) return skuId
  return `${s.specJson || '默认规格'} · ¥${Number(s.price).toFixed(2)}`
}

const skuOptions = computed(() => skus.value.filter((s) => s.productId === form.productId))

const blank = () => ({ id: null, memberId, productId: null, productSkuId: null, quantity: 1 })
const form = reactive(blank())

const load = async () => {
  loading.value = true
  try {
    const [cartRows, member, prods, allSkus] = await Promise.all([
      listCarts({ memberId }),
      getMember(memberId).catch(() => null),
      listProducts(),
      listSkus()
    ])
    rows.value = cartRows
    memberName.value = member?.username || ''
    products.value = prods
    skus.value = allSkus
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()

const rules = {
  productId: [{ required: true, message: '请选择商品', trigger: 'change' }],
  productSkuId: [{ required: true, message: '请选择规格', trigger: 'change' }],
  quantity: [{ required: true, message: '请输入数量', trigger: 'blur' }]
}

const onProductChange = () => { form.productSkuId = null }

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
    if (form.id) await updateCart(form.id, body)
    else await createCart(body)
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
  const ok = await ElMessageBox.confirm('确认从购物车删除该条目？', '提示', { type: 'warning' })
    .then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteCart(row.id)
    ElMessage.success('删除成功')
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

onMounted(load)
</script>
