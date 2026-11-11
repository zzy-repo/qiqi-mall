<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>商品分类</span>
        <el-button type="primary" :icon="Plus" data-testid="category-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="tree" border row-key="id" default-expand-all data-testid="category-list-table">
      <el-table-column prop="name" label="分类名称" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <span :data-testid="`category-row-${row.id}`">
            <el-button link type="primary" size="small" data-testid="category-row-edit" @click="openForm(row)">编辑</el-button>
          </span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑分类' : '新增分类'" width="480px" data-testid="category-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" data-testid="category-form-name" placeholder="请输入分类名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="父分类" prop="parentId">
          <el-tree-select
            v-model="form.parentId"
            data-testid="category-form-parent"
            :data="parentOptions"
            check-strictly
            :render-after-expand="false"
            default-expand-all
            style="width: 100%"
            placeholder="请选择父分类"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="category-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="category-form-submit" @click="submit">确定</el-button>
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
// 收集节点及其所有后代 id（编辑时禁选，防止把自己挂到后代下）
const withDescendants = (node) => {
  const ids = [node.id]
  for (const c of node.children || []) ids.push(...withDescendants(c))
  return ids
}

const parentOptions = computed(() => {
  const disabledIds = form.id
    ? new Set(withDescendants(tree.value.find((n) => n.id === form.id) || { id: form.id }))
    : new Set()
  const toNode = (c) => ({
    value: c.id,
    label: c.name,
    disabled: disabledIds.has(c.id),
    children: (c.children || []).map(toNode)
  })
  return [{ value: 0, label: '作为顶级分类' }, ...tree.value.map(toNode)]
})

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
