<template>
  <el-card shadow="never" header="商品分类">
    <template #header>
      <div class="card-header">
        <span>商品分类</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table :data="list" border row-key="id" default-expand-all>
      <el-table-column prop="id" label="分类ID" width="100" />
      <el-table-column prop="parentId" label="父分类ID" width="120" />
      <el-table-column prop="name" label="分类名称" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑分类' : '新增分类'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入分类名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="父分类ID" prop="parentId">
          <el-input-number v-model="form.parentId" :min="0" :controls="false" style="width: 100%" />
          <div class="tip">顶级分类填 0</div>
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
const form = reactive({ id: null, parentId: 0, name: '' })

const rules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }],
  parentId: [{ required: true, message: '请输入父分类ID', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, parentId: 0, name: '' })
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
.tip { font-size: 12px; color: #909399; line-height: 1.6; }
</style>
