<template>
  <div>
    <h3 class="title">结算</h3>

    <el-card shadow="never" class="block">
      <template #header>收货信息</template>
      <el-form :model="form" label-width="90px" style="max-width: 620px">
        <el-form-item label="选择地址">
          <el-select
            v-model="addressId"
            data-testid="checkout-form-address"
            placeholder="选择已保存的地址"
            style="width: 100%"
            clearable
            @change="useAddress"
          >
            <el-option
              v-for="a in addresses"
              :key="a.id"
              :label="`${a.name} ${a.phone} ${a.province}${a.city}${a.region}${a.detail}`"
              :value="a.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="收货人" required>
          <el-input v-model="form.receiverName" data-testid="checkout-form-receiver-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.receiverPhone" data-testid="checkout-form-receiver-phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="收货地址" required>
          <el-input
            v-model="form.receiverAddress"
            data-testid="checkout-form-receiver-address"
            type="textarea"
            :rows="2"
            maxlength="255"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>商品清单</template>
      <el-table :data="items" border size="small">
        <el-table-column prop="productName" label="商品" min-width="180" />
        <el-table-column label="规格" min-width="160">
          <template #default="{ row }">{{ row.specText || '默认规格' }}</template>
        </el-table-column>
        <el-table-column label="单价" width="110">
          <template #default="{ row }">¥ {{ Number(row.price).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column label="小计" width="120">
          <template #default="{ row }">¥ {{ (Number(row.price) * row.quantity).toFixed(2) }}</template>
        </el-table-column>
      </el-table>

      <div class="footer">
        <span class="total">应付：<b>¥ {{ total }}</b></span>
        <el-button
          type="primary"
          size="large"
          :loading="submitting"
          data-testid="checkout-form-submit"
          @click="submit"
        >提交订单</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listCart } from '../../api/cart'
import { createOrder, getIdempotentToken } from '../../api/order'
import { listAddresses } from '../../api/member'

const router = useRouter()

const items = ref([])
const addresses = ref([])
const addressId = ref(null)
const submitting = ref(false)
const form = ref({ receiverName: '', receiverPhone: '', receiverAddress: '' })
// 幂等令牌：进结算页领一次，提交时携带；被消费/过期后自动重领
const idempotentToken = ref('')

const refreshToken = async () => {
  try {
    const data = await getIdempotentToken()
    idempotentToken.value = data.tokenId
  } catch {
    idempotentToken.value = ''
  }
}

const total = computed(() => items.value.reduce((sum, i) => sum + Number(i.price) * i.quantity, 0).toFixed(2))

const load = async () => {
  await refreshToken()
  try {
    items.value = await listCart()
    if (!items.value.length) {
      ElMessage.warning('购物车是空的，先挑点商品吧')
      router.replace('/cart')
      return
    }
    addresses.value = await listAddresses()
    const def = addresses.value.find((a) => a.defaultStatus === 1) || addresses.value[0]
    if (def) {
      addressId.value = def.id
      useAddress(def.id)
    }
  } catch (e) {
    ElMessage.error('加载结算信息失败：' + e.message)
  }
}

const useAddress = (id) => {
  const addr = addresses.value.find((a) => a.id === id)
  if (!addr) return
  form.value = {
    receiverName: addr.name,
    receiverPhone: addr.phone,
    receiverAddress: `${addr.province}${addr.city}${addr.region}${addr.detail}`
  }
}

const submit = async () => {
  if (!form.value.receiverName || !form.value.receiverAddress) {
    return ElMessage.warning('请填写收货人和收货地址')
  }
  submitting.value = true
  try {
    const data = await createOrder({
      ...form.value,
      fromCart: true,
      idempotentToken: idempotentToken.value,
      items: items.value.map((i) => ({ productSkuId: i.productSkuId, quantity: i.quantity }))
    })
    ElMessage.success('下单成功，去支付')
    router.replace({ path: '/orders', query: { pay: data.order.id } })
  } catch (e) {
    ElMessage.error('下单失败：' + e.message)
    // 令牌可能已被消费/过期，重领后再让用户点一次
    await refreshToken()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.title { margin: 0 0 12px; font-size: 16px; }
.block { margin-bottom: 16px; }
.footer { display: flex; justify-content: flex-end; align-items: center; gap: 16px; margin-top: 16px; }
.total { color: #606266; }
.total b { color: #f56c6c; font-size: 18px; }
</style>
