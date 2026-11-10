<template>
  <div class="uploader">
    <div class="row">
      <el-input v-model="inner" :placeholder="placeholder" maxlength="500" clearable />
      <el-upload
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="doUpload"
        accept="image/jpeg,image/png,image/webp"
      >
        <el-button :loading="uploading" :icon="Upload">上传</el-button>
      </el-upload>
    </div>
    <div v-if="inner" class="preview">
      <el-image :src="inner" fit="cover" class="thumb">
        <template #error><span class="err">无法加载</span></template>
      </el-image>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { uploadImage } from '../api/upload'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '图片URL（也可直接上传）' }
})
const emit = defineEmits(['update:modelValue'])

const uploading = ref(false)
const inner = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

/** 前端先挡一层：类型白名单 + 5MB（后端同样校验） */
const beforeUpload = (file) => {
  const okType = ['image/jpeg', 'image/png', 'image/webp'].includes(file.type)
  if (!okType) {
    ElMessage.error('仅支持 jpg / png / webp')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB')
    return false
  }
  return true
}

const doUpload = async ({ file }) => {
  uploading.value = true
  try {
    const data = await uploadImage(file)
    inner.value = data.url
    ElMessage.success('上传成功')
  } catch (e) {
    ElMessage.error('上传失败：' + e.message)
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.uploader { width: 100%; }
.row { display: flex; gap: 8px; width: 100%; }
.preview { margin-top: 8px; }
.thumb { width: 72px; height: 72px; border-radius: 4px; border: 1px solid #ebeef5; }
.err { font-size: 11px; color: #c0c4cc; }
</style>
