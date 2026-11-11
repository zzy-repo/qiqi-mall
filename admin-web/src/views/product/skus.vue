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
        <el-button type="primary" :icon="Plus" data-testid="sku-create" @click="openForm()">新增 SKU</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="sku-list-table">
      <el-table-column label="图片" width="90">
        <template #default="{ row }">
          <el-image v-if="row.pic" :src="row.pic" fit="cover" class="thumb" data-testid="sku-row-thumb">
            <template #error><span class="empty-pic">无图</span></template>
          </el-image>
          <span v-else class="empty-pic">—</span>
        </template>
      </el-table-column>
      <el-table-column label="规格" min-width="220">
        <template #default="{ row }">
          <span v-if="row.specText" data-testid="sku-row-spec">{{ row.specText }}</span>
          <span v-else class="empty-pic">未设置</span>
        </template>
      </el-table-column>
      <el-table-column label="价格" width="130">
        <template #default="{ row }">
          <span data-testid="sku-row-price">¥ {{ Number(row.price).toFixed(2) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="100" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <span :data-testid="`sku-row-${row.id}`">
            <el-button link type="primary" size="small" data-testid="sku-row-edit" @click="openForm(row)">编辑</el-button>
          </span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑SKU' : '新增SKU'" width="600px" data-testid="sku-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="所属商品">
          <el-input :model-value="productName || form.productId" disabled />
        </el-form-item>
        <el-form-item label="SKU 图片">
          <ImageUploader v-model="form.pic" data-testid="sku-form-pic" placeholder="SKU 图片URL（可直接上传）" />
        </el-form-item>

        <el-form-item label="规格值">
          <div class="spec-list">
            <div
              v-for="(spec, index) in form.specs"
              :key="index"
              class="spec-row"
              data-testid="sku-form-spec-row"
            >
              <el-select v-model="spec.attributeId" data-testid="sku-form-spec-attr" placeholder="属性" style="width: 150px">
                <el-option v-for="a in attributes" :key="a.id" :label="a.name" :value="a.id" />
              </el-select>
              <el-input
                v-model="spec.value"
                data-testid="sku-form-spec-value"
                placeholder="值，如 黑色"
                style="flex: 1"
                maxlength="100"
              />
              <el-button
                link
                type="danger"
                size="small"
                data-testid="sku-form-spec-remove"
                @click="form.specs.splice(index, 1)"
              >删除</el-button>
            </div>
            <el-button
              size="small"
              :icon="Plus"
              data-testid="sku-form-spec-add"
              @click="form.specs.push({ attributeId: null, value: '' })"
            >
              添加规格值
            </el-button>
            <div v-if="!attributes.length" class="tip">该商品所属类型还没有属性，先去「商品类型 → 属性」里添加</div>
          </div>
        </el-form-item>

        <el-form-item label="价格" prop="price">
          <el-input-number
            v-model="form.price"
            data-testid="sku-form-price"
            :min="0"
            :precision="2"
            :step="1"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="库存" prop="stock">
          <el-input-number v-model="form.stock" data-testid="sku-form-stock" :min="0" :precision="0" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="sku-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="sku-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import ImageUploader from '../../components/ImageUploader.vue'
import { listSkus, getSku, createSku, updateSku, listSkuAttributes } from '../../api/sku'
import { getProduct } from '../../api/product'

const route = useRoute()
const productId = Number(route.params.productId)

const list = ref([])
const attributes = ref([])
const loading = ref(false)
const productName = ref('')

const blank = () => ({ id: null, productId, price: 0, stock: 0, pic: '', specs: [] })
const form = reactive(blank())

const load = async () => {
  loading.value = true
  try {
    const [rows, detail, attrs] = await Promise.all([
      listSkus({ productId }),
      getProduct(productId).catch(() => null),
      listSkuAttributes(productId).catch(() => [])
    ])
    list.value = rows
    productName.value = detail?.product?.name || ''
    attributes.value = attrs
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
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

const openForm = async (row) => {
  Object.assign(form, blank())
  if (row) {
    try {
      const data = await getSku(row.id)
      Object.assign(form, {
        id: data.sku.id,
        productId: data.sku.productId,
        price: Number(data.sku.price),
        stock: data.sku.stock,
        pic: data.sku.pic || '',
        specs: (data.specs || []).map((s) => ({ attributeId: s.attributeId, value: s.value }))
      })
    } catch (e) {
      ElMessage.error('加载SKU失败：' + e.message)
      return
    }
  }
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      sku: {
        productId,
        price: form.price,
        stock: form.stock,
        pic: form.pic
      },
      specs: form.specs.filter((s) => s.attributeId && s.value)
    }
    if (form.id) await updateSku(form.id, payload)
    else await createSku(payload)
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
.thumb { width: 44px; height: 44px; border-radius: 4px; }
.empty-pic { color: #c0c4cc; }
.spec-list { width: 100%; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.spec-row { display: flex; gap: 8px; width: 100%; align-items: center; }
.tip { font-size: 12px; color: #e6a23c; }
</style>
