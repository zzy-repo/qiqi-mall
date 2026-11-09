<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>商品分类</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="tree" border row-key="id" default-expand-all>
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
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listCategories, createCategory, updateCategory } from '../../api/category'

const list = ref([])
const loading = ref(false)

// 扁平数据转树结构
const tree = computed(() => {
  const map = new Map(list.value.map((r) => ({ ...r, children: [] })).map((r) => [r.id, r]))
  const roots = []
  map.forEach((node) => {
    const parent = node.parentId ? map.get(node.parentId) : null
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
})

const load = async () => {
  loading.value = true
  try {
    list.value = await listCategories()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const visible = ref(false)
const saving = ref(false)
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
  saving.value = true
  try {
    if (form.id) await updateCategory(form.id, form)
    else await createCategory(form)
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
.tip { font-size: 12px; color: #909399; line-height: 1.6; }
</style>
