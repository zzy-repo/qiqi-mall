<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div class="title-group">
          <el-button link :icon="ArrowLeft" @click="$router.push('/product')">返回</el-button>
          <el-divider direction="vertical" />
          <span>SKU 管理</span>
          <el-tag v-if="productName" size="small" type="info">{{ productName }}</el-tag>
        </div>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增 SKU</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="id" label="SKU ID" width="100" />
      <el-table-column prop="productId" label="商品ID" width="110" />
      <el-table-column prop="specJson" label="规格组合" min-width="240" show-overflow-tooltip />
      <el-table-column label="价格" width="130">
        <template #default="{ row }">¥ {{ Number(row.price).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="100" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑SKU' : '新增SKU'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="所属商品">
          <el-input :model-value="productName || form.productId" disabled />
        </el-form-item>
        <el-form-item label="规格组合" prop="specJson">
          <el-input
            v-model="form.specJson"
            type="textarea"
            :rows="3"
            placeholder='JSON，如 {"颜色":"黑","内存":"256G"}'
            maxlength="255"
          />
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="库存" prop="stock">
          <el-input-number v-model="form.stock" :min="0" :precision="0" style="width: 100%" />
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
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listSkus, createSku, updateSku } from '../../api/sku'
import { getProduct } from '../../api/product'

const route = useRoute()
const productId = Number(route.params.productId)

const list = ref([])
const loading = ref(false)
const productName = ref('')

const load = async () => {
  loading.value = true
  try {
    const [rows, product] = await Promise.all([
      listSkus({ productId }),
      getProduct(productId).catch(() => null)
    ])
    list.value = rows
    productName.value = product?.name || ''
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, productId, specJson: '', price: 0, stock: 0 })

const rules = {
  specJson: [{ required: true, message: '请输入规格组合', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row
    ? { ...row }
    : { id: null, productId, specJson: '', price: 0, stock: 0 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) await updateSku(form.id, form)
    else await createSku({ ...form, productId })
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
.title-group { display: flex; align-items: center; gap: 8px; }
</style>
