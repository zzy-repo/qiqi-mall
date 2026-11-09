<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>SKU</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table :data="list" border>
      <el-table-column prop="id" label="SKU ID" width="100" />
      <el-table-column prop="productId" label="商品ID" width="110" />
      <el-table-column prop="specJson" label="规格组合" min-width="220" show-overflow-tooltip />
      <el-table-column prop="price" label="价格" width="120" />
      <el-table-column prop="stock" label="库存" width="100" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑SKU' : '新增SKU'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="商品ID" prop="productId">
          <el-input-number v-model="form.productId" :min="1" :controls="false" style="width: 100%" />
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
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

// 数据来源：后端接口（待接入）
const list = ref([])

const visible = ref(false)
const formRef = ref()
const form = reactive({ id: null, productId: null, specJson: '', price: 0, stock: 0 })

const rules = {
  productId: [{ required: true, message: '请输入商品ID', trigger: 'blur' }],
  specJson: [{ required: true, message: '请输入规格组合', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row
    ? { ...row }
    : { id: null, productId: null, specJson: '', price: 0, stock: 0 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // TODO: 接入后端接口后替换
  ElMessage.info('接口未接入，数据未保存')
}
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
