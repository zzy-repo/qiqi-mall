<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>属性</span>
        <el-button type="primary" :icon="Plus" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table :data="list" border>
      <el-table-column prop="id" label="属性ID" width="100" />
      <el-table-column prop="typeId" label="所属类型ID" width="120" />
      <el-table-column prop="name" label="属性名称" />
      <el-table-column label="是否筛选" width="120">
        <template #default="{ row }">
          <el-tag :type="row.isFilter ? 'success' : 'info'" size="small">
            {{ row.isFilter ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑属性' : '新增属性'" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="所属类型ID" prop="typeId">
          <el-input-number v-model="form.typeId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="属性名称" prop="name">
          <el-input v-model="form.name" placeholder="如：颜色、内存" maxlength="50" />
        </el-form-item>
        <el-form-item label="是否可筛选">
          <el-switch v-model="form.isFilter" :active-value="1" :inactive-value="0" />
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
const form = reactive({ id: null, typeId: null, name: '', isFilter: 0 })

const rules = {
  typeId: [{ required: true, message: '请输入所属类型ID', trigger: 'blur' }],
  name: [{ required: true, message: '请输入属性名称', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, typeId: null, name: '', isFilter: 0 })
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
